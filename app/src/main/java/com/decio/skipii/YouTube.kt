package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.localization.DateWrapper
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.math.abs

@Immutable
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumbnail: String?,
    val avatar: String?,
    val durationSec: Long,
    val views: Long,
    val uploaded: String?,
)

@Immutable
data class VideoDetails(
    val item: VideoItem,
    val description: String,
    val likes: Long,
    val subscribers: Long,
    val related: List<VideoItem>,
    val playable: Boolean,
    /** Height of the chosen stream, e.g. "1080p". */
    val quality: String?,
    /** Every video height YouTube offers for this video, highest first. */
    val qualities: List<Int>,
    /** Width / height of the chosen stream, known before the first frame; null if unknown. */
    val aspect: Float? = null,
    /** The video's chapters in order (from its description timestamps / YouTube's markers); empty if none. */
    val chapters: List<Chapter> = emptyList(),
    /** YouTube's storyboards (the scrub preview's frames), smallest frames first; empty if none. */
    val storyboards: List<Storyboard> = emptyList(),
)

@Immutable
data class Chapter(val startMs: Long, val title: String)

/**
 * One storyboard level: sheets ([urls]) of [columns] × [rows] frames of [frameWidth] × [frameHeight]
 * px, [count] frames in all, one every [msPerFrame] (0 = spread evenly over the video).
 */
@Immutable
data class Storyboard(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val msPerFrame: Int,
) {
    /** The sheet and the spot in it of the frame shown at [ms] of a [durationMs] video. */
    fun frameAt(ms: Long, durationMs: Long): StoryboardFrame? {
        if (urls.isEmpty() || count <= 0 || columns <= 0 || rows <= 0) return null
        val step = if (msPerFrame > 0) msPerFrame.toLong() else (durationMs / count).coerceAtLeast(1L)
        val n = (ms / step).toInt().coerceIn(0, count - 1)
        val perSheet = columns * rows
        val url = urls.getOrNull(n / perSheet) ?: return null
        val k = n % perSheet
        return StoryboardFrame(url, (k % columns) * frameWidth, (k / columns) * frameHeight, frameWidth, frameHeight)
    }
}

@Immutable
data class StoryboardFrame(val url: String, val x: Int, val y: Int, val width: Int, val height: Int)

@Immutable
data class ChannelDetails(
    val url: String,
    val id: String,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val subscribers: Long,
    val description: String,
    val videosTab: ListLinkHandler?,
)

@Immutable
data class Comment(
    val id: String,
    /** The @handle (YouTube's comment data carries nothing else); also the reply @mention. */
    val author: String,
    /** The author's channel, for their display name ([ChannelNames]). */
    val channelId: String?,
    /** Display name when already known (the account's own comments). */
    val name: String? = null,
    val avatar: String?,
    val text: String,
    val likes: Int,
    val ago: String?,
    val pinned: Boolean,
    val hearted: Boolean,
    val replyCount: Int,
    /** Continuation for this thread's replies. */
    val repliesToken: String?,
    /** Liked by the signed-in account (from YouTube's toolbar state). */
    val liked: Boolean = false,
)

@Immutable
data class CommentsPage(
    val comments: List<Comment>,
    val total: Int,
    val disabled: Boolean,
    val next: String?,
    /** The video's chapters, read off the same first `next` page (see [CommentFeed.parseChapters]). */
    val chapters: List<Chapter> = emptyList(),
)

/** Everything the app asks of YouTube goes through here. All calls are blocking-safe (IO). */
object YouTube {
    private val service get() = ServiceList.YouTube

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toVideoItem() }
    }

    suspend fun details(id: String): VideoDetails = withContext(Dispatchers.IO) {
        val info = BotCheck.retrying(id) { StreamCache.getOrLoad(id) }
        val picked = StreamSelection.pick(info)
        VideoDetails(
            item = VideoItem(
                id = id,
                title = info.name,
                channel = info.uploaderName.orEmpty(),
                channelUrl = info.uploaderUrl,
                thumbnail = info.thumbnails.bestUrl(),
                avatar = info.uploaderAvatars.bestUrl(targetWidth = 96),
                durationSec = info.duration,
                views = info.viewCount,
                uploaded = uploadedText(info.textualUploadDate, info.uploadDate),
            ),
            description = info.description?.let { d ->
                if (d.type == Description.HTML) HtmlCompat.fromHtml(d.content, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                else d.content.trim()
            }.orEmpty(),
            likes = info.likeCount,
            subscribers = info.uploaderSubscriberCount,
            related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoItem() },
            playable = picked.isPlayable,
            quality = picked.playedHeight?.let { "${it}p" },
            qualities = StreamSelection.heights(info),
            aspect = (if (picked.usesSeparate) picked.video else picked.muxed)
                ?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height },
            chapters = info.streamSegments.orEmpty()
                .map { Chapter(it.startTimeSeconds * 1000L, it.title.orEmpty().trim()) }
                .sortedBy { it.startMs },
            storyboards = info.previewFrames.orEmpty()
                .filter { it.urls.isNotEmpty() && it.frameWidth > 0 && it.frameHeight > 0 }
                .map { Storyboard(it.urls, it.frameWidth, it.frameHeight, it.framesPerPageX, it.framesPerPageY, it.totalCount, it.durationPerFrame) }
                .sortedBy { it.frameWidth },
        )
    }

    // ── Channels ──

    suspend fun channel(url: String): ChannelDetails = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(service, url)
        val videosTab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
        ChannelDetails(
            url = url,
            id = info.id,
            name = info.name,
            avatar = info.avatars.bestUrl(targetWidth = 176),
            banner = info.banners.bestUrl(targetWidth = 1080),
            subscribers = info.subscriberCount,
            description = info.description.orEmpty(),
            videosTab = videosTab,
        )
    }

    /**
     * One page of a channel's uploads; [token] null for the first. Read through InnerTube
     * (not NewPipe) because only the raw page carries the "Members only" badge.
     */
    suspend fun channelVideos(channel: ChannelDetails, token: String?): FeedPage {
        val page = InnerTube.channelVideos(channel.id, token)
        // The uploads list doesn't repeat the channel on every item; fill it in.
        return page.copy(videos = page.videos.map {
            it.copy(channel = it.channel.ifBlank { channel.name }, avatar = it.avatar ?: channel.avatar, channelUrl = it.channelUrl ?: channel.url)
        })
    }

    private fun StreamInfoItem.toVideoItem(): VideoItem? {
        // Live and upcoming entries need HLS handling that isn't built yet.
        if (streamType != StreamType.VIDEO_STREAM) return null
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return VideoItem(
            id = id,
            title = name,
            channel = uploaderName.orEmpty(),
            channelUrl = uploaderUrl,
            thumbnail = thumbnails.bestUrl(),
            avatar = uploaderAvatars.bestUrl(targetWidth = 96),
            durationSec = duration,
            views = viewCount,
            uploaded = uploadedText(textualUploadDate, uploadDate),
        )
    }

    /** The image closest to [targetWidth]: sharp enough for a full-width card, no 4K downloads. */
    private fun List<Image>.bestUrl(targetWidth: Int = 720): String? =
        filter { it.width > 0 }.minByOrNull { abs(it.width - targetWidth) }?.url ?: firstOrNull()?.url

    /**
     * For brand-new uploads the extractor's "textual" date is a raw ISO timestamp
     * ("2026-10-04T04:00:22-07:00"); show it relative, like everywhere else.
     */
    private fun uploadedText(textual: String?, date: DateWrapper?): String? {
        if (textual != null && !ISO_DATE.containsMatchIn(textual)) return textual
        val then = date?.offsetDateTime()?.toInstant() ?: return textual?.substringBefore('T')
        return relativeTime(then)
    }

    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}T")

    fun relativeTime(then: Instant, now: Instant = Instant.now()): String {
        val s = Duration.between(then, now).seconds.coerceAtLeast(0)
        fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "just now"
            s < 3_600 -> ago(s / 60, "minute")
            s < 86_400 -> ago(s / 3_600, "hour")
            s < 7 * 86_400 -> ago(s / 86_400, "day")
            s < 30 * 86_400 -> ago(s / (7 * 86_400), "week")
            s < 365 * 86_400 -> ago(s / (30 * 86_400), "month")
            else -> ago(s / (365 * 86_400), "year")
        }
    }

    /** Turns extractor failures into a sentence a person can act on. */
    fun friendlyError(t: Throwable): String = when (t) {
        is AgeRestrictedContentException -> "This video is age-restricted and needs a YouTube account to watch."
        is GeographicRestrictionException -> "This video isn't available in your country."
        is SignInConfirmNotBotException ->
            "YouTube is asking to confirm you're not a bot. Try again in a few minutes."
        is org.schabi.newpipe.extractor.exceptions.PrivateContentException -> "This video is private."
        // Members-only videos (e.g. "Insider Edition") need a channel membership; Skipii's
        // stream extraction is anonymous, so it can't play them even when you're a member.
        is ContentNotAvailableException ->
            if (t.message.orEmpty().contains("member", ignoreCase = true) || t is org.schabi.newpipe.extractor.exceptions.PaidContentException)
                "This is a members-only video. Skipii can't play members-only videos yet."
            else "This video isn't available."
        is ReCaptchaException -> "YouTube is rate-limiting requests right now. Try again in a minute."
        is IOException -> "Couldn't reach YouTube. Check your connection."
        else -> "Something went wrong loading this. Try again."
    }
}

/**
 * Stream info is fetched once per video and shared by the UI (details) and the playback
 * service (media URLs). Both live in one process, so a map is enough. Stream URLs expire
 * after roughly six hours; entries older than [MAX_AGE_MS] are refetched.
 */
object StreamCache {
    private const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
    /** A load whose stream request was refused ([keepRefused]): the next open after this tries again. */
    private const val REFUSED_MAX_AGE_MS = 10 * 60 * 1000L
    private class Entry(val info: StreamInfo, val loadedAt: Long, val refused: Boolean = false) {
        val fresh get() = System.currentTimeMillis() - loadedAt < if (refused) REFUSED_MAX_AGE_MS else MAX_AGE_MS
    }
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Stream URLs are signed for the network they were fetched on and expire; a stale one
     * gets HTTP 403. Dropping it forces a fresh extraction on the next load.
     */
    fun invalidate(id: String) { entries.remove(id) }

    /** The phone switched networks: every cached URL is now bound to the old address. */
    fun invalidateAll() = entries.clear()

    fun get(id: String): StreamInfo? = entries[id]?.takeIf { it.fresh }?.info

    /**
     * Keeps [StreamsRefused.info] for the media source to play after all ([BotCheck] gave up),
     * not for long. [getOrLoad] never returns it: every load tries for the real streams again.
     */
    fun keepRefused(id: String, info: StreamInfo) {
        entries[id] = Entry(info, System.currentTimeMillis(), refused = true)
    }

    /**
     * One extraction per video at a time: a prefetch (touch-down, top of Home) and the real
     * open share the same in-flight load instead of racing two full extractions.
     */
    private val inflight = ConcurrentHashMap<String, FutureTask<StreamInfo>>()

    fun getOrLoad(id: String): StreamInfo {
        entries[id]?.takeIf { it.fresh && !it.refused }?.let { return it.info }
        val mine = FutureTask {
            val startedAt = SystemClock.elapsedRealtime()
            StreamInfo.getInfo(ServiceList.YouTube, YouTube.watchUrl(id)).also {
                val picked = StreamSelection.pick(it)
                // Details came, but the stream request was refused: a bot check, not a dead
                // video (or one with only ANDROID's 360p combined stream), so nothing is
                // cached and the load can be retried.
                if (!picked.usesSeparate && BotCheck.refusedSince(id, startedAt)) throw StreamsRefused(id, it)
                entries[id] = Entry(it, System.currentTimeMillis())
                Net.noteMediaHost((picked.video ?: picked.muxed)?.content)
            }
        }
        val running = inflight.putIfAbsent(id, mine)
        val task = running ?: mine.also { it.run() }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (running == null) inflight.remove(id, mine)
        }
    }

    /** Background warm-up; one at a time so prefetches never crowd a real open. */
    private val prefetcher = Executors.newSingleThreadExecutor()

    fun prefetch(id: String) {
        if (get(id) != null || inflight.containsKey(id)) return
        prefetcher.execute { runCatching { getOrLoad(id) } }
    }
}

/**
 * The extractor's VISIONOS request (every stream link) was refused, but ANDROID's details
 * came; [info] may still hold ANDROID's 360p combined stream, which played silently in place
 * of the real streams before (2026-10-27).
 */
class StreamsRefused(id: String, val info: StreamInfo) : SignInConfirmNotBotException("VISIONOS was refused for $id")

/**
 * "Sign in to confirm you're not a bot" (see [PlayerFallback]) comes and goes: on 2026-10-27
 * the same client refused and then served the same videos minutes apart. A load it refuses
 * is retried quietly a few times, the player keeping its spinner with a note ([waiting]),
 * before giving up: then a 360p combined stream, if the refused load had one, plays rather
 * than nothing; else the error stands. Prefetches don't retry; only loads someone waits for.
 */
object BotCheck {
    private val RetryDelaysMs = longArrayOf(3_000, 8_000, 20_000)

    /** The video whose load is waiting out a bot check, for the player's note. */
    var waiting by mutableStateOf<String?>(null)
        private set

    private val refusedAt = ConcurrentHashMap<String, Long>()

    /** A stream request for [id] was refused ([PlayerFallback] sees every one). */
    fun noteRefused(id: String) { refusedAt[id] = SystemClock.elapsedRealtime() }

    fun refusedSince(id: String, since: Long) = (refusedAt[id] ?: Long.MIN_VALUE) >= since

    /** [load] for [id], retried while YouTube answers with the bot check. */
    suspend fun retrying(id: String, load: () -> StreamInfo): StreamInfo {
        try {
            for ((attempt, wait) in RetryDelaysMs.withIndex()) {
                try {
                    return load()
                } catch (e: SignInConfirmNotBotException) {
                    Log.w("Skipii", "bot check for $id; trying again in ${wait / 1000}s (${attempt + 1}/${RetryDelaysMs.size})")
                    waiting = id
                    delay(wait)
                }
            }
            return load().also { Log.i("Skipii", "bot check for $id passed on the last try") }
        } catch (e: SignInConfirmNotBotException) {
            return giveUp(id, e)
        } finally {
            if (waiting == id) waiting = null
        }
    }

    /** [retrying] for a thread that can block (the playback service's resolver). */
    fun retryingBlocking(id: String, load: () -> StreamInfo): StreamInfo = runBlocking { retrying(id, load) }

    /**
     * [load] with no waiting, for items nobody is watching yet (a restored session, the
     * queue): a restored item that resolved after its retries became the current video,
     * replacing the one just opened meanwhile.
     */
    fun withoutWaiting(id: String, load: () -> StreamInfo): StreamInfo =
        try { load() } catch (e: SignInConfirmNotBotException) { giveUp(id, e) }

    private fun giveUp(id: String, e: SignInConfirmNotBotException): StreamInfo {
        val partial = (e as? StreamsRefused)?.info
        if (partial != null && StreamSelection.pick(partial).isPlayable) {
            Log.w("Skipii", "bot check for $id; giving up on the real streams, playing the 360p combined one")
            StreamCache.keepRefused(id, partial)
            return partial
        }
        Log.w("Skipii", "bot check for $id; giving up")
        throw e
    }
}

/**
 * The user's chosen video height, or null for Auto. Read by the playback service when it
 * builds a media source, so it lives outside Compose; persisted across launches.
 */
object QualityPreference {
    private const val KEY = "quality_height"
    private var prefs: SharedPreferences? = null

    @Volatile
    var height: Int? = null
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        height = prefs?.getInt(KEY, 0)?.takeIf { it > 0 }
    }

    fun set(value: Int?) {
        height = value
        prefs?.edit()?.putInt(KEY, value ?: 0)?.apply()
    }
}

/** Chooses which of YouTube's many streams to play. */
object StreamSelection {
    /** Above this height, phones gain little and pay a lot in data. */
    private const val MAX_HEIGHT = 1080

    data class Picked(val video: VideoStream?, val audio: AudioStream?, val muxed: VideoStream?) {
        val isPlayable get() = (video != null && audio != null) || muxed != null
        /** What will actually play: the separate streams, or the combined fallback. */
        val usesSeparate get() = video != null && audio != null
        val playedHeight: Int? get() = (if (usesSeparate) video?.height else muxed?.height)?.takeIf { it > 0 }
    }

    fun pick(info: StreamInfo): Picked {
        // H.264 first: every device decodes it in hardware. VP9 next, anything else last.
        fun formatRank(format: MediaFormat?) = when (format) {
            MediaFormat.MPEG_4 -> 0
            MediaFormat.WEBM -> 1
            else -> 2
        }
        val candidates = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        val chosen = QualityPreference.height
        // A chosen height plays exactly if offered, else the nearest below it. Auto caps at 1080p.
        val ceiling = chosen ?: MAX_HEIGHT
        val video = (candidates.filter { it.height <= ceiling }.ifEmpty { candidates })
            .sortedWith(
                compareBy<VideoStream> { if (it.height <= ceiling) ceiling - it.height else it.height }
                    .thenBy { formatRank(it.format) },
            )
            .firstOrNull()
        // The original language is preferred, but ANY audio track beats none: with no
        // accepted audio the player fell back to the 360p combined stream while the label
        // still said 1080p (the blurry-video bug, 2026-10-04).
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareBy<AudioStream> { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1 }
                    .thenBy { if (it.format == MediaFormat.M4A) 0 else 1 }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }
        return Picked(video, audio, muxed)
    }

    /**
     * The Auto ladder: one codec family (H.264 if offered, else VP9 — the player won't
     * switch between codecs), one stream per height (highest frame rate wins), up to 1080p.
     * Played as a single adaptive DASH set, so playback starts at whatever the connection
     * can sustain immediately and climbs, like YouTube.
     */
    fun autoLadder(info: StreamInfo): List<VideoStream> {
        val usable = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..MAX_HEIGHT && it.itagItem != null }
        val family = usable.filter { it.codec?.startsWith("avc") == true }.ifEmpty { usable.filter { it.format == MediaFormat.WEBM } }
        return family.groupBy { it.height }
            .map { (_, sameHeight) -> sameHeight.maxBy { it.fps * 100_000L + it.bitrate } }
            .sortedBy { it.height }
    }

    fun heights(info: StreamInfo): List<Int> = info.videoOnlyStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        .map { it.height }
        .distinct()
        .sortedDescending()
}
