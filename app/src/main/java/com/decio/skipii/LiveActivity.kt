package com.decio.skipii

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import okhttp3.Request
import java.util.concurrent.Executor

/**
 * Playback controls in a live activity while Skipii is off screen (2026-11-05, Derek; a switch in Settings ›
 * Playback, off by default). Android 16 QPR1 promotes an ongoing notification that asks for it into a Live
 * Update: a chip at the left of the status bar, the card first in the shade and on the lock screen. Pixel
 * shows all three; One UI 8 shows the chip and the card (its Now Bar keeps the media session, as it does for
 * every Media3 app; third-party Live Updates reach the Now Bar only with Samsung's developer option "Live
 * notifications for all apps").
 *
 * The media notification can't be it: MediaStyle isn't a style Android promotes, and without it Pixel's
 * media player and One UI's Now Bar would lose the session. So [LiveActivity] posts a second notification,
 * and only where it's promoted: a phone that shows it as a plain notification (below Android 16 QPR1, Live
 * Updates turned off for Skipii, an OEM that keeps them to its own apps) would just get a copy of the media
 * controls, so it's taken down again (see [LiveActivity.checkPromoted]).
 */
object LiveControls {
    private const val KEY = "live_controls"
    internal const val CHANNEL = "live_controls"
    internal const val EXTRA_OPEN = "com.skipii.live.open"
    private var prefs: SharedPreferences? = null

    var on by mutableStateOf(false)
        private set

    /** The last live activity was posted but the phone didn't promote it (Settings says so). */
    var refusedBySystem by mutableStateOf(false)
        internal set

    /**
     * The phone has Live Updates: Android 16 QPR1 (36.1) on, or an earlier 16 build that already lets apps
     * promote (an OEM's). Plain 16.0 has the API but says no (the emulator's image, 2026-11-05).
     */
    fun supported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (Build.VERSION.SDK_INT_FULL >= SDK_36_1) return true
        return context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
    }

    /** The app's window is on screen (MainActivity's ON_START / ON_STOP). */
    var onScreen = false
        private set

    /** The service's live activity, while there is one; it hears [setOnScreen] and the notification's buttons. */
    internal var target: LiveActivity? = null

    /** A tap on the live activity: the shell opens the player on the current video and clears this. */
    var openPlayer by mutableStateOf(false)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        on = prefs?.getBoolean(KEY, false) == true
    }

    fun set(value: Boolean) {
        on = value
        if (value) refusedBySystem = false
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }

    fun setOnScreen(visible: Boolean) {
        if (onScreen == visible) return
        onScreen = visible
        target?.update()
    }

    /** Notifications allowed and Live Updates not turned off for Skipii in Android's settings. */
    fun canPromote(context: Context): Boolean {
        if (!supported(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.canPostPromotedNotifications()) return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Android's page for Skipii's Live Updates (the app's notification page if it has none, or notifications are off). */
    fun openSettings(context: Context) {
        val appOff = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        val action = if (!appOff && Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= SDK_36_1) Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
        val intent = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "live activity: no notification settings page (${it.message})") }
    }

    /** The live activity's tap carries this; the shell opens the player. Cleared so a re-created window doesn't repeat it. */
    fun takeOpen(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN, false) != true) return
        intent.removeExtra(EXTRA_OPEN)
        openPlayer = true
    }

    private const val TAG = "Skipii"
    /** Build.VERSION_CODES_FULL.BAKLAVA_1, named in the SDK from 36.1. */
    private const val SDK_36_1 = 3_600_001
}

/** The live activity's buttons (and its dismissal) arrive here, for the service's [LiveActivity]. */
class LiveControlsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveControls.target?.command(intent.action)
    }
}

/**
 * The live activity itself, owned by [PlaybackService]. Shown while the app is off screen once the video has
 * been playing there (leaving the app on a paused video, or on Shorts, shows nothing); kept through pauses until
 * the app is back, the video ends or the queue is cleared. The card: thumbnail, title, channel, the position as
 * a progress bar (SponsorBlock segments in their colours, as on the player's bar), −step · play · +step. The chip:
 * the time left counting down (wall time, so it follows the speed); paused, the pause glyph alone.
 */
@OptIn(UnstableApi::class)
class LiveActivity(
    private val service: Service,
    private val player: Player,
    private val io: Executor,
) : Player.Listener {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The video played while off screen: from then on the live activity shows (paused too). */
    private var armed = false
    private var shown = false
    /** Swiped away: not posted again until the app has been back on screen (Android's guidance). */
    private var dismissed = false
    /** Posted but not promoted: a plain copy of the media controls, so not posted again (see [checkPromoted]). */
    private var refused = false
    private var thumbId: String? = null
    private var thumb: Bitmap? = null
    private var postQueued = false

    init {
        player.addListener(this)
        LiveControls.target = this
    }

    fun release() {
        player.removeListener(this)
        main.removeCallbacksAndMessages(null)
        cancel()
        if (LiveControls.target === this) LiveControls.target = null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
        ) update()
    }

    fun update() {
        if (LiveControls.onScreen) {
            armed = false
            dismissed = false
            refused = false
            cancel()
            return
        }
        if (player.playWhenReady) armed = true
        val wanted = LiveControls.on && armed && !dismissed && !refused &&
            player.currentMediaItem != null && player.playbackState != Player.STATE_ENDED
        val allowed = wanted && LiveControls.canPromote(service)
        if (wanted && !allowed && !saidNotAllowed) {
            saidNotAllowed = true
            Log.i(TAG, "live activity: not allowed (Android ${Build.VERSION.SDK_INT}, notifications or Live Updates off for Skipii)")
        }
        if (!allowed) cancel() else schedulePost()
    }

    private var saidNotAllowed = false

    fun command(action: String?) {
        when (action) {
            ACTION_BACK -> player.seekBack()
            ACTION_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            ACTION_FORWARD -> player.seekForward()
            ACTION_DISMISSED -> {
                Log.i(TAG, "live activity: dismissed; back once the app has been on screen")
                dismissed = true
                shown = false
                main.removeCallbacks(refresh)
            }
        }
    }

    /** Events come in bursts (a seek is a discontinuity, a state change and a play change): one post for them all. */
    private fun schedulePost() {
        if (postQueued) return
        postQueued = true
        main.postDelayed({
            postQueued = false
            if (!LiveControls.onScreen && !dismissed && !refused && LiveControls.target === this) post()
        }, 120)
    }

    /** The progress bar isn't driven by the system: refreshed every few seconds while playing. */
    private val refresh = Runnable { update() }

    private fun post() {
        if (Build.VERSION.SDK_INT < 36) return
        val item = player.currentMediaItem ?: return
        loadThumb(item.mediaId)
        ensureChannel()
        val notification = build(item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString(), item.mediaId)
        val first = !shown
        runCatching { manager.notify(ID, notification) }
            .onSuccess {
                shown = true
                if (first) {
                    Log.i(TAG, "live activity: posted for ${item.mediaId}")
                    main.postDelayed(::checkPromoted, 1_000)
                }
            }
            .onFailure { Log.w(TAG, "live activity: couldn't post (${it.message})") }
        main.removeCallbacks(refresh)
        if (player.isPlaying) main.postDelayed(refresh, REFRESH_MS)
    }

    @RequiresApi(36)
    private fun build(title: String, channel: String?, videoId: String): Notification {
        val playing = player.playWhenReady
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = player.currentPosition.coerceAtLeast(0)
        val step = (player.seekBackIncrement / 1000).toInt()
        val open = Intent(service, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(LiveControls.EXTRA_OPEN, true)
        return Notification.Builder(service, LiveControls.CHANNEL)
            // The chip shows the small icon: Skipii's glyph while playing, the pause glyph while paused.
            .setSmallIcon(if (playing) R.drawable.ic_stat_skipii else androidx.media3.session.R.drawable.media3_icon_pause)
            .setColor(service.getColor(R.color.brand))
            .setContentTitle(title.ifEmpty { str(R.string.app_name) })
            .apply { if (!channel.isNullOrEmpty()) setContentText(channel) }
            .apply { thumb?.takeIf { thumbId == videoId }?.let { setLargeIcon(Icon.createWithBitmap(it)) } }
            .setStyle(progressStyle(videoId, duration, position))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .apply {
                // The chip counts the time left down by itself; wall time, so a 2× video counts at 2×.
                if (player.isPlaying && duration != null) {
                    val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
                    setWhen(System.currentTimeMillis() + ((duration - position).coerceAtLeast(0) / speed).toLong())
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .setContentIntent(
                PendingIntent.getActivity(service, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setDeleteIntent(broadcast(ACTION_DISMISSED))
            .addAction(action(skipBackIcon(step), str(R.string.app_live_back, step.toString()), ACTION_BACK))
            .addAction(
                action(
                    if (playing) androidx.media3.session.R.drawable.media3_icon_pause else androidx.media3.session.R.drawable.media3_icon_play,
                    str(if (playing) R.string.bar_pause else R.string.bar_play),
                    ACTION_PLAY_PAUSE,
                ),
            )
            .addAction(action(skipForwardIcon(step), str(R.string.app_live_forward, step.toString()), ACTION_FORWARD))
            .build()
    }

    /**
     * The position as a progress bar, in seconds. SponsorBlock's segments (the ones not turned off) are drawn in
     * their colours, the rest in Skipii's; Android limits the pieces, so a video with too many gets one plain bar.
     */
    @RequiresApi(36)
    private fun progressStyle(videoId: String, duration: Long?, position: Long): Notification.ProgressStyle {
        val style = Notification.ProgressStyle().setStyledByProgress(true)
        if (duration == null) return style.setProgressIndeterminate(true)
        val total = (duration / 1000).toInt().coerceAtLeast(1)
        val pieces = mutableListOf<Notification.ProgressStyle.Segment>()
        var at = 0
        SponsorBlock.cached(videoId).orEmpty()
            .filter { it.action != SponsorBlock.Action.Off }
            .sortedBy { it.startMs }
            .forEach { s ->
                val start = (s.startMs / 1000).toInt().coerceIn(at, total)
                val end = (s.endMs / 1000).toInt().coerceIn(start, total)
                if (end - start < 1) return@forEach
                if (start > at) pieces += Notification.ProgressStyle.Segment(start - at)
                pieces += Notification.ProgressStyle.Segment(end - start).setColor(s.category.color.toArgb())
                at = end
            }
        if (at < total) pieces += Notification.ProgressStyle.Segment(total - at)
        val segments = if (pieces.size <= MAX_SEGMENTS) pieces else listOf(Notification.ProgressStyle.Segment(total))
        return style.setProgressSegments(segments).setProgress((position / 1000).toInt().coerceIn(0, total))
    }

    /**
     * Some phones show a notification that asked to be promoted as a plain one (Android 16 before QPR1, an OEM
     * that keeps Live Updates to its own apps): then it's only a second copy of the media controls, so it goes.
     */
    private fun checkPromoted() {
        if (!shown || Build.VERSION.SDK_INT < 36) return
        val posted = manager.activeNotifications.firstOrNull { it.id == ID } ?: return
        if (posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) {
            LiveControls.refusedBySystem = false
            return
        }
        Log.i(TAG, "live activity: not promoted by the system; the media controls stay the only ones")
        refused = true
        LiveControls.refusedBySystem = true
        cancel()
    }

    private fun cancel() {
        main.removeCallbacks(refresh)
        if (!shown) return
        shown = false
        manager.cancel(ID)
        Log.i(TAG, "live activity: removed")
    }

    private fun loadThumb(id: String) {
        if (thumbId == id) return
        thumbId = id
        thumb = null
        io.execute {
            val bitmap = runCatching { fetchSquare("https://i.ytimg.com/vi/$id/mqdefault.jpg") }.getOrNull()
            main.post {
                if (thumbId != id || bitmap == null) return@post
                thumb = bitmap
                if (shown) schedulePost()
            }
        }
    }

    /** The thumbnail's middle square (the large icon is shown square; reposted every few seconds, so kept small). */
    private fun fetchSquare(url: String): Bitmap? {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        val full = Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.byteStream()?.use(BitmapFactory::decodeStream)
        } ?: return null
        val side = minOf(full.width, full.height)
        return Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
    }

    private fun ensureChannel() {
        // Created again every time: that only renames it (the app's language); the importance the person chose stays.
        manager.createNotificationChannel(
            NotificationChannel(LiveControls.CHANNEL, str(R.string.app_live_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = str(R.string.app_live_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun broadcast(action: String): PendingIntent = PendingIntent.getBroadcast(
        service, action.hashCode(),
        Intent(service, LiveControlsReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(icon: Int, label: String, action: String) =
        Notification.Action.Builder(Icon.createWithResource(service, icon), label, broadcast(action)).build()

    private fun skipBackIcon(step: Int) = when (step) {
        5 -> androidx.media3.session.R.drawable.media3_icon_skip_back_5
        10 -> androidx.media3.session.R.drawable.media3_icon_skip_back_10
        15 -> androidx.media3.session.R.drawable.media3_icon_skip_back_15
        30 -> androidx.media3.session.R.drawable.media3_icon_skip_back_30
        else -> androidx.media3.session.R.drawable.media3_icon_skip_back
    }

    private fun skipForwardIcon(step: Int) = when (step) {
        5 -> androidx.media3.session.R.drawable.media3_icon_skip_forward_5
        10 -> androidx.media3.session.R.drawable.media3_icon_skip_forward_10
        15 -> androidx.media3.session.R.drawable.media3_icon_skip_forward_15
        30 -> androidx.media3.session.R.drawable.media3_icon_skip_forward_30
        else -> androidx.media3.session.R.drawable.media3_icon_skip_forward
    }

    private companion object {
        const val TAG = "Skipii"
        const val ID = 0x5C1B
        const val REFRESH_MS = 5_000L
        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, named in the SDK from 36.1 (Skipii compiles against 36). */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        /** Android draws at most this many pieces in a ProgressStyle bar. */
        const val MAX_SEGMENTS = 15
        const val ACTION_BACK = "com.skipii.live.BACK"
        const val ACTION_PLAY_PAUSE = "com.skipii.live.PLAY_PAUSE"
        const val ACTION_FORWARD = "com.skipii.live.FORWARD"
        const val ACTION_DISMISSED = "com.skipii.live.DISMISSED"
    }
}
