package com.decio.skipii

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.hypot
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.ClosedCaption
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import kotlinx.coroutines.CoroutineScope
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
 * overlay controls; they auto-hide while playing. In portrait (0.5.34) the video normally has no
 * controls of its own: they all live in the player's bar (PlayerBar.kt); double-tap seek, the
 * loading spinner, the SponsorBlock Ask pill / skipped note and errors stay on the video.
 * With the controls on the video instead ([PlayerControls], 0.5.66) portrait gets the same
 * overlay as fullscreen, and its bottom edge is the playback head ([edge], drawn and grabbed by
 * VideoStage's [videoEdge]). The overlay's buttons are drawn like the playback pill's.
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
    /** Portrait with the controls on the video: the scrub on the video's bottom edge. */
    edge: EdgeScrub? = null,
) {
    val overlay = fullscreen || !PlayerControls.inBar
    // Hidden until the video is tapped: opening the player (from the mini-player too) and
    // rotating in or out of fullscreen never pop the controls up.
    var controlsVisible by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    fun touched() { touchedAt = System.nanoTime() }
    // Fullscreen's quality and speed pickers (OverlayDrop, GitHub #25 / #31): the controls stay up while one is open.
    var drop by remember { mutableStateOf<DropKind?>(null) }
    LaunchedEffect(controlsVisible, state.isPlaying, touchedAt, drop) {
        if (controlsVisible && state.isPlaying && drop == null) {
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
    LaunchedEffect(controlsVisible, fullscreen) { if (!controlsVisible || !fullscreen) drop = null }
    BackHandler(enabled = drop != null) { drop = null }
    val currentInteractive by androidx.compose.runtime.rememberUpdatedState(interactive)
    val currentInactiveTap by androidx.compose.runtime.rememberUpdatedState(onInactiveTap)
    fun toggleControls() { if (overlay) { controlsVisible = !controlsVisible; touched() } }
    SideEffect { edge?.controlsShown = controlsVisible && interactive }
    // Scrubbing the bottom edge: the controls step aside for the landing frame, and the hide timer
    // starts over once it's let go.
    val scrubbing = edge?.fraction != null
    LaunchedEffect(scrubbing) { if (!scrubbing) touched() }
    val scrubFade by animateFloatAsState(
        if (scrubbing) 1f else 0f,
        if (reduced) tween(0) else tween(160, easing = Motion.Emphasized), label = "scrubFade",
    )
    fun seekStep(side: Int) {
        val moved = state.seekBy(side * SeekStepMs)
        burstSeconds += ((abs(moved) + 500) / 1000).toInt()
        burstTaps++
    }
    val pip = LocalPictureInPicture.current
    val view = LocalView.current
    Box(modifier.background(Color.Black).onGloballyPositioned { pip?.videoBounds = it.boundsInWindow() }.pointerInput(state) {
        val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
        coroutineScope {
            // A lone tap on a side waits out the double-tap timeout before toggling the controls.
            var pendingTap: Job? = null
            var pendingSide = 0
            var pendingAt = 0L
            awaitEachGesture {
                val down = awaitFirstDown()
                // Held still on the right side: 2× until it lifts (GitHub #36). Moving first (a swipe,
                // the volume slide, a scrub) takes the gesture away from it before the timeout.
                val holdable = currentInteractive && state.error == null && !burstActive && down.position.x / size.width > 0.6f
                val up = (if (!holdable) waitForUpOrCancellation() else try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                } catch (_: PointerEventTimeoutCancellationException) {
                    pendingTap?.cancel()
                    if (state.startFastHold()) {
                        controlsVisible = false
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    try {
                        // Every move and the lift are the hold's: nothing slides, swipes or scrubs under it.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        state.endFastHold()
                    }
                    return@awaitEachGesture
                }) ?: return@awaitEachGesture
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
            // Captions on the picture, stepping up out of the controls' way while they show (and the Ask pill's).
            if (state.error == null) {
                val captionLift by androidx.compose.animation.core.animateDpAsState(
                    when {
                        overlay && controlsVisible && interactive -> if (fullscreen) 72.dp else 56.dp
                        state.askSegment != null -> 50.dp
                        else -> if (fullscreen) 22.dp else 12.dp
                    },
                    if (reduced) tween(0) else spring(dampingRatio = 0.9f, stiffness = 380f),
                    label = "captionLift",
                )
                CaptionLayer(state, bottom = captionLift, alpha = controlsAlpha)
            }
            // Where the burst has got to, while the controls (and their scrubber) are hidden. The
            // playback head on the edge (on-video controls) already shows it.
            if (edge == null) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).graphicsLayer { alpha = peekAlpha }) {
                drawRect(Color.White.copy(alpha = 0.28f))
                val played = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(Color.White, size = Size(size.width * played, size.height))
            }
        }
        SeekBurstPill(burstSide, burstSeconds, burstTaps, burstActive, fullscreen)
        FastHoldPill(
            state.holdingFast, fullscreen,
            Modifier.align(Alignment.TopCenter)
                .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(top = 20.dp) else Modifier.padding(top = 12.dp)),
        )

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
                stringResource(R.string.video_bot_check_waiting),
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
        if (overlay) Box(Modifier.fillMaxSize().graphicsLayer { alpha = visibleAlpha * controlsAlpha() * (1f - scrubFade) }) {
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
            // Drawn like the playback pill's buttons (0.5.67): the bar's tone for the round ones, a
            // full-primary circle, a size up, for play.
            val tool = if (fullscreen) 40.dp else 36.dp
            val inset = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
            // Fullscreen's quality and speed cards hang from their pills: both measured where the tap happens.
            val dropAnchors = remember { arrayOfNulls<LayoutCoordinates>(4) }
            var dropAnchor by remember { mutableStateOf(Rect.Zero) }
            var dropRoom by remember { mutableFloatStateOf(0f) }
            /** Opens (or closes) [kind]'s card under the pill at [slot]; false if there's no card here (portrait). */
            fun toggleDrop(kind: DropKind, slot: Int): Boolean {
                val box = dropAnchors[0]
                val pill = dropAnchors[slot]
                if (!fullscreen || box == null || pill == null || !box.isAttached || !pill.isAttached) return false
                dropAnchor = box.localBoundingBoxOf(pill, clipBounds = false)
                dropRoom = box.size.height.toFloat()
                drop = if (drop == kind) null else kind
                return true
            }
            Box(Modifier.fillMaxSize().then(inset).onGloballyPositioned { dropAnchors[0] = it }) {
                // Top row: the title (fullscreen); audio only (portrait), speed, quality, picture-in-picture.
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        // Audio only has no fullscreen, so it's offered in portrait only.
                        OverlayTile(stringResource(R.string.video_audio_only), enabled, tool, filled = AudioOnly.enabled, onClick = { touched(); state.setAudioOnly(!AudioOnly.enabled) }) { ink ->
                            Icon(Icons.Outlined.Headphones, null, tint = ink, modifier = Modifier.size(tool * 0.5f))
                        }
                    }
                    val stickySpeed = PlaybackSpeed.remember && !PlaybackSpeed.isNormal(state.speed)
                    OverlayPill(
                        PlaybackSpeed.label(state.speed) + "×", stringResource(R.string.video_playback_speed), enabled, tool, filled = stickySpeed,
                        modifier = Modifier.onGloballyPositioned { dropAnchors[2] = it },
                    ) {
                        touched()
                        if (!toggleDrop(DropKind.Speed, 2)) state.sheet = PlayerSheet.Speed
                    }
                    // Captions (filled while on): fullscreen's card, or the sheet.
                    if (state.captions.tracks.isNotEmpty()) {
                        OverlayTile(
                            stringResource(R.string.video_captions), enabled, tool, filled = state.captions.on,
                            modifier = Modifier.onGloballyPositioned { dropAnchors[3] = it },
                            onClick = {
                                touched()
                                // On at once (no second press); the card / sheet is there to pick another language.
                                state.captions.turn(true)
                                if (!toggleDrop(DropKind.Captions, 3)) state.sheet = PlayerSheet.Captions
                            },
                        ) { ink -> Icon(Icons.Rounded.ClosedCaption, null, tint = ink, modifier = Modifier.size(tool * 0.52f)) }
                    }
                    // The label is the resolution actually decoded; the requested one until known.
                    (state.decodedHeight?.let { "${it}p" } ?: state.details?.quality)?.let { quality ->
                        OverlayPill(
                            if (QualityPreference.height == null) stringResource(R.string.video_quality_auto) + " · $quality" else quality, stringResource(R.string.video_quality), enabled, tool,
                            modifier = Modifier.onGloballyPositioned { dropAnchors[1] = it },
                        ) {
                            touched()
                            if (!toggleDrop(DropKind.Quality, 1)) state.sheet = PlayerSheet.Quality
                        }
                    }
                    if (pip?.supported == true) {
                        OverlayTile(stringResource(R.string.video_pip), enabled, tool, onClick = {
                            controlsVisible = false
                            if (!pip.enter(state.videoAspect)) state.actionMessage = str(R.string.video_pip_off)
                        }) { ink -> Icon(Icons.Rounded.PictureInPictureAlt, null, tint = ink, modifier = Modifier.size(tool * 0.5f)) }
                    }
                }
                // −10 · play · +10, as on the pill; play a circle a size up from the skips.
                val seek = if (fullscreen) 60.dp else 51.dp
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 32.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlaySeek(-1, enabled, seek) { touched(); state.seekBy(-10_000) }
                    OverlayPlay(
                        playing = state.isPlaying,
                        buffering = state.isBuffering || (state.loading && state.details == null),
                        size = if (fullscreen) 78.dp else 66.dp,
                        enabled = enabled,
                    ) { touched(); state.togglePlay() }
                    OverlaySeek(1, enabled, seek) { touched(); state.seekBy(10_000) }
                }
                // Times, the Ask pill, Next and fullscreen; under them the scrubber in fullscreen. In portrait
                // the video's own bottom edge is the scrubber (VideoStage), so the row sits just clear of it.
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, bottom = if (fullscreen) 6.dp else 14.dp),
                ) {
                    var scrubPreview by remember { mutableStateOf<Long?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            val shownMs = scrubPreview ?: state.positionMs
                            Text(formatDuration(shownMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                " / " + formatDuration(state.durationMs),
                                color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                            state.chapterAt(shownMs)?.let {
                                Text(
                                    " · $it", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        state.askSegment?.let { segment ->
                            SkipSegmentPill(segment, enabled = true) { touched(); state.skipSegment(segment) }
                        }
                        if (state.queue.isNotEmpty()) {
                            OverlayTile(stringResource(R.string.video_next), enabled, 34.dp, onClick = { touched(); state.playNextUp() }) { ink ->
                                Icon(Icons.Rounded.SkipNext, null, tint = ink, modifier = Modifier.size(20.dp))
                            }
                        }
                        OverlayTile(stringResource(if (fullscreen) R.string.video_exit_fullscreen else R.string.video_fullscreen), enabled, 34.dp, onClick = onToggleFullscreen) { ink ->
                            Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, null, tint = ink, modifier = Modifier.size(21.dp))
                        }
                    }
                    if (fullscreen) {
                        Scrubber(
                            segments = if (SponsorBlock.enabled) state.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList(),
                            positionMs = state.positionMs,
                            bufferedMs = state.bufferedMs,
                            durationMs = state.durationMs,
                            enabled = enabled,
                            onPreview = { scrubPreview = it; touched() },
                            onSeek = { scrubPreview = null; state.seekTo(it); touched() },
                            // As the portrait playback head draws it.
                            track = Color.White.copy(alpha = 0.32f),
                            buffered = Color.White.copy(alpha = 0.45f),
                            played = MaterialTheme.colorScheme.primary,
                            trackHeight = 3.dp,
                            chapters = state.details?.chapters.orEmpty().map { it.startMs },
                        )
                    }
                }
                if (fullscreen) {
                    QualityDrop(state, drop == DropKind.Quality, dropAnchor, dropRoom, onClose = { drop = null })
                    SpeedDrop(state, drop == DropKind.Speed, dropAnchor, dropRoom, onClose = { drop = null })
                    CaptionsDrop(state, drop == DropKind.Captions, dropAnchor, dropRoom, onClose = { drop = null })
                }
            }
        }
        if (edge != null) EdgeScrubPreview(state, edge)
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
 *
 * With the playback pill the open player folds this stage away (the pill has the controls); with
 * the controls on the video ([PlayerControls]) it stays, and its top row turns audio only off again.
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
            // Where the video's overlay has them (on-video controls): audio only (on) and speed.
            if (!PlayerControls.inBar) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tile = c.surfaceContainerHighest
                    OverlayTile(stringResource(R.string.video_audio_only_on), interactive, 36.dp, filled = true, onClick = { state.setAudioOnly(false) }) { ink ->
                        Icon(Icons.Rounded.Headphones, null, tint = ink, modifier = Modifier.size(18.dp))
                    }
                    val stickySpeed = PlaybackSpeed.remember && !PlaybackSpeed.isNormal(state.speed)
                    OverlayPill(PlaybackSpeed.label(state.speed) + "×", stringResource(R.string.video_playback_speed), interactive, 36.dp, filled = stickySpeed, tile = tile) {
                        state.sheet = PlayerSheet.Speed
                    }
                }
            }
            // Same row as the video's: 51 dp skips beside a 66 dp play, 18 dp apart.
            val gap = 18.dp
            val skip = 51.dp
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
                val tile = c.surfaceContainerHighest
                Box(Modifier.slide(-1)) { OverlaySeek(-1, interactive, skip, tile) { state.seekBy(-10_000) } }
                Box(Modifier.graphicsLayer { val k = lerpF(0.6f, 1f, gather()); scaleX = k; scaleY = k }) {
                    OverlayPlay(
                        playing = state.isPlaying,
                        buffering = state.isBuffering || (state.loading && state.details == null),
                        size = play,
                        enabled = interactive,
                    ) { state.togglePlay() }
                }
                Box(Modifier.slide(1)) { OverlaySeek(1, interactive, skip, tile) { state.seekBy(10_000) } }
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

/**
 * A round control on the video, in the playback pill's look: the bar's tone ([tile]), or full
 * primary while [filled] (a mode that's on). [content] draws the glyph in the ink it's given.
 */
@Composable
private fun OverlayTile(
    description: String,
    enabled: Boolean,
    size: Dp,
    filled: Boolean = false,
    tile: Color = MaterialTheme.colorScheme.surfaceVariant,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (ink: Color) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    Box(
        modifier
            .squishOn(filled)
            .size(size)
            .pressable(enabled = enabled) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            .clip(CircleShape)
            .background(if (filled) c.primary else tile)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(if (filled) c.onPrimary else c.onSurface) }
}

/** A worded control on the video (speed, quality): a capsule [height] tall, otherwise [OverlayTile]'s look. */
@Composable
private fun OverlayPill(
    label: String,
    description: String,
    enabled: Boolean,
    height: Dp,
    filled: Boolean = false,
    tile: Color = MaterialTheme.colorScheme.surfaceVariant,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    Box(
        modifier
            .squishOn(filled)
            .height(height)
            .widthIn(min = height)
            .pressable(enabled = enabled) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            .clip(CircleShape)
            .background(if (filled) c.primary else tile)
            .padding(horizontal = 11.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) c.onPrimary else c.onSurface, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** −10 / +10 on the video, as on the pill: the arrow kicks the way it seeks. */
@Composable
private fun OverlaySeek(side: Int, enabled: Boolean, size: Dp, tile: Color = MaterialTheme.colorScheme.surfaceVariant, onSeek: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val spin = remember { Animatable(0f) }
    Box(
        Modifier
            .size(size)
            .pressable(enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onSeek()
                if (!reduced) scope.launch { spin.animateTo(0f, keyframes { durationMillis = 420; side * 50f at 150 }) }
            }
            .clip(CircleShape)
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (side < 0) Icons.Rounded.Replay10 else Icons.Rounded.Forward10,
            contentDescription = stringResource(if (side < 0) R.string.video_back_10 else R.string.video_forward_10),
            tint = c.onSurface,
            modifier = Modifier.size(size * 0.51f).graphicsLayer { rotationZ = spin.value },
        )
    }
}

/** Play / pause on the video: a full-primary circle [size] across (Derek, 2026-10-31); a spinner while loading. */
@Composable
private fun OverlayPlay(playing: Boolean, buffering: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    Box(
        Modifier
            .size(size)
            .pressable(enabled = enabled) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            .clip(CircleShape)
            .background(c.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(color = c.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(size * 0.4f))
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.video_pause else R.string.video_play),
                tint = c.onPrimary,
                modifier = Modifier.size(size * 0.46f).squishOn(playing),
            )
        }
    }
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

/** The playback head on the portrait video's bottom edge (on-video controls, [PlayerControls]). */
private val EdgeLineHeight = 3.dp
private val EdgeLineScrubbing = 5.dp
private val EdgeKnob = 5.dp
private val EdgeKnobShown = 7.dp
private val EdgeKnobScrubbing = 9.dp
/** A grab starts from this strip along the video's bottom edge. */
private val EdgeGrabHeight = 36.dp
/** Within this of the knob a grab scrubs whichever way the finger first moves (elsewhere only sideways). */
private val EdgeKnobReach = 28.dp
/** The gap left in the line where a chapter starts. */
private val EdgeChapterGap = 2.dp

/** Where a scrub on the video's bottom edge would land (0..1 of the video), or null; and whether the controls show. */
@Stable
class EdgeScrub {
    var fraction by mutableStateOf<Float?>(null)
    var controlsShown by mutableStateOf(false)
}

/**
 * The portrait video's bottom edge as its playback head (Derek, 2026-10-31; on-video controls): a line
 * flush with the edge — played in primary, buffered paler, SponsorBlock marks in their colours, a gap at
 * each chapter — and a primary knob centred on it, which overhangs the edge (this sits outside the
 * stage's clip). Grabbed from the strip along the edge: sideways anywhere, any way on the knob; the knob
 * follows the finger, the video seeks on release, and each chapter edge crossed ticks. [shown] fades it
 * with the morph; [scale] is the stage's rubber-band swell (grows down from the top edge).
 */
@Composable
fun Modifier.videoEdge(state: PlayerState, edge: EdgeScrub, enabled: Boolean, shown: () -> Float, scale: () -> Float): Modifier {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val scrubbing = edge.fraction != null
    val spec = if (reduced) snap<Float>() else spring(dampingRatio = 0.7f, stiffness = 700f)
    val knob by animateFloatAsState(
        (if (scrubbing) EdgeKnobScrubbing else if (edge.controlsShown) EdgeKnobShown else EdgeKnob).value, spec, label = "edgeKnob",
    )
    val line by animateFloatAsState((if (scrubbing) EdgeLineScrubbing else EdgeLineHeight).value, spec, label = "edgeLine")
    val segments = if (SponsorBlock.enabled) state.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList()
    val chapters = state.details?.chapters.orEmpty()
    val gestures = if (!enabled) Modifier else Modifier.pointerInput(state, edge) {
        // Seen first (Initial pass): once this takes a gesture, the stage's drag and the video's taps see it consumed.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val duration = state.durationMs
            if (duration <= 0L || down.position.y < size.height - EdgeGrabHeight.toPx()) return@awaitEachGesture
            val knobX = size.width * (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            val onKnob = abs(down.position.x - knobX) < EdgeKnobReach.toPx()
            var moved = Offset.Zero
            var chapter: String? = null
            fun follow(x: Float) {
                val f = (x / size.width).coerceIn(0f, 1f)
                edge.fraction = f
                val now = state.chapterAt((f * duration).toLong())
                if (now != chapter) { chapter = now; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (edge.fraction == null) {
                        // Held at 2× (VideoContent): the finger belongs to the hold, even sliding along the edge.
                        if (!change.pressed || state.holdingFast) break
                        moved += change.position - change.previousPosition
                        if (hypot(moved.x, moved.y) <= viewConfiguration.touchSlop) continue
                        // Not ours: a swipe up / down on the video (minimize, fullscreen) carries on.
                        if (!onKnob && abs(moved.x) <= abs(moved.y)) break
                        chapter = state.chapterAt(state.positionMs)
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    change.consume()
                    if (!change.pressed) {
                        edge.fraction?.let { state.seekTo((it * duration).toLong()) }
                        edge.fraction = null
                        break
                    }
                    follow(change.position.x)
                }
            } finally {
                // A grab whose pointer vanished (the page went away mid-scrub) is dropped, no seek.
                edge.fraction = null
            }
        }
    }
    return this.then(gestures).drawWithContent {
        drawContent()
        val a = shown().coerceIn(0f, 1f)
        val duration = state.durationMs
        if (a <= 0f || duration <= 0L) return@drawWithContent
        val k = scale()
        scale(k, k, pivot = Offset(size.width / 2f, 0f)) {
            val w = size.width
            val h = line.dp.toPx()
            val top = size.height - h
            fun at(ms: Long) = w * (ms.toFloat() / duration).coerceIn(0f, 1f)
            val played = edge.fraction?.let { it * w } ?: at(state.positionMs)
            val cuts = chapters.map { at(it.startMs) }.filter { it > 0f && it < w }.sorted()
            val gap = EdgeChapterGap.toPx()
            // One stretch of the line, from x0 to x1, with the chapter gaps left open.
            fun span(color: Color, x0: Float, x1: Float) {
                var from = x0
                for (cut in cuts) {
                    if (cut >= x1) break
                    if (cut - gap / 2f > from) drawRect(color, Offset(from, top), Size(cut - gap / 2f - from, h), alpha = a)
                    from = maxOf(from, cut + gap / 2f)
                }
                if (x1 > from) drawRect(color, Offset(from, top), Size(x1 - from, h), alpha = a)
            }
            span(Color.White.copy(alpha = 0.32f), 0f, w)
            span(Color.White.copy(alpha = 0.45f), 0f, at(state.bufferedMs))
            span(c.primary, 0f, played)
            segments.forEach { seg ->
                val x0 = at(seg.startMs)
                val x1 = at(seg.endMs)
                if (x1 > x0) drawRect(seg.category.color, Offset(x0, top), Size(x1 - x0, h), alpha = a)
            }
            drawCircle(c.primary, radius = knob.dp.toPx(), center = Offset(played, top + h / 2f), alpha = a)
        }
    }
}

/**
 * While the bottom edge is scrubbed: the frame it would land on (YouTube's storyboard) over a time pill,
 * centred over the knob and kept inside the video. Without a storyboard just the time.
 */
@Composable
private fun EdgeScrubPreview(state: PlayerState, edge: EdgeScrub) {
    val c = MaterialTheme.colorScheme
    val sheets = rememberStoryboardSheets(state)
    val fraction = edge.fraction ?: return
    val duration = state.durationMs.coerceAtLeast(1L)
    val ms = (fraction * duration).toLong()
    val board = state.details?.storyboards?.lastOrNull()
    Column(
        Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = (constraints.maxHeight - 24.dp.roundToPx()).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val margin = 8.dp.toPx()
                val x = (constraints.maxWidth * fraction - p.width / 2f).coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
                p.place(x.roundToInt(), constraints.maxHeight - p.height - 18.dp.roundToPx())
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (board != null) {
            val aspect = board.frameWidth.toFloat() / board.frameHeight
            StoryboardThumb(
                sheets, state.details?.storyboards.orEmpty(), ms, duration,
                Modifier
                    // Up to 160 × 90, smaller on a short stage; the time pill keeps its room under it.
                    .layout { measurable, constraints ->
                        val hMax = minOf(90.dp.toPx(), (constraints.maxHeight - 40.dp.toPx()) * 0.9f).coerceAtLeast(0f)
                        val w = minOf(160.dp.toPx(), hMax * aspect)
                        val p = measurable.measure(Constraints.fixed(w.roundToInt(), (w / aspect).roundToInt()))
                        layout(p.width, p.height) { p.place(0, 0) }
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.clip(CircleShape).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatDuration(ms), color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            state.chapterAt(ms)?.let {
                Text(
                    " · $it", color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
                )
            }
        }
    }
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
    // Up/down on the left = brightness, on the right = volume; the middle swipes down to exit.
    val sliders = rememberFullscreenSliders()
    Box(Modifier.fillMaxSize()) {
        VideoContent(
            state = state,
            fullscreen = true,
            onCollapse = onExit,
            onToggleFullscreen = onExit,
            modifier = Modifier
                .fillMaxSize()
                .fullscreenGestures(sliders, onPull = { drag = it }, onSwipeDown = onExit)
                .graphicsLayer {
                    // A little give while dragging, so the gesture is visibly being heard.
                    translationY = if (reduced) 0f else drag * 0.35f
                },
        )
        SliderLevel(sliders)
    }
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
        Text(stringResource(R.string.video_skip_segment, segment.category.short), color = ink, fontSize = 12.sp, fontWeight = FontWeight.Black)
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
            stringResource(R.string.video_skipped_segment, formatDuration(skip.segment.lengthMs), skip.segment.category.short),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.video_undo),
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
        // Scrolls: in fullscreen (landscape) only the first few rows fit (GitHub issue #1).
        Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.video_quality),
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        state.playingFormat?.let {
            Text(
                stringResource(R.string.video_quality_playing, it),
                color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        val playingNow = (state.decodedHeight?.let { "${it}p" } ?: details.quality)?.let { " · " + stringResource(R.string.video_quality_auto_playing, it) }.orEmpty()
        QualityRow(stringResource(R.string.video_quality_auto), stringResource(R.string.video_quality_auto_best) + playingNow, chosen == null) {
            state.setQuality(null); onDismiss()
        }
        details.qualities.forEach { h ->
            QualityRow("${h}p", qualityTag(h), chosen == h) { state.setQuality(h); onDismiss() }
        }
        Spacer(Modifier.height(28.dp))
        }
    }
}

/** Which of fullscreen's overlay cards is open. */
private enum class DropKind { Quality, Speed, Captions }

/**
 * Fullscreen's captions card (docs/design/captions.html), the quality card's twin hanging from the CC tile: Off, every
 * track, then the translations made on the phone. A pick lands its check and the card folds; picking a translation
 * that still has to download starts it (the captions say so on the video).
 */
@Composable
private fun BoxScope.CaptionsDrop(state: PlayerState, open: Boolean, anchor: Rect, boxHeight: Float, onClose: () -> Unit) {
    val shown = dropShown(open)
    if (shown <= 0f || anchor == Rect.Zero) return
    val currentClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    val cap = state.captions
    // Fixed while the card is up, so a pick never reshuffles the rows under the finger.
    val options = remember { captionOptions(cap, all = false).filterIsInstance<CaptionOption.Pick>() }
    OverlayDrop(open, shown, anchor, boxHeight, stringResource(R.string.video_drop_captions), openAt = { if (!cap.on) 0 else 1 + options.indexOfFirst { cap.shows(it) } }, onClose = onClose) { scope ->
        DropRow(title = stringResource(R.string.video_captions_off), tag = null, selected = !cap.on) {
            if (!open) return@DropRow
            cap.turn(false)
            scope.launch { delay(420); currentClose() }
        }
        options.forEach { option ->
            DropRow(
                title = option.title,
                tag = when {
                    option.choice.translateTo == null -> if (option.choice.track.auto) stringResource(R.string.video_captions_tag_auto) else null
                    option.busy -> stringResource(R.string.video_captions_tag_downloading)
                    option.ready -> stringResource(R.string.video_captions_tag_translated)
                    else -> stringResource(R.string.video_captions_tag_download)
                },
                selected = cap.shows(option),
            ) {
                if (!open) return@DropRow
                cap.pick(option.choice)
                scope.launch { delay(420); currentClose() }
            }
        }
    }
}

/**
 * Fullscreen's quality picker (GitHub #25; Derek picked A "Drop" in `docs/design/fullscreen-quality.html`): a small
 * card of the overlay's glass hanging from the quality pill instead of a sheet over the whole video. Auto, then every
 * height with its tag. A pick lands its check, then the card folds after 420 ms.
 */
@Composable
private fun BoxScope.QualityDrop(state: PlayerState, open: Boolean, anchor: Rect, boxHeight: Float, onClose: () -> Unit) {
    val details = state.details ?: return
    val shown = dropShown(open)
    if (shown <= 0f || anchor == Rect.Zero) return
    val currentClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    val rows = remember(details) { listOf<Int?>(null) + details.qualities }
    var picked by remember { mutableStateOf(QualityPreference.height) }
    OverlayDrop(open, shown, anchor, boxHeight, stringResource(R.string.video_drop_quality), openAt = { rows.indexOf(picked) }, onClose = onClose) { scope ->
        val playing = state.decodedHeight?.let { "${it}p" } ?: details.quality
        rows.forEach { h ->
            DropRow(
                title = if (h == null) stringResource(R.string.video_quality_auto) else "${h}p",
                tag = if (h == null) playing else qualityTag(h),
                selected = picked == h,
            ) {
                if (!open) return@DropRow
                picked = h
                if (h != QualityPreference.height) state.setQuality(h)
                scope.launch { delay(420); currentClose() }
            }
        }
    }
}

/**
 * Fullscreen's speed picker (GitHub #31: the ruler's sheet filled the landscape screen): the quality card's look,
 * hanging from the speed pill, with preset speeds. A speed set on the ruler (portrait) that isn't a preset shows as
 * its own "Custom" row. Under them, "Every video" is the ruler's "Remember for every video" (it stays open on that one).
 */
@Composable
private fun BoxScope.SpeedDrop(state: PlayerState, open: Boolean, anchor: Rect, boxHeight: Float, onClose: () -> Unit) {
    val shown = dropShown(open)
    if (shown <= 0f || anchor == Rect.Zero) return
    val currentClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    var picked by remember { mutableFloatStateOf(PlaybackSpeed.snap(state.speed)) }
    // Fixed while the card is up, so a pick never reshuffles the rows under the finger.
    val rows = remember {
        val custom = picked.takeIf { s -> SpeedPresets.none { abs(it - s) < PlaybackSpeed.STEP / 2 } }
        (SpeedPresets + listOfNotNull(custom)).sorted()
    }
    fun same(a: Float, b: Float) = abs(a - b) < PlaybackSpeed.STEP / 2
    OverlayDrop(open, shown, anchor, boxHeight, stringResource(R.string.video_drop_speed), openAt = { rows.indexOfFirst { same(it, picked) } }, onClose = onClose) { scope ->
        rows.forEach { s ->
            DropRow(
                title = PlaybackSpeed.label(s) + "×",
                tag = when {
                    PlaybackSpeed.isNormal(s) -> stringResource(R.string.video_speed_normal)
                    SpeedPresets.none { same(it, s) } -> stringResource(R.string.video_speed_custom)
                    else -> null
                },
                selected = same(picked, s),
            ) {
                if (!open) return@DropRow
                picked = s
                state.commitSpeed(s)
                scope.launch { delay(420); currentClose() }
            }
        }
        Spacer(Modifier.height(5.dp))
        DropRow(title = stringResource(R.string.video_speed_every_video), tag = null, selected = PlaybackSpeed.remember) { if (open) state.toggleRememberSpeed() }
    }
}

/** 0.5–3×, the ruler's range. */
private val SpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/**
 * The card both pickers share ([shown] from [dropShown]): the overlay's glass, hanging 8 dp under its pill ([anchor], in this box's pixels;
 * [boxHeight] = that box's), right edges aligned, popping out of the pill. It scrolls when the rows are taller than the
 * room above the scrubber, opening scrolled to row [openAt] (centred). A tap anywhere else (a layer BEHIND the card,
 * which keeps its own touches) closes it; Back too (VideoContent).
 */
/** How far a card is out (0 gone … 1 open); its state starts over each time it opens from 0. */
@Composable
private fun dropShown(open: Boolean): Float {
    val reduced = rememberReducedMotion()
    val shown by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (reduced) tween(0) else if (open) tween(420, easing = Motion.Pop) else tween(220, easing = Motion.Exit),
        label = "overlayDrop",
    )
    return shown
}

@Composable
private fun BoxScope.OverlayDrop(
    open: Boolean,
    shown: Float,
    anchor: Rect,
    boxHeight: Float,
    title: String,
    openAt: () -> Int,
    onClose: () -> Unit,
    rows: @Composable ColumnScope.(CoroutineScope) -> Unit,
) {
    val currentClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    if (open) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    // Everything this touch does is taken, so a tap beside the card only closes it.
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    currentClose()
                }
            },
        )
    }
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val width = with(density) { DropWidth.toPx() }
    // Never over the time row and scrubber.
    val room = with(density) { (boxHeight - anchor.bottom - gap).toDp() } - DropBottomReserve
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        // Open scrolled to the chosen row, centred when the list scrolls.
        withFrameNanos { }
        val index = openAt().coerceAtLeast(0)
        val row = with(density) { (DropRowHeight + DropRowGap).toPx() }
        val top = with(density) { DropHeaderHeight.toPx() } + index * row
        scroll.scrollTo((top - (scroll.viewportSize - row) / 2f).roundToInt().coerceAtLeast(0))
    }
    Column(
        Modifier
            .offset { IntOffset((anchor.right - width).roundToInt(), (anchor.bottom + gap).roundToInt()) }
            .width(DropWidth)
            .heightIn(max = room.coerceAtLeast(DropHeaderHeight + DropRowHeight * 2))
            .graphicsLayer {
                val s = 0.4f + 0.6f * shown
                scaleX = s
                scaleY = s
                alpha = (shown * 3f).coerceIn(0f, 1f)
                // Grows out of the pill, from under its middle.
                transformOrigin = TransformOrigin(1f - anchor.width / 2f / size.width, -10.dp.toPx() / size.height)
                shape = RoundedCornerShape(22.dp)
                clip = true
            }
            .background(Color(0xFF121216).copy(alpha = 0.72f))
            .verticalScroll(scroll)
            .padding(6.dp),
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
            maxLines = 1,
            modifier = Modifier.height(DropHeaderHeight).padding(start = 12.dp, top = 6.dp),
        )
        rows(scope)
    }
}

@Composable
private fun DropRow(title: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    val reduced = rememberReducedMotion()
    val check by animateFloatAsState(
        if (selected) 1f else 0f,
        if (reduced) tween(0) else if (selected) tween(380, easing = Motion.Pop) else tween(150),
        label = "dropCheck",
    )
    val fill by animateFloatAsState(if (selected) 0.16f else 0f, tween(if (reduced) 0 else 250), label = "dropFill")
    Row(
        Modifier
            .padding(bottom = DropRowGap)
            .fillMaxWidth()
            .height(DropRowHeight)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = fill))
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(
            tag.orEmpty(),
            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.Check, if (selected) stringResource(R.string.video_selected) else null, tint = Color.White,
            modifier = Modifier.size(18.dp).graphicsLayer {
                scaleX = 0.3f + 0.7f * check
                scaleY = 0.3f + 0.7f * check
                alpha = check.coerceIn(0f, 1f)
            },
        )
    }
}

private val DropWidth = 184.dp
private val DropRowHeight = 38.dp
private val DropRowGap = 1.dp
private val DropHeaderHeight = 24.dp
/** Kept clear under the card: the time row and the scrubber. */
private val DropBottomReserve = 64.dp

/** The speed ruler as a sheet, for the controls on the video and fullscreen (the playback pill shows it inside the bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(state: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        // Scrolls: in fullscreen (landscape) the sheet is short.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.video_speed),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
            SpeedPanelContent(state)
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun qualityTag(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "QHD"
    height >= 1080 -> str(R.string.video_quality_full_hd)
    height >= 720 -> "HD"
    height <= 240 -> str(R.string.video_quality_data_saver)
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
        if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.video_selected), tint = c.onPrimaryContainer)
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
        ) { Text(stringResource(R.string.video_try_again), color = Color(0xFF141414), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
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
 * "2× ▸▸" at the top of the video while a finger holds it at [PlaybackSpeed.HOLD]: the double-tap
 * pill's glass, springing in; the glyph keeps dashing forward while held. Reduced motion: fade only.
 */
@Composable
private fun FastHoldPill(shown: Boolean, fullscreen: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else if (shown) 140 else 200, easing = Motion.Emphasized),
        label = "holdPillAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0.6f,
        animationSpec = if (shown) spring(dampingRatio = 0.5f, stiffness = 500f) else tween(200, easing = Motion.Emphasized),
        label = "holdPillScale",
    )
    if (alpha <= 0f) return
    val dash = remember { Animatable(0f) }
    LaunchedEffect(shown, reduced) {
        if (!shown || reduced) { dash.snapTo(0f); return@LaunchedEffect }
        while (true) dash.animateTo(0f, keyframes { durationMillis = 700; 1f at 220 })
    }
    val font = if (fullscreen) 20.sp else 16.sp
    Row(
        modifier
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
            .height(if (fullscreen) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(start = if (fullscreen) 18.dp else 15.dp, end = if (fullscreen) 14.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${PlaybackSpeed.HOLD.roundToInt()}×", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = font)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.FastForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(if (fullscreen) 28.dp else 22.dp)
                .graphicsLayer { translationX = 4.dp.toPx() * dash.value },
        )
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
                stringResource(if (playing) R.string.video_pause else R.string.video_play),
                tint = content,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/**
 * Scrubber: buffered and played tracks, a knob that grows while dragging. Thin and white on
 * the video; the audio-only stage passes its own colors and sizes. With [chapters] (start
 * times) the line has a gap at each chapter, as the portrait playback head does.
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
    chapters: List<Long> = emptyList(),
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
            // Its own layer, so the chapter gaps can be cut out of the line.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
        val gap = EdgeChapterGap.toPx()
        chapters.forEach { start ->
            val x = size.width * (start.toFloat() / duration)
            if (x > 0f && x < size.width) drawRect(Color.Black, Offset(x - gap / 2f, y), Size(gap, trackH), blendMode = BlendMode.Clear)
        }
        drawCircle(played, radius = knobRadius.toPx(), center = Offset(size.width * playedFraction, size.height / 2f))
    }
}
