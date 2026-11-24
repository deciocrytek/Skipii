package com.decio.skipii

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.C
import androidx.media3.common.Player
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.concurrent.Executors

/**
 * Home-screen widgets (0.6.0; Derek approved section 8 of docs/design/skipii-2.html, layout per Neon's spec). RemoteViews,
 * no new library. The player widget: what's playing (thumbnail, title, channel · time, the progress with SponsorBlock's
 * marks, −step · play · +step), then Continue watching from [RecentVideos] (works signed out and offline). Up next: the
 * queue's next video. Search: opens the Search tab. Colours: the wallpaper's on Android 12+ (values-v31).
 *
 * While the service runs, [WidgetBridge] feeds them from the player; otherwise they show the saved session paused.
 */
object WidgetUpdater {
    private const val TAG = "Skipii"
    /** The service's player while it runs (main looper only). */
    @Volatile internal var player: Player? = null
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Decoded thumbnails by URL, so a 15 s refresh never decodes (or sends) one again. */
    private val thumbs = object : LinkedHashMap<String, Bitmap>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    const val ACTION_BACK = "com.skipii.widget.BACK"
    const val ACTION_PLAY = "com.skipii.widget.PLAY"
    const val ACTION_FORWARD = "com.skipii.widget.FORWARD"
    const val ACTION_NEXT = "com.skipii.widget.NEXT"
    const val EXTRA_OPEN_SEARCH = "com.skipii.widget.SEARCH"
    internal const val EXTRA_PLAY = "com.skipii.widget.PLAY_ON_OPEN"

    /** The play button was pressed with no player running: the shell plays the restored video once it opens it. */
    @Volatile var playOnOpen = false

    fun takePlay(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PLAY, false) != true) return
        intent.removeExtra(EXTRA_PLAY)
        playOnOpen = true
    }

    /** The app is on screen (it shows the same things): the 15 s progress refresh rests. Set by the shell. */
    @Volatile var appOnScreen = false

    /** [update]s asked for within this of each other become one (a seek sends a burst of player events). */
    private const val CoalesceMs = 150L
    private var pending: Runnable? = null

    private class Snapshot(val item: VideoItem?, val playing: Boolean, val positionMs: Long, val durationMs: Long, val next: VideoItem?)

    /** What to show, read on the main thread (the player lives there). */
    private fun snapshot(): Snapshot {
        val p = player
        if (p != null && p.mediaItemCount > 0) {
            fun item(i: Int) = p.getMediaItemAt(i).let { m ->
                VideoItem(
                    m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty(), null,
                    m.mediaMetadata.artworkUri?.toString() ?: "https://i.ytimg.com/vi/${m.mediaId}/hqdefault.jpg", null,
                    (m.mediaMetadata.durationMs ?: 0L) / 1000L, -1, null,
                )
            }
            val i = p.currentMediaItemIndex
            val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            return Snapshot(item(i), p.isPlaying, p.currentPosition, duration, if (i + 1 < p.mediaItemCount) item(i + 1) else null)
        }
        val saved = SavedSession.load()
        val current = saved.firstOrNull()?.let { VideoItem(it.id, it.title, it.channel, null, it.thumbnail, null, it.durationSec, -1, null) }
        val next = saved.getOrNull(1)?.let { VideoItem(it.id, it.title, it.channel, null, it.thumbnail, null, it.durationSec, -1, null) }
        return Snapshot(current, false, current?.let { ResumeStore.startPosition(it.id) } ?: 0L, (current?.durationSec ?: 0L) * 1000L, next)
    }

    private fun ids(context: Context, cls: Class<*>): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls)) }.getOrDefault(IntArray(0))

    /** Every widget redrawn, a moment later so a burst of player events is one redraw. Main thread. */
    fun update(context: Context) {
        val app = context.applicationContext
        pending?.let(main::removeCallbacks)
        pending = Runnable { pending = null; updateNow(app, done = null) }.also { main.postDelayed(it, CoalesceMs) }
    }

    /**
     * Every widget redrawn (thumbnails from the cache once decoded). Always a FULL update: partial updates with a new
     * picture pile every picture up in the views the launcher keeps, past the binder's 1 MB. [done] runs once sent.
     */
    fun updateNow(context: Context, done: (() -> Unit)?) {
        val app = context.applicationContext
        val player = ids(app, PlayerWidget::class.java)
        val upNext = ids(app, UpNextWidget::class.java)
        val search = ids(app, SearchWidget::class.java)
        if (player.isEmpty() && upNext.isEmpty() && search.isEmpty()) { done?.invoke(); return }
        val snap = snapshot()
        val continuing = RecentVideos.unfinished().filter { it.id != snap.item?.id }.take(3)
            .map { it to ResumeStore.remainingMs(it.id) }
        val segments = snap.item?.id?.let { SponsorBlock.cached(it) }.orEmpty()
        // A player with nothing in it (Android restarted the service on its own) can't play the saved video.
        val running = (this.player?.mediaItemCount ?: 0) > 0
        work.execute {
            try {
                val manager = AppWidgetManager.getInstance(app)
                if (player.isNotEmpty()) manager.updateAppWidget(player, playerViews(app, snap, continuing, segments, running))
                if (upNext.isNotEmpty()) manager.updateAppWidget(upNext, upNextViews(app, snap, running))
                if (search.isNotEmpty()) manager.updateAppWidget(search, searchViews(app))
            } catch (x: Exception) {
                Log.w(TAG, "widgets: update failed", x)
            } finally {
                done?.invoke()
            }
        }
    }

    private fun meta(s: Snapshot): String = listOfNotNull(
        s.item?.channel?.ifBlank { null },
        if (s.durationMs > 0) "${formatDuration(s.positionMs)} / ${formatDuration(s.durationMs)}" else null,
    ).joinDots()

    private fun playerViews(context: Context, s: Snapshot, continuing: List<Pair<VideoItem, Long?>>, segments: List<SponsorBlock.Segment>, running: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_player)
        val item = s.item
        val shown = if (item != null) View.VISIBLE else View.GONE
        listOf(R.id.w_now, R.id.w_progress, R.id.w_controls).forEach { v.setViewVisibility(it, shown) }
        v.setViewVisibility(R.id.w_empty, if (item == null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.w_state, str(if (s.playing) R.string.widget_playing else R.string.widget_paused))
        v.setOnClickPendingIntent(R.id.w_root, openApp(context, player = item != null))
        if (item != null) {
            v.setTextViewText(R.id.w_title, item.title)
            v.setTextViewText(R.id.w_meta, meta(s))
            thumb(context, Downloads.withLocalThumb(item).thumbnail, 256, 144, 14f)?.let { v.setImageViewBitmap(R.id.w_thumb, it) }
            v.setImageViewBitmap(R.id.w_progress, progressBitmap(context, s, segments))
            v.setImageViewResource(R.id.w_play_icon, if (s.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            v.setContentDescription(R.id.w_play, str(if (s.playing) R.string.bar_pause else R.string.bar_play))
            val n = SeekStep.seconds.toString()
            v.setTextViewText(R.id.w_back_n, n)
            v.setTextViewText(R.id.w_fwd_n, n)
            if (running) {
                v.setOnClickPendingIntent(R.id.w_back, control(context, ACTION_BACK, 1))
                v.setOnClickPendingIntent(R.id.w_play, control(context, ACTION_PLAY, 2))
                v.setOnClickPendingIntent(R.id.w_fwd, control(context, ACTION_FORWARD, 3))
            } else {
                // Nothing running: a broadcast can't open the app (Android 12+ blocks activity starts from the
                // background), so the buttons open it themselves; play also starts the restored video.
                val open = openApp(context, player = true)
                v.setOnClickPendingIntent(R.id.w_back, open)
                v.setOnClickPendingIntent(R.id.w_fwd, open)
                v.setOnClickPendingIntent(R.id.w_play, PendingIntent.getActivity(
                    context, 42,
                    Intent(context, MainActivity::class.java).putExtra(LiveControls.EXTRA_OPEN, true).putExtra(EXTRA_PLAY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }
        }
        val slots = listOf(Triple(R.id.w_c0, R.id.w_c0_thumb, R.id.w_c0_left), Triple(R.id.w_c1, R.id.w_c1_thumb, R.id.w_c1_left), Triple(R.id.w_c2, R.id.w_c2_thumb, R.id.w_c2_left))
        v.setViewVisibility(R.id.w_cont_label, if (continuing.isEmpty()) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.w_cont, if (continuing.isEmpty()) View.GONE else View.VISIBLE)
        slots.forEachIndexed { i, (slot, image, left) ->
            val c = continuing.getOrNull(i)
            v.setViewVisibility(slot, if (c == null) View.INVISIBLE else View.VISIBLE)
            if (c != null) {
                thumb(context, Downloads.withLocalThumb(c.first).thumbnail, 160, 90, 11f)?.let { v.setImageViewBitmap(image, it) }
                v.setTextViewText(left, c.second?.let(::remainingLabel).orEmpty())
                v.setContentDescription(slot, c.first.title)
                v.setOnClickPendingIntent(slot, openVideo(context, c.first, 10 + i))
            }
        }
        return v
    }

    private fun upNextViews(context: Context, s: Snapshot, running: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_upnext)
        val next = s.next
        if (next == null) {
            v.setTextViewText(R.id.w_title, str(R.string.widget_queue_empty))
            v.setImageViewBitmap(R.id.w_thumb, null)
            v.setOnClickPendingIntent(R.id.w_root, openApp(context, player = false))
            return v
        }
        v.setTextViewText(R.id.w_title, next.title)
        thumb(context, Downloads.withLocalThumb(next).thumbnail, 256, 144, 12f)?.let { v.setImageViewBitmap(R.id.w_thumb, it) }
        // With the service up, the player moves on; otherwise the app opens on that video.
        v.setOnClickPendingIntent(R.id.w_root, if (running) control(context, ACTION_NEXT, 4) else openVideo(context, next, 20))
        return v
    }

    private fun searchViews(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_search)
        v.setOnClickPendingIntent(
            R.id.w_root,
            PendingIntent.getActivity(
                context, 30,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_SEARCH, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return v
    }

    private fun remainingLabel(ms: Long): String {
        val parts = compactDurationFromMs(ms) ?: return ""
        return when {
            parts.underOneMinute -> str(R.string.ui_remaining_under_min)
            parts.hours > 0 && parts.minutes > 0 -> str(R.string.ui_remaining_hr_min, parts.hours.toString(), parts.minutes.toString())
            parts.hours > 0 -> str(R.string.ui_remaining_hr, parts.hours.toString())
            else -> str(R.string.ui_remaining_min, parts.minutes.toString())
        }
    }

    private fun openApp(context: Context, player: Boolean): PendingIntent = PendingIntent.getActivity(
        context, if (player) 40 else 41,
        Intent(context, MainActivity::class.java).apply { if (player) putExtra(LiveControls.EXTRA_OPEN, true) }
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openVideo(context: Context, item: VideoItem, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("skipii://widget/$code/${item.id}"))
            .putExtra(NewVideos.EXTRA_ID, item.id)
            .putExtra(NewVideos.EXTRA_TITLE, item.title)
            .putExtra(NewVideos.EXTRA_CHANNEL, item.channel)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun control(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, WidgetControlsReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A thumbnail with round corners (so ARGB), small: every picture of a widget goes through ONE binder call of at
     * most 1 MB (256×144 + three 160×90 + the line ≈ 330 KB). Cached by URL.
     */
    private fun thumb(context: Context, url: String?, w: Int, h: Int, radiusDp: Float): Bitmap? {
        val src = url ?: return null
        synchronized(thumbs) { thumbs["$src@$w"] }?.let { return it }
        // Coil reports a failed load as an ErrorResult (it doesn't throw): logged, and the slot keeps its tile.
        val result = kotlinx.coroutines.runBlocking {
            context.imageLoader.execute(ImageRequest.Builder(context).data(src).size(w, h).allowHardware(false).build())
        }
        if (result is coil.request.ErrorResult) { Log.w(TAG, "widgets: thumbnail ${src.takeLast(40)} not loaded (${result.throwable.message})"); return null }
        val loaded = ((result as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(w.toFloat() / loaded.width, h.toFloat() / loaded.height)
        val shader = BitmapShader(loaded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - loaded.width * scale) / 2f, (h - loaded.height * scale) / 2f)
            })
        }
        val r = radiusDp * context.resources.displayMetrics.density * (w / (104f * context.resources.displayMetrics.density)).coerceAtMost(1.6f)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        synchronized(thumbs) { thumbs["$src@$w"] = out }
        return out
    }

    /** The progress line as a picture (RemoteViews' ProgressBar can't draw SponsorBlock's marks): track, marks, played. */
    private fun progressBitmap(context: Context, s: Snapshot, segments: List<SponsorBlock.Segment>): Bitmap {
        val w = 400
        val h = 8
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = h / 2f
        paint.color = context.getColor(R.color.widget_tile)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
        if (s.durationMs > 0) {
            if (SponsorBlock.enabled) segments.filter { it.action != SponsorBlock.Action.Off }.forEach { seg ->
                paint.color = android.graphics.Color.argb(255, (seg.category.color.red * 255).toInt(), (seg.category.color.green * 255).toInt(), (seg.category.color.blue * 255).toInt())
                canvas.drawRoundRect(RectF(w * seg.startMs.toFloat() / s.durationMs, 0f, w * seg.endMs.toFloat() / s.durationMs, h.toFloat()), r, r, paint)
            }
            paint.color = context.getColor(R.color.widget_primary)
            val played = (w * (s.positionMs.toFloat() / s.durationMs)).coerceIn(0f, w.toFloat())
            if (played > 0f) canvas.drawRoundRect(RectF(0f, 0f, played.coerceAtLeast(h.toFloat()), h.toFloat()), r, r, paint)
        }
        return bmp
    }
}

/** The system asked for a redraw (added, resized, the 30 min period): kept alive until it's sent ([goAsync]). */
private fun redrawFromSystem(receiver: BroadcastReceiver, context: Context) {
    val pending = receiver.goAsync()
    WidgetUpdater.updateNow(context) { pending.finish() }
}

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redrawFromSystem(this, context)
}

class UpNextWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redrawFromSystem(this, context)
}

class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redrawFromSystem(this, context)
}

/**
 * The widget's buttons while the service runs. Buttons drawn then can outlive it (Android killed the app without the
 * service's goodbye): with no player the app is opened if Android allows it (play also plays), and the widgets are
 * redrawn at once with buttons that open the app themselves, so the next tap works.
 */
class WidgetControlsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = WidgetUpdater.player?.takeIf { it.mediaItemCount > 0 }
        if (p == null) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).putExtra(LiveControls.EXTRA_OPEN, true)
                        .apply { if (intent.action == WidgetUpdater.ACTION_PLAY) putExtra(WidgetUpdater.EXTRA_PLAY, true) }
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }.onFailure { Log.w("Skipii", "widgets: couldn't open the app from a stale button (${it.message})") }
            val pending = goAsync()
            WidgetUpdater.updateNow(context) { pending.finish() }
            return
        }
        when (intent.action) {
            WidgetUpdater.ACTION_BACK -> p.seekTo((p.currentPosition - SeekStep.ms).coerceAtLeast(0))
            WidgetUpdater.ACTION_FORWARD -> p.seekTo(p.currentPosition + SeekStep.ms)
            WidgetUpdater.ACTION_PLAY -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            WidgetUpdater.ACTION_NEXT -> if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.play() }
        }
        WidgetUpdater.update(context)
    }
}

/**
 * The service's link to the widgets: redraws them as playback changes, and the progress every 15 s while playing with the
 * screen on (nothing at all while paused or with the screen off).
 */
class WidgetBridge(private val context: Context, private val player: Player) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val tick = object : Runnable {
        override fun run() {
            // Not while the app itself is on screen (it shows the same), nor with the screen off.
            if (player.isPlaying && power?.isInteractive != false && !WidgetUpdater.appOnScreen) WidgetUpdater.updateNow(context, done = null)
            if (player.isPlaying) main.postDelayed(this, TickMs)
        }
    }

    init {
        WidgetUpdater.player = player
        player.addListener(this)
        WidgetUpdater.updateNow(context, done = null)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) WidgetUpdater.update(context)
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            main.removeCallbacks(tick)
            if (player.isPlaying) main.postDelayed(tick, TickMs)
        }
    }

    fun release() {
        main.removeCallbacks(tick)
        player.removeListener(this)
        if (WidgetUpdater.player === player) WidgetUpdater.player = null
        // At once: the service is going, and the buttons must now open the app instead.
        WidgetUpdater.updateNow(context, done = null)
    }

    private companion object {
        const val TickMs = 15_000L
    }
}
