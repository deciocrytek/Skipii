package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import org.schabi.newpipe.extractor.stream.Stream
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
    /** The card's own "Not interested" / "Don't recommend channel" (signed-in feeds only, #16). */
    val feedback: CardFeedback? = null,
)

/** Tokens YouTube puts on a signed-in card for its feedback items; sent back as they are ([InnerTube.sendFeedback]). */
@Immutable
data class CardFeedback(val notInterested: String?, val dontRecommend: String?)

@Immutable
data class VideoDetails(
    val item: VideoItem,
    /** The description with its links (Description.kt). */
    val description: Description,
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
    /** Every caption track YouTube has for this video (Captions.kt). */
    val captions: List<CaptionTrack> = emptyList(),
    /** Distinct audio tracks offered when the video has multiple (dubs, audio description); empty for single-track videos. */
    val audioTracks: List<AudioTrack> = emptyList(),
    /** The active audio track's id ([AudioTrack.id]), or null for single-track / unknown. */
    val audioTrackId: String? = null,
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

/** One of YouTube's comment orders: [newest] = "Newest" (else "Top"); [token] reloads the comments in that order. */
@Immutable
data class CommentSort(val newest: Boolean, val token: String, val selected: Boolean)

@Immutable
data class CommentsPage(
    val comments: List<Comment>,
    val total: Int,
    val disabled: Boolean,
    val next: String?,
    /** The video's chapters, read off the same first `next` page (see [CommentFeed.parseChapters]). */
    val chapters: List<Chapter> = emptyList(),
    /** YouTube's sort choices for these comments (Top / Newest), from the first page's header; empty on later pages. */
    val sorts: List<CommentSort> = emptyList(),
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

    /**
     * [wait] false: no quiet retries through a bot check ([BotCheck.withoutWaiting]) — Shorts
     * would rather move on than hold a short on a spinner for up to half a minute.
     */
    /**
     * Music (#35: it plays at 1× even with a speed remembered): YouTube's own category, or a channel
     * YouTube makes for an artist's tracks ("Artist - Topic"). Categories come in English (hl=en).
     */
    fun isMusic(info: StreamInfo): Boolean =
        runCatching { info.category }.getOrNull().equals("Music", ignoreCase = true) ||
            info.uploaderName.orEmpty().endsWith(" - Topic")

    suspend fun details(id: String, wait: Boolean = true): VideoDetails = withContext(Dispatchers.IO) {
        // A downloaded video (Downloads.kt): YouTube's live details when they come (More videos, current counts),
        // shown with the kept stream's quality; offline, or when YouTube refuses, the details kept with it.
        val kept = Downloads.details(id)
        if (kept != null) {
            if (!Downloads.online) return@withContext kept
            return@withContext try {
                kotlinx.coroutines.withTimeout(8_000) { Downloads.overlay(liveDetails(id, wait = false)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) kept else throw e
            } catch (e: Exception) {
                AppLog.i("Skipii", "details for downloaded $id from the phone (${e.javaClass.simpleName})")
                kept
            }
        }
        liveDetails(id, wait)
    }

    private suspend fun liveDetails(id: String, wait: Boolean): VideoDetails = withContext(Dispatchers.IO) {
        val load = { Net.onceMoreAfterStall("links for $id") { StreamCache.getOrLoad(id) } }
        val info = if (wait) BotCheck.retrying(id, load) else BotCheck.withoutWaiting(id, load)
        val audioTracks = AudioTracks.from(info)
        val picked = StreamSelection.pick(info, id)
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
                if (d.type == org.schabi.newpipe.extractor.stream.Description.HTML) Description.fromHtml(d.content)
                else Description.fromPlain(d.content)
            } ?: Description.Empty,
            likes = info.likeCount,
            subscribers = info.uploaderSubscriberCount,
            related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideoItem() },
            // A just-ended live stream plays from YouTube's manifest for it (YouTubeMediaSourceFactory), so it needs one.
            playable = picked.isPlayable && (
                info.streamType != StreamType.POST_LIVE_STREAM || info.dashMpdUrl.isNotBlank() ||
                    (picked.video?.let(PostLiveManifests::cached) != null && picked.audio?.let(PostLiveManifests::cached) != null)
                ),
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
            // NewPipe keeps each track once, as TTML; the same link with fmt=srv3 has the word timings.
            captions = info.subtitles.orEmpty()
                .filter { !it.content.isNullOrBlank() }
                .map { CaptionTrack(it.languageTag, it.isAutoGenerated, it.content.replace(Regex("([?&])fmt=[^&]*"), "$1fmt=srv3")) }
                .distinctBy { it.language to it.auto },
            audioTracks = audioTracks,
            audioTrackId = picked.audio?.let { it.audioTrackId.orEmpty() }?.takeIf { audioTracks.isNotEmpty() },
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
        is AgeRestrictedContentException -> str(R.string.player_error_age_restricted)
        is GeographicRestrictionException -> str(R.string.player_error_geo)
        is SignInConfirmNotBotException ->
            str(R.string.player_error_bot_check) + "\n" + str(R.string.player_error_bot_check_hint)
        is org.schabi.newpipe.extractor.exceptions.PrivateContentException -> str(R.string.player_error_private)
        // Members-only videos (e.g. "Insider Edition") need a channel membership; Skipii's
        // stream extraction is anonymous, so it can't play them even when you're a member.
        is ContentNotAvailableException ->
            if (t.message.orEmpty().contains("member", ignoreCase = true) || t is org.schabi.newpipe.extractor.exceptions.PaidContentException)
                str(R.string.player_error_members_only)
            else str(R.string.player_error_unavailable)
        is ReCaptchaException -> str(R.string.player_error_rate_limited)
        is IOException -> str(R.string.player_error_offline)
        else -> str(R.string.player_error_generic)
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
                val picked = StreamSelection.pick(it, id)
                // Details came, but the stream request was refused: a bot check, not a dead
                // video (or one with only ANDROID's 360p combined stream), so nothing is
                // cached and the load can be retried.
                if (!picked.usesSeparate && BotCheck.refusedSince(id, startedAt)) throw StreamsRefused(id, it)
                // No stream links because the request for them stalled (NewPipe carries on without it): a stall, tried
                // again (Net.onceMoreAfterStall), never kept for hours as a video with only its 360p combined stream.
                if (!picked.usesSeparate && Net.lastStallAt >= startedAt) throw java.io.InterruptedIOException("stream links request stalled for $id")
                if (it.streamType == StreamType.POST_LIVE_STREAM) PostLiveManifests.prepare(it, picked)
                entries[id] = Entry(it, System.currentTimeMillis())
                Net.noteMediaHost((picked.video ?: picked.muxed)?.content)
                AppLog.i(
                    "Skipii",
                    "links for $id in ${SystemClock.elapsedRealtime() - startedAt} ms${if (it.streamType == StreamType.POST_LIVE_STREAM) " (live stream that just ended)" else ""}: " +
                        "video ${StreamSelection.heights(it).takeIf { h -> h.isNotEmpty() }?.joinToString("/", postfix = "p") ?: "none usable (${it.videoOnlyStreams.size} offered)"}, " +
                        "${it.audioStreams.size} audio (${AudioTracks.from(it).size.coerceAtLeast(1)} track(s)), ${it.videoStreams.size} combined; " +
                        "picked ${picked.video?.let { v -> "itag ${v.itag} ${v.height}p" } ?: "no video"} + ${picked.audio?.let { a -> "itag ${a.itag}${a.audioTrackId?.let { t -> " [$t]" }.orEmpty()}" } ?: "no audio"}",
                )
            }
        }
        var running = inflight.putIfAbsent(id, mine)
        // A load that just failed but isn't cleared away yet: a retry starts a new one rather than rejoin it.
        if (running != null && running.isDone && inflight.replace(id, running, mine)) running = null
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
        var refused: SignInConfirmNotBotException? = null
        try {
            for ((attempt, wait) in RetryDelaysMs.withIndex()) {
                try {
                    return load()
                } catch (e: IOException) {
                    // A retry that couldn't reach YouTube at all (Android cuts an idle app's network soon after it
                    // leaves the screen) says nothing new: the bot check stands, not "Couldn't reach YouTube" (a
                    // problem report, 2026-11-25, on working Wi-Fi). Seen first, it's the error as before.
                    throw refused ?: e
                } catch (e: SignInConfirmNotBotException) {
                    refused = e
                    // The user moved on while that request ran (it can't be interrupted): no note, no more tries.
                    currentCoroutineContext().ensureActive()
                    AppLog.w("Skipii", "bot check for $id; trying again in ${wait / 1000}s (${attempt + 1}/${RetryDelaysMs.size})")
                    waiting = id
                    delay(wait)
                }
            }
            return try {
                load().also { AppLog.i("Skipii", "bot check for $id passed on the last try") }
            } catch (e: IOException) {
                throw refused ?: e
            }
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
            AppLog.w("Skipii", "bot check for $id; giving up on the real streams, playing the 360p combined one")
            StreamCache.keepRefused(id, partial)
            return partial
        }
        AppLog.w("Skipii", "bot check for $id; giving up")
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
        // Saved before qualities were named by the short side ("1920p" from an upright video's best): Auto again, once.
        if (prefs?.getBoolean(KEY_SHORT_SIDE, false) != true) {
            if (height != null && height !in StandardQualities) set(null)
            prefs?.edit()?.putBoolean(KEY_SHORT_SIDE, true)?.apply()
        }
    }

    private const val KEY_SHORT_SIDE = "quality_short_side"
    private val StandardQualities = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

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
        val playedHeight: Int? get() = (if (usesSeparate) video else muxed)?.let(::side)?.takeIf { it > 0 }
    }

    /**
     * A stream's quality as YouTube names it: its SHORT side, so an upright 1080×1920 video is "1080p", as its
     * landscape twin is. Heights named the long side: picking an upright video's best quality saved "1920p" (or
     * "1126p"), and every landscape video after it played pinned at 1080p with no Auto (two problem reports,
     * 2026-11-26); Auto also stopped upright videos at 720×1280.
     */
    fun side(stream: VideoStream): Int = if (stream.width > 0 && stream.height > 0) minOf(stream.width, stream.height) else stream.height

    /**
     * A live stream that just ended ("post-live DVR", problem report 2026-11-26) comes with no plain links: every
     * stream is DASH, its [Stream.getContent] the base of numbered segments [ItagItem.targetDurationSec] long.
     * The media source builds a manifest for those (`YoutubePostLiveStreamDvrDashManifestCreator`).
     */
    fun isPostLive(stream: Stream): Boolean =
        stream.deliveryMethod == DeliveryMethod.DASH && !stream.isUrl && (stream.itagItem?.targetDurationSec ?: 0) > 0

    /** A stream the player can open: a plain link, or a just-ended live stream's segments ([isPostLive]). */
    fun isUsable(stream: Stream): Boolean =
        (stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) || isPostLive(stream)

    /** Video streams worth offering: usable, a known height, and one this phone's decoder can start ([DecoderLimits]). */
    private fun videoCandidates(info: StreamInfo): List<VideoStream> {
        val usable = info.videoOnlyStreams.filter { isUsable(it) && it.height > 0 }
        return usable.filter { DecoderLimits.allows(side(it), it.fps) }.ifEmpty { usable }
    }

    fun pick(info: StreamInfo, videoId: String? = null): Picked {
        // H.264 first: every device decodes it in hardware. VP9 next, anything else last.
        fun formatRank(format: MediaFormat?) = when (format) {
            MediaFormat.MPEG_4 -> 0
            MediaFormat.WEBM -> 1
            else -> 2
        }
        val candidates = videoCandidates(info)
        val chosen = QualityPreference.height
        // A chosen quality plays exactly if offered, else the nearest below it. Auto caps at 1080p.
        val ceiling = chosen ?: MAX_HEIGHT
        val video = (candidates.filter { side(it) <= ceiling }.ifEmpty { candidates })
            .sortedWith(
                compareBy<VideoStream> { if (side(it) <= ceiling) ceiling - side(it) else side(it) }
                    .thenBy { formatRank(it.format) },
            )
            .firstOrNull()
        // For multi-audio videos, resolve the preferred track (Original by default, so single-track and
        // normal English videos are completely unaffected). Fallback to ANY audio track so a video never
        // drops down to the 360p combined stream (the blurry-video bug, 2026-10-04).
        val usableAudio = info.audioStreams.filter { isUsable(it) }
        val tracks = AudioTracks.from(info)
        val wantedTrackId = if (tracks.size > 1) {
            AudioTrackPreference.resolveTrackId(tracks, overrideId = videoId?.let(AudioTrackPreference::overrideFor))
        } else null
        val audio = wantedTrackId
            ?.let { id -> usableAudio.filter { it.audioTrackId.orEmpty() == id }.takeIf { it.isNotEmpty() } }
            ?.let(AudioTracks::bestStream)
            ?: usableAudio
                .sortedWith(
                    compareBy<AudioStream> { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1 }
                        .thenBy { if (AudioTracks.isDrc(it.content)) 1 else 0 }
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
        val usable = videoCandidates(info).filter { side(it) <= MAX_HEIGHT && it.itagItem != null }
        val family = usable.filter { it.codec?.startsWith("avc") == true }.ifEmpty { usable.filter { it.format == MediaFormat.WEBM } }
        return family.groupBy { side(it) }
            .map { (_, sameHeight) -> sameHeight.maxBy { it.fps * 100_000L + it.bitrate } }
            .sortedBy { it.height }
    }

    fun heights(info: StreamInfo): List<Int> = videoCandidates(info)
        .map { side(it) }
        .distinct()
        .sortedDescending()
}

/**
 * Video formats this phone's decoder wouldn't start (problem report 2026-11-26: a Redmi's hardware H.264 decoder
 * failed on every 1080p 60 fps stream while 720p 60 played; Android still called 1080p60 supported). The service
 * notes each failure ([noteFailed]) and rebuilds the video one step down, at the same spot, and Auto and the quality
 * list leave the format out from then on. Kept for this run of the app only: a decoder that was merely busy (another
 * app, Shorts' players) shouldn't cost the phone its HD for good.
 */
object DecoderLimits {
    private data class Failed(val height: Int, val fps: Int)
    private val failed = java.util.concurrent.CopyOnWriteArrayList<Failed>()

    /**
     * Whether a stream may play: not one that failed, nor anything bigger. Sizes are short sides ([StreamSelection.side]):
     * a landscape 1080p60 failing mustn't rule out an upright 720×1280.
     */
    fun allows(height: Int, fps: Int): Boolean = failed.none { f -> height > f.height || (height == f.height && fps >= f.fps) }

    /** A decoder wouldn't start for [height] at [fps]; false if that format was already left out (nothing new to try). */
    fun noteFailed(height: Int, fps: Int): Boolean {
        if (height <= 0 || !allows(height, fps)) return false
        failed += Failed(height, fps.coerceAtLeast(1))
        return true
    }

    internal fun resetForTest() = failed.clear()

    /** For the problem report: what's being left out. */
    fun summary(): String? = failed.takeIf { it.isNotEmpty() }?.joinToString { "${it.height}p${it.fps}" }
}

/**
 * Manifests for a live stream that just ended ([StreamSelection.isPostLive]), built with NewPipe's creator from the
 * extractor's own per-stream links. YouTube's ready-made manifest for it ([StreamInfo.getDashMpdUrl]) plays too, but
 * its links were refused after ~40 segments every time (probed 2026-11-26: 403 from segment 42 on, while the
 * per-stream link served all 400 asked for), so the video stalled every 20 s for fresh links. The creator asks
 * YouTube how many segments there are (one request per stream), so it runs here, on the extraction's thread, for
 * every stream the media source may use; the media source only reads what's ready ([cached]).
 */
object PostLiveManifests {
    private val pool = Executors.newFixedThreadPool(4)

    fun prepare(info: StreamInfo, picked: StreamSelection.Picked) {
        val durationSec = info.duration.takeIf { it > 0 } ?: return
        val streams = (StreamSelection.autoLadder(info) + listOfNotNull(picked.video, picked.audio))
            .filter { StreamSelection.isPostLive(it) && it.itagItem != null }
            .distinctBy { it.content }
        val startedAt = SystemClock.elapsedRealtime()
        val failures = streams.map { stream ->
            pool.submit<Throwable?> { runCatching { create(stream, durationSec) }.onFailure { e -> AppLog.w("Skipii", "post-live manifest for itag ${stream.itagItem?.id}", e) }.exceptionOrNull() }
        }.mapNotNull { it.get() }
        AppLog.i("Skipii", "post-live manifests for ${info.id}: ${streams.size - failures.size} of ${streams.size} in ${SystemClock.elapsedRealtime() - startedAt} ms")
        // A connection that stalled meanwhile: the load is tried again rather than kept without its manifests.
        failures.firstOrNull { Net.isStall(it) }?.let { throw java.io.InterruptedIOException("post-live manifests stalled for ${info.id}").apply { initCause(it) } }
    }

    private fun create(stream: Stream, durationSec: Long): String {
        val itag = stream.itagItem!!
        return org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
            .fromPostLiveStreamDvrStreamingUrl(stream.content, itag, itag.targetDurationSec, durationSec)
    }

    /** The manifest [prepare] made for [stream], or null (never a network request: callers may be on the main thread). */
    fun cached(stream: Stream): String? {
        val cache = org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator.getCache()
        return if (cache.containsKey(stream.content)) cache.get(stream.content)?.second else null
    }
}
