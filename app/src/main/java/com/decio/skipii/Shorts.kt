package com.decio.skipii

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Shorts (0.5.50, Derek, 2026-10-28; a Shorts tab was first proposed by essopsp in PR #10).
// A full-bleed vertical feed on its own players: the app's video pauses while it's open and
// comes back when it's left. Entering it turns the tab bar into a dark frosted-glass bar —
// ‹ on its own, then Like · Comments · Share · channel (tap to subscribe) — Derek's mockup.

/** The glass bar: 50 dp tall, ‹ 50 + 8 + the pill 236, centred; its bottom is the tab bar's. */
internal val GlassHeight = 50.dp
private val BackWidth = 50.dp
private val GlassGap = 8.dp
private val PillWidth = 236.dp
/** The pill's four buttons spread over it minus this much at each rounded end. */
private val PillInset = 6.dp
/** The tab bar's left end that becomes ‹ (its first slot). */
private val BackFrom = 62.dp
/** One UI's dark glass tint (#252525), much lighter than its 85% so the video shows through. */
private val GlassTint = Color(0xFF252525).copy(alpha = 0.38f)
/** Before the first frame there's nothing to blur: a darker plain tint. */
private val GlassTintBare = Color(0xFF252525).copy(alpha = 0.6f)
private val Ink = Color(0xFFFAFAFA)

/** In over 620 ms (linear; each part eases on its own ramp below), out over 460 ms, fast first. */
const val ShortsEnterMs = 620
const val ShortsExitMs = 460
val ShortsExitEasing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

private fun decel(t: Float): Float { val u = 1f - t; return 1f - u * u * u }

/** How far tab [i] has dropped out of the bar at Shorts progress [p] (Home fades into ‹ instead). */
internal fun shortsTabDrop(p: Float, i: Int) = if (i == 0) 0f else decel(ramp(p, 0.04f * (i - 1), 0.24f + 0.04f * (i - 1)))
internal fun shortsHomeFade(p: Float) = ramp(p, 0f, 0.18f)
/** The tab bar's own tone fading out under the glass. */
internal fun shortsBarFade(p: Float) = ramp(p, 0.04f, 0.4f)

private const val TAG = "Skipii"
/** Players for the short before (swiping back), this one and the two after it. */
private const val PoolSize = 4
private const val TickMs = 150L
/** Each short's player loads it whole (a short is ≤ 3 min), within this much memory. */
private const val ShortBufferBytes = 20 * 1024 * 1024
private const val ShortBufferMs = 180_000
/**
 * A waiting short loads this much at first: enough to start at once when swiped to. It loads in
 * full only once the shorts before it have (2026-10-29: loading the next short only after the
 * current one was complete meant a swipe usually found nothing loaded — 2–3.5 s to start).
 */
private const val PreviewMs = 5_000L
/** The short on screen keeps at least this much loaded ahead before a waiting short may load. */
private const val LeadMs = 5_000L
/** How many shorts' links (extractions, 2–3.5 s each) are fetched at once, nearest first. */
private const val LinkFetches = 3
/** Links are kept ready this far ahead (and one behind). */
private const val LinksAhead = 5
/** The glass samples the frame this wide (px), then blurs it: ~20 dp of blur once drawn full size. */
private const val GlassSampleWidth = 40
private const val GlassBlurRadius = 2

/**
 * The Shorts feed and its playback.
 *
 * Feed: [InnerTube.shorts] (the reel sequence; signed out it's a generic one). Each short's
 * streams and details come from [YouTube.details] like any video: the one on screen first,
 * then the next three.
 *
 * Playback flows without waits: a small pool of players holds the short before this one,
 * this one and the two after, each on its own page's TextureView. The short on screen always
 * comes first: only once it has [LeadMs] loaded ahead do the next two load their first
 * [PreviewMs] (so a swipe lands on a short already showing its first frame and ready to play),
 * and each loads in full once every short before it has (Derek, 2026-10-28: "if the current
 * video is fully loaded, start loading the next"). Links for the next [LinksAhead] are fetched
 * ahead of all that. Shorts loop.
 *
 * Under all that, the [ShortsBank]: the next 10 unseen shorts kept whole on disk, so they play
 * from flash at once — also right after the app opens. It's topped up one short at a time
 * whenever the connection isn't needed for what's playing; a banked short is forgotten once
 * seen. Created once per process ([SkipiiApplication]), so it keeps filling with the app in
 * the background.
 */
@Stable
class ShortsState(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext

    /** Shorts seen this session (on screen at least once): never banked, never offered again. */
    private val viewed = HashSet<String>()
    private var bankJob: Job? = null
    private var bankingId: String? = null
    private val bankFailed = HashSet<String>()
    /** The app's own video is waiting on the network (set by the shell); the bank holds off then. */
    var mainBusy: () -> Boolean = { false }

    // ── Feed ──
    var items by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped when the feed is replaced, so the pager starts again from the top. */
    var feedVersion by mutableIntStateOf(0)
        private set
    private var next: String? = null
    private var pageJob: Job? = null
    private var moreFailedAt = 0L

    /** The short on screen (the pager's settled page). */
    var index by mutableIntStateOf(0)
        private set
    val current: VideoItem? get() = items.getOrNull(index)

    val details = mutableStateMapOf<String, VideoDetails>()
    val failed = mutableStateMapOf<String, String>()
    private val detailJobs = HashMap<String, Job>()
    /** Width / height of the frames actually decoded (better than the stream's numbers). */
    val aspects = mutableStateMapOf<String, Float>()

    // ── Account (signed in) ──
    val accountStates = mutableStateMapOf<String, VideoAccountState>()
    private val accountAsked = HashSet<String>()
    private val watched = HashSet<String>()
    /** Short notes ("Sign in to like Shorts."), shown by the app shell. */
    var onMessage: (String) -> Unit = {}

    val comments = CommentsState(scope)
    var commentsOpen by mutableStateOf(false)
    /** The channel button's card (Subscribe · Go to channel) is open. */
    var channelMenu by mutableStateOf(false)
    /** A page (the channel) covers Shorts: the short waits, paused. */
    var covered by mutableStateOf(false)
    private var commentsFor: String? = null

    // ── Playback ──
    private val pool = ArrayList<ShortPlayer>()
    /** Which player holds which page; a page shows its player's picture. */
    internal val assigned = mutableStateMapOf<Int, ShortPlayer>()
    var active by mutableStateOf(false)
        private set
    private var foreground = true
    var userPaused by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    /** How far into the short on screen, 0..1 (the line along the screen's bottom edge). */
    var progress by mutableFloatStateOf(0f)
        private set
    private var loop: Job? = null
    /** How many shorts past this one get a player loading them; 1 once the device runs out of video decoders. */
    private var maxAhead = 2
    private val decoderFailures = HashMap<String, Int>()
    /** When the short on screen came on screen, and what was ready then (for the timing log). */
    private var onScreenAt = 0L
    private var readyWhenShown = ""
    private var startLogged = false

    // ── Glass ── tiny blurred copies of the frames under the bar, drawn under its tint. One per
    // page, so mid-swipe the glass over the incoming short shows that short, not the one leaving.
    internal val glassFrames = mutableStateMapOf<Int, ImageBitmap>()
    /** Each page's video box in root pixels (unclipped), to line the glass up with the picture. */
    internal val videoRects = mutableStateMapOf<Int, Rect>()
    /** The bar's span in root pixels (set by [ShortsBar]); only frames reaching it are sampled. */
    internal var barTop = 0f
    internal var barBottom = 0f
    private val samples = HashMap<Int, Array<Bitmap?>>()
    private val flips = HashMap<Int, Int>()
    private val sampledAt = HashMap<Int, Long>()
    private var pixels = IntArray(0)
    private var spare = IntArray(0)

    init {
        // Last time's bank first: these play at once, before any request.
        val banked = ShortsBank.entries()
        if (banked.isNotEmpty()) {
            items = banked.map { it.item }
            banked.forEach { details[it.item.id] = it.details() }
            next = ShortsBank.next
            feedVersion++
        }
        // Shortly after the app opens (its own pages load first), then every 1.5 s for good.
        scope.launch { delay(3_000); while (isActive) { fillBank(); delay(1_500) } }
    }

    private fun ShortsBank.Entry.details() = VideoDetails(
        item = item, description = "", likes = -1, subscribers = -1, related = emptyList(),
        playable = true, quality = null, qualities = emptyList(), aspect = aspect,
    )

    /** The tab opened: load the feed if there's none, and start playing where it was left. */
    fun enter() {
        if (active) return
        active = true
        // Players still being let go from last time carry on going in the background (none is in use).
        markShown()
        if (items.isEmpty() && !loading) refresh()
        loop = scope.launch { while (isActive) { tick(); delay(TickMs) } }
    }

    /**
     * The tab closed: everything stops at once; the feed and the spot stay. The players go after
     * the bar has turned back into tabs, one a frame: releasing an ExoPlayer blocks until its
     * decoder is shut, and releasing four on the tap froze the ‹ button on the phone (Derek,
     * 2026-10-29; the Back gesture hid it behind the system's own animation). The player of the
     * short it will reopen on stays, paused, so reopening is instant too ([prewarm]).
     */
    fun leave() {
        if (!active) return
        active = false
        loop?.cancel()
        loop = null
        commentsOpen = false
        channelMenu = false
        covered = false
        playing = false
        buffering = false
        pool.forEach { it.exo.playWhenReady = false }
        // Next time it opens on a short not seen yet (the bank's next one), like YouTube.
        if (current?.id in viewed && index < items.lastIndex) {
            index++
            feedVersion++
        }
        val keep = assigned[index]?.takeIf { it.id == items.getOrNull(index)?.id && it.prepared }
        val going = pool.filter { it !== keep }
        pool.removeAll(going)
        assigned.clear()
        if (keep != null) assigned[index] = keep
        retired += going
        glassFrames.clear()
        samples.clear()
        retiring = scope.launch {
            delay(ShortsExitMs + 120L)
            while (retired.isNotEmpty()) {
                retired.removeAt(0).release()
                delay(32)
            }
        }
    }

    /** Players left behind by [leave], released one a frame once the tab has closed. */
    private val retired = ArrayList<ShortPlayer>()
    private var retiring: Job? = null

    private fun dropRetired() {
        retired.forEach { it.release() }
        retired.clear()
    }

    /** The app left the screen (or came back): Shorts don't play in the background. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (active) tick()
        // In the background the waiting player ([prewarm]) gives its decoder back.
        else if (!value) releasePlayers()
    }

    /**
     * The app is open but Shorts isn't: the short it will open on sits ready — player made,
     * decoder up, its start read from the bank — so the tab plays the moment it opens (making
     * all that on the tap took 1–2.4 s on the emulator, 2026-10-29). Only from the bank: nothing
     * is downloaded for a tab that isn't open.
     */
    private fun prewarm() {
        if (active || !foreground) return
        val id = items.getOrNull(index)?.id ?: return
        if (!ShortsBank.has(id) || id in viewed) return
        val held = assigned[index]
        if (held?.id == id) return
        val p = held ?: pool.firstOrNull { it.index == -1 } ?: ShortPlayer(app, this).also(pool::add)
        p.take(index, id)
        assigned[index] = p
        p.exo.playWhenReady = false
        prepare(p, "ready for the tab")
    }

    fun togglePause() {
        userPaused = !userPaused
        tick()
    }

    /** The pager settled on [page]. The short left behind rewinds, ready to play again. */
    fun select(page: Int) {
        if (page == index) return
        assigned[index]?.exo?.let { it.playWhenReady = false; it.seekTo(0) }
        index = page
        userPaused = false
        progress = 0f
        commentsOpen = false
        channelMenu = false
        markShown()
        tick()
    }

    private fun markShown() {
        // Seen: out of the bank (another unseen short takes its place), and its comments start
        // loading now so they're there when the panel opens.
        current?.id?.let { id ->
            viewed.add(id)
            CommentFeed.prefetch(id)
        }
        onScreenAt = SystemClock.elapsedRealtime()
        startLogged = false
        val p = assigned[index]
        val id = items.getOrNull(index)?.id
        readyWhenShown = when {
            p?.firstFrame == true -> if (p.banked) "first frame ready, banked" else "first frame ready"
            p?.prepared == true -> if (p.banked) "loading, banked" else "loading"
            id != null && ShortsBank.has(id) -> "banked, not loading"
            id != null && details.containsKey(id) -> "links ready, not loading"
            else -> "nothing ready"
        }
    }

    /** A player started playing: how long the short on screen took to (Shorts' feel, in the log). */
    internal fun onStartedPlaying(player: ShortPlayer) {
        if (startLogged || player.index != index) return
        startLogged = true
        Log.i(TAG, "shorts: ${player.id} playing ${SystemClock.elapsedRealtime() - onScreenAt} ms after coming on screen ($readyWhenShown)")
    }

    fun refresh() {
        pageJob?.cancel()
        loading = true
        error = null
        pageJob = scope.launch {
            runCatching { InnerTube.shorts() }
                .onSuccess { page ->
                    releasePlayers()
                    items = page.videos
                    next = page.next
                    index = 0
                    feedVersion++
                    ShortsBank.setNext(next)
                    if (page.videos.isEmpty()) error = "YouTube didn't offer any Shorts right now."
                    page.videos.take(LinkFetches).forEach { want(it.id) }
                    if (active) markShown()
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "shorts: feed failed", it)
                    error = if (it is java.io.IOException && it.message?.startsWith("YouTube didn't") == true) it.message else YouTube.friendlyError(it)
                }
            loading = false
        }
    }

    private fun loadMore() {
        val token = next ?: return
        if (loading || SystemClock.elapsedRealtime() - moreFailedAt < 10_000) return
        loading = true
        pageJob = scope.launch {
            runCatching { InnerTube.shorts(token) }
                .onSuccess { page ->
                    val seen = items.mapTo(HashSet()) { it.id }
                    items = items + page.videos.filter { seen.add(it.id) }
                    next = page.next
                    ShortsBank.setNext(next)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "shorts: next page failed", it)
                    moreFailedAt = SystemClock.elapsedRealtime()
                    // A kept token can go stale (the bank's, from days ago): start a new sequence.
                    if (items.isNotEmpty()) { next = null; ShortsBank.setNext(null); pendingFresh = true }
                }
            loading = false
        }
    }

    private var pendingFresh = false

    /** The kept token failed: a fresh sequence (new seed) goes after what's there. */
    private fun appendFresh() {
        if (loading) return
        pendingFresh = false
        loading = true
        pageJob = scope.launch {
            runCatching { InnerTube.shorts() }
                .onSuccess { page ->
                    val seen = items.mapTo(HashSet()) { it.id }
                    items = items + page.videos.filter { seen.add(it.id) }
                    next = page.next
                    ShortsBank.setNext(next)
                }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "shorts: fresh sequence failed", it) }
            loading = false
        }
    }

    private var ticking = false
    private var tickAgain = false

    /**
     * Everything follows the short on screen; runs every [TickMs] while the tab is open and on
     * any change. Never nested: ExoPlayer tells its listeners about a prepare() before returning,
     * and a nested pass prepared the same short twice (seen as doubled "loading" lines).
     */
    private fun tick() {
        if (ticking) { tickAgain = true; return }
        ticking = true
        try {
            do { tickAgain = false; tickOnce() } while (tickAgain)
        } finally {
            ticking = false
        }
    }

    private fun tickOnce() {
        val list = items
        if (!active || list.isEmpty()) return
        val cur = index.coerceIn(0, list.lastIndex)
        // Players follow the pages around this one; one that falls out is reused for a new one.
        val window = (cur - 1)..(cur + 2)
        pool.forEach { p -> if (p.index != -1 && p.index !in window) { assigned.remove(p.index); p.free() } }
        for (i in window) {
            if (i !in list.indices || assigned[i] != null) continue
            val p = pool.firstOrNull { it.index == -1 } ?: if (pool.size < PoolSize) ShortPlayer(app, this).also(pool::add) else break
            p.take(i, list[i].id)
            assigned[i] = p
        }
        // Links (streams and details), nearest first, a few at a time: this short, the next
        // ones, the one before.
        val curId = list[cur].id
        (listOf(cur) + (cur + 1..cur + LinksAhead) + (cur - 1)).forEach { i -> list.getOrNull(i)?.let { want(it.id) } }

        val now = assigned[cur]
        if (now != null && !now.prepared) prepare(now, "on screen")
        now?.capMs = Long.MAX_VALUE
        pool.forEach { it.exo.playWhenReady = it === now && foreground && !userPaused && !covered }
        // The shorts after it: their first seconds once this one has a safe lead, all of each
        // once everything before it is loaded.
        // Nothing else starts until this short has played its first second: three players starting
        // at once took 2.4 s to show a banked short, and a decoder starting just as one begins
        // stalled it (emulator, 2026-10-29).
        val started = now == null || failed.containsKey(curId) || !foreground ||
            now.exo.currentPosition >= 1_000 || (userPaused && now.firstFrame)
        val nowOk = started && (now == null || failed.containsKey(curId) || now.loaded || now.lead >= LeadMs)
        var allBefore = now == null || failed.containsKey(curId) || now.loaded
        var previewBefore = nowOk
        for (i in cur + 1..cur + maxAhead) {
            val p = assigned[i] ?: break
            val id = p.id ?: break
            if (failed.containsKey(id)) continue
            if (!started) break
            // Banked: it's on disk, it takes nothing from the connection; ready it now.
            if (ShortsBank.has(id) || p.banked) {
                p.capMs = Long.MAX_VALUE
                if (!p.prepared) prepare(p, if (i == cur + 1) "next, banked" else "after next, banked")
                continue
            }
            if (!previewBefore) break
            p.capMs = if (allBefore) Long.MAX_VALUE else PreviewMs
            if (!p.prepared && !prepare(p, if (i == cur + 1) "next" else "after next")) break
            previewBefore = p.loaded || p.lead >= PreviewMs - 500
            allBefore = allBefore && p.loaded
        }
        // The bank may use the connection once what's playing and the previews don't need it.
        bankMay = started && previewBefore && (now == null || now.banked || now.loaded || now.lead >= LeadMs)
        pool.forEach { it.noteLoaded() }
        if (cur >= list.size - 4) loadMore()

        playing = now?.exo?.isPlaying == true
        buffering = now != null && !failed.containsKey(curId) && (!now.prepared || now.exo.playbackState == Player.STATE_BUFFERING)
        val duration = now?.exo?.duration ?: 0L
        progress = if (now != null && duration > 0) (now.exo.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
        accountFor(curId)
        if (playing) joinHistory(curId)
    }

    /**
     * Starts [p] on its short: from the bank (no request at all) or from fetched links. False
     * while there's nothing to play it from yet.
     */
    private fun prepare(p: ShortPlayer, why: String): Boolean {
        val id = p.id ?: return false
        val item = MediaItem.Builder().setMediaId(id).setUri(YouTube.watchUrl(id)).build()
        ShortsBank.get(id)?.let { e ->
            p.prepare(why, ShortsBank.mediaSource(e.video, e.audio, item), e.urls, banked = true)
            // Seen already (it's the one on screen): the player has it now; the bank lets it go.
            if (id in viewed) ShortsBank.forget(id)
            return true
        }
        if (!details.containsKey(id)) return false
        val info = StreamCache.get(id)
        val tracks = info?.let { ShortsBank.tracks(it) }
        if (tracks == null) {
            // Links expired (or the network changed) since they were fetched: fetch again.
            if (info == null) details.remove(id) else failed[id] = "This Short can't play right now."
            return false
        }
        p.prepare(why, ShortsBank.mediaSource(tracks.first, tracks.second, item), listOf(tracks.first.url, tracks.second.url), banked = false)
        return true
    }

    // ── The bank ──

    private var bankMay = true

    /**
     * Keeps the bank at [ShortsBank.Size]: the next unseen shorts of the feed, downloaded one at
     * a time, only while the connection is free (on Shorts: what's playing and the previews are
     * loaded; elsewhere: the app's video isn't waiting on it). Runs every 1.5 s, app open or not.
     */
    private fun fillBank() {
        val list = items
        if (list.isEmpty()) { if (!active && !loading && error == null) refresh(); return }
        if (pendingFresh) appendFresh()
        // Seen shorts leave the bank, unless a player is about to start one from it.
        ShortsBank.entries().forEach { e ->
            val id = e.item.id
            if (id in viewed && pool.none { it.id == id && !it.prepared }) ShortsBank.forget(id)
        }
        val upcoming = list.drop(index).filter { it.id !in viewed && it.id !in bankFailed && !failed.containsKey(it.id) }.take(ShortsBank.Size)
        if (upcoming.size < ShortsBank.Size) loadMore()
        val busy = if (active) !bankMay else mainBusy()
        val running = bankJob?.isActive == true
        // What it's fetching came on screen (it plays live now), or the network's busy: stop.
        if (running && (bankingId in viewed || busy)) { bankJob?.cancel(); return }
        prewarm()
        if (running || busy || ShortsBank.size >= ShortsBank.Size) return
        val target = upcoming.firstOrNull { !ShortsBank.has(it.id) } ?: return
        bankingId = target.id
        bankJob = scope.launch {
            runCatching {
                val info = withContext(Dispatchers.IO) { BotCheck.withoutWaiting(target.id) { StreamCache.getOrLoad(target.id) } }
                val tracks = ShortsBank.tracks(info) ?: error("no playable streams")
                val d = details[target.id] ?: YouTube.details(target.id, wait = false).also { details[target.id] = it }
                ShortsBank.download(d.item, aspects[target.id] ?: d.aspect ?: (9f / 16f), tracks.first, tracks.second)
            }.onFailure {
                if (it is CancellationException) return@onFailure
                Log.w(TAG, "shorts bank: couldn't bank ${target.id}", it)
                bankFailed.add(target.id)
            }
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { ShortsBank.cleanup(inUseUrls()) }
        }
    }

    private fun inUseUrls(): Set<String> = pool.flatMapTo(HashSet()) { it.urls }

    private fun want(id: String) {
        if (details.containsKey(id) || failed.containsKey(id) || detailJobs[id]?.isActive == true) return
        if (detailJobs.values.count { it.isActive } >= LinkFetches) return
        val startedAt = SystemClock.elapsedRealtime()
        detailJobs[id] = scope.launch {
            runCatching { YouTube.details(id, wait = false) }
                .onSuccess {
                    Log.i(TAG, "shorts: links for $id in ${SystemClock.elapsedRealtime() - startedAt} ms")
                    details[id] = it
                    poke()
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "shorts: details for $id", it)
                    failed[id] = YouTube.friendlyError(it)
                }
        }
    }

    /** Something changed (links in, a player loaded more): act on it now, not at the next tick. */
    internal fun poke() {
        if (active) tick()
    }

    internal fun onPlayerError(player: ShortPlayer, error: PlaybackException) {
        val id = player.id ?: return
        // A banked short missing some bytes asked YouTube with its old link: play it live instead.
        if (player.banked) {
            Log.w(TAG, "shorts bank: $id didn't play from the bank (${error.errorCodeName}); playing it live")
            ShortsBank.forget(id)
            if (StreamCache.get(id) == null) details.remove(id)
            player.unprepare()
            return
        }
        // An expired or network-bound link (HTTP 403): fresh links once, as the main player does.
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && player.retry()) {
            Log.i(TAG, "shorts: stream refused for $id; fetching fresh links")
            StreamCache.invalidate(id)
            details.remove(id)
            return
        }
        // Out of hardware video decoders (each loaded short holds one): load one ahead from now
        // on, and for the short on screen the one before gives its decoder up.
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        ) {
            val tries = (decoderFailures[id] ?: 0) + 1
            decoderFailures[id] = tries
            if (tries <= 3) {
                Log.w(TAG, "shorts: no video decoder free for $id; loading one short ahead from now on")
                maxAhead = 1
                player.unprepare()
                if (player.index == index) assigned[index - 1]?.unprepare()
                return
            }
        }
        Log.w(TAG, "shorts: playback error for $id (${error.errorCodeName})", error)
        failed[id] = "This Short can't play right now."
    }

    private fun releasePlayers() {
        retiring?.cancel()
        dropRetired()
        pool.forEach { it.release() }
        pool.clear()
        assigned.clear()
        glassFrames.clear()
        samples.clear()
    }

    /** A page left the pager: its glass sample goes with it. */
    internal fun forgetPage(page: Int) {
        videoRects.remove(page)
        glassFrames.remove(page)
        samples.remove(page)
        sampledAt.remove(page)
    }

    // ── Account ──

    private fun accountFor(id: String) {
        if (!Account.signedIn || !accountAsked.add(id)) return
        scope.launch {
            runCatching { InnerTube.videoState(id) }
                .onSuccess { accountStates[id] = it }
                .onFailure { if (it !is CancellationException) { Log.w(TAG, "shorts: account state for $id", it); accountAsked.remove(id) } }
        }
    }

    /** Watched Shorts join the account's history, as on YouTube (the start ping; no watch time). */
    private fun joinHistory(id: String) {
        if (!Account.signedIn || !watched.add(id)) return
        scope.launch(Dispatchers.IO) {
            runCatching { InnerTube.startWatch(id) }.onFailure { Log.w(TAG, "shorts: history for $id", it) }
        }
    }

    fun toggleLike() {
        val id = current?.id ?: return
        if (!Account.signedIn) { onMessage("Sign in to like Shorts."); return }
        val before = accountStates[id] ?: return
        val next = if (before.like == LikeStatus.Like) LikeStatus.None else LikeStatus.Like
        accountStates[id] = before.copy(like = next)
        scope.launch {
            runCatching { InnerTube.setLike(id, next) }
                .onFailure { if (it !is CancellationException) { accountStates[id] = before; onMessage("Couldn't update your like.") } }
        }
    }

    fun toggleSubscribe() {
        val id = current?.id ?: return
        if (!Account.signedIn) { onMessage("Sign in to subscribe."); return }
        val before = accountStates[id] ?: return
        val channelId = before.channelId
            ?: details[id]?.item?.channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
            ?: return
        val subscribe = before.subscribed != true
        // Every loaded short from that channel shows it.
        val touched = accountStates.filterValues { it.channelId == channelId }.keys + id
        touched.forEach { k -> accountStates[k]?.let { accountStates[k] = it.copy(subscribed = subscribe, channelId = channelId) } }
        scope.launch {
            runCatching { InnerTube.setSubscribed(channelId, subscribe) }
                .onFailure {
                    if (it is CancellationException) throw it
                    touched.forEach { k -> accountStates[k]?.let { s -> accountStates[k] = s.copy(subscribed = !subscribe) } }
                    onMessage("Couldn't update your subscription.")
                }
        }
    }

    fun toggleChannelMenu() {
        channelMenu = !channelMenu
    }

    /** A page went over Shorts (or away): pause the short under it (or carry on). */
    fun cover(value: Boolean) {
        if (covered == value) return
        covered = value
        if (value) channelMenu = false
        if (active) tick()
    }

    fun openComments() {
        val id = current?.id ?: return
        if (commentsFor != id) { comments.load(id); commentsFor = id }
        commentsOpen = true
    }

    // ── Glass ──

    /**
     * A frame reached page [page]'s view: sample it for the glass while it reaches the bar (≤ 30
     * a second), and once in any case — a waiting short's still first frame, ready for a swipe.
     */
    internal fun onFrameDrawn(view: TextureView, page: Int) {
        if (view.width <= 0 || view.height <= 0) return
        val rect = videoRects[page] ?: return
        val underBar = rect.top < barBottom && rect.bottom > barTop
        if (!underBar && glassFrames.containsKey(page)) return
        val now = SystemClock.uptimeMillis()
        if (now - (sampledAt[page] ?: 0L) < 33) return
        sampledAt[page] = now
        val w = GlassSampleWidth
        val h = (w.toLong() * view.height / view.width).toInt().coerceIn(1, w * 4)
        // Two bitmaps in turn: the one being drawn is never the one being written.
        val flip = 1 - (flips[page] ?: 0)
        flips[page] = flip
        val pair = samples.getOrPut(page) { arrayOfNulls(2) }
        val bitmap = pair[flip]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { pair[flip] = it }
        view.getBitmap(bitmap) ?: return
        blur(bitmap)
        glassFrames[page] = bitmap.asImageBitmap()
    }

    /** Three box-blur passes each way (close to a Gaussian), on a bitmap a few dozen pixels wide. */
    private fun blur(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (pixels.size != w * h) { pixels = IntArray(w * h); spare = IntArray(w * h) }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            boxPass(pixels, spare, w, h, horizontal = true)
            boxPass(spare, pixels, w, h, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val r = GlassBlurRadius
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val n = 2 * r + 1
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var red = 0; var green = 0; var blue = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val c = src[if (horizontal) line * w + j else j * w + line]
                    red += (c shr 16) and 0xFF; green += (c shr 8) and 0xFF; blue += c and 0xFF
                }
                dst[if (horizontal) line * w + i else i * w + line] =
                    (0xFF shl 24) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
            }
        }
    }
}

/** One player of the pool: a short's streams, looping, on whichever page view it's given. */
@OptIn(UnstableApi::class)
internal class ShortPlayer(context: Context, private val state: ShortsState) {
    val exo: ExoPlayer = ExoPlayer.Builder(context, SyncedRenderersFactory(context))
        .setMediaSourceFactory(YouTubeMediaSourceFactory(context))
        // The whole short, up to a memory cap (four of these are alive at once); a waiting
        // short only its first seconds ([capMs]).
        .setLoadControl(CappedLoadControl(capUs = { capMs.let { if (it == Long.MAX_VALUE) it else it * 1_000 } }))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_ONE }

    var index = -1
        private set
    var id: String? = null
        private set
    var prepared = false
        private set
    /** Its current short has a frame on its current view (until then the page shows the first-frame image). */
    var firstFrame by mutableStateOf(false)
        private set
    private var surface: Surface? = null
    private var retried = false
    private var loadedNoted = false
    /** How far ahead of its position it may load (ms); read on the playback thread. */
    @Volatile
    var capMs = Long.MAX_VALUE

    /** Loaded ahead of where it is, in ms. */
    val lead: Long get() = if (prepared) (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0) else 0

    init {
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) state.onStartedPlaying(this@ShortPlayer) }
            override fun onIsLoadingChanged(isLoading: Boolean) = state.poke()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING && exo.currentPosition > 0 && index == state.index) {
                    Log.w("Skipii", "shorts: $id stalled at ${exo.currentPosition / 1000}s (${lead} ms loaded ahead)")
                }
                state.poke()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val v = id ?: return
                if (videoSize.width > 0 && videoSize.height > 0) state.aspects[v] = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onPlayerError(error: PlaybackException) = state.onPlayerError(this@ShortPlayer, error)
        })
        // Lip sync: the same TextureView correction as the main player (AvSync).
        exo.setVideoFrameMetadataListener { _, releaseTimeNs, _, _ -> AvSync.onFrameReleased(releaseTimeNs) }
    }

    fun take(page: Int, videoId: String) {
        exo.stop()
        exo.clearMediaItems()
        index = page
        id = videoId
        prepared = false
        firstFrame = false
        retried = false
        loadedNoted = false
        capMs = PreviewMs
        urls = emptyList()
        banked = false
    }

    fun free() {
        take(-1, "")
        id = null
    }

    /** The stream URLs it reads (cache keys the bank's cleanup must leave alone). */
    var urls: List<String> = emptyList()
        private set
    /** Playing from the bank (on disk). */
    var banked = false
        private set

    /** Start loading [source] (its short's video + audio, through the bank's cache). */
    fun prepare(why: String, source: androidx.media3.exoplayer.source.MediaSource, streamUrls: List<String>, banked: Boolean) {
        val v = id ?: return
        Log.i("Skipii", "shorts: loading $v ($why)")
        prepared = true
        urls = streamUrls
        this.banked = banked
        exo.setMediaSource(source)
        exo.prepare()
    }

    /** Once per short: start over on fresh links. */
    fun retry(): Boolean {
        if (retried) return false
        retried = true
        unprepare()
        return true
    }

    /** Drops what it loaded (and its decoder); the next tick that wants it prepares it again. */
    fun unprepare() {
        exo.stop()
        prepared = false
        banked = false
        firstFrame = false
        loadedNoted = false
    }

    /** Loaded to its end, or as much as it's allowed to hold (a long short) with plenty ahead. */
    val loaded: Boolean
        get() {
            val duration = exo.duration
            if (!prepared || duration == C.TIME_UNSET || duration <= 0) return false
            val buffered = exo.bufferedPosition
            return buffered >= duration - 300 || (!exo.isLoading && buffered - exo.currentPosition >= 30_000)
        }

    /** Logs once when its short is in memory (what lets the next one start loading). */
    fun noteLoaded() {
        if (loadedNoted || !loaded) return
        loadedNoted = true
        Log.i("Skipii", "shorts: $id loaded (${exo.bufferedPosition / 1000}s of ${exo.duration / 1000}s)")
    }

    fun attach(s: Surface) {
        if (surface === s) return
        surface = s
        firstFrame = false
        exo.setVideoSurface(s)
    }

    fun detach(s: Surface) {
        if (surface !== s) return
        exo.clearVideoSurface(s)
        surface = null
        firstFrame = false
    }

    fun release() = exo.release()
}

/**
 * The Shorts players' load control: the whole short within [ShortBufferBytes], but never more
 * than [capUs] ahead of the playhead (a waiting short's preview). Forwarded one by one, as in
 * PlaybackService (Kotlin's `by` skips Java default methods).
 */
@OptIn(UnstableApi::class)
private class CappedLoadControl(
    private val capUs: () -> Long,
    private val inner: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(ShortBufferMs, ShortBufferMs, 500, 1_000)
        .setTargetBufferBytes(ShortBufferBytes)
        .build(),
) : androidx.media3.exoplayer.LoadControl {
    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: androidx.media3.exoplayer.LoadControl.Parameters,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.onStopped(playerId)
    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.onReleased(playerId)
    override fun getAllocator(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId) = inner.retainBackBufferFromKeyframe(playerId)
    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters) = inner.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(
        playerId: androidx.media3.exoplayer.analytics.PlayerId,
        timeline: androidx.media3.common.Timeline,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ) = inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        if (parameters.bufferedDurationUs >= capUs()) return false
        return inner.shouldContinueLoading(parameters)
    }
}

// ── The page ────────────────────────────────────────────────────────────────

/**
 * The Shorts tab: a vertical pager of full-bleed shorts (top to bottom, under the system bars).
 * Tap pauses / plays. [overlayBottom] keeps the channel and title clear of the glass bar.
 */
@Composable
fun ShortsScreen(shorts: ShortsState, progress: () -> Float, overlayBottom: Dp) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                // The picture settles in as the bar turns to glass.
                .graphicsLayer {
                    val s = lerpF(1.07f, 1f, decel(ramp(progress(), 0f, 0.8f)))
                    scaleX = s
                    scaleY = s
                },
        ) {
            when {
                shorts.items.isNotEmpty() -> key(shorts.feedVersion) { ShortsPager(shorts, overlayBottom) }
                shorts.error != null && !shorts.loading -> ShortsProblem(shorts.error.orEmpty(), onRetry = shorts::refresh)
                else -> CircularProgressIndicator(color = Ink, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
        }
        // How far in: a hairline near the screen's bottom edge (the glass bar holds only buttons).
        // Kept inside the display's rounded corners and a little off the edge, where the phone's
        // bezel hid its ends (Derek, 2026-10-29).
        val view = LocalView.current
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ProgressLift + ProgressThickness)
                .graphicsLayer { alpha = ramp(progress(), 0.5f, 1f) }
                .drawBehind {
                    val thick = ProgressThickness.toPx()
                    val lift = ProgressLift.toPx()
                    val r = bottomCornerRadius(view).toFloat()
                    // Where the corner's curve leaves the screen visible at the line's height.
                    val clear = if (r > lift) r - sqrt(r * r - (r - lift) * (r - lift)) else 0f
                    val inset = clear + ProgressMargin.toPx()
                    val w = (size.width - 2 * inset).coerceAtLeast(0f)
                    val top = size.height - lift - thick
                    val ends = CornerRadius(thick / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(inset, top), Size(w, thick), ends)
                    drawRoundRect(Ink, Offset(inset, top), Size(w * shorts.progress, thick), ends)
                },
        )
        if (shorts.commentsOpen) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CommentsPanel(shorts.comments, partialTop = maxHeight * 0.42f, onClose = { shorts.commentsOpen = false })
            }
        }
    }
}

private val ProgressThickness = 2.5.dp
/** Off the screen's bottom edge, and in from the corners' curve by [ProgressMargin]. */
private val ProgressLift = 4.dp
private val ProgressMargin = 12.dp

/** The display's bottom corner radius in px (Android 12+); 0 when unknown or square. */
private fun bottomCornerRadius(view: android.view.View): Int {
    if (android.os.Build.VERSION.SDK_INT < 31) return 0
    val insets = view.rootWindowInsets ?: return 0
    return maxOf(
        insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
        insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
    )
}

@Composable
private fun ShortsPager(shorts: ShortsState, overlayBottom: Dp) {
    val pager = rememberPagerState(initialPage = shorts.index.coerceIn(0, (shorts.items.size - 1).coerceAtLeast(0))) { shorts.items.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { shorts.select(it) } }
    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        // The pages either side are composed, so their views (and loaded first frames) are ready mid-swipe.
        beyondViewportPageCount = 1,
        key = { shorts.items.getOrNull(it)?.id ?: it },
    ) { page -> ShortPage(shorts, page, overlayBottom) }
}

@Composable
private fun ShortPage(shorts: ShortsState, page: Int, overlayBottom: Dp) {
    val item = shorts.items.getOrNull(page) ?: return
    val details = shorts.details[item.id]
    val failure = shorts.failed[item.id]
    val aspect = shorts.aspects[item.id] ?: details?.aspect ?: (9f / 16f)
    val onScreen = shorts.index == page
    DisposableEffect(page) { onDispose { shorts.forgetPage(page) } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInputTap(page) { if (shorts.index == page) shorts.togglePause() },
    ) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            // Vertical shorts fill the screen (a little cropped at the sides); wider ones fit its width.
            Box(
                Modifier
                    .fillMaxSize()
                    .aspectFit(aspect, cover = aspect < 0.7f)
                    .onGloballyPositioned { shorts.videoRects[page] = Rect(it.positionInRoot(), it.size.toSize()) },
            ) {
                AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                ShortVideo(shorts, page, Modifier.fillMaxSize())
            }
        }
        // Shades for the status bar's icons and the text.
        Box(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Black.copy(alpha = 0f)))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(330.dp)
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0f), 0.35f to Color.Black.copy(alpha = 0.18f), 1f to Color.Black.copy(alpha = 0.62f))),
        )
        if (details != null) {
            val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 2f), 4f))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 24.dp, bottom = overlayBottom)) {
                Text(details.item.channel, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shade)
                if (details.item.title.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(details.item.title, color = Ink, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, style = shade)
                }
            }
        }
        when {
            failure != null -> Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This Short can't play", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(failure, color = Ink.copy(alpha = 0.8f), fontSize = 13.5.sp, textAlign = TextAlign.Center)
            }
            onScreen && shorts.buffering -> CircularProgressIndicator(color = Ink, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
        }
        val pausedAlpha by animateFloatAsState(if (onScreen && shorts.userPaused) 1f else 0f, tween(160), label = "shortPaused")
        Icon(
            Icons.Rounded.PlayArrow, contentDescription = null, tint = Ink,
            modifier = Modifier.align(Alignment.Center).size(72.dp).graphicsLayer { alpha = pausedAlpha * 0.9f },
        )
    }
}

/**
 * A tap that leaves the touch to the pager: nothing is consumed (detectTapGestures takes the
 * down, and a quick flick then came out as a tap that paused instead of a swipe), and a touch
 * that travels past touch slop is no tap.
 */
private fun Modifier.pointerInputTap(key: Any, onTap: () -> Unit) =
    this.then(
        Modifier.pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                    up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                ) onTap()
            }
        },
    )

/**
 * The page's own TextureView, showing whichever pool player holds this page. Hidden until that
 * player has drawn a frame of its short here, so a reused view never flashes an older picture.
 */
@Composable
private fun ShortVideo(shorts: ShortsState, page: Int, modifier: Modifier) {
    val player = shorts.assigned[page]
    val latest by rememberUpdatedState(player)
    var surface by remember { mutableStateOf<Surface?>(null) }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { surface = Surface(st) }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        surface?.let { s -> latest?.detach(s); s.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        AvSync.onFrameDrawn()
                        shorts.onFrameDrawn(this@apply, page)
                    }
                }
            }
        },
        modifier = modifier.graphicsLayer { alpha = if (latest?.firstFrame == true) 1f else 0f },
    )
    DisposableEffect(player, surface) {
        val s = surface
        if (player != null && s != null) player.attach(s)
        onDispose { if (player != null && s != null) player.detach(s) }
    }
}

@Composable
private fun ShortsProblem(message: String, onRetry: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Ink.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = Ink, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .pressable(onClick = onRetry)
                .background(c.primary, CircleShape)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) { Text("Try again", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

// ── The glass bar ───────────────────────────────────────────────────────────

/**
 * The tab bar's Shorts form (Derek's mockup, 2026-10-28): it splits into ‹ and a pill of dark
 * frosted glass, both shrinking to 50 dp, while the tabs drop out (NavPill) and the buttons pop
 * in. All of it follows one [progress] (0 tabs, 1 glass), read in draw and layer lambdas only.
 *
 * The glass is One UI Launcher's recipe over the live video: the frame, sampled tiny and
 * blurred ([ShortsState.onFrameDrawn]), saturation ×1.25 and Samsung's tone curve, under a
 * 38% #252525 tint. No outline (rule 7).
 */
@Composable
fun ShortsBar(shorts: ShortsState, progress: () -> Float, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val live by remember { derivedStateOf { progress() > 0.99f } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier.onGloballyPositioned {
            origin = it.positionInRoot()
            shorts.barTop = origin.y
            shorts.barBottom = origin.y + it.size.height
        },
    ) {
        val density = LocalDensity.current
        val g = remember(constraints.maxWidth, constraints.maxHeight, density) {
            GlassGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density)
        }
        Box(Modifier.fillMaxSize().drawBehind { drawGlass(shorts, g, progress(), origin) })

        val id = shorts.current?.id
        val details = id?.let { shorts.details[it] }
        val account = id?.let { shorts.accountStates[it] }
        with(density) {
            // ‹ rides the left piece; it turns in as the bar splits.
            GlassSlot(g, g.backCenterX, BackWidth.toPx(), progress, piece = { g.left(it) }, follow = { r, x -> x - g.backCenterX + r.center.x }, appear = { p ->
                val t = ramp(p, 0.12f, 0.4f)
                Appear(alpha = t, dx = lerpF(7.dp.toPx(), 0f, decel(t)), rotation = lerpF(-25f, 0f, decel(t)))
            }, enabled = live, description = "Back", onClick = onBack) {
                Icon(BackGlyph, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp).optical(Mass.back, 22.dp))
            }
            val slot = g.slotWidth
            val tool = { k: Int -> { p: Float ->
                val t = ramp(p, 0.26f + 0.07f * k, 0.6f + 0.07f * k)
                Appear(alpha = ramp(t, 0f, 0.35f), dy = lerpF(14.dp.toPx(), 0f, decel(t)), scale = 0.6f + 0.4f * Motion.Pop.transform(t))
            } }
            val onPill = { r: Rect, x: Float -> r.left + (x - g.pillLeft) * r.width / g.pw }
            GlassSlot(g, g.toolCenterX(0), slot, progress, piece = { g.right(it) }, follow = onPill, appear = tool(0), enabled = live, description = "Like", onClick = shorts::toggleLike) {
                val liked = account?.like == LikeStatus.Like
                // Keyed so a short that arrives already liked (or loads its state) doesn't pop.
                key(id, account != null) {
                    Box(Modifier.optical(Mass.like, 22.dp)) {
                        FlourishIcon(if (liked) LikedGlyph else LikeGlyph, null, active = liked, kind = Flourish.Pop, tint = Ink, size = 22.dp)
                    }
                }
            }
            GlassSlot(g, g.toolCenterX(1), slot, progress, piece = { g.right(it) }, follow = onPill, appear = tool(1), enabled = live, description = "Comments", onClick = shorts::openComments) {
                Icon(CommentGlyph, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp).optical(Mass.comment, 22.dp))
            }
            GlassSlot(g, g.toolCenterX(2), slot, progress, piece = { g.right(it) }, follow = onPill, appear = tool(2), enabled = live, description = "Share", onClick = {
                val v = shorts.current ?: return@GlassSlot
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://youtube.com/shorts/${v.id}")
                    .putExtra(Intent.EXTRA_SUBJECT, shorts.details[v.id]?.item?.title ?: "")
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(ShareGlyph, contentDescription = null, tint = Ink, modifier = Modifier.size(23.dp).optical(Mass.share, 23.dp))
            }
            GlassSlot(g, g.toolCenterX(3), slot, progress, piece = { g.right(it) }, follow = onPill, appear = tool(3), enabled = live, description = "Channel", onClick = shorts::toggleChannelMenu) {
                // The channel as drawn (no optical nudge: Derek, 2026-10-28), its badge + / ✓.
                Box(Modifier.size(30.dp)) {
                    ChannelAvatar(details?.item?.avatar, details?.item?.channel.orEmpty(), size = 30)
                    if (Account.signedIn && account != null) {
                        val subscribed = account.subscribed == true
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 4.dp, y = 2.dp)
                                .size(15.dp)
                                .squishOn(subscribed)
                                .background(Ink, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (subscribed) CheckGlyph else PlusGlyph, contentDescription = null, tint = Color(0xFF141218), modifier = Modifier.size(11.dp)) }
                    }
                }
            }
        }
    }
}

/** A button's way in: fade, nudge, turn, pop (each one's own ramp of the progress). */
private class Appear(val alpha: Float, val dx: Float = 0f, val dy: Float = 0f, val rotation: Float = 0f, val scale: Float = 1f)

/**
 * One of the bar's buttons, [width] wide and the glass's height, centred at [centerX] where it
 * ends up; until then it rides its glass piece ([piece] at the progress, [follow] maps its x).
 */
@Composable
private fun GlassSlot(
    g: GlassGeometry,
    centerX: Float,
    width: Float,
    progress: () -> Float,
    piece: (Float) -> Rect,
    follow: (Rect, Float) -> Float,
    appear: (Float) -> Appear,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset((centerX - width / 2f).roundToInt(), (g.h - g.h2).roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { g.h2.toDp() })
            .graphicsLayer {
                val p = progress()
                val r = piece(p)
                val a = appear(p)
                translationX = follow(r, centerX) - centerX + a.dx
                translationY = r.center.y - g.centerY + a.dy
                alpha = a.alpha
                rotationZ = a.rotation
                scaleX = a.scale
                scaleY = a.scale
            }
            .pressable(enabled = enabled, onClick = onClick)
            .semanticsLabel(description),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.semanticsLabel(label: String) =
    this.then(Modifier.semantics { contentDescription = label })

/** The bar's shapes along the progress, in the bar's own pixels: tab bar (0) → ‹ + pill (1). */
private class GlassGeometry(val w: Float, val h: Float, density: Density) {
    val h2: Float
    private val cw: Float
    private val backFrom: Float
    val pw: Float
    private val x0: Float
    val pillLeft: Float
    private val inset: Float

    init {
        with(density) {
            h2 = GlassHeight.toPx()
            cw = BackWidth.toPx()
            backFrom = BackFrom.toPx()
            val gap = GlassGap.toPx()
            pw = PillWidth.toPx().coerceAtMost(w - cw - gap)
            x0 = (w - (cw + gap + pw)) / 2f
            pillLeft = x0 + cw + gap
            inset = PillInset.toPx()
        }
    }

    val centerY get() = h - h2 / 2f
    val backCenterX get() = x0 + cw / 2f
    val slotWidth get() = (pw - 2 * inset) / 4f
    fun toolCenterX(k: Int) = pillLeft + inset + (k + 0.5f) * slotWidth

    fun split(p: Float) = decel(ramp(p, 0.04f, 0.66f))

    private fun height(p: Float) = lerpF(h, h2, split(p))

    fun left(p: Float): Rect {
        val s = split(p)
        val x = lerpF(0f, x0, s)
        return Rect(x, h - height(p), x + lerpF(backFrom, cw, s), h)
    }

    fun right(p: Float): Rect {
        val s = split(p)
        val x = lerpF(backFrom, pillLeft, s)
        return Rect(x, h - height(p), x + lerpF(w - backFrom, pw, s), h)
    }
}

/** Saturation ×1.25, then Samsung's blur curve (15–255 → 53.5–232), as One UI Launcher's glass. */
private val GlassTone: ColorFilter = run {
    val s = 1.25f
    val inv = 1f - s
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    val k = (232f - 53.5f) / (255f - 15f)
    val o = 53.5f - 15f * k
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                k * (r + s), k * g, k * b, 0f, o,
                k * r, k * (g + s), k * b, 0f, o,
                k * r, k * g, k * (b + s), 0f, o,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/** The two glass pieces: the blurred frame lined up with the picture under them, then the tint. */
private fun DrawScope.drawGlass(shorts: ShortsState, g: GlassGeometry, p: Float, origin: Offset) {
    val alpha = ramp(p, 0f, 0.28f)
    if (alpha <= 0f) return
    val s = g.split(p)
    // The pages under the bar right now (two mid-swipe), each with its blurred frame.
    val under = shorts.framesUnder(origin, size)
    for (leftPiece in booleanArrayOf(true, false)) {
        val rect = if (leftPiece) g.left(p) else g.right(p)
        val outer = CornerRadius(rect.height / 2f)
        val inner = CornerRadius(rect.height / 2f * s)
        val shape = Path().apply {
            addRoundRect(
                RoundRect(
                    rect,
                    topLeft = if (leftPiece) outer else inner,
                    topRight = if (leftPiece) inner else outer,
                    bottomRight = if (leftPiece) inner else outer,
                    bottomLeft = if (leftPiece) outer else inner,
                ),
            )
        }
        drawFrost(shape, under, origin, alpha, GlassTint)
    }
}

/** Frosted glass in [shape]: the blurred frames ([under], root rects) lined up with the picture, then [tint]. */
private fun DrawScope.drawFrost(shape: Path, under: List<Pair<Rect, ImageBitmap>>, origin: Offset, alpha: Float, tint: Color) {
    clipPath(shape) {
        under.forEach { (video, frame) ->
            drawImage(
                frame,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset((video.left - origin.x).roundToInt(), (video.top - origin.y).roundToInt()),
                dstSize = IntSize(video.width.roundToInt(), video.height.roundToInt()),
                alpha = alpha,
                colorFilter = GlassTone,
                filterQuality = FilterQuality.Low,
            )
        }
        drawRect(if (under.isNotEmpty()) tint else GlassTintBare, alpha = alpha)
    }
}

/** The frames of the pages reaching a box at [origin] of [size] (root pixels). */
private fun ShortsState.framesUnder(origin: Offset, size: Size) = glassFrames.mapNotNull { (page, frame) ->
    videoRects[page]?.takeIf { it.top < origin.y + size.height && it.bottom > origin.y }?.let { it to frame }
}

/** The channel card's glass: darker than the bar's, so its words read over any picture. */
private val MenuTint = Color(0xFF252525).copy(alpha = 0.62f)

/**
 * The channel button's card (Derek, 2026-10-29: "two options, one to subscribe, one to go to the
 * channel"): the same dark frosted glass as the bar, rising from the button, its right edge on
 * the pill's. The channel's name, then Subscribe / Unsubscribe and Go to channel. A tap anywhere
 * else, Back or the next short closes it. [bottom] is the glass bar's top plus a gap.
 */
@Composable
fun ShortsChannelMenu(shorts: ShortsState, bottom: Dp, onChannel: (String) -> Unit) {
    val reduced = rememberReducedMotion()
    val open = shorts.channelMenu
    val shown by animateFloatAsState(
        if (open) 1f else 0f,
        if (reduced) tween(0) else if (open) androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 700f) else tween(140),
        label = "channelMenu",
    )
    if (!open && shown <= 0f) return
    val id = shorts.current?.id
    val details = id?.let { shorts.details[it] }
    val account = id?.let { shorts.accountStates[it] }
    val subscribed = account?.subscribed == true
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Outside the card: any touch only closes it (the short underneath doesn't see it). A layer
        // BEHIND the card, not around it: around it, it saw the card's own taps first and closed
        // the card on touch-down, so Subscribe / Go to channel never got their tap (Derek, 2026-10-29).
        if (open) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        shorts.channelMenu = false
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
            )
        }
        // Right edge on the pill's: the bar's ‹ + gap + pill, centred in the tab bar's slot.
        val slot = minOf(maxWidth - 32.dp, NavWidth)
        val group = BackWidth + GlassGap + minOf(PillWidth, slot - BackWidth - GlassGap)
        val end = (maxWidth - group) / 2
        var origin by remember { mutableStateOf(Offset.Zero) }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = end, bottom = bottom)
                .width(MenuWidth)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .graphicsLayer {
                    alpha = shown.coerceIn(0f, 1f)
                    val k = 0.82f + 0.18f * shown
                    scaleX = k
                    scaleY = k
                    // Grows out of the channel button (bottom right).
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.86f, 1.15f)
                }
                .drawBehind {
                    val r = with(density) { 24.dp.toPx() }
                    val shape = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r))) }
                    drawFrost(shape, shorts.framesUnder(origin, size), origin, 1f, MenuTint)
                }
                // The whole card is the card's: a touch between its rows doesn't reach the layer behind.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(vertical = 8.dp),
        ) {
            Text(
                details?.item?.channel.orEmpty(),
                color = Ink.copy(alpha = 0.7f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 4.dp),
            )
            MenuRow(
                icon = if (subscribed) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd,
                label = if (subscribed) "Unsubscribe" else "Subscribe",
                enabled = open,
            ) {
                shorts.toggleSubscribe()
                shorts.channelMenu = false
            }
            MenuRow(icon = Icons.Rounded.AccountCircle, label = "Go to channel", enabled = open) {
                shorts.channelMenu = false
                details?.item?.channelUrl?.let(onChannel) ?: shorts.onMessage("This channel can't be opened.")
            }
        }
    }
}

private val MenuWidth = 212.dp

@Composable
private fun MenuRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Glyphs ── (the mockup's own paths, so the bar matches it exactly)

private fun glyph(name: String, vararg paths: String, stroke: Float = 0f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            if (stroke > 0f) addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            else addPath(addPathNodes(d), fill = SolidColor(Color.Black))
        }
    }.build()

private val BackGlyph = glyph("ShortsBack", "M15 4.5 7.5 12l7.5 7.5", stroke = 2.8f)
private val LikeGlyph = glyph(
    "ShortsLike",
    "M9 21h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.58 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2zM9 9l4.34-4.34L12 10h9v2l-3 7H9V9zM1 9h4v12H1z",
)
private val LikedGlyph = glyph(
    "ShortsLiked",
    "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z",
)
private val CommentGlyph = glyph(
    "ShortsComment",
    "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z",
    "M8 9h8a1 1 0 0 1 0 2H8a1 1 0 0 1 0-2z",
)
private val ShareGlyph = glyph(
    "ShortsShare",
    "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z",
)
private val PlusGlyph = glyph("ShortsPlus", "M12 5.5v13M5.5 12h13", stroke = 3.2f)
private val CheckGlyph = glyph("ShortsCheck", "M5 12.6l4.6 4.6L19.2 7.4", stroke = 2.6f)

/**
 * Where each glyph's weight sits in its 24-unit box (centroid of its filled silhouette, measured
 * at 480 px). Centring that point instead of the box is what makes them look centred (Derek,
 * 2026-10-28); the comment bubble goes only halfway (his call).
 */
private object Mass {
    val back = Offset(11.6f, 11.97f)
    val like = Offset(11.64f, 13.42f)
    val comment = Offset(11.74f, 11.12f)
    val share = Offset(13.38f, 11.9f)
}

/** Shifts a glyph drawn [size] big so its [mass] point lands where its box centre was. */
private fun Modifier.optical(mass: Offset, size: Dp) =
    this.then(Modifier.offset(x = size * ((12f - mass.x) / 24f), y = size * ((12f - mass.y) / 24f)))
