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
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1400f),
        label = "pressScale",
    )
    val layer = this.graphicsLayer { scaleX = scale; scaleY = scale }
    // Disabled: no click handling at all, so taps fall through to what's underneath
    // (a disabled combinedClickable still swallows them — e.g. hidden video controls
    // blocking the tap that should bring them back).
    if (!enabled) return@composed layer
    layer
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { long -> { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); long() } },
            onClick = onClick,
        )
}
