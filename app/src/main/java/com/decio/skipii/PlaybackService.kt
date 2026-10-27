package com.decio.skipii

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.Stream
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Owns the one ExoPlayer. Living in a service (not the activity) is what makes background
 * playback and the media notification work; the UI talks to it through a MediaController.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val resolver = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun onCreate() {
        super.onCreate()
        // The video renderer runs on AvSync's clock offset (TextureView lip sync).
        val sources = YouTubeMediaSourceFactory(this)
        val player = ExoPlayer.Builder(this, SyncedRenderersFactory(this))
            .setMediaSourceFactory(sources)
            // Auto quality climbs after 2 s of buffer instead of the default 10 s, so a
            // video that starts low reaches HD within a few seconds (YouTube-like).
            .setTrackSelector(
                DefaultTrackSelector(
                    this,
                    AdaptiveTrackSelection.Factory(
                        /* minDurationForQualityIncreaseMs = */ 2_000,
                        /* maxDurationForQualityDecreaseMs = */ 25_000,
                        /* minDurationToRetainAfterDiscardMs = */ 25_000,
                        /* bandwidthFraction = */ 0.75f,
                    ),
                ).apply {
                    // YouTube's 60 fps HD rungs can need a different decoder configuration
                    // than the 30 fps SD rungs; by default the player then drops them from
                    // the adaptive set and Auto is stuck at 480p. Allow switching across them.
                    setParameters(
                        buildUponParameters()
                            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
                            .setAllowVideoNonSeamlessAdaptiveness(true),
                    )
                },
            )
            .setLoadControl(MemoryBudgetLoadControl(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps CPU and Wi-Fi awake while playing, so background audio isn't cut off
            // when the phone sleeps.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        player.setVideoFrameMetadataListener { _, releaseTimeNs, _, _ -> AvSync.onFrameReleased(releaseTimeNs) }
        player.addListener(SponsorSkipper(player))
        player.addListener(SpeedKeeper(player))
        // Audio only is app-wide and kept; the UI applies it on connect, this covers a start
        // without one (e.g. resuming from the media notification).
        if (AudioOnly.enabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        // Before the session's own listener: the UI must see the recovery when the error reaches it.
        player.addListener(StreamRecoverer(player, sources, resolver))
        resumeSaver = ResumeSaver(player).also(player::addListener)
        player.addListener(SessionSaver())
        watchTime = WatchTimeReporter(player).also(player::addListener)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A window the app has moved on from (a bubble left for the app opened from Home) gets its
        // task removed by System UI; the open window keeps playing. Its task is still listed, as
        // the removed one no longer is by now.
        val open = MainActivity.openTaskId()
        if (open != null && getSystemService(ActivityManager::class.java).appTasks.any { it.taskInfo.taskId == open }) {
            Log.i("Skipii", "an older window's task was removed; still open in task $open, playing on")
            return
        }
        // Swiping the app away from Recents pauses and ends the session, playing or not
        // (Derek, 2026-10-10; it used to keep playing). onDestroy saves the resume point.
        Log.i("Skipii", "task removed: pausing and stopping")
        pauseAllPlayersAndStopSelf()
    }

    private var resumeSaver: ResumeSaver? = null
    private var watchTime: WatchTimeReporter? = null

    override fun onDestroy() {
        resumeSaver?.saveNow()
        watchTime?.finish()
        session?.run {
            player.release()
            release()
        }
        session = null
        resolver.shutdown()
        super.onDestroy()
    }

    /**
     * Controllers send items carrying only a video ID (Media3 strips URIs crossing the
     * controller boundary). Each one is resolved here into a playable item; the actual
     * streams come from [StreamCache] in [YouTubeMediaSourceFactory].
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = resolver.submit<MutableList<MediaItem>> {
            mediaItems.map { item ->
                // An opened video is already loaded (the player waited for it); this resolves
                // restored and queued items, which nobody is waiting for yet.
                val info = BotCheck.withoutWaiting(item.mediaId) { StreamCache.getOrLoad(item.mediaId) }
                item.buildUpon()
                    .setUri(YouTube.watchUrl(item.mediaId))
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setTitle(item.mediaMetadata.title ?: info.name)
                            .setArtist(item.mediaMetadata.artist ?: info.uploaderName)
                            // Kept with the saved session, so a restored queue shows lengths.
                            .setDurationMs(item.mediaMetadata.durationMs ?: info.duration.takeIf { it > 0 }?.times(1000))
                            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                            .build(),
                    )
                    .build()
            }.toMutableList()
        }
    }
}

/** [StreamRecoverer]'s progress, for the UI (same process, so plain Compose state). */
object StreamRecovery {
    /** Video whose links are being refetched: the player shows its spinner, not the error. */
    var recovering by mutableStateOf<String?>(null)
        internal set

    /** Refetching failed: video ID and the reason, shown in the error panel. */
    var failed by mutableStateOf<Pair<String, String>?>(null)
        internal set
}

/**
 * YouTube refuses a stream URL (HTTP 403) once it expires or the phone's address changes —
 * mobile data does that mid-video without the default network changing. Fetches fresh
 * links and resumes at the same spot; in the service, so background audio recovers too.
 * Again only after [PROGRESS_MS] more of the video: a stream refused twice at the same
 * spot is really refused, and the error stands.
 */
@OptIn(UnstableApi::class)
private class StreamRecoverer(
    private val player: ExoPlayer,
    private val sources: MediaSource.Factory,
    private val resolver: java.util.concurrent.Executor,
) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var lastId: String? = null
    private var lastPositionMs = 0L

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val position = player.currentPosition
        if (id == lastId && position < lastPositionMs + PROGRESS_MS) {
            Log.w("Skipii", "stream refused again for $id at ${position / 1000}s; giving up")
            return
        }
        lastId = id
        lastPositionMs = position
        Log.i("Skipii", "stream refused for $id; re-extracting and resuming at ${position / 1000}s")
        StreamRecovery.failed = null
        StreamRecovery.recovering = id
        StreamCache.invalidate(id)
        resolver.execute {
            val result = runCatching { BotCheck.retryingBlocking(id) { StreamCache.getOrLoad(id) } }
            main.post {
                StreamRecovery.recovering = null
                // The user moved on (another video, Try again) while the links loaded.
                if (player.currentMediaItem?.mediaId != id || player.playerError == null) return@post
                result
                    .onSuccess { resume(item, position) }
                    .onFailure {
                        Log.w("Skipii", "re-extracting $id failed", it)
                        StreamRecovery.failed = id to YouTube.friendlyError(it)
                    }
            }
        }
    }

    /**
     * A fresh source for the same item: added behind the refused one, which is then dropped
     * so the new one takes its index (no transient jump the UI's queue trimming could see).
     */
    private fun resume(item: MediaItem, positionMs: Long) {
        val index = player.currentMediaItemIndex
        player.addMediaSource(index + 1, sources.createMediaSource(item))
        player.removeMediaItem(index)
        player.seekTo(index, positionMs)
        player.prepare()
    }

    private companion object {
        const val PROGRESS_MS = 30_000L
    }
}

/**
 * Tells the signed-in account what was actually watched, as the web player does: when a
 * video starts, the playback ping (it joins watch history); while it plays, watch-time pings
 * at 10, 20 and 30 s and then every 40 s, plus on pause, seek and moving on, each carrying
 * the stretches played since the last one. YouTube builds its recommendations on watch time;
 * with only the start ping, every video looked abandoned at 0 s (Derek, 2026-10-10: Home's
 * suggestions were worse than the app's). In the service, so background audio counts too.
 */
@OptIn(UnstableApi::class)
private class WatchTimeReporter(private val player: ExoPlayer) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var videoId: String? = null
    private var session: InnerTube.WatchSession? = null
    private var startedAt = 0L
    /** Stretches played since the last ping, in seconds of the video. */
    private val starts = ArrayList<Double>()
    private val ends = ArrayList<Double>()
    /** Where the stretch being played began (ms); null while not playing. */
    private var stretchFrom: Long? = null
    private var pings = 0
    private val tick = Runnable { onTick() }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.ifBlank { null }
        // Fresh links for the same video (StreamRecoverer) are the same viewing.
        if (id == videoId) return
        finish()
        videoId = id
        if (id == null || !Account.signedIn) return
        startedAt = android.os.SystemClock.elapsedRealtime()
        if (player.isPlaying) startStretch()
        io.execute {
            val started = runCatching { InnerTube.startWatch(id) }
                .onFailure { Log.w("Skipii", "watch start for $id", it) }.getOrNull()
            main.post { if (videoId == id) session = started }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (videoId == null || !Account.signedIn) return
        if (isPlaying) {
            startStretch()
        } else {
            endStretch(player.currentPosition)
            send(playing = false, final = false)
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (stretchFrom == null) return
        endStretch(oldPosition.positionMs)
        // A seek within the video starts a new stretch; moving to another video waits for it.
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && player.isPlaying) stretchFrom = newPosition.positionMs
    }

    /** The video is over for this session (moved on, closed, service ending): last ping. */
    fun finish() {
        main.removeCallbacks(tick)
        if (stretchFrom != null && player.currentMediaItem?.mediaId == videoId) endStretch(player.currentPosition)
        stretchFrom = null
        send(playing = false, final = true)
        videoId = null
        session = null
        starts.clear(); ends.clear()
        pings = 0
    }

    private fun startStretch() {
        stretchFrom = player.currentPosition
        schedule()
    }

    private fun endStretch(atMs: Long) {
        val from = stretchFrom ?: return
        stretchFrom = null
        main.removeCallbacks(tick)
        if (atMs > from + 250) { starts += from / 1000.0; ends += atMs / 1000.0 }
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, PING_DELAYS_MS.getOrElse(pings) { PING_DELAYS_MS.last() })
    }

    private fun onTick() {
        if (!player.isPlaying) return
        val now = player.currentPosition
        endStretch(now)
        stretchFrom = now
        send(playing = true, final = false)
        schedule()
    }

    /** Sends what has been played since the last ping (kept for later until the session exists). */
    private fun send(playing: Boolean, final: Boolean) {
        val s = session ?: return
        if (starts.isEmpty() && !final) return
        pings++
        val position = player.currentPosition / 1000.0
        val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val st = starts.toList()
        val et = ends.toList()
        starts.clear(); ends.clear()
        io.execute {
            runCatching { InnerTube.pingWatchtime(s, position, st, et, playing, elapsed, final) }
                .onFailure { Log.w("Skipii", "watch time for ${s.videoId}", it) }
        }
    }

    private companion object {
        val PING_DELAYS_MS = longArrayOf(10_000, 10_000, 10_000, 40_000).toList()
    }
}

/**
 * Keeps the last 3 minutes of the video in memory (rewinds and replays within them are
 * instant) and loads ahead as far as a memory budget allows, topping up continuously.
 *
 * The budget covers the back buffer too (the allocator counts both), so it is 60% of the
 * app's large heap (largeHeap is on): ~300 MB on a Pixel. At 1080p (~7 Mbps) that is 3 min
 * behind + ~2 min ahead; far more ahead at lower qualities, the whole video in audio only.
 * However full the budget, the player always loads [MIN_AHEAD_MS] ahead, so a full back
 * buffer can never starve playback.
 * Starting needs 1 s of media (2 s after a stall): at 1080p60 that halves what must download
 * before the first frame.
 */
@OptIn(UnstableApi::class)
private class MemoryBudgetLoadControl(
    context: Context,
    private val budgetBytes: Long = context.getSystemService(ActivityManager::class.java)
        .largeMemoryClass * 1024L * 1024L * 60 / 100,
    private val inner: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_AHEAD_MS, Int.MAX_VALUE, 1_000, 2_000)
        .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .setTargetBufferBytes(budgetBytes.toInt())
        .build(),
) : LoadControl {
    // Forwarded one by one: Kotlin's `by` delegation skips Java default methods, and every
    // method the player calls is one (the old defaults throw "not implemented").
    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = inner.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) =
        inner.retainBackBufferFromKeyframe(playerId)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters) =
        inner.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ) = inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // Media time drains faster at higher speeds; keep the same wall-clock cushion.
        val minAheadUs = (MIN_AHEAD_MS * 1_000L * maxOf(1f, parameters.playbackSpeed)).toLong()
        if (parameters.bufferedDurationUs < minAheadUs) return true
        return inner.getAllocator(parameters.playerId).totalBytesAllocated < budgetBytes
    }

    companion object {
        const val MIN_AHEAD_MS = 15_000
        const val BACK_BUFFER_MS = 3 * 60_000
    }
}

/**
 * Each video starts at 1×, or at the remembered speed ([PlaybackSpeed]). Only a new video
 * resets it: fresh links (StreamRecoverer) or a quality switch reload the same video and keep
 * the speed it was playing at.
 */
private class SpeedKeeper(private val player: ExoPlayer) : Player.Listener {
    private var videoId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.ifBlank { null } ?: return
        if (id == videoId) return
        videoId = id
        player.setPlaybackSpeed(PlaybackSpeed.forNewVideo)
    }
}

/**
 * Schedules SponsorBlock auto-skips for each video as it becomes current. Runs in the
 * service, so skips happen in background and audio-only playback too.
 *
 * Each auto-skip segment gets a PlayerMessage at its start (delivered once, so seeking
 * back into a segment plays it — the user's choice wins). On delivery the audio fades out
 * (300 ms), the player jumps to the segment end, and fades back in (450 ms): never a hard cut.
 */
@OptIn(UnstableApi::class)
private class SponsorSkipper(private val player: ExoPlayer) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        io.execute {
            val segments = SponsorBlock.fetch(id)
            main.post {
                SponsorBlock.forUi[id] = segments
                // Still the same item? (The user may have moved on during the lookup.)
                if (player.currentMediaItem?.mediaId != id) return@post
                val index = player.currentMediaItemIndex
                segments.forEach { segment ->
                    player.createMessage { _, _ -> skip(id, segment) }
                        .setLooper(Looper.getMainLooper())
                        .setPosition(index, segment.startMs)
                        .send()
                }
            }
        }
    }

    private fun skip(videoId: String, segment: SponsorBlock.Segment) {
        // Settings are read at the moment of the skip, so changing them applies immediately.
        if (!SponsorBlock.enabled || segment.action != SponsorBlock.Action.Auto) return
        if (player.currentMediaItem?.mediaId != videoId) return
        if (player.currentPosition > segment.endMs - 500) return
        Log.i("Skipii", "SponsorBlock: skipping ${segment.category.key} ${segment.startMs}-${segment.endMs} in $videoId")
        ramp(from = 1f, to = 0f, durationMs = 300) {
            player.seekTo(segment.endMs)
            SponsorBlock.lastSkip = SponsorBlock.Skip(videoId, segment)
            ramp(from = 0f, to = 1f, durationMs = 450) {}
        }
    }

    private fun ramp(from: Float, to: Float, durationMs: Long, then: () -> Unit) {
        val start = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                player.volume = from + (to - from) * t
                if (t < 1f) main.postDelayed(this, 16) else then()
            }
        }
        main.post(step)
    }
}

/**
 * Builds the media source for a video ID. YouTube serves video and audio as separate
 * streams, so they're merged. Each is wrapped in a generated DASH manifest — the same
 * technique NewPipe uses — which makes ExoPlayer fetch in ranged chunks; requesting a
 * whole stream in one go gets throttled to a crawl.
 */
@OptIn(UnstableApi::class)
private class YouTubeMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(Net.client).setUserAgent(Net.USER_AGENT),
    )
    private val fallback = DefaultMediaSourceFactory(dataSourceFactory)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { fallback.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { fallback.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val info = StreamCache.get(mediaItem.mediaId) ?: return fallback.createMediaSource(mediaItem)
        val picked = StreamSelection.pick(info)
        // Auto: every quality as one adaptive set. A chosen quality stays pinned below.
        if (QualityPreference.height == null && picked.audio != null) {
            val adaptive = adaptiveVideo(info, mediaItem)
            if (adaptive != null) {
                Log.i("Skipii", "media source ${mediaItem.mediaId}: adaptive video ${StreamSelection.autoLadder(info).joinToString("/") { "${it.height}" }}p + audio itag ${picked.audio.itag}")
                return MergingMediaSource(true, true, adaptive, source(picked.audio, info.duration, mediaItem))
            }
        }
        Log.i(
            "Skipii",
            "media source ${mediaItem.mediaId}: " + if (picked.usesSeparate) "video itag ${picked.video?.itag} (${picked.video?.height}p) + audio itag ${picked.audio?.itag}"
            else "combined fallback itag ${picked.muxed?.itag} (${picked.muxed?.height}p); video=${picked.video != null} audio=${picked.audio != null}; " +
                "audio offered: " + info.audioStreams.joinToString { "${it.itag}/${it.format}/${it.deliveryMethod}/url=${it.isUrl}/${it.audioTrackType}" },
        )
        val video = picked.video
        val audio = picked.audio
        return when {
            video != null && audio != null -> MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
                source(video, info.duration, mediaItem),
                source(audio, info.duration, mediaItem),
            )
            picked.muxed != null -> source(picked.muxed, info.duration, mediaItem)
            // Nothing playable: let the default factory fail on the watch URL so the
            // player reports an error instead of hanging.
            else -> fallback.createMediaSource(mediaItem)
        }
    }

    /**
     * One DASH adaptation set holding every rung of the Auto ladder. NewPipe generates a
     * manifest per stream; their single representations are lifted into one set so the
     * player can start low and switch up at segment boundaries.
     */
    private fun adaptiveVideo(info: org.schabi.newpipe.extractor.stream.StreamInfo, mediaItem: MediaItem): MediaSource? {
        val durationSec = info.duration.takeIf { it > 0 } ?: return null
        val manifests = StreamSelection.autoLadder(info).mapNotNull { stream ->
            runCatching {
                val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, stream.itagItem!!, durationSec)
                DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(xml.toByteArray()))
            }.getOrNull()
        }
        if (manifests.size < 2) return null
        val representations = manifests.map { it.getPeriod(0).adaptationSets[0].representations[0] }
        val base = manifests.first()
        val manifest = DashManifest(
            base.availabilityStartTimeMs, base.durationMs, base.minBufferTimeMs, false,
            C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, null, null, null, null,
            listOf(Period("0", 0, listOf(AdaptationSet(0, C.TRACK_TYPE_VIDEO, representations, emptyList(), emptyList(), emptyList())))),
        )
        return DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, mediaItem)
    }

    private fun source(stream: Stream, durationSec: Long, mediaItem: MediaItem): MediaSource {
        val url = stream.content
        val itag = stream.itagItem
        if (itag != null && durationSec > 0) {
            val dash = runCatching {
                val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, durationSec)
                val manifest = DashManifestParser().parse(Uri.parse(url), ByteArrayInputStream(xml.toByteArray()))
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, mediaItem)
            }.getOrNull()
            if (dash != null) return dash
        }
        // Manifest generation failed: plain progressive still plays, just less efficiently.
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem.buildUpon().setUri(url).build())
    }
}
