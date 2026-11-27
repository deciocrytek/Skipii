package com.decio.skipii

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fullscreen brightness and volume (GitHub issues #3 and #6): a vertical drag that starts on the
 * left [SideShare] of the video sets the screen's brightness, on the right the media volume; up
 * is more, [SwipeRangeShare] of the screen's height is the whole range. The Morph stack
 * ([LevelStack], GitHub #88) shows where it is. The middle keeps swipe down to leave fullscreen. Nothing
 * is taken before touch slop, so taps and double-tap seek are untouched. The brightness is the window's own
 * (the system setting is never changed) and goes back to the system's when fullscreen closes.
 * Either side can be turned off in Settings › Playback ([FullscreenSwipes], #62).
 * Brightness levels are on Android's own slider curve ([toSlider] / [fromSlider]), not the raw
 * linear value: on that, the top half of a drag barely changed anything, everything happened
 * in the bottom third, and the pill disagreed with the system slider (40% vs its 82%).
 * Past either end the finger keeps a little travel ([raw], drawn as a rubber band); pulled
 * [AutoAt] below 0 the brightness goes back to the system's own (Auto), past [AutoBackAt] it's manual again.
 */
@Stable
class FullscreenSliders(context: Context, private val view: View) {
    enum class Kind { Brightness, Volume }

    private val activity = context as? Activity
    private val res = context
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    /** What's being dragged right now (null: nothing). */
    var active by mutableStateOf<Kind?>(null)
        private set
    /** The finger's level, 0..1 (fractional even for volume, so the stack follows the finger, not the steps). */
    var level by mutableFloatStateOf(0f)
        private set
    /** The finger's level without the ends: below 0 / above 1 is how far it has pulled past them. */
    var raw by mutableFloatStateOf(0f)
        private set
    /** Brightness handed back to the system (pulled past [AutoAt]). */
    var auto by mutableStateOf(false)
        private set
    /** Last time a drag moved, so the stack fades out a moment after it stops. */
    var touchedAt by mutableStateOf(0L)
        private set
    var shown by mutableStateOf<Kind?>(null)
        private set
    /** What TalkBack reads (a polite live region), throttled while dragging. */
    var spoken by mutableStateOf("")
        private set

    /** What the number says: real volume steps for volume, the slider position for brightness. */
    val percent: Int
        get() = if (shown == Kind.Volume) ((level * maxVolume).roundToInt() * 100f / maxVolume).roundToInt() else (level * 100).roundToInt()

    private var lastPercent = -1
    private var lastTenth = -1
    private var lastTickAt = 0L
    private var lastSpokenAt = 0L
    private var walled = false

    fun begin(kind: Kind): Boolean {
        if (kind == Kind.Volume && (audio == null || audio.isVolumeFixed)) return false
        level = when (kind) {
            Kind.Brightness -> currentBrightness()
            Kind.Volume -> audio!!.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        }
        raw = level
        auto = false
        active = kind
        shown = kind
        walled = false
        lastPercent = percent
        lastTenth = floor(level * 10).toInt()
        touchedAt = System.nanoTime()
        buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    /** [fraction] of the full range; positive = more. */
    fun move(fraction: Float) {
        val kind = active ?: return
        raw = (raw + fraction).coerceIn(-OverTravel, 1f + OverTravel)
        touchedAt = System.nanoTime()
        when (kind) {
            Kind.Brightness -> {
                if (raw < AutoAt) {
                    if (!auto) {
                        auto = true
                        level = 0f
                        releaseBrightness()
                        buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK)
                        say(res.getString(R.string.video_level_brightness_auto), now = true)
                    }
                } else {
                    if (auto && raw > AutoBackAt) auto = false
                    if (!auto) {
                        level = raw.coerceIn(0f, 1f)
                        setBrightness(level)
                    }
                }
            }
            // Whole steps only; the level itself stays fractional so slow drags still get there.
            Kind.Volume -> {
                level = raw.coerceIn(0f, 1f)
                val step = (level * maxVolume).roundToInt()
                if (step != audio?.getStreamVolume(AudioManager.STREAM_MUSIC)) audio?.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            }
        }
        // Pushing on well past an end: one gentle bump per drag.
        if ((raw > 1f + WallAt || raw < -WallAt) && !walled && !(kind == Kind.Brightness && auto)) {
            walled = true
            buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK)
        }
        if (raw in 0f..1f) walled = false
        if (auto) return
        val pct = percent
        if (pct != lastPercent) {
            val tenth = floor(level * 10).toInt()
            when {
                pct == 0 || pct == 100 -> {
                    buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
                    say(phrase(kind, pct), now = true)
                }
                kind == Kind.Volume -> buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                tenth != lastTenth -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTickAt > BrightnessTickGapMs) { lastTickAt = now; buzz(HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            lastTenth = tenth
            lastPercent = pct
            say(phrase(kind, pct), now = false)
        }
    }

    fun end() {
        val kind = active ?: return
        raw = if (auto) 0f else level
        active = null
        touchedAt = System.nanoTime()
        say(if (auto) res.getString(R.string.video_level_brightness_auto) else phrase(kind, percent), now = true)
    }

    fun hide() { if (active == null) shown = null }

    private fun phrase(kind: Kind, pct: Int): String = when (kind) {
        Kind.Volume -> when (pct) {
            0 -> res.getString(R.string.video_level_volume_muted)
            100 -> res.getString(R.string.video_level_volume_max)
            else -> res.getString(R.string.video_level_volume, pct.toString())
        }
        Kind.Brightness -> when (pct) {
            0 -> res.getString(R.string.video_level_brightness_min)
            100 -> res.getString(R.string.video_level_brightness_max)
            else -> res.getString(R.string.video_level_brightness, pct.toString())
        }
    }

    private fun say(text: String, now: Boolean) {
        val at = SystemClock.uptimeMillis()
        if (!now && at - lastSpokenAt < SpeakGapMs) return
        lastSpokenAt = at
        spoken = text
    }

    private fun buzz(constant: Int) { view.performHapticFeedback(constant) }

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

    private companion object {
        /** How far past either end the finger can still pull (share of the range). */
        const val OverTravel = 0.26f
        /** Brightness pulled this far below 0 goes back to the system's (Auto)… */
        const val AutoAt = -0.14f
        /** …and comes back to manual above this. */
        const val AutoBackAt = -0.06f
        /** Past this beyond either end: one REJECT bump. */
        const val WallAt = 0.16f
        const val BrightnessTickGapMs = 45L
        const val SpeakGapMs = 400L
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
 * height: top to bottom took two full swipes (Derek, 2026-10-29). Kept between [RangeMin] and
 * [RangeMax] so a short pane isn't twitchy and a tablet doesn't need an arm sweep (#88).
 */
private const val SwipeRangeShare = 0.4f
private val RangeMin = 130.dp
private val RangeMax = 260.dp
/** Share of the video's width, from each side, whose vertical drags set brightness / volume (leaves the middle 50% for pull-down exit and the drawer). */
internal const val SideShare = 0.25f
/** Starts this close to the top or bottom edge are left to the system's own swipes. */
private val EdgeGuard = 28.dp
/** The middle's swipe up opens the drawer past this far (or on a flick). */
private val DrawerOpenDistance = 56.dp
/** The middle's swipe down leaves fullscreen past this far, or on a flick ([ExitFlick] px/s). */
private const val ExitDistancePx = 120f
private const val ExitFlick = 1400f

@Composable
fun rememberFullscreenSliders(): FullscreenSliders {
    val context = LocalContext.current
    val view = LocalView.current
    val sliders = remember(context, view) { FullscreenSliders(context, view) }
    DisposableEffect(sliders) { onDispose { sliders.releaseBrightness() } }
    // The stack stays a moment after the finger lifts, so the last number can be read.
    LaunchedEffect(sliders.touchedAt, sliders.active) {
        if (sliders.active == null && sliders.shown != null) { delay(StackHoldMs); sliders.hide() }
    }
    return sliders
}

/**
 * The gestures: side drags go to [sliders]; middle drags follow [onPull] (how far down, in px) and
 * end in [onRelease]: `exit` when let go past [ExitDistancePx] or on a flick down (leave fullscreen),
 * else false (the picture eases back).
 */
fun Modifier.fullscreenGestures(
    sliders: FullscreenSliders,
    onPull: (Float) -> Unit,
    onRelease: (exit: Boolean) -> Unit,
    /** The middle's swipe UP (0.6.0, Neon's split): how far up it has pulled the drawer, in px… */
    onDrawerPull: ((Float) -> Unit)? = null,
    /** …and, let go, whether the drawer should open (past [DrawerOpenDistance] or a flick up). */
    onDrawerRelease: ((open: Boolean) -> Unit)? = null,
) = pointerInput(sliders) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val guard = EdgeGuard.toPx()
        if (down.position.y < guard || down.position.y > size.height - guard) return@awaitEachGesture
        val x = down.position.x / size.width
        val kind = when {
            // A side whose swipe is off in Settings (FullscreenSwipes) acts like the middle.
            x < SideShare && FullscreenSwipes.brightness -> FullscreenSliders.Kind.Brightness
            x > 1f - SideShare && FullscreenSwipes.volume -> FullscreenSliders.Kind.Volume
            else -> null
        }
        var over = 0f
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop -> change.consume(); over = overSlop } ?: return@awaitEachGesture
        val range = (size.height * SwipeRangeShare).coerceIn(RangeMin.toPx(), RangeMax.toPx())
        if (kind != null && sliders.begin(kind)) {
            sliders.move(-over / range)
            try {
                verticalDrag(start.id) { change -> sliders.move(-change.positionChange().y / range); change.consume() }
            } finally {
                sliders.end()
            }
        } else if (over < 0f && onDrawerPull != null && onDrawerRelease != null) {
            // Up first: the drawer's, to the end (it never turns into the exit swipe).
            val tracker = VelocityTracker()
            tracker.addPosition(start.uptimeMillis, start.position)
            var up = -over
            onDrawerPull(up)
            val finished = verticalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                up = (up - change.positionChange().y).coerceAtLeast(0f)
                onDrawerPull(up)
                change.consume()
            }
            onDrawerRelease(finished && (up > DrawerOpenDistance.toPx() || tracker.calculateVelocity().y < -ExitFlick))
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
            onRelease(finished && (pulled > ExitDistancePx || tracker.calculateVelocity().y > ExitFlick))
        }
    }
}

/* ---------------------------------------------------------------------------------------------
 * The Morph stack (GitHub #88; Neon's design, approved by Derek 2026-11-24, docs/design/morph-stack-neon.html).
 * Ten slots in a column a third of the way in from the side being swiped (34% / 66% of the width), no box.
 * The slot at the level grows from a 7 dp dot into a 28 × 10 capsule in step with the finger; the top
 * one is 36 × 12 with a sheen; the number rides above it; a 44 dp badge sits underneath (volume: a
 * rounded square whose corners tighten as it rises; brightness: a 9-scallop sun that turns).
 * Everything that follows the finger is read in the draw pass straight from [FullscreenSliders], so it
 * never lags; the flourishes (pops, ring, nudge, rubber band, arrival) are clocks in [StackMotion] on top.
 * ------------------------------------------------------------------------------------------- */

private const val StackHoldMs = 700L
private const val Slots = 10
private const val SlotPitch = 15.8f            // dp between slot centres
private const val DotSize = 7f
private const val CapsuleW = 28f
private const val CapsuleH = 10f
private const val CrestW = 36f
private const val CrestH = 12f
private const val BadgeSize = 44f
private val PopEase = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SettleEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Sun = Color(0xFFFFE3A3)
private val OnSun = Color(0xFF4A3300)
private val MutedBadge = Color(0xFF3B383E)
private val MutedInk = Color(0xFFE6E1E5)
private val DimSun = Color(0xFF3B352C)
private val DimSunInk = Color(0xFFF5E6C8)
private val AutoInk = Color(0xFFFFF4D2)
/** Brightness warms from amber at the bottom slot to sunlit cream at the top. */
private val SunSlots = listOf(
    0xFFFFC458, 0xFFFFC964, 0xFFFFCE72, 0xFFFFD480, 0xFFFFDA8E,
    0xFFFFE09C, 0xFFFFE5AA, 0xFFFFEBB8, 0xFFFFF0C6, 0xFFFFF5D4,
).map { Color(it) }

private fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)
private fun rubberBand(over: Float) = sign(over) * (1f - exp(-abs(over) * 3.2f)) * 0.28f

/** A keyframe flourish: 0 → [peak] → 0 over [ms], its overall progress eased by [ease]. */
private class StackClock(val ms: Float, val ease: CubicBezierEasing) {
    var t = -1f
    fun start() { t = 0f }
    fun step(dtMs: Float) { if (t >= 0f) { t += dtMs; if (t > ms) t = -1f } }
    /** 0 at rest; 1 at the peak (halfway), back to 0 at the end (slightly past, with an overshooting ease). */
    fun tent(): Float {
        if (t < 0f) return 0f
        val p = ease.transform(t / ms)
        return if (p < 0.5f) p / 0.5f else 1f - (p - 0.5f) / 0.5f
    }
    /** Plain eased progress 0..1 (for the ring). */
    fun progress(): Float = if (t < 0f) -1f else ease.transform(t / ms)
    val running get() = t >= 0f
}

/** The stack's own clocks. [frame] is read by the draw pass so every step redraws. */
private class StackMotion {
    var frame by mutableIntStateOf(0)
    var alpha = 0f
    var lift = 0f; var liftV = 0f                 // dp, arrival lift (11 → 0)
    var over = 0f; var overV = 0f                 // rubber band, springs back after the finger lifts
    val fill = Array(Slots) { StackClock(240f, PopEase) }
    val drain = Array(Slots) { StackClock(180f, SettleEase) }
    val full = BooleanArray(Slots)
    val stepPop = StackClock(150f, PopEase)
    val maxPop = StackClock(340f, PopEase)
    val badgePop = StackClock(320f, PopEase)
    val ring = StackClock(480f, SettleEase)
    val nudge = StackClock(280f, SettleEase)
    var shownKind: FullscreenSliders.Kind? = null
    var lastPercent = -1
    var wasMax = false
    var wasZero = false

    val idle get() = alpha <= 0f && abs(over) < 0.001f && abs(lift) < 0.05f

    fun step(dtMs: Float, s: FullscreenSliders, reduced: Boolean) {
        val dt = dtMs / 1000f
        val kind = s.shown
        // Arrival: fade 160 ms (100 reduced), lift 11 dp → 0 on a spring.
        if (kind != null && (shownKind != kind || alpha < 0.25f) && shownKind != kind) {
            if (!reduced && alpha < 0.25f) { lift = 11f; liftV = 0f }
            syncFull(s)
            lastPercent = s.percent
            wasMax = s.percent >= 100; wasZero = s.percent <= 0
        }
        if (kind != null) shownKind = kind
        alpha = if (kind != null) min(1f, alpha + dtMs / (if (reduced) 100f else 160f))
        else max(0f, alpha - dtMs / (if (reduced) 120f else 260f))
        if (kind == null && alpha <= 0f) shownKind = null
        integrate(dt, 480f, 28f, { lift }, { lift = it }, { liftV }, { liftV = it }, 0f)
        if (reduced) { lift = 0f; liftV = 0f }
        // The rubber band follows the finger while it's down and springs back once it lifts.
        val target = if (s.active != null) (if (s.raw > 1f) s.raw - 1f else if (s.raw < 0f) s.raw else 0f) else 0f
        if (s.active != null || reduced) { over = target; overV = 0f }
        else integrate(dt, 420f, 24f, { over }, { over = it }, { overV }, { overV = it }, 0f)
        // Flourishes, only while dragging and never with reduced motion.
        val active = s.active != null && !reduced
        val fillTenths = if (s.auto) 0f else s.level * Slots
        for (i in 0 until Slots) {
            val isFull = fillTenths - i >= 1f
            if (active && isFull && !full[i]) fill[i].start()
            if (active && !isFull && full[i]) drain[i].start()
            full[i] = isFull
            fill[i].step(dtMs); drain[i].step(dtMs)
        }
        val pct = s.percent
        val isMax = pct >= 100 && !s.auto && s.active != null
        val isZero = pct <= 0 && !s.auto && s.active != null
        if (active && pct != lastPercent && s.shown == FullscreenSliders.Kind.Volume && pct in 1..99) stepPop.start()
        if (active && isMax && !wasMax) { maxPop.start(); badgePop.start(); ring.start() }
        if (active && isZero && !wasZero) nudge.start()
        lastPercent = pct; wasMax = isMax; wasZero = isZero
        stepPop.step(dtMs); maxPop.step(dtMs); badgePop.step(dtMs); ring.step(dtMs); nudge.step(dtMs)
        frame++
    }

    private fun syncFull(s: FullscreenSliders) {
        val f = s.level * Slots
        for (i in 0 until Slots) full[i] = f - i >= 1f
    }

    private inline fun integrate(
        dt: Float, k: Float, c: Float,
        get: () -> Float, set: (Float) -> Unit, getV: () -> Float, setV: (Float) -> Unit, target: Float,
    ) {
        var x = get(); var v = getV()
        val n = max(1, ceil(dt / 0.004f).toInt()); val h = dt / n
        repeat(n) { val a = -k * (x - target) - c * v; v += a * h; x += v * h }
        if (abs(x - target) < 0.0005f && abs(v) < 0.005f) { x = target; v = 0f }
        set(x); setV(v)
    }
}

/** The Morph stack, over the whole fullscreen video. */
@Composable
fun BoxScope.LevelStack(sliders: FullscreenSliders) {
    val reduced = rememberReducedMotion()
    val motion = remember { StackMotion() }
    val shown = sliders.shown
    LaunchedEffect(shown) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            motion.step(min(40f, (now - last) / 1_000_000f), sliders, reduced)
            last = now
            if (sliders.shown == null && motion.idle) break
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryHi = lerp(primary, Color.White, 0.45f)
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current
    val icons = listOf(
        Icons.AutoMirrored.Rounded.VolumeOff, Icons.AutoMirrored.Rounded.VolumeMute, Icons.AutoMirrored.Rounded.VolumeDown,
        Icons.AutoMirrored.Rounded.VolumeUp, Icons.Rounded.BrightnessAuto, Icons.Rounded.BrightnessLow,
        Icons.Rounded.BrightnessMedium, Icons.Rounded.BrightnessHigh,
    ).associateWith { rememberVectorPainter(it) }
    val cookie = remember { Path() }
    val autoLabel = androidx.compose.ui.res.stringResource(R.string.video_level_auto)

    Canvas(Modifier.fillMaxSize()) {
        motion.frame // every motion step redraws
        val kind = motion.shownKind ?: return@Canvas
        val alpha = motion.alpha
        if (alpha <= 0f) return@Canvas
        val d = density
        val volume = kind == FullscreenSliders.Kind.Volume
        // Narrow panes (split screen): the lanes move out so the middle stays clear for taps.
        val laneShare = if (size.width / d < 520f) (if (volume) 0.76f else 0.24f) else (if (volume) 0.66f else 0.34f)
        val auto = !volume && sliders.auto
        val level = if (auto) 0f else sliders.level
        val pct = sliders.percent
        val isZero = pct <= 0 && !auto
        val fill = level * Slots
        val rb = if (reduced) 0f else rubberBand(motion.over)
        val up = max(0f, rb)
        val down = min(0f, rb)
        val gap = SlotPitch * (1f + up * 1.15f + down * 0.75f)
        val base = -96f + 9f * SlotPitch + 8f   // dp below the centre: the bottom slot's centre
        val crestBottom = if (fill > 0f) base - (fill - 0.5f) * gap - 13f - up * 14f else base - 11f - down * 18f
        val crestMid = crestBottom - 10f
        val rbY = 1f + up * 0.42f + down * 0.25f
        val rbX = 1f / sqrt(max(0.7f, rbY))
        val settle = if (sliders.shown == null && !reduced) (1f - alpha) * 5f else 0f
        // A short pane shrinks the stack a little; arriving, it's a touch smaller and lower.
        val u = d * (if (size.height / d < 320f) 0.85f else 1f) * (1f - min(0.08f, abs(motion.lift) * 0.007f))
        val ox = size.width * laneShare
        val oy = size.height / 2f + (motion.lift + settle) * d
        fun py(dpY: Float) = oy + dpY * u

        for (i in 0 until Slots) {
            val e = easeOutCubic((fill - i).coerceIn(0f, 1f))
            val eNext = if (i < Slots - 1) easeOutCubic((fill - i - 1).coerceIn(0f, 1f)) else 0f
            // The top size hands over to the next slot as that one grows (no jump at each tenth).
            val crest = if (fill > 0f) e * (1f - eNext) else 0f
            val w = DotSize + (CapsuleW - DotSize) * e + (CrestW - CapsuleW) * crest
            val h = DotSize + (CapsuleH - DotSize) * e + (CrestH - CapsuleH) * crest
            val y = base - i * gap - down * 22f
            val tone = if (volume) (if (crest > 0.65f) primaryHi else primary) else SunSlots[i]
            // Empty dots duck out behind the number so it never sits on one.
            val behind = if (e < 0.05f) ((abs(y - crestMid) - 8f) / 10f).coerceIn(0f, 1f) else 1f
            val dotA = 0.38f * (1f - e) * behind
            val outA = e + dotA * (1f - e)
            if (outA <= 0.001f) continue
            val col = Color(
                red = (tone.red * e + dotA * (1f - e)) / outA,
                green = (tone.green * e + dotA * (1f - e)) / outA,
                blue = (tone.blue * e + dotA * (1f - e)) / outA,
                alpha = (outA * alpha).coerceIn(0f, 1f),
            )
            var sx = 1f
            var sy = 1f
            if (!reduced && (up > 0.002f || down < -0.002f)) { sx *= rbX; sy *= rbY }
            motion.fill[i].tent().let { sx *= 1f + 0.24f * it; sy *= 1f + 0.18f * it }
            motion.drain[i].tent().let { sx *= 1f - 0.12f * it; sy *= 1f - 0.08f * it }
            val ww = w * sx * u
            val hh = h * sy * u
            val cy = py(y)
            shadowRoundRect(ox - ww / 2f, cy - hh / 2f, ww, hh, hh / 2f, col, 0.44f * outA * alpha, 6f * d, 2f * d)
            if (crest > 0f) {
                val inset = ww * 0.18f
                drawRoundRect(
                    Color.White.copy(alpha = (0.42f * crest * alpha).coerceIn(0f, 1f)),
                    topLeft = Offset(ox - ww / 2f + inset, cy - hh / 2f + 1.5f * u),
                    size = Size(ww - 2f * inset, 2.5f * u),
                    cornerRadius = CornerRadius(1.25f * u),
                )
            }
        }

        // The number, riding above the top capsule.
        val label = if (auto) autoLabel else "$pct%"
        val style = baseStyle.merge(
            TextStyle(
                color = if (auto) AutoInk else Color.White,
                fontSize = if (auto) 15.5.sp else 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = if (auto) 0.2.sp else (-0.4).sp,
                fontFeatureSettings = "tnum",
            ),
        )
        val soft = measurer.measure(label, style.copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 3f * d), 14f * d)))
        val crisp = measurer.measure(label, style.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f * d), 3f * d)))
        val pop = 1f + 0.09f * motion.stepPop.tent() + 0.2f * motion.maxPop.tent()
        val textA = alpha * (if (isZero) 0.74f else 1f)
        val tw = soft.size.width.toFloat()
        val th = soft.size.height.toFloat()
        val textBottom = py(crestBottom)
        withTransform({ scale(pop, pop, Offset(ox, textBottom - th / 2f)) }) {
            val tl = Offset(ox - tw / 2f, textBottom - th)
            drawText(soft, topLeft = tl, alpha = textA)
            drawText(crisp, topLeft = tl, alpha = textA)
        }

        // The badge underneath.
        val r = BadgeSize / 2f * u
        val bcx = ox + nudgeOffset(motion.nudge) * u
        val bcy = py(base + 22f - up * 7f - down * 10f + BadgeSize / 2f)
        val bY = if (reduced) 1f else 1f + down * 0.45f + up * 0.14f
        val bX = if (reduced) 1f else 1f / sqrt(max(0.75f, bY))
        val bPop = 1f + 0.15f * motion.badgePop.tent()
        withTransform({ scale(bX * bPop, bY * bPop, Offset(bcx, bcy)) }) {
            if (volume) {
                val corner = (22f - 10f * level) * u
                val p = motion.ring.progress()
                if (p >= 0f) {
                    val k = 1f + 0.68f * p
                    drawRoundRect(
                        primary.copy(alpha = (0.55f * (1f - p) * alpha).coerceIn(0f, 1f)),
                        topLeft = Offset(bcx - r * k, bcy - r * k), size = Size(2f * r * k, 2f * r * k),
                        cornerRadius = CornerRadius(corner * k),
                    )
                }
                shadowRoundRect(bcx - r, bcy - r, 2f * r, 2f * r, corner, if (isZero) MutedBadge else primary, 0.44f * alpha, 12f * d, 4f * d, alpha)
                val icon = when {
                    isZero -> Icons.AutoMirrored.Rounded.VolumeOff
                    level < 0.25f -> Icons.AutoMirrored.Rounded.VolumeMute
                    level < 0.6f -> Icons.AutoMirrored.Rounded.VolumeDown
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                }
                glyph(icons.getValue(icon), Offset(bcx, bcy), 24f * u, if (isZero) MutedInk else onPrimary, alpha, 0f)
            } else {
                val turn = if (reduced) 0f else level * 180f
                val k = if (reduced) 1f else 0.88f + 0.16f * level
                if (cookie.isEmpty) cookiePath(cookie, BadgeSize / 2f, 9, 0.075f)
                shadowPath(cookie, bcx, bcy, turn, k * u, if (isZero) DimSun else Sun, 0.44f * alpha, 12f * d, 4f * d, alpha)
                val icon = when {
                    auto -> Icons.Rounded.BrightnessAuto
                    level < 0.34f -> Icons.Rounded.BrightnessLow
                    level < 0.67f -> Icons.Rounded.BrightnessMedium
                    else -> Icons.Rounded.BrightnessHigh
                }
                glyph(icons.getValue(icon), Offset(bcx, bcy), 24f * u, if (isZero) DimSunInk else OnSun, alpha, if (reduced || auto) 0f else level * 90f)
            }
        }
    }
    // What TalkBack reads, as a polite live region (nothing on screen).
    if (sliders.spoken.isNotEmpty()) {
        Box(Modifier.align(Alignment.Center).size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = sliders.spoken
        })
    }
}

/** The mute "no": 0 → −4 → 3 → −1.5 → 0 dp. */
private fun nudgeOffset(f: StackClock): Float {
    val p = f.progress()
    if (p < 0f) return 0f
    val keys = floatArrayOf(0f, -4f, 3f, -1.5f, 0f)
    val x = (p.coerceIn(0f, 1f)) * 4f
    val i = min(3, x.toInt())
    return keys[i] + (keys[i + 1] - keys[i]) * (x - i)
}

private fun cookiePath(path: Path, radius: Float, bumps: Int, depth: Float) {
    val steps = 180
    for (i in 0..steps) {
        val a = i.toFloat() / steps * 2f * PI.toFloat()
        val r = radius * (1f - depth + depth * cos(bumps * a))
        if (i == 0) path.moveTo(r * cos(a), r * sin(a)) else path.lineTo(r * cos(a), r * sin(a))
    }
    path.close()
}

private val ShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/**
 * A filled round rect with a soft drop shadow ([shadowAlpha] black, [blur] and [dy] in px). Compose has no
 * drop shadow for a plain shape, so this goes through the framework paint (drawn on the GPU from Android 9).
 */
private fun DrawScope.shadowRoundRect(
    left: Float, top: Float, width: Float, height: Float, radius: Float, color: Color,
    shadowAlpha: Float, blur: Float, dy: Float, alpha: Float = 1f,
) {
    drawIntoCanvas { canvas ->
        val paint = ShadowPaint
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb()
        if (shadowAlpha > 0.003f) paint.setShadowLayer(blur / 2f, 0f, dy, Color.Black.copy(alpha = shadowAlpha.coerceIn(0f, 1f)).toArgb())
        canvas.nativeCanvas.drawRoundRect(left, top, left + width, top + height, radius, radius, paint)
        paint.clearShadowLayer()
    }
}

private val CookieMatrix = android.graphics.Matrix()
private val CookieDrawn = android.graphics.Path()

/** [path] (dp, around 0,0) turned [turn]°, scaled by [scale] and centred on [cx], [cy], with the same soft shadow. */
private fun DrawScope.shadowPath(
    path: Path, cx: Float, cy: Float, turn: Float, scale: Float, color: Color,
    shadowAlpha: Float, blur: Float, dy: Float, alpha: Float,
) {
    CookieMatrix.reset()
    CookieMatrix.postRotate(turn)
    CookieMatrix.postScale(scale, scale)
    CookieMatrix.postTranslate(cx, cy)
    path.asAndroidPath().transform(CookieMatrix, CookieDrawn)
    drawIntoCanvas { canvas ->
        val paint = ShadowPaint
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb()
        if (shadowAlpha > 0.003f) paint.setShadowLayer(blur / 2f, 0f, dy, Color.Black.copy(alpha = shadowAlpha.coerceIn(0f, 1f)).toArgb())
        canvas.nativeCanvas.drawPath(CookieDrawn, paint)
        paint.clearShadowLayer()
    }
}

/** An icon of [size] px centred on [center], tinted, turned [turn] degrees. */
private fun DrawScope.glyph(painter: androidx.compose.ui.graphics.vector.VectorPainter, center: Offset, size: Float, tint: Color, alpha: Float, turn: Float) {
    withTransform({
        rotate(turn, center)
        translate(center.x - size / 2f, center.y - size / 2f)
    }) {
        with(painter) { draw(Size(size, size), alpha = alpha, colorFilter = ColorFilter.tint(tint)) }
    }
}
