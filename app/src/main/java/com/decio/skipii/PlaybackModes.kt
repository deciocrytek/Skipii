package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Playback speed (Derek, 2026-10-13): 0.5–3× in 0.05 steps, from the speed panel under the
 * video. Normally a speed belongs to one video and the next one starts at 1×. With "Remember
 * for every video" it is saved and every video starts at it, until it is set back to 1×,
 * which ends remembering. Applied in the service (`SpeedKeeper`), so a queue advancing in
 * the background follows it too. Pitch is never changed.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 3f
    const val STEP = 0.05f
    /** Holding a finger on the video's right side plays at this, until it lifts. */
    const val HOLD = 2f
    /** Steps from [MIN] to [MAX]. */
    val LAST_STEP = ((MAX - MIN) / STEP).roundToInt()

    private const val KEY_REMEMBER = "speed_remember"
    private const val KEY_SPEED = "speed_saved"
    private var prefs: SharedPreferences? = null

    var remember by mutableStateOf(false)
        private set
    /** The remembered speed (meaningful while [remember]). */
    var saved by mutableFloatStateOf(1f)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        remember = prefs?.getBoolean(KEY_REMEMBER, false) == true
        saved = snap(prefs?.getFloat(KEY_SPEED, 1f) ?: 1f)
    }

    /** What a video that has just become current starts at. */
    val forNewVideo: Float get() = if (remember) saved else 1f

    fun setRemember(on: Boolean, current: Float) {
        remember = on
        saved = snap(current)
        persist()
    }

    fun save(speed: Float) {
        saved = snap(speed)
        persist()
    }

    private fun persist() {
        prefs?.edit()?.putBoolean(KEY_REMEMBER, remember)?.putFloat(KEY_SPEED, saved)?.apply()
    }

    fun step(speed: Float) = ((speed - MIN) / STEP).roundToInt().coerceIn(0, LAST_STEP)
    /** Exact hundredths (1.4, not 1.4000001), so speeds compare and store cleanly. */
    fun atStep(step: Int) = ((MIN + step * STEP) * 100f).roundToInt() / 100f
    fun snap(speed: Float) = atStep(step(speed))
    fun isNormal(speed: Float) = abs(speed - 1f) < STEP / 2

    /** "1", "1.5", "1.75" (the × is drawn separately where it's styled). */
    fun label(speed: Float): String =
        String.format(Locale.US, "%.2f", snap(speed)).trimEnd('0').trimEnd('.')
}

/**
 * Audio only (Derek, 2026-10-13): one switch for the whole app, kept until turned off. The
 * player's video track is disabled (as in the background), so no video is fetched or
 * decoded; the open player shows only its playback controls and can't go fullscreen.
 */
object AudioOnly {
    private const val KEY = "audio_only"
    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean(KEY, false) == true
    }

    fun set(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }
}

/**
 * Where the open portrait player's controls live (Derek, 2026-10-31; a choice in the account sheet):
 * in the playback pill the tab bar turns into (PlayerBar.kt, the default since 0.5.34), or on the
 * video itself (VideoContent's overlay, its bottom edge a playback head with a knob to scrub). On the
 * video, the tab bar steps down out of the open player's way. Fullscreen has its overlay either way.
 */
object PlayerControls {
    private const val KEY = "controls_in_bar"
    private var prefs: SharedPreferences? = null

    /** True: the playback pill; false: on the video. */
    var inBar by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        inBar = prefs?.getBoolean(KEY, true) != false
    }

    fun set(bar: Boolean) {
        inBar = bar
        prefs?.edit()?.putBoolean(KEY, bar)?.apply()
    }
}
