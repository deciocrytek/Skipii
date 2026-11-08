package com.decio.skipii

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Android TV (2026-11-07, Derek: "let's build it"; mockup in docs/design/android-tv.html). On a TV the open
 * player is always this: the picture across the screen, driven by the remote. Nothing is touched, so none of the
 * phone's gestures exist here (morph, the playback pill, double tap, slides, PiP).
 *
 * Hidden controls: ◀ ▶ scrub the track (it comes up with the controls), OK plays / pauses — or skips a SponsorBlock
 * segment set to Ask while its pill shows — ▲ ▼ just bring the controls. Shown: the D-pad walks the controls; the
 * focused track scrubs by the seek step (×3 once a key is held), shows the storyboard frame it would land on and
 * seeks once the keys rest ([ScrubSettleMs]) or on OK. ▼ from the keys reaches More videos, which slides up.
 * Back steps out: a panel, a pending scrub, the controls, then the player (the mini-player card carries on).
 */
@Composable
fun TvPlayer(state: PlayerState, onClose: () -> Unit, onOpen: (VideoItem) -> Unit, onChannel: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    fun touched() { touchedAt = System.nanoTime() }
    var panel by remember { mutableStateOf<TvPanel?>(null) }
    var inMore by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubPresses by remember { mutableIntStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val trackFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelKeys = remember { TvPanel.entries.associateWith { FocusRequester() } }
    // Where focus goes once the controls are composed (they aren't while hidden, so nothing hidden can hold it).
    var focusNext by remember { mutableStateOf<FocusRequester?>(rootFocus) }
    LaunchedEffect(focusNext, shown) {
        val target = focusNext ?: return@LaunchedEffect
        target.focusWhenReady()
        focusNext = null
    }
    fun reveal(target: FocusRequester) { shown = true; touched(); focusNext = target }
    fun hide() { shown = false; panel = null; inMore = false; focusNext = rootFocus }

    // Each video starts with the controls up for a moment, play focused.
    LaunchedEffect(state.current?.id) { panel = null; scrubMs = null; reveal(playFocus) }
    LaunchedEffect(shown, state.isPlaying, touchedAt, panel, scrubMs, inMore) {
        if (shown && state.isPlaying && panel == null && scrubMs == null && !inMore) {
            delay(TvControlsHideMs)
            hide()
        }
    }
    fun commitScrub() {
        scrubMs?.let(state::seekTo)
        scrubMs = null
        scrubPresses = 0
    }
    LaunchedEffect(scrubMs) {
        if (scrubMs == null) return@LaunchedEffect
        delay(ScrubSettleMs)
        commitScrub()
    }
    fun scrubStep(side: Int, held: Boolean) {
        if (state.durationMs <= 0) return
        val base = scrubMs ?: state.positionMs
        val step = SeekStep.ms * if (held || scrubPresses >= 6) 3 else 1
        scrubMs = (base + side * step).coerceIn(0L, state.durationMs)
        scrubPresses++
    }
    fun closePanel() { val was = panel; panel = null; focusNext = was?.let(panelKeys::get) ?: playFocus }

    BackHandler {
        when {
            panel != null -> closePanel()
            scrubMs != null -> { scrubMs = null; scrubPresses = 0 }
            inMore -> focusNext = playFocus
            shown -> hide()
            else -> onClose()
        }
    }

    val overlay by animateFloatAsState(if (shown) 1f else 0f, if (reduced) snap() else tween(220, easing = Motion.Emphasized), label = "tvControls")
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { touched(); false }
            // The remote stays in the player: the page under it is still composed, and focus walked off the last key into it.
            .trapFocus()
            .focusRequester(rootFocus)
            .focusable()
            // Only reached with the controls hidden (or nothing else taking the key): the root holds focus then.
            .onKeyEvent { event -> if (shown) false else hiddenKey(event, state, onScrub = { side, held -> reveal(trackFocus); scrubStep(side, held) }, onShow = { reveal(playFocus) }) },
    ) {
        Box(Modifier.fillMaxSize().aspectFit(state.videoAspect)) {
            Thumbnail(state.current?.thumbnail, Modifier.fillMaxSize(), radius = 0)
            LocalVideoSurface.current(Modifier.fillMaxSize().hideStaleVideo(state))
            if (state.error == null) {
                val lift by animateDpAsState(if (shown) CaptionsOverControls else 28.dp, if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 380f), label = "tvCaptionLift")
                CaptionLayer(state, bottom = lift)
            }
        }
        val error = state.error
        if (error != null) {
            ErrorPanel(error, state, onCollapse = onClose)
            return@Box
        }
        if (!shown && (state.isBuffering || state.hidesStaleVideo || (state.loading && state.details == null))) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }
        SkippedNote(state, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        // A segment set to Ask, controls hidden: OK skips it (as a TV app's Skip ad does).
        if (!shown) state.askSegment?.let { segment -> TvAskPill(segment, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp)) }

        if (shown || overlay > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = overlay }) {
                TvControls(
                    state, shown, inMore, scrubMs,
                    trackFocus = trackFocus, playFocus = playFocus, panelKeys = panelKeys,
                    onScrub = { side, held -> scrubStep(side, held) },
                    onCommitScrub = ::commitScrub,
                    onPanel = { panel = it; touched() },
                    onMoreFocus = { inMore = it },
                    onOpen = onOpen,
                )
            }
        }
        AnimatedVisibility(
            visible = panel != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduced) fadeIn(snap()) else slideInHorizontally(tween(320, easing = Motion.Emphasized)) { it / 3 } + fadeIn(tween(180)),
            exit = if (reduced) fadeOut(snap()) else slideOutHorizontally(tween(220, easing = Motion.Exit)) { it / 3 } + fadeOut(tween(160)),
        ) {
            // Kept through the slide-out: the last panel stays drawn while it leaves.
            val shownPanel = remember { mutableStateOf(TvPanel.More) }
            panel?.let { shownPanel.value = it }
            TvSidePanel(state, shownPanel.value, onDone = ::closePanel, onSwitch = { panel = it }, onChannel = { url -> panel = null; onChannel(url) })
        }
    }
}

/** On a TV, the feed card that opened the playing video: closing [TvPlayer] puts the remote back on it. */
object TvFocusMemory {
    var opener: FocusRequester? = null
}

/** The controls stay up this long after the last key while playing (longer than the phone's: across the room). */
private const val TvControlsHideMs = 5_000L
/** A scrub seeks once ◀ ▶ have rested this long. */
private const val ScrubSettleMs = 800L

private enum class TvPanel { Speed, Quality, Captions, Chapters, More }

private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** A key with the controls hidden; true if it was used. */
private fun hiddenKey(event: KeyEvent, state: PlayerState, onScrub: (side: Int, held: Boolean) -> Unit, onShow: () -> Unit): Boolean {
    val down = event.type == KeyEventType.KeyDown
    when {
        state.error != null -> if (event.isOk()) { if (down) state.retry(); return true }
        event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
            if (down) onScrub(if (event.key == Key.DirectionLeft) -1 else 1, event.nativeKeyEvent.repeatCount > 2)
            return true
        }
        event.isOk() -> {
            if (down && event.nativeKeyEvent.repeatCount == 0) {
                val ask = state.askSegment
                if (ask != null) state.skipSegment(ask) else { state.togglePlay(); onShow() }
            }
            return true
        }
        event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
            if (down) onShow()
            return true
        }
    }
    return false
}

@Composable
private fun BoxScope.TvControls(
    state: PlayerState,
    shown: Boolean,
    inMore: Boolean,
    scrubMs: Long?,
    trackFocus: FocusRequester,
    playFocus: FocusRequester,
    panelKeys: Map<TvPanel, FocusRequester>,
    onScrub: (side: Int, held: Boolean) -> Unit,
    onCommitScrub: () -> Unit,
    onPanel: (TvPanel) -> Unit,
    onMoreFocus: (Boolean) -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    // The loaded details' copy when there is one: an item restored from the last session has no channel link or avatar.
    val details = state.details
    val item = details?.item?.takeIf { it.id == state.current?.id } ?: state.current ?: return
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.66f),
                0.26f to Color.Black.copy(alpha = 0f),
                0.42f to Color.Black.copy(alpha = 0f),
                0.72f to Color.Black.copy(alpha = 0.6f),
                1f to Color.Black.copy(alpha = 0.92f),
            ),
        ),
    )
    // Title, channel and what's playing.
    Row(Modifier.align(Alignment.TopStart).padding(start = TvSide, end = TvSide, top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelAvatar(item.avatar, item.channel, size = 42)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val quality = state.decodedHeight?.let { "${it}p" } ?: details?.quality
            Text(
                listOfNotNull(item.channel.takeIf { it.isNotBlank() }, quality).joinDots(),
                color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }

    // The bottom block: times, track, keys, and More videos peeking under them (all of it slides up when focused).
    val related = remember(details) { details?.related.orEmpty().filterNot(Dismissed::hides).take(24) }
    val density = LocalDensity.current
    var moreHeight by remember { mutableIntStateOf(0) }
    val rise by animateFloatAsState(if (inMore) 1f else 0f, if (reduced) snap() else spring(dampingRatio = 0.86f, stiffness = 420f), label = "tvMoreRise")
    // The controls rise past the gradient with More videos: the picture dims behind them so they still read.
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = rise }.background(Color.Black.copy(alpha = 0.55f)))
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .graphicsLayer {
                val peek = with(density) { MorePeek.toPx() }
                translationY = (moreHeight - peek).coerceAtLeast(0f) * (1f - rise)
            },
    ) {
        val shownMs = scrubMs ?: state.positionMs
        Row(Modifier.padding(horizontal = TvSide), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(shownMs), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(" / " + formatDuration(state.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            state.chapterAt(shownMs)?.let {
                Text(" · $it", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            if (scrubMs != null) {
                val jump = scrubMs - state.positionMs
                Text((if (jump >= 0) "+" else "−") + formatDuration(abs(jump)), color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            } else {
                Text("−" + formatDuration((state.durationMs - state.positionMs).coerceAtLeast(0L)), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        TvTrack(state, shown, scrubMs, trackFocus, onScrub, onCommitScrub)
        Spacer(Modifier.height(14.dp))
        // −10 · play · +10 in the middle; captions and speed to the left, chapters, next and ⋯ to the right.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.captions.tracks.isNotEmpty()) {
                TvKey(stringResource(R.string.video_captions), shown, filled = state.captions.on, modifier = Modifier.focusRequester(panelKeys.getValue(TvPanel.Captions)), onClick = {
                    state.captions.turn(true); onPanel(TvPanel.Captions)
                }) { ink -> Icon(Icons.Rounded.ClosedCaption, null, tint = ink, modifier = Modifier.size(26.dp)) }
            }
            TvKey(stringResource(R.string.video_playback_speed), shown, filled = PlaybackSpeed.remember && !PlaybackSpeed.isNormal(state.speed), modifier = Modifier.focusRequester(panelKeys.getValue(TvPanel.Speed)), onClick = { onPanel(TvPanel.Speed) }) { ink ->
                Text(PlaybackSpeed.label(state.speed) + "×", color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            TvKey(seekLabel(-1), shown, onClick = { state.seekBy(-SeekStep.ms) }) { ink -> Icon(seekIcon(forward = false), null, tint = ink, modifier = Modifier.size(28.dp)) }
            TvPlayKey(state, shown, Modifier.focusRequester(playFocus))
            TvKey(seekLabel(1), shown, onClick = { state.seekBy(SeekStep.ms) }) { ink -> Icon(seekIcon(forward = true), null, tint = ink, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(10.dp))
            if (details?.chapters.orEmpty().isNotEmpty()) {
                TvKey(stringResource(R.string.bar_chapters), shown, modifier = Modifier.focusRequester(panelKeys.getValue(TvPanel.Chapters)), onClick = { onPanel(TvPanel.Chapters) }) { ink ->
                    Icon(Icons.Rounded.Toc, null, tint = ink, modifier = Modifier.size(26.dp))
                }
            }
            if (state.queue.isNotEmpty()) {
                TvKey(stringResource(R.string.video_next), shown, onClick = state::playNextUp) { ink -> Icon(Icons.Rounded.SkipNext, null, tint = ink, modifier = Modifier.size(28.dp)) }
            }
            TvKey(stringResource(R.string.bar_more_controls), shown, modifier = Modifier.focusRequester(panelKeys.getValue(TvPanel.More)), onClick = { onPanel(TvPanel.More) }) { ink ->
                Icon(Icons.Rounded.MoreHoriz, null, tint = ink, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.onSizeChanged { moreHeight = it.height }) {
            Row(Modifier.padding(horizontal = TvSide).height(MoreLabelHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (related.isNotEmpty()) TvHint("▼", stringResource(R.string.player_more_videos))
                TvHint("◀ ▶", stringResource(R.string.tv_hint_seek))
                TvHint("OK", stringResource(R.string.tv_hint_play_pause))
            }
            if (related.isNotEmpty()) {
                val list = rememberLazyListState()
                LazyRow(
                    state = list,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onMoreFocus(it.hasFocus) },
                    contentPadding = PaddingValues(start = TvSide, end = TvSide, top = 12.dp, bottom = 34.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(related, key = { it.id }) { video -> TvMoreCard(video, enabled = shown, onClick = { onOpen(video) }) }
                }
            }
        }
    }
}

/** Captions while the controls show: above their whole block (times, track, keys, hints). */
private val CaptionsOverControls = 236.dp

/** Room either side of the controls. */
private val TvSide = 48.dp
private val MoreLabelHeight = 24.dp
/** How much of More videos shows under the keys before it's focused: its hints and the top of the cards. */
private val MorePeek = 64.dp

/**
 * The scrub track, focusable: thicker with a bigger knob while focused (its focus cue), ◀ ▶ move a pending
 * spot (the stretch it would jump over in primary at 45%, the storyboard frame over it), OK seeks at once.
 */
@Composable
private fun TvTrack(state: PlayerState, enabled: Boolean, scrubMs: Long?, focus: FocusRequester, onScrub: (Int, Boolean) -> Unit, onCommit: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val thick by animateDpAsState(if (focused) 7.dp else 4.dp, label = "tvTrack")
    val knob by animateDpAsState(if (focused) 10.dp else 6.dp, label = "tvKnob")
    val duration = state.durationMs.coerceAtLeast(1L)
    val segments = if (SponsorBlock.enabled) state.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList()
    val chapters = state.details?.chapters.orEmpty().map { it.startMs }
    val sheets = rememberStoryboardSheets(state)
    val boards = state.details?.storyboards.orEmpty()
    var trackWidth by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().padding(horizontal = TvSide)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { trackWidth = it.width }
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled)
                .onKeyEvent { event ->
                    when {
                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) onScrub(if (event.key == Key.DirectionLeft) -1 else 1, event.nativeKeyEvent.repeatCount > 2)
                            true
                        }
                        event.isOk() -> {
                            if (event.type == KeyEventType.KeyUp) { if (scrubMs != null) onCommit() else state.togglePlay() }
                            true
                        }
                        // Leaving the track for the keys lands the scrub first.
                        event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> { onCommit(); false }
                        else -> false
                    }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val h = thick.toPx()
            val y = size.height / 2f - h / 2f
            val r = CornerRadius(h / 2f)
            val played = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * buffered, h), r)
            drawRoundRect(c.primary, Offset(0f, y), Size(size.width * played, h), r)
            val landing = scrubMs?.let { (it.toFloat() / duration).coerceIn(0f, 1f) }
            if (landing != null) {
                val a = minOf(played, landing)
                val b = maxOf(played, landing)
                drawRect(c.primary.copy(alpha = 0.45f), Offset(size.width * a, y), Size(size.width * (b - a), h))
            }
            segments.forEach { seg ->
                val x0 = size.width * (seg.startMs.toFloat() / duration).coerceIn(0f, 1f)
                val x1 = size.width * (seg.endMs.toFloat() / duration).coerceIn(0f, 1f)
                if (x1 > x0) drawRect(seg.category.color, Offset(x0, y), Size(x1 - x0, h))
            }
            val gap = 3.dp.toPx()
            chapters.forEach { start ->
                val x = size.width * (start.toFloat() / duration)
                if (x > 0f && x < size.width) drawRect(Color.Black, Offset(x - gap / 2f, y), Size(gap, h), blendMode = BlendMode.Clear)
            }
            drawCircle(c.primary, radius = knob.toPx(), center = Offset(size.width * (landing ?: played), size.height / 2f))
        }
        // Where the scrub would land: the storyboard frame and the time, floating over the knob (it takes no room,
        // so the controls under it never move).
        if (scrubMs != null) {
            val frameW = 224.dp
            Column(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixedWidth(frameW.roundToPx()))
                        layout(0, 0) {
                            val fraction = (scrubMs.toFloat() / duration).coerceIn(0f, 1f)
                            val x = (trackWidth * fraction - placeable.width / 2f).coerceIn(0f, (trackWidth - placeable.width).toFloat().coerceAtLeast(0f))
                            placeable.place(x.roundToInt(), -placeable.height - 2.dp.roundToPx())
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (boards.isNotEmpty()) {
                    StoryboardThumb(
                        sheets, boards, scrubMs, state.durationMs,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .graphicsLayer { shape = RoundedCornerShape(14.dp); clip = true; shadowElevation = 12.dp.toPx(); ambientShadowColor = Color.Black; spotShadowColor = Color.Black }
                            .background(Color.White.copy(alpha = 0.12f)),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(formatDuration(scrubMs), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/**
 * A round key on the overlay: the white glass tone, full primary while [filled] (a mode that's on). Focused it turns
 * solid white with a dark glyph (a filled one keeps its primary and grows): from across the room the remote's spot
 * has to be the first thing seen.
 */
@Composable
private fun TvKey(
    description: String,
    enabled: Boolean,
    filled: Boolean = false,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (ink: Color) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .onFocusChanged { focused = it.isFocused }
            .pressable(enabled = enabled, focusedScale = 1.14f, focusTone = filled, focusCorner = size / 2, onClick = onClick)
            .clip(CircleShape)
            .background(
                when {
                    filled -> c.primary
                    focused -> Color.White
                    else -> Color.White.copy(alpha = 0.16f)
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(if (filled) c.onPrimary else if (focused) Color(0xFF111111) else Color.White) }
}

/** Play / pause: the full-primary circle a size up, a spinner while loading. */
@Composable
private fun TvPlayKey(state: PlayerState, enabled: Boolean, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    // Already the brightest thing on screen: focused, it grows and glows in its own colour.
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "tvPlayGlow")
    Box(
        modifier
            .size(78.dp)
            .onFocusChanged { focused = it.isFocused }
            .pressable(enabled = enabled, focusedScale = 1.12f, focusCorner = 39.dp, onClick = state::togglePlay)
            .graphicsLayer {
                shape = CircleShape; clip = true
                shadowElevation = 16.dp.toPx() * glow; ambientShadowColor = c.primary; spotShadowColor = c.primary
            }
            .background(c.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (state.isBuffering || (state.loading && state.details == null)) {
            CircularProgressIndicator(color = c.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        } else {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (state.isPlaying) R.string.video_pause else R.string.video_play),
                tint = c.onPrimary, modifier = Modifier.size(38.dp),
            )
        }
    }
}

/** "◀ ▶ Seek": a keycap and what it does, under the keys. */
@Composable
private fun TvHint(cap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(cap, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(7.dp))
        Text(label, color = Color.White.copy(alpha = 0.66f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A More videos card under the controls. */
@Composable
private fun TvMoreCard(video: VideoItem, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(212.dp).pressable(enabled = enabled, focusedScale = 1.07f, focusCorner = 16.dp, onClick = onClick)) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 16)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(7.dp), small = true)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(8.dp))
        Text(video.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(video.channel, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Controls hidden over a segment set to Ask: the segment's pill with the key that skips it. */
@Composable
private fun TvAskPill(segment: SponsorBlock.Segment, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(segment.category.color.copy(alpha = 0.94f)).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (segment.category.color.luminance() > 0.45f) Color(0xFF151515) else Color.White
        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(ink.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text("OK", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.video_skip_segment, segment.category.short), color = ink, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Icon(Icons.Rounded.SkipNext, null, tint = ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * The right-hand panel a key opens (speed, quality, captions, chapters, ⋯): a column of rows on the theme's
 * container, the chosen one filled and focused as it opens. A pick lands its check, then the panel closes.
 */
@Composable
private fun TvSidePanel(state: PlayerState, panel: TvPanel, onDone: () -> Unit, onSwitch: (TvPanel) -> Unit, onChannel: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val item = state.details?.item?.takeIf { it.id == state.current?.id } ?: state.current
    val rows: List<TvRowSpec> = when (panel) {
        TvPanel.Speed -> {
            val now = PlaybackSpeed.snap(state.speed)
            fun same(a: Float, b: Float) = abs(a - b) < PlaybackSpeed.STEP / 2
            val custom = now.takeIf { s -> SpeedPresets.none { same(it, s) } }
            (SpeedPresets + listOfNotNull(custom)).sorted().map { s ->
                TvRowSpec(
                    PlaybackSpeed.label(s) + "×",
                    when {
                        PlaybackSpeed.isNormal(s) -> stringResource(R.string.video_speed_normal)
                        custom != null && same(s, custom) -> stringResource(R.string.video_speed_custom)
                        else -> null
                    },
                    same(s, now),
                ) { state.commitSpeed(s); true }
            } + TvRowSpec(stringResource(R.string.video_speed_every_video), null, PlaybackSpeed.remember, toggle = true) { state.toggleRememberSpeed(); false }
        }
        TvPanel.Quality -> {
            val details = state.details
            val chosen = QualityPreference.height
            val playing = state.decodedHeight?.let { "${it}p" } ?: details?.quality
            listOf(TvRowSpec(stringResource(R.string.video_quality_auto), playing, chosen == null) { state.setQuality(null); true }) +
                details?.qualities.orEmpty().map { h -> TvRowSpec("${h}p", qualityTag(h), chosen == h) { if (h != QualityPreference.height) state.setQuality(h); true } }
        }
        TvPanel.Captions -> {
            val cap = state.captions
            val options = remember { captionOptions(cap, all = false).filterIsInstance<CaptionOption.Pick>() }
            listOf(TvRowSpec(stringResource(R.string.video_captions_off), null, !cap.on) { cap.turn(false); true }) + options.map { option ->
                TvRowSpec(
                    option.title,
                    when {
                        option.choice.translateTo == null -> if (option.choice.track.auto) stringResource(R.string.video_captions_tag_auto) else null
                        option.busy -> stringResource(R.string.video_captions_tag_downloading)
                        option.ready -> stringResource(R.string.video_captions_tag_translated)
                        else -> stringResource(R.string.video_captions_tag_download)
                    },
                    cap.on && cap.shows(option),
                ) { cap.pick(option.choice); true }
            }
        }
        TvPanel.Chapters -> {
            val chapters = state.details?.chapters.orEmpty()
            val current = chapters.indexOfLast { it.startMs <= state.positionMs }
            chapters.mapIndexed { i, ch ->
                TvRowSpec(ch.title.ifBlank { stringResource(R.string.bar_chapter_untitled, (i + 1).toString()) }, formatDuration(ch.startMs), i == current) { state.seekTo(ch.startMs); true }
            }
        }
        TvPanel.More -> buildList {
            val account = state.accountState
            if (Account.signedIn && account != null) {
                add(TvRowSpec(stringResource(R.string.player_like), null, account.like == LikeStatus.Like, toggle = true, icon = Icons.Rounded.ThumbUp) { state.setLike(LikeStatus.Like); false })
                add(TvRowSpec(stringResource(R.string.player_dislike), null, account.like == LikeStatus.Dislike, toggle = true, icon = Icons.Rounded.ThumbDown) { state.setLike(LikeStatus.Dislike); false })
            }
            state.details?.let { details ->
                val now = state.decodedHeight?.let { "${it}p" } ?: details.quality
                val label = if (QualityPreference.height == null) listOfNotNull(stringResource(R.string.video_quality_auto), now).joinDots() else "${QualityPreference.height}p"
                add(TvRowSpec(stringResource(R.string.video_quality), label, false, icon = Icons.Rounded.HighQuality) { onSwitch(TvPanel.Quality); false })
            }
            add(TvRowSpec(stringResource(R.string.video_audio_only), null, AudioOnly.enabled, toggle = true, icon = Icons.Rounded.Headphones) { state.setAudioOnly(!AudioOnly.enabled); false })
            if (state.queue.isNotEmpty()) add(TvRowSpec(stringResource(R.string.video_next), state.queue.first().title, false, icon = Icons.Rounded.SkipNext) { state.playNextUp(); true })
            item?.channelUrl?.let { url ->
                add(TvRowSpec(stringResource(R.string.shorts_go_to_channel), item.channel, false, icon = Icons.Rounded.AccountCircle) { onChannel(url); false })
            }
        }
    }
    val title = stringResource(
        when (panel) {
            TvPanel.Speed -> R.string.video_playback_speed
            TvPanel.Quality -> R.string.video_quality
            TvPanel.Captions -> R.string.video_captions
            TvPanel.Chapters -> R.string.bar_chapters
            TvPanel.More -> R.string.bar_more_controls
        },
    )
    val first = remember(panel) { FocusRequester() }
    val start = remember(panel) { rows.indexOfFirst { it.selected && !it.toggle }.coerceAtLeast(0) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LaunchedEffect(panel) { first.focusWhenReady() }
    var closing by remember(panel) { mutableStateOf(false) }
    LaunchedEffect(closing) { if (closing) { delay(320); onDone() } }
    Column(
        Modifier
            .padding(20.dp)
            .width(380.dp)
            .fillMaxHeight()
            .graphicsLayer { shape = RoundedCornerShape(30.dp); clip = true; shadowElevation = 24.dp.toPx(); ambientShadowColor = Color.Black; spotShadowColor = Color.Black }
            .trapFocus()
            .background(c.surfaceContainer)
            .padding(top = 26.dp, start = 14.dp, end = 14.dp),
    ) {
        Text(title, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, modifier = Modifier.padding(start = 14.dp, bottom = 14.dp))
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows) { i, row ->
                TvRow(row, Modifier.then(if (i == start) Modifier.focusRequester(first) else Modifier)) {
                    if (!closing && row.onPick()) closing = true
                }
            }
        }
    }
}

private class TvRowSpec(
    val title: String,
    val tag: String?,
    val selected: Boolean,
    /** A switch-like row (stays open, shows on/off) rather than a pick. */
    val toggle: Boolean = false,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** True when the panel should close after it. */
    val onPick: () -> Boolean,
)

/** A panel row: the chosen one in primaryContainer with its check; the focused one in the inverse tone. */
@Composable
private fun TvRow(row: TvRowSpec, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { focused = it.isFocused }
            .pressable(focusedScale = 1.03f, focusTone = false, onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    focused -> c.inverseSurface
                    row.selected -> c.primaryContainer
                    else -> c.surfaceContainerHigh
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = when {
            focused -> c.inverseOnSurface
            row.selected -> c.onPrimaryContainer
            else -> c.onSurface
        }
        row.icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(row.title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.tag?.let { Text(it, color = if (row.selected || focused) ink.copy(alpha = 0.75f) else c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (row.selected) Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(22.dp))
    }
}

/** Focus can move around inside this, never out of it (D-pad off its edge does nothing). */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.trapFocus(): Modifier = focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

/** Focus leaving this group [ways] goes to [target] (anything else leaves as usual). */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.leaveTo(target: FocusRequester, ways: List<FocusDirection>): Modifier =
    focusProperties { exit = { if (it in ways) target else FocusRequester.Default } }.focusGroup()

/**
 * On a TV, a page laid over the others (Settings, a channel, a playlist) takes the remote as it opens and keeps it:
 * the pages under it stay composed, and the D-pad wandered into them. [toRail]: ◀ off its left edge still reaches
 * the rail. Nothing on a phone.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.tvPage(toRail: Boolean = false): Modifier {
    if (!LocalWindowLayout.current.tv) return this
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    return focusRequester(focus)
        .focusProperties { exit = { if (toRail && it == FocusDirection.Left) FocusRequester.Default else FocusRequester.Cancel } }
        .focusGroup()
}
