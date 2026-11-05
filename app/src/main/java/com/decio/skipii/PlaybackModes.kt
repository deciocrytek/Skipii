package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    private const val KEY_MUSIC = "speed_music_normal"
    private var prefs: SharedPreferences? = null

    var remember by mutableStateOf(false)
        private set
    /** The remembered speed (meaningful while [remember]). */
    var saved by mutableFloatStateOf(1f)
        private set
    /**
     * Music plays at 1× even while a speed is remembered (#35), and the next video that isn't
     * music is back at the remembered speed. On unless turned off in Settings › Playback.
     */
    var musicAtNormal by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        remember = prefs?.getBoolean(KEY_REMEMBER, false) == true
        saved = snap(prefs?.getFloat(KEY_SPEED, 1f) ?: 1f)
        musicAtNormal = prefs?.getBoolean(KEY_MUSIC, true) != false
    }

    /** What a video that has just become current starts at; [music] from [YouTube.isMusic]. */
    fun forNewVideo(music: Boolean): Float = if (remember && !(music && musicAtNormal)) saved else 1f

    fun chooseMusicAtNormal(on: Boolean) {
        musicAtNormal = on
        prefs?.edit()?.putBoolean(KEY_MUSIC, on)?.apply()
    }

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

/**
 * How far a double tap on the video and the −/+ buttons jump (#36): 5 to 30 seconds in steps of 5, picked in
 * Settings › Playback. Every −/+ button draws it (`seekIcon`). The media session's own back / forward
 * (headphones, the notification) takes it when the service starts.
 */
object SeekStep {
    val choices = listOf(5, 10, 15, 20, 25, 30)
    private const val KEY = "seek_step"
    private var prefs: SharedPreferences? = null

    var seconds by mutableIntStateOf(10)
        private set
    val ms: Long get() = seconds * 1000L

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        seconds = prefs?.getInt(KEY, 10)?.takeIf { it in choices } ?: 10
    }

    fun set(value: Int) {
        if (value !in choices) return
        seconds = value
        prefs?.edit()?.putInt(KEY, value)?.apply()
    }
}

/**
 * Picking a chapter leaves the chapter list open (#46), so you can go through them one after another;
 * it closes on a tap outside it, ✕ or Back. Off (YouTube's way): the list folds once a chapter is picked.
 */
object ChaptersStayOpen {
    private const val KEY = "chapters_stay_open"
    private var prefs: SharedPreferences? = null

    var on by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        on = prefs?.getBoolean(KEY, false) == true
    }

    fun set(value: Boolean) {
        on = value
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }
}

/**
 * Picture-in-picture by itself (#42): leaving the app (Home, Recents, another app) while a video plays
 * turns it into the floating window. Off by default (Derek): background play is audio only then.
 */
object AutoPip {
    private const val KEY = "auto_pip"
    private var prefs: SharedPreferences? = null

    var on by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        on = prefs?.getBoolean(KEY, false) == true
    }

    fun set(value: Boolean) {
        on = value
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }
}

/**
 * Zoom to fill in fullscreen (2026-11-02, Derek; asked for by many users), as YouTube's app does it:
 * pinch out on the fullscreen video and it fills the screen (the edges past the screen are cropped),
 * pinch in and the whole picture is back with black bars. A pinch only changes that video
 * ([PlayerState.zoomedToFill]); this setting (Settings › Playback) is where every video starts.
 */
object FullscreenZoom {
    private const val KEY = "zoom_fill"
    private var prefs: SharedPreferences? = null

    /** True: every video fills the screen in fullscreen until pinched back. */
    var fill by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        fill = prefs?.getBoolean(KEY, false) == true
    }

    fun set(on: Boolean) {
        fill = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }
}
