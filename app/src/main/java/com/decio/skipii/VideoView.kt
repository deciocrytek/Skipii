package com.decio.skipii

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The video — shared by the portrait player and fullscreen. In fullscreen a tap toggles the
 * overlay controls; they auto-hide while playing. In portrait (0.5.34) the video has no
 * controls of its own: they all live in the player's bar (PlayerBar.kt); double-tap seek, the
 * loading spinner, the SponsorBlock Ask pill / skipped note and errors stay on the video.
 * [controlsAlpha] lets the morph hold them back until the video has landed.
 */
@Composable
fun VideoContent(
    state: PlayerState,
    fullscreen: Boolean,
    onCollapse: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    controlsAlpha: () -> Float = { 1f },
    /** False while the player collapses: no controls, and a tap calls [onInactiveTap]. */
    interactive: Boolean = true,
    onInactiveTap: () -> Unit = {},
) {
    // Hidden until the video is tapped: opening the player (from the mini-player too) and
    // rotating in or out of fullscreen never pop the controls up.
    var controlsVisible by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    fun touched() { touchedAt = System.nanoTime() }
    LaunchedEffect(controlsVisible, state.isPlaying, touchedAt) {
        if (controlsVisible && state.isPlaying) {
            delay(Motion.ControlsHideMs)
            controlsVisible = false
        }
    }
    val reduced = rememberReducedMotion()
    val visibleAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = if (reduced) tween(0) else tween(240, easing = Motion.Emphasized),
        label = "controlsAlpha",
    )

    // Double-tap seek: a double tap on the left/right 40% starts a burst of -/+10 s; each
    // further tap on that side within SeekBurstWindowMs adds 10 s more (SeekBurstPill).
    var burstSide by remember { mutableIntStateOf(1) }
    var burstSeconds by remember { mutableIntStateOf(0) }
    var burstTaps by remember { mutableIntStateOf(0) }
    var burstActive by remember { mutableStateOf(false) }
    LaunchedEffect(burstTaps) {
        if (burstTaps == 0) return@LaunchedEffect
        delay(SeekBurstWindowMs)
        burstActive = false
    }
    // Each tap nudges the picture a few dp the way it seeks (scaled up so no edge shows).
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(burstTaps) {
        if (burstTaps > 0 && !reduced) nudge.animateTo(0f, keyframes { durationMillis = 440; 1f at 130 })
    }
    val peekAlpha by animateFloatAsState(
        targetValue = if (burstActive) 1f else 0f,
        animationSpec = if (reduced) tween(0) else tween(200, easing = Motion.Emphasized),
        label = "seekPeek",
    )

    LaunchedEffect(interactive) { if (!interactive) { controlsVisible = false; burstActive = false } }
    val currentInteractive by androidx.compose.runtime.rememberUpdatedState(interactive)
    val currentInactiveTap by androidx.compose.runtime.rememberUpdatedState(onInactiveTap)
    fun toggleControls() { if (fullscreen) { controlsVisible = !controlsVisible; touched() } }
    fun seekStep(side: Int) {
        val moved = state.seekBy(side * SeekStepMs)
        burstSeconds += ((abs(moved) + 500) / 1000).toInt()
        burstTaps++
    }
    val pip = LocalPictureInPicture.current
    Box(modifier.background(Color.Black).onGloballyPositioned { pip?.videoBounds = it.boundsInWindow() }.pointerInput(state) {
        val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
        coroutineScope {
            // A lone tap on a side waits out the double-tap timeout before toggling the controls.
            var pendingTap: Job? = null
            var pendingSide = 0
            var pendingAt = 0L
            awaitEachGesture {
                awaitFirstDown()
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                if (!currentInteractive) { currentInactiveTap(); return@awaitEachGesture }
                val x = up.position.x / size.width
                val side = if (x < 0.4f) -1 else if (x > 0.6f) 1 else 0
                if (burstActive && side == burstSide) { seekStep(side); return@awaitEachGesture }
                burstActive = false
                val waiting = pendingTap?.isActive == true
                pendingTap?.cancel()
                if (side == 0 || state.error != null) {
                    toggleControls()
                } else if (waiting && side == pendingSide && up.uptimeMillis - pendingAt < doubleTapMs) {
                    controlsVisible = false
                    burstSide = side
                    burstSeconds = 0
                    burstActive = true
                    seekStep(side)
                } else {
                    pendingSide = side
                    pendingAt = up.uptimeMillis
                    pendingTap = launch { delay(doubleTapMs); toggleControls() }
                }
            }
        }
    }) {
        Box(
            Modifier
                // As large as fits in whatever box we're given. The portrait stage already has
                // the video's shape, so bars appear only past its limits (and in fullscreen).
                .fillMaxSize()
                .aspectFit(state.videoAspect)
                .clipToBounds(),
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val n = nudge.value
                    translationX = burstSide * 5.dp.toPx() * n
                    scaleX = 1f + 0.03f * n
                    scaleY = 1f + 0.03f * n
                },
            ) {
                // Under the (transparent until it has a frame) TextureView, so opening or
                // re-attaching never shows a black void.
                Thumbnail(state.current?.thumbnail, Modifier.fillMaxSize(), radius = 0)
                LocalVideoSurface.current(Modifier.fillMaxSize().hideStaleVideo(state))
            }
            // Where the burst has got to, while the controls (and their scrubber) are hidden.
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).graphicsLayer { alpha = peekAlpha }) {
                drawRect(Color.White.copy(alpha = 0.28f))
                val played = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(Color.White, size = Size(size.width * played, size.height))
            }
        }
        SeekBurstPill(burstSide, burstSeconds, burstTaps, burstActive, fullscreen)

        val error = state.error
        if (error != null) {
            ErrorPanel(error, onRetry = state::retry, onCollapse = onCollapse)
            return@Box
        }

        val enabled = controlsVisible && interactive
        // With the controls hidden, loading still shows: a small spinner in the middle.
        if (!controlsVisible && (state.isBuffering || state.hidesStaleVideo || (state.loading && state.details == null))) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
        }
        // Waiting out a bot check (BotCheck): the spinner says why this is taking a while.
        val botWait by animateFloatAsState(
            if (!controlsVisible && BotCheck.waiting != null && BotCheck.waiting == state.current?.id) 1f else 0f,
            label = "botWait",
        )
        if (botWait > 0f) {
            Text(
                "YouTube is checking this connection. Trying again…",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)),
                modifier = Modifier.align(Alignment.Center).padding(top = 76.dp, start = 32.dp, end = 32.dp).graphicsLayer { alpha = botWait },
            )
        }
        SkippedNote(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).graphicsLayer { alpha = controlsAlpha() })
        // The Ask pill stays reachable while the controls are hidden.
        if (!controlsVisible) {
            state.askSegment?.let { segment ->
                SkipSegmentPill(segment, enabled = true, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    touched(); state.skipSegment(segment)
                }
            }
        }
        if (fullscreen) Box(Modifier.fillMaxSize().graphicsLayer { alpha = visibleAlpha * controlsAlpha() }) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.32f to Color.Black.copy(alpha = 0.08f),
                        0.58f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
            )
            val edge = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
            Box(Modifier.fillMaxSize().then(edge)) {
                // Top row: minimize / exit fullscreen, title (fullscreen), quality.
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // No minimize button: swipe the player down (or Back) instead.
                    if (fullscreen) {
                        Text(
                            state.current?.title.orEmpty(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // The label is the resolution actually decoded; the requested one until known.
                    (state.decodedHeight?.let { "${it}p" } ?: state.details?.quality)?.let { quality ->
                        Box(
                            Modifier
                                .pressable(enabled = enabled) { touched(); state.sheet = PlayerSheet.Quality }
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.38f))
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        ) {
                            Text(
                                if (QualityPreference.height == null) "Auto · $quality" else quality,
                                color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black,
                            )
                        }
                    }
                    if (pip?.supported == true) {
                        Spacer(Modifier.width(8.dp))
                        GlassButton(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture", enabled, size = 30.dp, iconSize = 18.dp) {
                            controlsVisible = false
                            if (!pip.enter(state.videoAspect)) state.actionMessage = "Picture-in-picture is turned off for Skipii in Settings."
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 56.dp else 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val skip = if (fullscreen) 56.dp else 48.dp
                    GlassButton(Icons.Rounded.Replay10, "Back 10 seconds", enabled, size = skip, iconSize = skip * 0.62f) {
                        touched(); state.seekBy(-10_000)
                    }
                    MorphPlayButton(
                        playing = state.isPlaying,
                        buffering = state.isBuffering || (state.loading && state.details == null),
                        size = if (fullscreen) 76.dp else 66.dp,
                        // Same glass as the skip buttons.
                        container = Color.Black.copy(alpha = 0.38f),
                        content = Color.White,
                        enabled = enabled,
                    ) { touched(); state.togglePlay() }
                    GlassButton(Icons.Rounded.Forward10, "Forward 10 seconds", enabled, size = skip, iconSize = skip * 0.62f) {
                        touched(); state.seekBy(10_000)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 6.dp)) {
                    var scrubPreview by remember { mutableStateOf<Long?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(scrubPreview ?: state.positionMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            " / " + formatDuration(state.durationMs),
                            color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        state.askSegment?.let { segment ->
                            SkipSegmentPill(segment, enabled = true) { touched(); state.skipSegment(segment) }
                            Spacer(Modifier.width(6.dp))
                        }
                        GlassButton(
                            if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (fullscreen) "Exit fullscreen" else "Fullscreen",
                            enabled,
                            size = 34.dp,
                            iconSize = 22.dp,
                            tinted = false,
                        ) { onToggleFullscreen() }
                    }
                    Scrubber(
                        segments = if (SponsorBlock.enabled) state.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList(),
                        positionMs = state.positionMs,
                        bufferedMs = state.bufferedMs,
                        durationMs = state.durationMs,
                        enabled = enabled,
                        onPreview = { scrubPreview = it; touched() },
                        onSeek = { scrubPreview = null; state.seekTo(it); touched() },
                    )
                }
            }
        }
    }
}

/**
 * The stage in audio only (Derek, 2026-10-13): no picture, just the playback controls, always
 * shown, on a tonal container the video's own shape. Every control sits exactly where the
 * video's overlay puts it (Derek, 2026-10-14): skips and play centred at the same sizes, the
 * times row and scrubber along the bottom, the skipped note at the top; only the look changes
 * (tonal buttons, primary play, thicker scrubber). The thumbnail sits underneath at the
 * stage's centred 16:9 window and shows through as it shrinks, so it lands as the mini-player,
 * which shows the thumbnail in audio only.
 *
 * Switching from or to video happens in place: [cover] fades the whole stage over the picture,
 * and [gather] brings the controls together (skips slide out from behind the play button,
 * the bottom row rises in), with a soft overshoot.
 */
@Composable
fun AudioOnlyStage(
    state: PlayerState,
    interactive: Boolean,
    onInactiveTap: () -> Unit,
    progress: () -> Float,
    controlsAlpha: () -> Float,
    modifier: Modifier = Modifier,
    cover: () -> Float = { 1f },
    gather: () -> Float = { 1f },
) {
    val c = MaterialTheme.colorScheme
    val currentInteractive by androidx.compose.runtime.rememberUpdatedState(interactive)
    val currentInactiveTap by androidx.compose.runtime.rememberUpdatedState(onInactiveTap)
    // While collapsing the controls are off (their taps fall through): a tap re-expands.
    Box(
        modifier
            .graphicsLayer { alpha = cover().coerceIn(0f, 1f) }
            .pointerInput(Unit) { detectTapGestures { if (!currentInteractive) currentInactiveTap() } },
    ) {
        Box(Modifier.fillMaxSize().aspectFit(16f / 9f)) {
            Thumbnail(state.current?.thumbnail, Modifier.fillMaxSize(), radius = 0)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = ramp(progress(), 0.25f, 0.85f) }.background(c.surfaceContainer))
        val error = state.error
        if (error != null) {
            ErrorPanel(error, onRetry = state::retry, onCollapse = {})
            return@Box
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha() * gather().coerceIn(0f, 1f) }) {
            SkippedNote(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            // Same row as the video's: 48 dp skips, 66 dp play, 30 dp apart.
            val gap = 30.dp
            val skip = 48.dp
            val play = 66.dp
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Each skip starts tucked behind the play button and slides out to its place.
                fun Modifier.slide(side: Int) = graphicsLayer {
                    translationX = -side * (1f - gather()) * (gap + (play + skip) / 2).toPx()
                }
                Box(Modifier.slide(-1)) { TonalSkip(Icons.Rounded.Replay10, "Back 10 seconds", interactive, skip) { state.seekBy(-10_000) } }
                Box(Modifier.graphicsLayer { val k = lerpF(0.6f, 1f, gather()); scaleX = k; scaleY = k }) {
                    MorphPlayButton(
                        playing = state.isPlaying,
                        buffering = state.isBuffering || (state.loading && state.details == null),
                        size = play,
                        container = c.primary,
                        content = c.onPrimary,
                        enabled = interactive,
                    ) { state.togglePlay() }
                }
                Box(Modifier.slide(1)) { TonalSkip(Icons.Rounded.Forward10, "Forward 10 seconds", interactive, skip) { state.seekBy(10_000) } }
            }
            // Same place and padding as the video's bottom bar (its row is the fullscreen button's 34 dp).
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 8.dp, bottom = 6.dp)
                    .graphicsLayer { translationY = (1f - gather()) * 16.dp.toPx() },
            ) {
                var scrubPreview by remember { mutableStateOf<Long?>(null) }
                Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(scrubPreview ?: state.positionMs), color = c.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        " / " + formatDuration(state.durationMs),
                        color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    state.askSegment?.let { ask -> SkipSegmentPill(ask, enabled = interactive) { state.skipSegment(ask) } }
                }
                Scrubber(
                    segments = if (SponsorBlock.enabled) state.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList(),
                    positionMs = state.positionMs,
                    bufferedMs = state.bufferedMs,
                    durationMs = state.durationMs,
                    enabled = interactive,
                    onPreview = { scrubPreview = it },
                    onSeek = { scrubPreview = null; state.seekTo(it) },
                    track = c.surfaceContainerHighest,
                    buffered = c.primary.copy(alpha = 0.3f),
                    played = c.primary,
                    trackHeight = 6.dp,
                    knob = 8.dp,
                    knobDragging = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun TonalSkip(icon: ImageVector, description: String, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).pressable(enabled = enabled, onClick = onClick).clip(CircleShape).background(c.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = c.onSurface, modifier = Modifier.size(size * 0.62f)) }
}

/**
 * Hides the picture while the shared surface still holds the frame from before audio only
 * after video is turned back on ([PlayerState.hidesStaleVideo]); the thumbnail underneath shows
 * instead, and the fresh frame fades in.
 */
@Composable
fun Modifier.hideStaleVideo(state: PlayerState): Modifier {
    val hide = state.hidesStaleVideo
    val alpha by animateFloatAsState(
        targetValue = if (hide) 0f else 1f,
        animationSpec = if (hide || rememberReducedMotion()) tween(0) else tween(220, easing = Motion.Emphasized),
        label = "freshVideo",
    )
    return graphicsLayer { this.alpha = alpha }
}

/**
 * Lays the content out at [aspect], centred: as large as fits inside the incoming box, or
 * with [cover] as small as covers it (the overflow is for the caller to clip).
 */
fun Modifier.aspectFit(aspect: Float, cover: Boolean = false) = layout { measurable, constraints ->
    val boxW = constraints.maxWidth.toFloat()
    val boxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else boxW / aspect
    // Height decides when the box is wider than the content (fit) or narrower (cover).
    val byHeight = (boxW / boxH > aspect) != cover
    val w = (if (byHeight) boxH * aspect else boxW).roundToInt()
    val h = (if (byHeight) boxH else boxW / aspect).roundToInt()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(boxW.roundToInt(), boxH.roundToInt()) {
        placeable.place(((boxW - w) / 2f).roundToInt(), ((boxH - h) / 2f).roundToInt())
    }
}

/**
 * Fullscreen (landscape, or upright for a vertical video). Swipe down (or Back, or the exit button) returns to portrait.
 * System bars are hidden by the caller while this is shown.
 */
@Composable
fun FullscreenPlayer(state: PlayerState, onExit: () -> Unit) {
    val reduced = rememberReducedMotion()
    var drag by remember { mutableStateOf(0f) }
    VideoContent(
        state = state,
        fullscreen = true,
        onCollapse = onExit,
        onToggleFullscreen = onExit,
        modifier = Modifier
            .fillMaxSize()
            .draggable(
                state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (drag > 120f || velocity > 1400f) onExit()
                    drag = 0f
                },
            )
            .graphicsLayer {
                // A little give while dragging, so the gesture is visibly being heard.
                translationY = if (reduced) 0f else drag * 0.35f
            },
    )
}

/** "Skip sponsor ›" — shown while playback is inside a segment set to Ask. */
@Composable
private fun SkipSegmentPill(segment: SponsorBlock.Segment, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(32.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(segment.category.color.copy(alpha = 0.92f))
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (segment.category.color.luminance() > 0.45f) Color(0xFF151515) else Color.White
        Text("Skip ${segment.category.short}", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Icon(Icons.Rounded.SkipNext, null, tint = ink, modifier = Modifier.size(18.dp))
    }
}

/** "Skipped 1:12 sponsor · Undo" after an automatic skip; fades after a few seconds. */
@Composable
private fun SkippedNote(state: PlayerState, modifier: Modifier) {
    val skip = SponsorBlock.lastSkip?.takeIf { it.videoId == state.current?.id } ?: return
    LaunchedEffect(skip) {
        delay(4_000)
        if (SponsorBlock.lastSkip == skip) SponsorBlock.lastSkip = null
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Skipped ${formatDuration(skip.segment.lengthMs)} ${skip.segment.category.short}",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "Undo",
            color = skip.segment.category.color, fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.pressable { state.undoSkip(skip) }.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(state: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val details = state.details ?: return
    val chosen = QualityPreference.height
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Text(
            "Quality",
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        state.playingFormat?.let {
            Text(
                "Playing $it",
                color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        QualityRow("Auto", "Best up to 1080p" + ((state.decodedHeight?.let { "${it}p" } ?: details.quality)?.let { " · playing $it" } ?: ""), chosen == null) {
            state.setQuality(null); onDismiss()
        }
        details.qualities.forEach { h ->
            QualityRow("${h}p", qualityTag(h), chosen == h) { state.setQuality(h); onDismiss() }
        }
        Spacer(Modifier.height(28.dp))
    }
}

private fun qualityTag(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "QHD"
    height >= 1080 -> "Full HD"
    height >= 720 -> "HD"
    height <= 240 -> "Data saver"
    else -> null
}

@Composable
private fun QualityRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.primaryContainer else c.primaryContainer.copy(alpha = 0f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (selected) c.onPrimaryContainer else c.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) c.onPrimaryContainer.copy(alpha = 0.75f) else c.onSurfaceVariant)
            }
        }
        if (selected) Icon(Icons.Rounded.Check, "Selected", tint = c.onPrimaryContainer)
    }
}

@Composable
private fun BoxScope.ErrorPanel(message: String, onRetry: () -> Unit, onCollapse: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.pressable(onClick = onRetry).clip(CircleShape).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
        ) { Text("Try again", color = Color(0xFF141414), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable
private fun GlassButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 22.dp,
    tinted: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(if (tinted) Color.Black.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize)) }
}

private const val SeekStepMs = 10_000L
/** A burst stays open this long after its last tap; further taps on that side add to it. */
private const val SeekBurstWindowMs = 600L

/**
 * Double-tap seek feedback: a glass pill at 22% / 78% across the video with the rewind /
 * fast-forward glyph and the burst's running total. Springs in; every tap ([taps] changes)
 * squishes it, dashes the glyph the way it seeks and throws sparks (the like Pop's burst).
 * Reduced motion: fade only.
 */
@Composable
private fun SeekBurstPill(side: Int, seconds: Int, taps: Int, active: Boolean, fullscreen: Boolean) {
    val reduced = rememberReducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else if (active) 140 else 200, easing = Motion.Emphasized),
        label = "seekPillAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (active || reduced) 1f else 0.4f,
        animationSpec = if (active) spring(dampingRatio = 0.5f, stiffness = 500f) else tween(200, easing = Motion.Emphasized),
        label = "seekPillScale",
    )
    val squishX = remember { Animatable(1f) }
    val squishY = remember { Animatable(1f) }
    val dash = remember { Animatable(0f) }
    val sparks = remember { Animatable(1f) } // 0→1 = sparks flying; 1 = none
    LaunchedEffect(taps) {
        if (taps == 0 || reduced) return@LaunchedEffect
        launch { squishX.animateTo(1f, keyframes { durationMillis = 440; 1.14f at 120; 0.96f at 270 }) }
        launch { squishY.animateTo(1f, keyframes { durationMillis = 440; 0.88f at 120; 1.04f at 270 }) }
        launch { dash.animateTo(0f, keyframes { durationMillis = 380; 1f at 115 }) }
        launch { sparks.snapTo(0f); sparks.animateTo(1f, tween(520, easing = Motion.Emphasized)) }
    }
    val sparkColor = MaterialTheme.colorScheme.primary
    val font = if (fullscreen) 20.sp else 16.sp
    Row(
        Modifier
            .layout { measurable, constraints ->
                val pill = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val centerX = constraints.maxWidth * (if (side < 0) 0.22f else 0.78f)
                    pill.place((centerX - pill.width / 2f).roundToInt(), (constraints.maxHeight - pill.height) / 2)
                }
            }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale * squishX.value
                scaleY = scale * squishY.value
            }
            .drawBehind {
                val t = sparks.value
                if (t >= 1f) return@drawBehind
                // Eight sparks, from just inside the pill out past its edge, fading.
                val reach = size.width / 2f + 16.dp.toPx()
                for (i in 0 until 8) {
                    val a = (i / 8f + 1f / 16f) * 2f * PI.toFloat()
                    val from = Offset(cos(a) * size.width * 0.36f, sin(a) * 12.dp.toPx())
                    val to = Offset(cos(a) * reach, sin(a) * reach * 0.62f)
                    drawCircle(
                        (if (i % 2 == 0) sparkColor else Color.White).copy(alpha = 1f - t),
                        radius = 3.5.dp.toPx() * (1f - 0.65f * t),
                        center = center + from + (to - from) * t,
                    )
                }
            }
            .height(if (fullscreen) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(start = if (fullscreen) 14.dp else 11.dp, end = if (fullscreen) 18.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(if (fullscreen) 28.dp else 22.dp)
                .graphicsLayer { translationX = side * 6.dp.toPx() * dash.value },
        )
        Spacer(Modifier.width(8.dp))
        Text(if (side < 0) "−" else "+", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = font)
        RollingText("$seconds", Color.White, up = true, fontSize = font)
    }
}

/**
 * Play/pause that changes shape with state: a squircle while playing, a circle when
 * paused (Material 3 Expressive shape morph). Shows a spinner while buffering.
 */
@Composable
fun MorphPlayButton(
    playing: Boolean,
    buffering: Boolean,
    size: Dp,
    container: Color,
    content: Color,
    enabled: Boolean = true,
    backdrop: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val radius by animateDpAsState(
        targetValue = if (playing) size * 0.33f else size / 2,
        animationSpec = if (reduced) tween(0) else spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "playShape",
    )
    Box(
        modifier = Modifier
            .size(size)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(radius))
            .then(backdrop)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(color = content, strokeWidth = 3.dp, modifier = Modifier.size(size * 0.42f))
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = content,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/**
 * Scrubber: buffered and played tracks, a knob that grows while dragging. Thin and white on
 * the video; the audio-only stage passes its own colors and sizes.
 */
@Composable
private fun Scrubber(
    segments: List<SponsorBlock.Segment>,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onPreview: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    track: Color = Color.White.copy(alpha = 0.3f),
    buffered: Color = Color.White.copy(alpha = 0.5f),
    played: Color = Color.White,
    trackHeight: Dp = 4.dp,
    knob: Dp = 7.dp,
    knobDragging: Dp = 9.dp,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1L)
    val playedFraction = dragFraction ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    val knobRadius by animateDpAsState(if (dragFraction != null) knobDragging else knob, label = "knob")
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek((offset.x / size.width * durationMs).toLong()) }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onPreview((dragFraction!! * durationMs).toLong())
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onPreview((dragFraction!! * durationMs).toLong())
                }
            },
    ) {
        val trackH = trackHeight.toPx()
        val y = size.height / 2f - trackH / 2f
        val r = CornerRadius(trackH / 2f)
        drawRoundRect(track, Offset(0f, y), Size(size.width, trackH), r)
        drawRoundRect(buffered, Offset(0f, y), Size(size.width * bufferedFraction, trackH), r)
        drawRoundRect(played, Offset(0f, y), Size(size.width * playedFraction, trackH), r)
        // SponsorBlock segments, in their category colors, over the track.
        segments.forEach { seg ->
            val x0 = size.width * (seg.startMs.toFloat() / duration).coerceIn(0f, 1f)
            val x1 = size.width * (seg.endMs.toFloat() / duration).coerceIn(0f, 1f)
            if (x1 > x0) drawRect(seg.category.color, Offset(x0, y), Size(x1 - x0, trackH))
        }
        drawCircle(played, radius = knobRadius.toPx(), center = Offset(size.width * playedFraction, size.height / 2f))
    }
}
