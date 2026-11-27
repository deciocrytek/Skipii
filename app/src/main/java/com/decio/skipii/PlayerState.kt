package com.decio.skipii

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UI's view of playback. Holds the MediaController plus everything the player
 * screens render, as snapshot state so only readers recompose.
 */
@Stable
class PlayerState(private val scope: CoroutineScope) {
    var controller by mutableStateOf<MediaController?>(null)
        internal set

    /** What's loaded (or loading). Null means nothing — no mini-player. */
    var current by mutableStateOf<VideoItem?>(null)
        private set
    var details by mutableStateOf<VideoDetails?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** [error] is YouTube's bot check: the panel adds its own advice and Open in YouTube. */
    var botCheck by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** The player's playback speed (see [PlaybackSpeed]). */
    var speed by mutableFloatStateOf(1f)
        private set

    init {
        // The service's stream recovery has no player event for its outcome: follow its state.
        scope.launch {
            snapshotFlow { StreamRecovery.recovering to StreamRecovery.failed }.collect { (_, failed) ->
                syncFromPlayer()
                if (failed != null && failed.first == current?.id) {
                    fail(failed.second)
                    isBuffering = false
                }
            }
        }
    }
    /**
     * The open video has shown the spinner for [StuckAfterMs] without starting or failing (GitHub #102: it can
     * sit there for good, no error). The player then offers a problem report (Diagnostics.kt).
     */
    var stuck by mutableStateOf(false)
        private set
    private var waitingSince = 0L
    /** How long the spinner has been up (0 = it isn't), for the report. */
    val stuckForMs: Long get() = if (waitingSince > 0L) android.os.SystemClock.elapsedRealtime() - waitingSince else 0L

    init {
        scope.launch {
            snapshotFlow { Triple(current?.id, (isBuffering || (loading && details == null)) && BotCheck.waiting != current?.id, error == null) }
                .collectLatest { (id, waiting, noError) ->
                    stuck = false
                    // Waiting out a bot check has its own note under the spinner (and ends in its own panel): offering a
                    // problem report meanwhile read as "it's hung" (two reports sent mid-wait, 2026-11-25).
                    if (id == null || !waiting || !noError) { waitingSince = 0L; return@collectLatest }
                    waitingSince = android.os.SystemClock.elapsedRealtime()
                    delay(StuckAfterMs)
                    stuck = true
                    AppLog.w(
                        TAG,
                        "player: $id still on the spinner after ${StuckAfterMs / 1000} s (details ${if (details != null) "in" else "not yet"}, " +
                            "engine ${controller?.playbackState}, playWhenReady ${controller?.playWhenReady}, at ${positionMs / 1000}s, loaded to ${bufferedMs / 1000}s)",
                    )
                }
        }
    }

    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    /** Set once real frames have reported their size; the stream's own numbers no longer apply. */
    private var aspectFromFrames = false

    /**
     * The portrait player's shape: the video's own, within [StageMinAspect]..[StageMaxAspect].
     * Audio only keeps it too (Derek, 2026-10-14), so switching never moves the page.
     */
    val stageAspect: Float get() = videoAspect.coerceIn(StageMinAspect, StageMaxAspect).coerceAtLeast(stageFloor)
    /**
     * The flattest the stage may be for the window, so it stays within [StageMaxHeightShare]
     * of its height (a short split-screen pane); set by the shell from the window's size.
     */
    var stageFloor by mutableFloatStateOf(StageMinAspect)
    var firstFrameRendered by mutableStateOf(false)
        private set
    /**
     * The shared video surface still holds the frame from when audio only began. Turning video
     * back on switches straight away, so the picture stays hidden (thumbnail + spinner) until
     * the renderer draws a fresh frame, instead of showing that frozen one. Starting in audio
     * only, the surface has no frame of this video yet either.
     */
    var videoStale by mutableStateOf(AudioOnly.enabled)
        private set
    /** Only once video is wanted again: going into audio only, the last frame stays as the audio stage fades over it. */
    val hidesStaleVideo: Boolean get() = videoStale && !AudioOnly.enabled

    /**
     * The open video plays from a sound-only download (Downloads.kt): no frame ever comes, so the picture stays
     * hidden over its thumbnail (the shared surface would keep the last video's frame), and there's no fullscreen
     * or picture-in-picture for it, as in audio only.
     */
    val audioOnlyDownload: Boolean get() = current?.id?.let { id -> Downloads.entry(id)?.let { it.ready && it.audioOnly } } == true
    /** What the decoder is really producing, e.g. "1920×1080 · H.264 · itag 299". */
    var playingFormat by mutableStateOf<String?>(null)
        private set
    /** Height of the frames actually decoded; the quality label shows this when known. */
    var decodedHeight by mutableStateOf<Int?>(null)
        private set

    var expanded by mutableStateOf(false)
    /** Fullscreen: requested (button, swipe up; upright for a vertical video) or entered (rotation). */
    var fullscreen by mutableStateOf(false)
    /** Which bottom sheet is open over the player, if any. */
    var sheet by mutableStateOf<PlayerSheet?>(null)

    val comments = CommentsState(scope).apply {
        // NewPipe only knows chapters typed into the description; YouTube's auto chapters come
        // from the watch page the comments load from.
        onChapters = { id, chapters ->
            val d = details
            if (d != null && d.item.id == id && d.chapters.isEmpty()) details = d.copy(chapters = chapters)
        }
        onSortFailed = { actionMessage = it }
    }

    /** The open video's captions (Captions.kt). */
    val captions = CaptionsState(scope) { controller?.currentPosition ?: positionMs }

    // ── Signed-in account state for the open video (full sync) ──
    /** Like/subscribe state from the account; null while loading or signed out. */
    var accountState by mutableStateOf<VideoAccountState?>(null)
        private set
    private var originalLike = LikeStatus.None
    /** A short message after an account action fails (shown briefly over the player). */
    var actionMessage by mutableStateOf<String?>(null)
    /** An Undo offered with the message it belongs to (shown only while that message is). */
    var actionUndo by mutableStateOf<Pair<String, () -> Unit>?>(null)

    private var loadJob: Job? = null
    /** A video picked before the service connection finished; started on connect. */
    private var pendingStart: VideoItem? = null

    // ── Queue ──
    // The player's playlist is always [current, ...queue]: opening a video swaps the
    // current item, auto-advancing drops the finished one. Items carry only an id across
    // the controller boundary, so the full VideoItems are remembered here.
    private val known = HashMap<String, VideoItem>()
    /** Videos after the current one, in play order. */
    var queue by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    /** The video ID for which the user cancelled the "Up Next" auto-advance countdown. */
    var upNextCancelledFor by mutableStateOf<String?>(null)
        private set

    val upNextCountdown: UpNextCountdown?
        get() = upNextCountdown(
            positionMs = positionMs,
            durationMs = durationMs,
            hasNext = queue.isNotEmpty(),
            isPlaying = isPlaying,
            cancelled = upNextCancelledFor != null && upNextCancelledFor == current?.id,
        )

    fun cancelUpNext() {
        upNextCancelledFor = current?.id
        UpNextHold.setHold(true)
    }

    fun open(item: VideoItem, expand: Boolean = true, startAtMs: Long? = null) {
        if (expand) expanded = true
        if (current?.id == item.id && error == null) { startAtMs?.let(::seekTo); return }
        known[item.id] = item
        startAt = startAtMs?.let { item.id to it }
        show(item, startPlayback = true)
    }

    /** A link's t= for the video being opened (IncomingLinks): wins over the resume position. */
    private var startAt: Pair<String, Long>? = null

    /**
     * Shows [item] as the current video: details, comments and account state load; with
     * [startPlayback] it also becomes the playing item (otherwise the player already moved
     * to it, e.g. the queue advanced).
     */
    private var openedAt = 0L

    private fun show(item: VideoItem, startPlayback: Boolean) {
        openedAt = android.os.SystemClock.elapsedRealtime()
        AppLog.i("SkipiiTime", "open ${item.id}")
        if (startPlayback) {
            Net.prewarmMediaConnection()
            // The video that was playing stops now, not once this one's links are in: it played on under the new
            // video's spinner — and under its error when the links never came (bot check: two minutes in a
            // problem report, 2026-11-26), its own errors landing on the new video. Stopping also frees the
            // connection for this load. Its queue stays; startPlayback swaps the current slot.
            controller?.takeIf { it.currentMediaItem != null && it.currentMediaItem?.mediaId != item.id }?.let { player ->
                player.pause()
                player.stop()
                // The shared surface keeps the stopped video's last frame: hidden (this video's thumbnail shows) until
                // this one draws its own.
                videoStale = true
            }
        }
        retryOnNetwork = false
        upNextCancelledFor = null
        UpNextHold.setHold(false)
        current = item
        details = null
        error = null
        botCheck = false
        firstFrameRendered = false
        videoAspect = 16f / 9f
        aspectFromFrames = false
        decodedHeight = null
        playingFormat = null
        loading = true
        sheet = null
        comments.clearFor(item.id)
        captions.clear()
        accountState = null
        loadAccountState(item.id)
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }
                .onSuccess { d ->
                    AppLog.i("SkipiiTime", "details +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
                    details = d
                    // The player takes the video's shape before its first frame arrives.
                    if (!aspectFromFrames) d.aspect?.let { videoAspect = it }
                    // Comments wait for the stream data: their request duplicates the video
                    // page fetch and only slowed down the critical path.
                    comments.load(item.id)
                    known[item.id] = d.item
                    // Opened from a bare id (a description link): the details fill in the title, channel, etc.
                    if (current?.id == d.item.id && current?.title.isNullOrBlank()) current = d.item
                    captions.useTracks(d.captions, d.item.id)
                    if (!d.playable) error = str(R.string.player_error_no_stream)
                    else if (startPlayback) startPlayback(d.item)
                }
                .onFailure {
                    // Cancelled by the next open (a bot-check wait can last ~40 s): this
                    // video's outcome no longer matters, and the new load owns the state.
                    if (it is CancellationException) throw it
                    AppLog.w(TAG, "details failed for ${item.id}", it)
                    fail(it)
                    // Comments wait for details; without this their spinner never stopped.
                    comments.load(item.id)
                }
            loading = false
        }
    }

    private fun loadAccountState(id: String) {
        if (!Account.signedIn) return
        scope.launch {
            runCatching { InnerTube.videoState(id) }
                .onSuccess { if (current?.id == id) { accountState = it; originalLike = it.like } }
                .onFailure { AppLog.w(TAG, "account state for $id", it) }
        }
    }

    private fun mediaItemFor(item: VideoItem) = MediaItem.Builder()
        .setMediaId(item.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.channel)
                .setArtworkUri(item.thumbnail?.toUri())
                .apply { if (item.durationSec > 0) setDurationMs(item.durationSec * 1000L) }
                .build(),
        )
        .build()

    private fun startPlayback(item: VideoItem) {
        val player = controller ?: run { pendingStart = item; return }
        // Pick up where this video was left off (ResumeStore; written by the service).
        val linked = startAt?.takeIf { it.first == item.id }?.second
        startAt = null
        replaceCurrent(player, item, positionMs = linked ?: ResumeStore.startPosition(item.id))
        pendingList?.takeIf { it.first == item.id }?.let { (_, rest) ->
            pendingList = null
            clearQueue()
            rest.forEach { known[it.id] = it }
            player.addMediaItems(rest.map(::mediaItemFor))
        }
        player.play()
    }

    /** Play all / Shuffle's videos after the first, queued once the first is in the current slot. */
    private var pendingList: Pair<String, List<VideoItem>>? = null

    /**
     * A whole list (a playlist's Play all / Shuffle, GitHub #81): the first plays now, the rest
     * becomes the queue, replacing what was queued.
     */
    fun playAll(items: List<VideoItem>, shuffle: Boolean = false) {
        val list = (if (shuffle) items.shuffled() else items).distinctBy { it.id }
        val first = list.firstOrNull() ?: return
        pendingList = first.id to list.drop(1)
        if (current?.id == first.id && error == null) {
            // Already the current video: open() wouldn't restart it, so queue the rest here.
            expanded = true
            controller?.let { player ->
                pendingList = null
                clearQueue()
                list.drop(1).forEach { known[it.id] = it }
                player.addMediaItems(list.drop(1).map(::mediaItemFor))
                player.seekTo(player.currentMediaItemIndex, 0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            return
        }
        // Something in the player already: queue the rest now, behind it, so they're there (and Next works) even if the
        // first one fails to load; opening it then swaps the current slot and keeps the queue. An empty player has no
        // slot to queue behind, so the rest waits for the first to start (startPlayback).
        controller?.takeIf { it.mediaItemCount > 0 }?.let { player ->
            pendingList = null
            clearQueue()
            list.drop(1).forEach { known[it.id] = it }
            player.addMediaItems(list.drop(1).map(::mediaItemFor))
        }
        open(first)
    }

    /**
     * Puts [item] in the current slot, keeping the queue behind it: insert after, jump to
     * it, drop the old one. (setMediaItem would wipe the queue; replaceMediaItem with the
     * same id would keep the old media source, so a quality change wouldn't apply.)
     */
    private fun replaceCurrent(player: MediaController, item: VideoItem, positionMs: Long) {
        if (player.mediaItemCount == 0) {
            player.setMediaItem(mediaItemFor(item), positionMs)
        } else {
            // The controller reports the transition synchronously inside seekTo; the flag
            // stops onPlayerMovedTo from trimming while this swap is half done.
            swapping = true
            try {
                val index = player.currentMediaItemIndex
                player.addMediaItem(index + 1, mediaItemFor(item))
                player.seekTo(index + 1, positionMs)
                player.removeMediaItem(index)
            } finally {
                swapping = false
            }
        }
        player.prepare()
    }

    /** Plays right after the current video. Nothing playing: starts it in the mini-player. */
    fun playNext(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, mediaItemFor(item))
        actionMessage = str(R.string.player_playing_next)
        continueIfEnded(player)
    }

    /** Queued after the current video already finished: carry on into it, as YouTube does. */
    private fun continueIfEnded(player: MediaController) {
        if (player.playbackState == Player.STATE_ENDED && player.currentMediaItemIndex + 1 < player.mediaItemCount) {
            player.seekTo(player.currentMediaItemIndex + 1, 0L)
            player.play()
        }
    }

    fun addToQueue(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(mediaItemFor(item))
        actionMessage = str(R.string.player_added_to_queue)
        continueIfEnded(player)
    }

    /** The bar's Next: the queue's first video (the bar only offers it while the queue has one). */
    fun playNextUp() {
        if (queue.isNotEmpty()) playFromQueue(0)
    }

    /** The chapter playing at [ms], or null when the video has none. */
    fun chapterAt(ms: Long): String? =
        details?.chapters?.lastOrNull { it.startMs <= ms }?.title?.takeIf { it.isNotBlank() }

    /** [index] into [queue]. */
    fun playFromQueue(index: Int) {
        val player = controller ?: return
        upNextCancelledFor = null
        UpNextHold.setHold(false)
        player.seekTo(player.currentMediaItemIndex + 1 + index, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** Moves queued item at [from] to [to] (0-based indices into [queue]). */
    fun moveInQueue(from: Int, to: Int) {
        val player = controller ?: return
        val next = reorderList(queue, from, to) ?: return
        queue = next
        val base = player.currentMediaItemIndex + 1
        if (base + from < player.mediaItemCount && base + to < player.mediaItemCount) {
            player.moveMediaItem(base + from, base + to)
        }
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        player.removeMediaItem(player.currentMediaItemIndex + 1 + index)
    }

    fun clearQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
    }

    private fun rebuildQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        queue = (from until player.mediaItemCount).map { i -> itemFor(player.getMediaItemAt(i)) }
        // "Download my queue" (Downloads.kt): what's queued downloads by itself on Wi-Fi.
        Downloads.syncQueue(queue)
    }

    private fun itemFor(media: MediaItem): VideoItem = known[media.mediaId] ?: VideoItem(
        id = media.mediaId,
        title = media.mediaMetadata.title?.toString().orEmpty(),
        channel = media.mediaMetadata.artist?.toString().orEmpty(),
        channelUrl = null,
        thumbnail = media.mediaMetadata.artworkUri?.toString(),
        avatar = null,
        durationSec = (media.mediaMetadata.durationMs ?: 0L) / 1000L,
        views = -1,
        uploaded = null,
    ).also { known[media.mediaId] = it }

    /** The player moved on by itself (queue advanced, notification "next"): follow it. */
    private var swapping = false

    private fun onPlayerMovedTo(media: MediaItem) {
        val player = controller ?: return
        if (swapping || media.mediaId.isBlank()) return
        // Keep the playlist as [current, ...queue]: finished items fall off the front.
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
        if (media.mediaId != current?.id) show(itemFor(media), startPlayback = false)
    }

    // ── SponsorBlock (segments fetched by the service; read here for the UI) ──
    val segments: List<SponsorBlock.Segment>
        get() = current?.id?.let { SponsorBlock.forUi[it] }.orEmpty()

    /** The "Ask" segment playback is inside right now, if any — drives the Skip pill. */
    val askSegment: SponsorBlock.Segment?
        get() = if (!SponsorBlock.enabled) null else segments.firstOrNull {
            it.action == SponsorBlock.Action.Button && positionMs in it.startMs until it.endMs - 500
        }

    fun skipSegment(segment: SponsorBlock.Segment) = seekTo(segment.endMs)

    fun undoSkip(skip: SponsorBlock.Skip) {
        if (skip.videoId == current?.id) seekTo(skip.segment.startMs)
        SponsorBlock.lastSkip = null
    }

    /** After sign-in or sign-out: reload (or drop) the open video's account state. */
    fun refreshAccountState() {
        val item = current ?: return
        accountState = null
        if (!Account.signedIn) return
        scope.launch {
            runCatching { InnerTube.videoState(item.id) }
                .onSuccess { if (current?.id == item.id) { accountState = it; originalLike = it.like } }
                .onFailure { AppLog.w(TAG, "account state for ${item.id}", it) }
        }
    }

    /** Like count adjusted for the user's own change since the video opened. */
    val displayLikes: Long
        get() {
            val base = details?.likes ?: return -1
            val now = accountState?.like ?: return base
            return base + when {
                now == LikeStatus.Like && originalLike != LikeStatus.Like -> 1
                now != LikeStatus.Like && originalLike == LikeStatus.Like -> -1
                else -> 0
            }
        }

    fun setLike(target: LikeStatus) {
        val id = current?.id ?: return
        val before = accountState ?: return
        val next = if (before.like == target) LikeStatus.None else target
        accountState = before.copy(like = next)
        scope.launch {
            runCatching { InnerTube.setLike(id, next) }
                .onFailure { accountState = before; actionMessage = str(R.string.player_like_failed) }
        }
    }

    fun toggleSubscribe() {
        val before = accountState ?: return
        val channelId = before.channelId
            ?: details?.item?.channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
            ?: return
        val subscribe = before.subscribed != true
        accountState = before.copy(subscribed = subscribe, channelId = channelId)
        scope.launch {
            runCatching { InnerTube.setSubscribed(channelId, subscribe) }
                .onSuccess { if (!subscribe) Bells.forget(channelId) }
                .onFailure { accountState = before; actionMessage = str(R.string.player_subscribe_failed) }
        }
    }

    /** Long-press Save. Runs here, not in the sheet: closing the sheet would cancel its scope mid-request. */
    fun saveToWatchLater(video: VideoItem) {
        scope.launch {
            runCatching { InnerTube.setInWatchLater(video.id, true) }
                .onSuccess { actionMessage = str(R.string.player_saved_watch_later) }
                .onFailure { actionMessage = str(R.string.player_watch_later_failed); AppLog.w(TAG, "watch later ${video.id}", it) }
        }
    }

    /** "Not interested" on a card (#16): it leaves the feeds now, and YouTube learns from it. */
    fun notInterested(video: VideoItem) = sendFeedback(video, video.feedback?.notInterested, channel = false)

    /** "Don't recommend channel" (#16): every card of that channel leaves the feeds. */
    fun dontRecommendChannel(video: VideoItem) = sendFeedback(video, video.feedback?.dontRecommend, channel = true)

    private fun sendFeedback(video: VideoItem, token: String?, channel: Boolean) {
        token ?: return
        Dismissed.hide(video, channel)
        scope.launch {
            runCatching { InnerTube.sendFeedback(token) }
                .onSuccess { actionMessage = str(if (channel) R.string.player_channel_not_recommended else R.string.player_not_interested_done) }
                .onFailure {
                    Dismissed.restore(video, channel)
                    actionMessage = str(R.string.player_feedback_failed)
                    AppLog.w(TAG, "feedback for ${video.id} (channel: $channel)", it)
                }
        }
    }

    // ── Save to playlist (GitHub issue #12) ──
    /** The video the "Save to playlist" sheet is open for; null = closed. */
    /** The video the Download sheet is open for (the action row's Download, a card's long-press); null = closed. */
    var downloadTarget by mutableStateOf<VideoItem?>(null)

    var playlistTarget by mutableStateOf<VideoItem?>(null)
        private set
    /** Its playlists (Watch later first); null while loading. */
    var playlistChoices by mutableStateOf<List<PlaylistChoice>?>(null)
        private set
    var playlistError by mutableStateOf<String?>(null)
        private set
    private var choicesJob: Job? = null

    fun openPlaylists(video: VideoItem) {
        playlistTarget = video
        playlistChoices = null
        playlistError = null
        choicesJob?.cancel()
        choicesJob = scope.launch {
            runCatching { InnerTube.saveChoices(video.id) }
                .onSuccess { choices -> if (playlistTarget?.id == video.id) playlistChoices = choices }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLog.w(TAG, "playlists for ${video.id}", it)
                    if (playlistTarget?.id == video.id) playlistError = YouTube.friendlyError(it)
                }
        }
    }

    fun closePlaylists() {
        choicesJob?.cancel()
        playlistTarget = null
    }

    /** Runs here, not in the sheet: closing the sheet mid-request would cancel it. */
    fun setInPlaylist(video: VideoItem, choice: PlaylistChoice, add: Boolean) {
        fun mark(value: Boolean?) {
            playlistChoices = playlistChoices?.map { if (it.id == choice.id) it.copy(contains = value) else it }
        }
        mark(add)
        scope.launch {
            runCatching { InnerTube.setInPlaylist(choice.id, video.id, add) }
                .onSuccess { actionMessage = if (add) str(R.string.player_saved_to, choice.title) else str(R.string.player_removed_from, choice.title) }
                .onFailure {
                    mark(choice.contains)
                    actionMessage = str(R.string.player_playlist_update_failed, choice.title)
                    AppLog.w(TAG, "playlist ${choice.id} ${video.id}", it)
                }
        }
    }

    fun createPlaylist(video: VideoItem, title: String, privacy: String) {
        scope.launch {
            runCatching { InnerTube.createPlaylist(title, privacy, video.id) }
                .onSuccess { id ->
                    val made = PlaylistChoice(id, title, contains = true, privacy = privacy)
                    playlistChoices = playlistChoices?.let { list ->
                        val wl = list.takeWhile { it.id == "WL" }
                        wl + made + list.drop(wl.size)
                    }
                    actionMessage = str(R.string.player_saved_to, title)
                }
                .onFailure { actionMessage = str(R.string.player_create_playlist_failed); AppLog.w(TAG, "create playlist", it) }
        }
    }

    /**
     * Switches quality (null = Auto). The media source is rebuilt from the cached stream
     * info at the same position, keeping play/pause as it was.
     */
    fun setQuality(height: Int?) {
        // A downloaded video plays its one kept stream: nothing to switch, and the everyday pick stays as it was.
        if (current?.id?.let(Downloads::isReady) == true) return
        QualityPreference.set(height)
        val player = controller ?: return
        val item = details?.item ?: current ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        replaceCurrent(player, item, position)
        player.playWhenReady = playWhenReady
        val info = StreamCache.get(item.id) ?: return
        details = details?.copy(quality = StreamSelection.pick(info, item.id).playedHeight?.let { "${it}p" })
    }

    /** Distinct audio tracks for the open video (empty when the video has only one). */
    val audioTracks: List<AudioTrack> get() = details?.audioTracks.orEmpty()

    /** The audio track currently playing (null when the video has only one track). */
    val activeAudioTrack: AudioTrack?
        get() {
            val d = details ?: return null
            if (d.audioTracks.size <= 1) return null
            return d.audioTracks.firstOrNull { it.id == d.audioTrackId } ?: d.audioTracks.firstOrNull { it.isOriginal }
        }

    /** True when the open multi-audio video is playing a dubbed / non-original track. */
    val isDubbedAudio: Boolean get() = activeAudioTrack?.let { !it.isOriginal } == true

    /**
     * Switches audio track for the current video, keeping the exact position, play/pause state,
     * and queue intact. Only affects this video unless Settings › Playback is set to "Last picked".
     */
    fun setAudioTrack(track: AudioTrack) {
        val item = details?.item ?: current ?: return
        val before = details?.audioTrackId
        AudioTrackPreference.setForVideo(item.id, track)
        details = details?.copy(audioTrackId = track.id)
        if (before == track.id) return
        val player = controller ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        replaceCurrent(player, item, position)
        player.playWhenReady = playWhenReady
    }

    /** Called when the Settings › Playback audio default changes: applies immediately to the open video if it changed. */
    fun refreshAudioTrackForSetting() {
        val d = details ?: return
        if (d.audioTracks.size <= 1) return
        val item = d.item
        val nextId = AudioTrackPreference.resolveTrackId(d.audioTracks)
        if (nextId == null || nextId == d.audioTrackId) return
        details = d.copy(audioTrackId = nextId)
        val player = controller ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        replaceCurrent(player, item, position)
        player.playWhenReady = playWhenReady
    }

    private fun fail(t: Throwable) {
        error = YouTube.friendlyError(t)
        botCheck = t is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
        retryOnNetwork = botCheck || generateSequence(t) { it.cause.takeIf { c -> c !== it } }.any { it is java.io.IOException }
    }

    /**
     * The error on screen may go away with another connection: no connection, a stalled request, YouTube's bot check
     * (whose advice is airplane mode on and off). The next network to come up ([Net.networkUps]) tries the video again
     * by itself, so nobody has to find Try again after turning airplane mode off.
     */
    private var retryOnNetwork = false

    init {
        scope.launch {
            Net.networkUps.drop(1).collectLatest {
                // A network that just came up often can't look names up for a moment yet.
                delay(NetworkSettleMs)
                retryAfterNetwork()
            }
        }
    }

    /** A network came up while off screen or on Shorts: the retry waits for the screen (it would start playing). */
    private var networkCameBack = false

    private fun retryAfterNetwork() {
        val item = current ?: return
        if (error == null || !retryOnNetwork) return
        if (wantBackground || wantAside) { networkCameBack = true; return }
        networkCameBack = false
        AppLog.i(TAG, "network back: trying ${item.id} again")
        retry(expand = false)
    }

    /** Fresh links too: the cached ones may be exactly what YouTube refused. */
    fun retry(expand: Boolean = true) = current?.let { item -> StreamCache.invalidate(item.id); current = null; open(item, expand = expand) }

    /**
     * The video in YouTube's own app (or the browser), from where it stopped: the way out when
     * YouTube's bot check refuses Skipii's requests ([botCheck]).
     */
    fun openInYouTube(context: android.content.Context) {
        val item = current ?: return
        val seconds = positionMs / 1000
        val url = YouTube.watchUrl(item.id) + if (seconds > 0) "&t=${seconds}s" else ""
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            actionMessage = str(R.string.settings_no_app)
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun togglePlay() {
        val player = controller ?: return
        // Play under a video that hasn't loaded would resume the previous one: try this one again if it failed.
        if (engineOnOther(player)) { if (error != null) retry(expand = false); return }
        if (player.isPlaying) player.pause() else {
            val atEnd = player.playbackState == Player.STATE_ENDED ||
                (upNextCancelledFor == current?.id && durationMs > 0L && positionMs >= durationMs - 600L)
            if (atEnd) {
                upNextCancelledFor = null
                UpNextHold.setHold(false)
                player.seekTo(0)
            }
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    /** Seeks by [deltaMs], clamped to the video; returns how far it actually moved. */
    fun seekBy(deltaMs: Long): Long {
        val player = controller ?: return 0L
        val from = player.currentPosition
        seekTo(from + deltaMs)
        return positionMs - from
    }

    fun seekTo(ms: Long) {
        val player = controller ?: return
        if (engineOnOther(player)) return
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val to = ms.coerceIn(0L, max)
        // A restored, not-yet-prepared item isn't known to be seekable, so Media3 drops a plain
        // seek (scrubs and chapter taps did nothing until play); seeking to the item itself is allowed.
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(to)
        else player.seekTo(player.currentMediaItemIndex, to)
        positionMs = player.currentPosition
    }

    /** The ruler is moving: play at [value] now (remembering waits for [commitSpeed]). */
    fun previewSpeed(value: Float) {
        val player = controller ?: return
        val s = PlaybackSpeed.snap(value)
        if (player.playbackParameters.speed != s) player.setPlaybackSpeed(s)
        speed = s
    }

    /**
     * The ruler has landed on [value]. While remembering, it becomes the remembered speed —
     * unless it is 1× coming from another speed, which ends remembering (Derek: "until the
     * user resets it back to 1×").
     */
    fun commitSpeed(value: Float) {
        previewSpeed(value)
        val s = PlaybackSpeed.snap(value)
        if (!PlaybackSpeed.remember) return
        if (PlaybackSpeed.isNormal(s) && !PlaybackSpeed.isNormal(PlaybackSpeed.saved)) PlaybackSpeed.setRemember(false, 1f)
        else PlaybackSpeed.save(s)
    }

    fun toggleRememberSpeed() = PlaybackSpeed.setRemember(!PlaybackSpeed.remember, speed)

    /** A pinch in fullscreen, for one video: its id and whether it fills the screen. */
    private var zoomFor by mutableStateOf<Pair<String, Boolean>?>(null)

    /** Whether the current video fills the screen in fullscreen: its own pinch, else [FullscreenZoom.fill]. */
    val zoomedToFill: Boolean
        get() = zoomFor?.takeIf { it.first == current?.id }?.second ?: FullscreenZoom.fill

    /** A pinch out ([fill]) or in: for this video only; the next one starts from the setting again. */
    fun setZoomedToFill(fill: Boolean) {
        zoomFor = current?.id?.let { it to fill }
    }

    /** The setting changed: it wins over a pinch made before, the playing video's too. */
    fun setZoomFillSetting(on: Boolean) {
        FullscreenZoom.set(on)
        zoomFor = null
    }

    /** A finger is held on the video's right side (GitHub #11/#26/#32/#36): playing at [PlaybackSpeed.HOLD]. */
    var holdingFast by mutableStateOf(false)
        private set
    private var heldFrom: HeldSpeed? = null
    private class HeldSpeed(val videoId: String?, val speed: Float, val wasPlaying: Boolean)

    /** Starts the hold: [PlaybackSpeed.HOLD], playing (a paused video plays while held). Never saved or remembered. */
    fun startFastHold(): Boolean {
        val player = controller ?: return false
        if (holdingFast || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        heldFrom = HeldSpeed(current?.id, player.playbackParameters.speed, player.playWhenReady)
        player.setPlaybackSpeed(PlaybackSpeed.HOLD)
        if (!player.playWhenReady) player.play()
        holdingFast = true
        return true
    }

    /** Let go: the speed it had (and paused again if it was). */
    fun endFastHold() {
        val from = heldFrom ?: return
        heldFrom = null
        holdingFast = false
        val player = controller ?: return
        // The next video started mid-hold: SpeedKeeper already gave it its own speed.
        if (from.videoId != current?.id) return
        player.setPlaybackSpeed(from.speed)
        if (!from.wasPlaying) player.pause()
    }

    /** Audio only for the whole app: the video track goes off (or back on) right away. */
    fun setAudioOnly(on: Boolean) {
        AudioOnly.set(on)
        if (on) videoStale = true
        applyVideoTrack()
    }

    /**
     * App left the screen: stop fetching video and keep only audio (saves data and battery);
     * back on screen, video resumes. Done through the player's track selection, so the
     * video stream simply stops loading.
     */
    fun setBackground(background: Boolean) {
        wantBackground = background
        applyVideoTrack()
        if (!background && networkCameBack) retryAfterNetwork()
    }

    /**
     * What the screen wants, remembered even before the player connection exists. The
     * service keeps its track settings across screens, so a rebuilt screen (dark-mode switch,
     * process trim) that came back while disconnected left video off for good; the state is
     * now applied on every connect too.
     */
    private var wantBackground = false

    /**
     * The Shorts tab is open: the paused video gives its decoder back (Shorts keeps four of
     * its own; with this one too the fourth failed to start on the emulator, 2026-10-28).
     */
    fun setVideoAside(aside: Boolean) {
        wantAside = aside
        applyVideoTrack()
        if (!aside && networkCameBack) retryAfterNetwork()
    }

    private var wantAside = false

    /** Video is off in the background, while Shorts is open and whenever audio only is on. */
    private fun applyVideoTrack() {
        val off = wantBackground || wantAside || AudioOnly.enabled
        val player = controller ?: return
        if (player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) == off) return
        AppLog.i(TAG, if (off) "video off (${if (AudioOnly.enabled) "audio only" else if (wantAside) "Shorts" else "background"})" else "video on")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, off)
            .build()
    }

    fun close() {
        loadJob?.cancel()
        controller?.run { stop(); clearMediaItems() }
        queue = emptyList()
        upNextCancelledFor = null
        UpNextHold.setHold(false)
        expanded = false
        fullscreen = false
        sheet = null
        current = null
        details = null
        captions.clear()
        error = null
        botCheck = false
        loading = false
    }

    /**
     * Called once the MediaController connects. Starts anything picked while connecting,
     * and if the service was already playing (app reopened mid-video), rebuilds the
     * mini-player from the session's current item.
     */
    internal fun onConnected() {
        syncFromPlayer()
        applyVideoTrack()
        controller?.videoSize?.let(::takeVideoSize)
        pendingStart?.let { pendingStart = null; startPlayback(it); return }
        val player = controller ?: return
        if (player.mediaItemCount == 0 && current == null) { restoreSession(player); return }
        rebuildQueue()
        val playing = player.currentMediaItem ?: return
        if (current != null || playing.mediaId.isBlank()) return
        val meta = playing.mediaMetadata
        val item = VideoItem(
            id = playing.mediaId,
            title = meta.title?.toString().orEmpty(),
            channel = meta.artist?.toString().orEmpty(),
            channelUrl = null,
            thumbnail = meta.artworkUri?.toString(),
            avatar = null,
            durationSec = player.duration.takeIf { it > 0 }?.div(1000) ?: 0L,
            views = -1,
            uploaded = null,
        )
        current = item
        // The rest of what show() loads: a window rebuilt by a fold reopens this video's page,
        // which had no comments or like state before.
        comments.clearFor(item.id)
        loadAccountState(item.id)
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }.onSuccess { details = it; current = it.item; captions.useTracks(it.captions, it.item.id) }
            comments.load(item.id)
        }
    }

    /**
     * Fresh service after a crash, force stop or swipe from Recents: put back what was open
     * ([SavedSession]) as the mini-player, paused where it was left. Not prepared, so nothing
     * downloads until play (togglePlay / playFromQueue prepare an idle player).
     */
    private fun restoreSession(player: MediaController) {
        val saved = SavedSession.load().takeIf { it.isNotEmpty() } ?: return
        val items = saved.map {
            VideoItem(
                id = it.id, title = it.title, channel = it.channel, channelUrl = null, thumbnail = it.thumbnail,
                avatar = null, durationSec = it.durationSec, views = -1, uploaded = null,
            ).also { item -> known[item.id] = item }
        }
        AppLog.i(TAG, "restoring ${items.first().id} + ${items.size - 1} queued")
        player.setMediaItems(items.map(::mediaItemFor), 0, ResumeStore.startPosition(items.first().id))
        show(items.first(), startPlayback = false)
    }

    /**
     * The player still holds the previous video (stopped) while this one's links load or after they failed
     * ([show]): its position, play state and Play button aren't this video's.
     */
    private fun engineOnOther(player: Player): Boolean {
        val id = current?.id ?: return false
        val onId = player.currentMediaItem?.mediaId ?: return false
        return onId != id
    }

    internal fun syncFromPlayer() {
        val player = controller ?: return
        if (engineOnOther(player)) {
            isPlaying = false
            isBuffering = false
            positionMs = 0L
            bufferedMs = 0L
            durationMs = (current?.durationSec ?: 0L) * 1000L
            return
        }
        isPlaying = player.isPlaying
        // While the service refetches refused links the player sits idle; show the spinner.
        // (current?.id null with nothing recovering is null == null: "loading" with nothing open, which also held
        // the Shorts bank back for good — it waits while the main video loads.)
        isBuffering = player.playbackState == Player.STATE_BUFFERING || (current != null && StreamRecovery.recovering == current?.id)
        positionMs = player.currentPosition.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0 } ?: ((current?.durationSec ?: 0L) * 1000L)
        speed = player.playbackParameters.speed
    }

    internal val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) rebuildQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) onPlayerMovedTo(mediaItem)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = takeVideoSize(videoSize)

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            controller?.videoSize?.takeIf { it.height > 0 }?.let { describeFormat(it.height, it.width) }
        }

        override fun onRenderedFirstFrame() {
            AppLog.i("SkipiiTime", "first frame +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
            firstFrameRendered = true
            videoStale = false
        }

        override fun onPlayerError(e: PlaybackException) {
            val status = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            val failedId = controller?.currentMediaItem?.mediaId
            AppLog.w(TAG, "playback error ${e.errorCodeName}${status?.let { " (HTTP $it)" } ?: ""} for $failedId", e)
            // Another video's error (the one being replaced, still in the player while this one's links load): not this one's.
            if (current == null || (failedId != null && failedId != current?.id)) return
            // The service is on it: fresh links for a refused or expired stream URL (StreamRecoverer), or a step down
            // from a format the decoder wouldn't start (DecoderStepDown). Its outcome comes through StreamRecovery.
            if (current != null && StreamRecovery.recovering == current?.id) return
            botCheck = false
            retryOnNetwork = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> str(R.string.player_error_connection)
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> str(R.string.player_error_refused)
                else -> str(R.string.player_error_unplayable)
            }
        }
    }

    private fun takeVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        aspectFromFrames = true
        // Auto switches resolution mid-play; the label follows the real frames.
        describeFormat(videoSize.height, videoSize.width)
    }

    /** Fills the quality label and the "Playing …" line from the frames actually decoded. */
    private fun describeFormat(height: Int, width: Int = 0) {
        // Named by the short side, as the quality list names them (StreamSelection.side): an upright 1080×1920 is 1080p.
        val side = if (width > 0) minOf(width, height) else height
        if (decodedHeight != side) AppLog.i("SkipiiTime", "now ${side}p at +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
        decodedHeight = side
        val formats = controller?.currentTracks?.groups.orEmpty()
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        val format = formats.firstOrNull { it.height == height }
        if (format == null) { playingFormat = "${height}p"; return }
        run {
            val codec = when {
                format.codecs?.startsWith("avc") == true -> "H.264"
                format.codecs?.startsWith("vp9") == true || format.codecs?.startsWith("vp09") == true -> "VP9"
                format.codecs?.startsWith("av01") == true -> "AV1"
                else -> format.codecs ?: format.sampleMimeType ?: "?"
            }
            val auto = if (formats.size > 1) str(R.string.player_quality_auto) else null
            playingFormat = listOfNotNull(auto, "${format.width}×${format.height}", codec, format.id?.substringAfterLast(":")?.let { "itag $it" }).joinToString(" · ")
        }
    }
}

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PlayerState(scope) }
    DisposableEffect(context) {
        // Bound through the APPLICATION, never the window: Media3 unbinds a released controller later
        // (a posted message, up to 30 s while a command is pending), after Android has already dropped
        // a destroyed window's bindings ("leaked ServiceConnection"), and that late unbind crashed with
        // "Service not registered" — Back out of the app, open it again, crash.
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { controller ->
                state.controller = controller
                controller.addListener(state.listener)
                state.onConnected()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            state.controller?.removeListener(state.listener)
            MediaController.releaseFuture(future)
            state.controller = null
        }
    }
    // Position isn't evented; poll it only while something is actually playing.
    LaunchedEffect(state.isPlaying) {
        while (isActive && state.isPlaying) {
            state.syncFromPlayer()
            delay(if (state.upNextCountdown != null) 80 else 250)
        }
    }
    return state
}

enum class PlayerSheet { Quality, Speed, Audio, Comments, Queue, Captions, Transcript }

/** Comments and the transcript open as a panel inside the player (PlayerScreen), not a sheet. */
val PlayerSheet?.isPanel: Boolean get() = this == PlayerSheet.Comments || this == PlayerSheet.Transcript

/** A network that just came up gets this long before a failed video is tried again on it. */
private const val NetworkSettleMs = 1_500L

/** The spinner this long on the open video: the player offers a problem report ([PlayerState.stuck]). */
internal const val StuckAfterMs = 20_000L

internal const val UpNextWindowMs = 8_000L
internal const val UpNextMinVideoMs = 15_000L

data class UpNextCountdown(val remainingMs: Long, val secondsLeft: Int, val progress: Float)

internal fun upNextCountdown(
    positionMs: Long,
    durationMs: Long,
    hasNext: Boolean,
    isPlaying: Boolean,
    cancelled: Boolean,
): UpNextCountdown? {
    if (!hasNext || !isPlaying || cancelled || durationMs < UpNextMinVideoMs) return null
    val rem = durationMs - positionMs
    if (rem !in 1L..UpNextWindowMs) return null
    val secs = ((rem + 999L) / 1000L).toInt().coerceIn(1, (UpNextWindowMs / 1000L).toInt())
    return UpNextCountdown(rem, secs, (rem.toFloat() / UpNextWindowMs).coerceIn(0f, 1f))
}

internal fun <T> reorderList(items: List<T>, from: Int, to: Int): List<T>? {
    if (from == to || from !in items.indices || to !in items.indices) return null
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun queueTotalDurationSec(items: List<VideoItem>): Long =
    items.sumOf { it.durationSec.coerceAtLeast(0L) }

private const val TAG = "Skipii"
