package com.decio.skipii

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * The single motion vocabulary (shared with Castii). Tuning dials:
 *  - [Emphasized] ease-out for page changes and small reveals.
 *  - [Morph] the slow-start, single-soft-overshoot curve for the player morph.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
    val Morph = CubicBezierEasing(0.35f, 0f, 0.15f, 1.12f)
    /** Emphasized accelerate: things leaving (cleared queue cards sliding away). */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** A spring-like pop with one overshoot, for things driven by a progress (no time of their own). */
    val Pop = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    const val PageEnterMs = 220
    const val PageRiseMs = 280
    const val PageExitMs = 90
    /** Tap-initiated open: slow start so the eye sees where the video grows from. */
    const val MorphMs = 480
    /** Tap/back-initiated collapse. Gesture releases use PlayerMorph's spring instead. */
    const val CollapseMs = 380
    /** Overlay controls hide after this long without a touch while playing. */
    const val ControlsHideMs = 2_000L
}

/** True when animations are off system-wide; all motion collapses to instant switches. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) == 0f
    }
}

/**
 * Tap target with the app's press feedback: a small scale-down while held and a quick
 * spring back, done as a layer transform (no relayout). No ripple.
 *
 * Reached with a D-pad (a TV remote, a keyboard) it's the focus target too: focused, it grows to
 * [focusedScale] and a light tone of the ink lies over it, rounded at [focusCorner] (or half its
 * height, if less). Tone and scale, never an outline (rule 7); touch never focuses it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    focusedScale: Float = 1.08f,
    focusCorner: Dp = 20.dp,
    /** False when the control draws its own focus (the rail's pill). */
    focusTone: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed && !reduced -> pressedScale
            focused && !reduced -> focusedScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = if (focused && !pressed) 700f else 1400f),
        label = "pressScale",
    )
    val toneShown by animateFloatAsState(if (focused) 1f else 0f, if (reduced) snap() else tween(140), label = "focusTone")
    val ink = LocalContentColor.current
    val layer = this.graphicsLayer {
        // Growing on focus is capped at [MaxFocusGrow] across, so a full-width row doesn't spill off the screen.
        val s = if (scale > 1f && size.width > 0f) 1f + (scale - 1f) * minOf(1f, MaxFocusGrow.toPx() / ((focusedScale - 1f) * size.width)) else scale
        scaleX = s
        scaleY = s
    }
    // Disabled: no click handling at all, so taps fall through to what's underneath
    // (a disabled combinedClickable still swallows them — e.g. hidden video controls
    // blocking the tap that should bring them back).
    if (!enabled) return@composed layer
    layer
        .drawWithContent {
            drawContent()
            if (focusTone && toneShown > 0f) {
                val r = minOf(size.minDimension / 2f, focusCorner.toPx())
                drawRoundRect(ink.copy(alpha = FocusToneAlpha * toneShown), cornerRadius = CornerRadius(r))
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { long -> { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); long() } },
            onClick = onClick,
        )
}

/** The most a focused control grows across (and so at most half this each side). */
private val MaxFocusGrow = 24.dp

/** How strongly a focused control is toned with its ink (M3's focus state layer, a step up for across the room). */
const val FocusToneAlpha = 0.16f

/**
 * Moves focus to this requester once it's attached (a list's first card arrives a frame or two after its data):
 * tries for up to [frames] frames, then leaves focus where it is.
 */
suspend fun FocusRequester.focusWhenReady(frames: Int = 30) {
    repeat(frames) {
        withFrameNanos { }
        // Compose 1.7 throws while the requester isn't attached to anything yet.
        val done = try { requestFocus(); true } catch (_: IllegalStateException) { false }
        if (done) return
    }
}
