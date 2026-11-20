package com.decio.skipii

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Queues a video from a swipe (MainActivity provides it; null = nothing to queue into, e.g. on a TV). */
val LocalQueueAdd = androidx.compose.runtime.staticCompositionLocalOf<((VideoItem) -> Unit)?> { null }

/**
 * A video that can be swiped (Derek, 2026-11-19): RIGHT queues it, LEFT takes it out of a list it can leave
 * (the account's own playlists #85, the queue). Every list of videos wraps its cards in this.
 *
 * Queuing needs a real swipe ("too easy to add to queue by accident"): the card has to travel [QueueSwipeShare]
 * of its width, a tray grows behind it and turns full primary with a tick once it's far enough, and letting go
 * short of that does nothing, however fast the flick. A drag only becomes a swipe when it starts clearly
 * sideways ([SwipeSideways]); anything steeper is the list's scroll, so scrolling never moves a card.
 *
 * The gesture is read on this box, never on the sliding content: a detector inside the moving layer sees its
 * own coordinates move under the finger and lags behind it.
 *
 * [inset] is where the card's own shape sits inside the box and [corner] its radius, so the trays line up
 * with it (a feed card: 6 / 7 dp in, 28 dp; a More videos row: the thumbnail's 16 dp).
 */
@Composable
fun SwipeVideo(
    video: VideoItem,
    modifier: Modifier = Modifier,
    inset: PaddingValues = PaddingValues(0.dp),
    corner: Dp = 28.dp,
    /** Swiping right queues (false in the queue itself). */
    queue: Boolean = true,
    onSwipeLeft: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val add = if (queue) LocalQueueAdd.current else null
    val tv = LocalWindowLayout.current.tv
    val right = add != null && !tv
    val left = onSwipeLeft != null && !tv
    if (!right && !left) {
        Box(modifier) { content() }
        return
    }
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val state = remember { SwipeState() }
    var width by remember { mutableIntStateOf(0) }
    val queueLabel = stringResource(R.string.ui_add_to_queue)
    val removeLabel = stringResource(R.string.ui_remove)
    // Read when a swipe ends, which can be long after the gesture started.
    val current by rememberUpdatedState(video)
    val queueIt by rememberUpdatedState(add)
    val removeIt by rememberUpdatedState(onSwipeLeft)
    Box(
        modifier
            .onSizeChanged { width = it.width }
            // (TalkBack queues from the long-press menu, which every card and row has.)
            .pointerInput(right, left) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Sideways or not is decided once the finger has moved past touch slop.
                    val slop = viewConfiguration.touchSlop
                    var travel = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Lifted, or taken by someone else (the list's scroll, a reorder): not a swipe.
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        travel += change.positionChange()
                        if (travel.getDistance() >= slop) {
                            val sideways = abs(travel.x) >= abs(travel.y) * SwipeSideways
                            if (!sideways || !(if (travel.x > 0f) right else left)) return@awaitEachGesture
                            change.consume()
                            break
                        }
                    }
                    state.job?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val w = width.toFloat()
                    horizontalDrag(down.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        state.offset = swipeOffset(state.offset, change.positionChange().x, w, right, left)
                        change.consume()
                        val queueArmed = right && swipeQueues(state.offset, w)
                        if (queueArmed != state.armedRight) {
                            state.armedRight = queueArmed
                            view.thresholdTick(queueArmed)
                        }
                        val removeArmed = left && -state.offset > w * RemoveSwipeShare
                        if (removeArmed != state.armedLeft) {
                            state.armedLeft = removeArmed
                            view.thresholdTick(removeArmed)
                        }
                    }
                    val velocity = tracker.calculateVelocity().x
                    val from = state.offset
                    when {
                        // Queued: only from the far side of the line, whatever the speed.
                        right && from > 0f && swipeQueues(from, w) -> {
                            queueIt?.invoke(current)
                            state.settle(scope, 0f, velocity, reduced)
                        }
                        // Out of the list: far enough, or flicked (it has an Undo).
                        left && from < 0f && (-from > w * RemoveSwipeShare || velocity < -RemoveFling) -> {
                            state.job = scope.launch {
                                if (reduced) state.offset = -w
                                else animate(from, -w, velocity, tween(220, easing = Motion.Exit)) { v, _ -> state.offset = v }
                                removeIt?.invoke()
                            }
                        }
                        else -> state.settle(scope, 0f, velocity, reduced)
                    }
                    state.armedRight = false
                    state.armedLeft = false
                }
            },
    ) {
        if (right) SwipeTray(
            reveal = { state.offset },
            armed = state.armedRight,
            icon = if (state.armedRight) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd,
            label = queueLabel,
            rest = c.primaryContainer to c.onPrimaryContainer,
            lit = c.primary to c.onPrimary,
            inset = inset,
            corner = corner,
            alignEnd = false,
        )
        if (left) SwipeTray(
            reveal = { -state.offset },
            armed = state.armedLeft,
            icon = Icons.Rounded.Delete,
            label = removeLabel,
            rest = c.errorContainer to c.onErrorContainer,
            lit = c.error to c.onError,
            inset = inset,
            corner = corner,
            alignEnd = true,
        )
        Box(Modifier.graphicsLayer { translationX = state.offset }) { content() }
    }
}

/** One card's swipe: where it is (px, + right) and whether letting go now would act. */
private class SwipeState {
    var offset by mutableFloatStateOf(0f)
    var armedRight by mutableStateOf(false)
    var armedLeft by mutableStateOf(false)
    /** The settle running (a new touch takes the card from wherever it is). */
    var job: Job? = null

    fun settle(scope: kotlinx.coroutines.CoroutineScope, to: Float, velocity: Float, reduced: Boolean) {
        job?.cancel()
        if (reduced) { offset = to; return }
        val from = offset
        job = scope.launch {
            animate(from, to, velocity, spring(dampingRatio = 0.72f, stiffness = 520f, visibilityThreshold = 0.5f)) { v, _ -> offset = v }
        }
    }
}

/**
 * The tray uncovered behind a sliding card: it grows from the card's resting edge to just short of where the
 * card now is ([TrayGap]), in the card's own shape, so it reads as a pill pulled out from under it. [rest]
 * colours it until letting go would act, [lit] (full primary / error) from then on.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeTray(
    reveal: () -> Float,
    armed: Boolean,
    icon: ImageVector,
    label: String,
    rest: Pair<Color, Color>,
    lit: Pair<Color, Color>,
    inset: PaddingValues,
    corner: Dp,
    alignEnd: Boolean,
) {
    val reduced = rememberReducedMotion()
    val bg by animateColorAsState(if (armed) lit.first else rest.first, if (reduced) snap() else tween(140, easing = Motion.Emphasized), label = "trayBg")
    val ink by animateColorAsState(if (armed) lit.second else rest.second, if (reduced) snap() else tween(140, easing = Motion.Emphasized), label = "trayInk")
    // Pops (an underdamped spring) as the line is crossed.
    val pop by animateFloatAsState(
        if (armed) 1.1f else 1f,
        if (reduced) snap() else spring(dampingRatio = 0.4f, stiffness = 700f),
        label = "trayPop",
    )
    Box(
        Modifier
            .matchParentSize()
            .padding(inset)
            .layout { measurable, constraints ->
                // Only the uncovered stretch: content without a background of its own (a More videos
                // row) never shows the tray through its gaps.
                val w = (reveal() - TrayGap.toPx()).roundToInt().coerceIn(0, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(if (alignEnd) constraints.maxWidth - w else 0, 0)
                }
            }
            .clip(RoundedCornerShape(corner))
            .background(bg),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            Modifier
                // Never squeezed by the narrow tray: the words are uncovered as the card slides.
                .wrapContentWidth(if (alignEnd) Alignment.End else Alignment.Start, unbounded = true)
                .padding(horizontal = 22.dp)
                .graphicsLayer {
                    // Fades in over the first stretch of the swipe.
                    alpha = ((reveal() - TrayGap.toPx()) / 48.dp.toPx()).coerceIn(0f, 1f)
                    scaleX = pop
                    scaleY = pop
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts: List<@Composable () -> Unit> = listOf(
                { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp)) },
                { Spacer(Modifier.width(8.dp)) },
                { Text(label, color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, softWrap = false) },
            )
            // The icon sits at the tray's outer edge on both sides (the app is always laid out left to right).
            (if (alignEnd) parts.reversed() else parts).forEach { it() }
        }
    }
}

/** Crossing a swipe's line: Android's threshold haptics (14+), a confirm / tick before. */
private fun View.thresholdTick(on: Boolean) {
    performHapticFeedback(
        when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            Build.VERSION.SDK_INT >= 30 -> if (on) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
            else -> if (on) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK
        },
    )
}

/** Where the card goes when the finger moves [delta]: 1:1 up to the queue line, then heavy, and never past [SwipeMaxShare]. */
internal fun swipeOffset(current: Float, delta: Float, width: Float, right: Boolean, left: Boolean): Float {
    if (width <= 0f) return 0f
    val min = if (left) -width else 0f
    val max = if (right) width * SwipeMaxShare else 0f
    val past = current > width * QueueSwipeShare && delta > 0f
    return (current + if (past) delta * SwipePastDrag else delta).coerceIn(min, max)
}

/** Letting go here queues the video: the card is at least [QueueSwipeShare] of its width across. */
internal fun swipeQueues(offset: Float, width: Float): Boolean = width > 0f && offset >= width * QueueSwipeShare

/**
 * How far right a card must be pulled to queue it: a third of the way across (Derek on 0.5.119, screenshots:
 * "activate around here", not at 60%), never a short tug or a flick.
 */
internal const val QueueSwipeShare = 0.33f
/** Past the queue line the card follows at this rate, up to [SwipeMaxShare]. */
private const val SwipePastDrag = 0.45f
private const val SwipeMaxShare = 0.6f
/** A drag is a swipe only when it's this many times wider than tall at touch slop (≈ 31° off level at most). */
private const val SwipeSideways = 1.65f
/** Left: past this share of the width (or [RemoveFling]) the card comes out. */
private const val RemoveSwipeShare = 0.4f
/** px/s: a flick left this fast takes the card even short of [RemoveSwipeShare] (removing has an Undo). */
private const val RemoveFling = 2200f
/** Between the tray's edge and the card's. */
private val TrayGap = 4.dp
