package com.decio.skipii

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Decides the activity's orientation, YouTube-style:
 *  - Only the open player may rotate; tabs and the mini-player stay portrait.
 *  - With the player open, it follows the phone (and the system rotation lock), but never
 *    upside down: USER, not FULL_USER, which allowed reverse portrait (Derek, 2026-10-10).
 *  - The fullscreen button forces landscape even while the phone is upright, and lets go
 *    once the phone is physically turned, so turning it back later exits naturally. The
 *    exit button does the mirror image.
 *  - Split screen (any multi-window mode) gets a pane, not the phone: locking the orientation
 *    there only letterboxed the app into a grey-barred box when the pane was wider than tall
 *    (Derek, 2026-10-23). It asks for nothing and lays out in the pane's own shape, and
 *    fullscreen fills the pane: on by itself in a wide pane (as turning the phone does), and
 *    the button / swipe up / Back toggle it either way.
 *  - A vertical video (taller than wide; GitHub issues #20/#22) goes fullscreen UPRIGHT: the
 *    button / swipe up keep the phone portrait and fill the screen. Turning the phone sideways
 *    then makes it ordinary landscape fullscreen (and turning it back exits, as usual).
 *  - Folding or unfolding (another screen) starts over from how the phone is held: a landscape
 *    forced on the inner screen kept the cover screen sideways in fullscreen while it was held
 *    upright, and only turning the phone sideways and back (with auto-rotate on) let go
 *    (Z Fold 6 user, 2026-11-02).
 *  - A large screen (tablet, open foldable; Adaptive.kt) is never held portrait and is laid out for
 *    whichever way it's turned (the two-pane player, Rail 3). Turning it isn't fullscreen there:
 *    as in split screen, the button / swipe up / Back toggle it, and it carries on across a turn
 *    and from the cover screen to the open one.
 */
@Stable
class OrientationController(private val activity: ComponentActivity) {
    private enum class Forced { Landscape, Portrait }

    private var forced by mutableStateOf<Forced?>(null)
    private var playerOpen = false

    var multiWindow by mutableStateOf(activity.isInMultiWindowMode)
        private set
    /** A tablet or an open foldable's inner screen ([onLargeScreen]). */
    var largeScreen by mutableStateOf(activity.onLargeScreen())
        private set
    /** The pane's or the large screen's own shape decides, not the phone's: fullscreen is a choice there. */
    private val free get() = multiWindow || largeScreen
    /** Split screen: fullscreen chosen with the button; null follows the pane's shape. */
    private var paneFullscreen by mutableStateOf<Boolean?>(null)
    /** Fullscreen in portrait, for a vertical video; dropped once the window turns landscape. */
    private var portraitFullscreen by mutableStateOf(false)

    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val physicallyLandscape = degrees in 60..120 || degrees in 240..300
            val physicallyPortrait = degrees <= 30 || degrees >= 330
            when {
                // Only release a forced landscape when auto-rotate is on; with the rotation
                // lock on, releasing would snap straight back to portrait.
                forced == Forced.Landscape && physicallyLandscape && autoRotateOn() -> forced = null
                forced == Forced.Portrait && physicallyPortrait -> forced = null
            }
        }
    }

    private fun autoRotateOn() =
        Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** Whether the open player shows fullscreen; [landscape] is the window's (pane's) shape. */
    fun showsFullscreen(playerOpen: Boolean, landscape: Boolean): Boolean = when {
        !playerOpen -> false
        // Audio only has no picture to fill the pane with.
        multiWindow -> !AudioOnly.enabled && (paneFullscreen ?: landscape)
        largeScreen -> !AudioOnly.enabled && paneFullscreen == true
        else -> landscape || (portraitFullscreen && !AudioOnly.enabled)
    }

    /** [vertical]: the video is taller than wide, so fullscreen stays portrait. */
    fun enterFullscreen(vertical: Boolean) {
        when {
            free -> paneFullscreen = true
            vertical -> portraitFullscreen = true
            else -> forced = Forced.Landscape
        }
    }

    fun exitFullscreen() {
        when {
            free -> paneFullscreen = false
            portraitFullscreen -> portraitFullscreen = false
            else -> forced = Forced.Portrait
        }
    }

    fun setPlayerOpen(open: Boolean) {
        playerOpen = open
        if (!open) { forced = null; paneFullscreen = null; portraitFullscreen = false }
        apply()
    }

    fun apply() {
        activity.requestedOrientation = when {
            free -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            !playerOpen -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            forced == Forced.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            forced == Forced.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    @Composable
    fun Bind(playerOpen: Boolean, landscape: Boolean) {
        DisposableEffect(Unit) {
            if (listener.canDetectOrientation()) listener.enable()
            val windowListener = Consumer<MultiWindowModeChangedInfo> { info ->
                multiWindow = info.isInMultiWindowMode
                forced = null
            }
            activity.addOnMultiWindowModeChangedListener(windowListener)
            onDispose {
                listener.disable()
                activity.removeOnMultiWindowModeChangedListener(windowListener)
            }
        }
        // A pane that changes shape (the divider dragged) starts over from its new shape. A large
        // screen just turned keeps what was chosen.
        LaunchedEffect(landscape, multiWindow) {
            if (multiWindow || !largeScreen) paneFullscreen = null
            // Turned sideways: from here it's landscape fullscreen, which turning back exits.
            if (landscape || multiWindow) portraitFullscreen = false
        }
        // Another screen (fold / unfold): no forced side. Not the smallest width: on the near-square
        // inner screen that changes with rotation too (the system bars come off it).
        val config = LocalConfiguration.current
        val screen = remember(config.screenWidthDp, config.screenHeightDp, config.densityDpi) { activity.screenSides() }
        // Fullscreen as it was just before the screen changed: the open screen carries it on.
        val wasFullscreen = remember { booleanArrayOf(false) }
        LaunchedEffect(screen) {
            forced = null
            val large = activity.onLargeScreen()
            if (large && !largeScreen) paneFullscreen = if (wasFullscreen[0]) true else null
            largeScreen = large
        }
        SideEffect { wasFullscreen[0] = showsFullscreen(playerOpen, landscape) }
        LaunchedEffect(playerOpen, forced, multiWindow, largeScreen) { setPlayerOpen(playerOpen) }
    }
}

@Composable
fun rememberOrientationController(activity: ComponentActivity) = remember(activity) { OrientationController(activity) }

/** Hides the status and navigation bars while [hidden]; a swipe from the edge peeks them. */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Picture-in-picture (Derek, 2026-10-10): the button beside the quality pill shrinks the app
 * into a floating window that shows only the video; its play/pause come from the media
 * session. Expanding the window brings the player back; closing it pauses, as YouTube does.
 */
@Stable
class PipController(private val activity: ComponentActivity) {
    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set
    val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    /** The video's bounds in the window, so the floating window grows out of it. */
    var videoBounds: Rect? = null

    /** False when the system refused (PiP switched off for the app in Settings). */
    fun enter(videoAspect: Float): Boolean {
        if (!supported) return false
        // Android accepts 1:2.39 to 2.39:1; anything outside it would throw.
        val ratio = videoAspect.coerceIn(0.42f, 2.38f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .apply {
                videoBounds?.let { b -> setSourceRectHint(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())) }
            }
            .build()
        return activity.enterPictureInPictureMode(params)
    }

    @Composable
    fun Bind(onClosed: () -> Unit) {
        DisposableEffect(activity) {
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                active = info.isInPictureInPictureMode
                // Closing the window stops the activity; expanding it doesn't.
                if (!info.isInPictureInPictureMode && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onClosed()
            }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
        }
    }
}

/** The app's [PipController]; null where there's none (previews). */
val LocalPictureInPicture = staticCompositionLocalOf<PipController?> { null }
