package com.decio.skipii

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fullscreen brightness and volume (GitHub issues #3 and #6): a vertical drag that starts on the
 * left [SideShare] of the video sets the screen's brightness, on the right the media volume; up
 * is more, [SwipeRangeShare] of the screen's height is the whole range. A level pill on that side shows where it
 * is. The middle keeps swipe down to leave fullscreen ([onSwipeDown]). Nothing is taken before
 * touch slop, so taps and double-tap seek are untouched. The brightness is the window's own
 * (the system setting is never changed) and goes back to the system's when fullscreen closes.
 * Brightness levels are on Android's own slider curve ([toSlider] / [fromSlider]), not the raw
 * linear value: on that, the top half of a drag barely changed anything, everything happened
 * in the bottom third, and the pill disagreed with the system slider (40% vs its 82%).
 */
@Stable
class FullscreenSliders(context: Context) {
    enum class Kind { Brightness, Volume }

    private val activity = context as? Activity
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    /** What's being dragged right now (null: nothing), and its level, 0..1. */
    var active by mutableStateOf<Kind?>(null)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    /** Last time a drag moved, so the pill fades out a moment after it stops. */
    var touchedAt by mutableStateOf(0L)
        private set
    var shown by mutableStateOf<Kind?>(null)
        private set

    fun begin(kind: Kind): Boolean {
        if (kind == Kind.Volume && (audio == null || audio.isVolumeFixed)) return false
        level = when (kind) {
            Kind.Brightness -> currentBrightness()
            Kind.Volume -> audio!!.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        }
        active = kind
        shown = kind
        touchedAt = System.nanoTime()
        return true
    }

    /** [fraction] of the full range; positive = more. */
    fun move(fraction: Float) {
        val kind = active ?: return
        level = (level + fraction).coerceIn(0f, 1f)
        touchedAt = System.nanoTime()
        when (kind) {
            Kind.Brightness -> setBrightness(level)
            // Whole steps only; the level itself stays fractional so slow drags still get there.
            Kind.Volume -> {
                val step = (level * maxVolume).roundToInt()
                if (step != audio?.getStreamVolume(AudioManager.STREAM_MUSIC)) audio?.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            }
        }
    }

    fun end() {
        if (active == Kind.Volume) level = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVolume
        active = null
        touchedAt = System.nanoTime()
    }

    fun hide() { if (active == null) shown = null }

    /**
     * Where the brightness is now, as a slider position: the window's own if one is set, else the
     * system's (which follows adaptive brightness too: Android writes what it picked into it).
     */
    private fun currentBrightness(): Float {
        val own = activity?.window?.attributes?.screenBrightness ?: -1f
        if (own >= 0f) return toSlider(own)
        val system = runCatching { Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        // The setting's 1..255 is the 0..1 brightness, linearly (BrightnessSynchronizer).
        return toSlider(((system - 1) / 254f).coerceIn(0f, 1f))
    }

    private fun setBrightness(slider: Float) {
        val window = activity?.window ?: return
        // Never fully 0: some screens go black at 0 and the slider would look broken.
        window.attributes = window.attributes.apply { screenBrightness = fromSlider(slider).coerceAtLeast(0.002f) }
    }

    /** Back to following the system's brightness. */
    fun releaseBrightness() {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
}

// Android's brightness slider curve (SettingsLib BrightnessUtils, HLG-based): a slider position
// 0..1 ↔ the linear 0..1 brightness a window sets. Equal drags then look like equal changes.
private const val CurveR = 0.5f
private const val CurveA = 0.17883277f
private const val CurveB = 0.28466892f
private const val CurveC = 0.55991073f

internal fun toSlider(linear: Float): Float {
    val n = linear.coerceIn(0f, 1f) * 12f
    val g = if (n <= 1f) sqrt(n) * CurveR else CurveA * ln(n - CurveB) + CurveC
    return g.coerceIn(0f, 1f)
}

internal fun fromSlider(slider: Float): Float {
    val g = slider.coerceIn(0f, 1f)
    val n = if (g <= CurveR) (g / CurveR) * (g / CurveR) else exp((g - CurveC) / CurveA) + CurveB
    return (n / 12f).coerceIn(0f, 1f)
}

/**
 * One swipe over this share of the screen's height runs the whole range. 0.5.58 used the full
 * height: top to bottom took two full swipes (Derek, 2026-10-29).
 */
private const val SwipeRangeShare = 0.4f
/** Share of the video's width, from each side, whose vertical drags set brightness / volume. */
private const val SideShare = 0.35f
/** Starts this close to the top or bottom edge are left to the system's own swipes. */
private val EdgeGuard = 28.dp
/** The middle's swipe down leaves fullscreen past this far, or on a flick ([ExitFlick] px/s). */
private const val ExitDistancePx = 120f
private const val ExitFlick = 1400f

@Composable
fun rememberFullscreenSliders(): FullscreenSliders {
    val context = LocalContext.current
    val sliders = remember(context) { FullscreenSliders(context) }
    DisposableEffect(sliders) { onDispose { sliders.releaseBrightness() } }
    // The pill fades a moment after the finger stops.
    LaunchedEffect(sliders.touchedAt, sliders.active) {
        if (sliders.active == null && sliders.shown != null) { delay(700); sliders.hide() }
    }
    return sliders
}

/**
 * The gestures: side drags go to [sliders]; middle drags follow [onPull] (how far down, for a
 * little give) and leave fullscreen on release past [ExitDistancePx] or a flick down.
 */
fun Modifier.fullscreenGestures(sliders: FullscreenSliders, onPull: (Float) -> Unit, onSwipeDown: () -> Unit) = pointerInput(sliders) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val guard = EdgeGuard.toPx()
        if (down.position.y < guard || down.position.y > size.height - guard) return@awaitEachGesture
        val x = down.position.x / size.width
        val kind = when {
            x < SideShare -> FullscreenSliders.Kind.Brightness
            x > 1f - SideShare -> FullscreenSliders.Kind.Volume
            else -> null
        }
        var over = 0f
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop -> change.consume(); over = overSlop } ?: return@awaitEachGesture
        val range = (size.height * SwipeRangeShare).coerceAtLeast(1f)
        if (kind != null && sliders.begin(kind)) {
            sliders.move(-over / range)
            try {
                verticalDrag(start.id) { change -> sliders.move(-change.positionChange().y / range); change.consume() }
            } finally {
                sliders.end()
            }
        } else {
            val tracker = VelocityTracker()
            tracker.addPosition(start.uptimeMillis, start.position)
            var pulled = over.coerceAtLeast(0f)
            onPull(pulled)
            val finished = verticalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                pulled = (pulled + change.positionChange().y).coerceAtLeast(0f)
                onPull(pulled)
                change.consume()
            }
            onPull(0f)
            if (finished && (pulled > ExitDistancePx || tracker.calculateVelocity().y > ExitFlick)) onSwipeDown()
        }
    }
}

/** The level pill on the gesture's side: a tall glass capsule filling from the bottom, its icon underneath. */
@Composable
fun BoxScope.SliderLevel(sliders: FullscreenSliders) {
    val reduced = rememberReducedMotion()
    val kind = sliders.shown
    val alpha by animateFloatAsState(
        if (kind != null) 1f else 0f,
        if (reduced) tween(0) else tween(if (kind != null) 120 else 260, easing = Motion.Emphasized),
        label = "sliderLevel",
    )
    // Keeps the last side while it fades out.
    var side by remember { mutableStateOf(FullscreenSliders.Kind.Brightness) }
    if (kind != null) side = kind
    if (alpha <= 0f) return
    val level = sliders.level
    val icon = when (side) {
        FullscreenSliders.Kind.Brightness -> when {
            level < 0.34f -> Icons.Rounded.BrightnessLow
            level < 0.67f -> Icons.Rounded.BrightnessMedium
            else -> Icons.Rounded.BrightnessHigh
        }
        FullscreenSliders.Kind.Volume -> when {
            level <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
            level < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
            else -> Icons.AutoMirrored.Rounded.VolumeUp
        }
    }
    Column(
        Modifier
            .align(if (side == FullscreenSliders.Kind.Brightness) Alignment.CenterStart else Alignment.CenterEnd)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 40.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(6.dp).height(132.dp).drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.28f), cornerRadius = r)
                val h = size.height * level
                drawRoundRect(Color.White, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r)
            },
        )
        Spacer(Modifier.height(10.dp))
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
