package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Where each video was left off. Written by the playback service (so it keeps saving in
 * the background and survives the app being killed), read when a video opens and by the
 * thumbnails' progress bars. Finished videos are forgotten, so they restart from 0.
 */
object ResumeStore {
    private const val MAX_ENTRIES = 500
    /** Closer than this to either end isn't worth resuming. */
    private const val EDGE_MS = 10_000L

    private var prefs: SharedPreferences? = null
    /** id → (positionMs, durationMs), also observable by the UI. */
    val positions = mutableStateMapOf<String, Pair<Long, Long>>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("resume", Context.MODE_PRIVATE).also { prefs = it }
        p.all.forEach { (id, value) ->
            val parts = (value as? String)?.split(',') ?: return@forEach
            val pos = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            positions[id] = pos to dur
        }
    }

    /** Where to start [id], or 0. */
    fun startPosition(id: String): Long = positions[id]?.first ?: 0L

    /** 0..1 watched, or null if never started / finished. For thumbnail bars. */
    fun progress(id: String): Float? {
        val (pos, dur) = positions[id] ?: return null
        return if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
    }

    /** Unwatched time left in ms, or null if not partially watched. */
    fun remainingMs(id: String): Long? {
        val (pos, dur) = positions[id] ?: return null
        return if (dur > 0L) (dur - pos).coerceAtLeast(0L) else null
    }

    fun save(id: String, positionMs: Long, durationMs: Long) {
        if (id.isBlank() || durationMs <= 0 || durationMs == C.TIME_UNSET) return
        if (positionMs < EDGE_MS || positionMs > durationMs - EDGE_MS) {
            // Barely started or essentially finished: nothing to resume.
            if (positions.remove(id) != null) prefs?.edit()?.remove(id)?.apply()
            return
        }
        positions[id] = positionMs to durationMs
        prefs?.edit()?.putString(id, "$positionMs,$durationMs")?.apply()
        if (positions.size > MAX_ENTRIES) trim()
    }

    private fun trim() {
        // Oldest-first isn't tracked; dropping arbitrary extras is fine at this size.
        val extra = positions.keys.take(positions.size - MAX_ENTRIES)
        val edit = prefs?.edit()
        extra.forEach { positions.remove(it); edit?.remove(it) }
        edit?.apply()
    }
}

/** Rounded hours & minutes for short human labels ("14m left", "1h 12m left", "47 min"). */
internal data class CompactDuration(val hours: Int, val minutes: Int, val underOneMinute: Boolean)

internal fun compactDurationFromMs(ms: Long): CompactDuration? {
    if (ms <= 0L) return null
    if (ms < 60_000L) return CompactDuration(0, 0, underOneMinute = true)
    val totalMin = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
    return CompactDuration(totalMin / 60, totalMin % 60, underOneMinute = false)
}

/**
 * What was loaded when the app last went away: the current video and the queue behind it.
 * Written by the service on every playlist change, so a crash, force stop or swipe from
 * Recents leaves it in place; closing the mini-player empties the playlist and so clears it.
 * On the next launch the UI puts it back, paused, at the video's [ResumeStore] position.
 */
object SavedSession {
    class Entry(val id: String, val title: String, val channel: String, val thumbnail: String?, val durationSec: Long)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    }

    /** Current video first, then the queue; empty when nothing was left open. */
    fun load(): List<Entry> {
        val raw = prefs?.getString("items", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Entry(o.getString("id"), o.optString("title"), o.optString("channel"), o.optString("thumbnail").ifBlank { null }, o.optLong("duration"))
            }
        } catch (e: JSONException) {
            // Unreadable (older format, partial write): start clean rather than fail every launch.
            Log.w("Skipii", "saved session unreadable, dropped", e)
            prefs?.edit()?.remove("items")?.apply()
            emptyList()
        }
    }

    fun save(items: List<Entry>) {
        val edit = prefs?.edit() ?: return
        if (items.isEmpty()) { edit.remove("items").apply(); return }
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)
                    .put("thumbnail", it.thumbnail.orEmpty()).put("duration", it.durationSec),
            )
        }
        edit.putString("items", array.toString()).apply()
    }
}

/** Service-side: keeps [SavedSession] equal to the player's [current, ...queue]. */
@OptIn(UnstableApi::class)
class SessionSaver : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
        if (!events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) return
        // Without the UI connected nothing trims finished items off the front: start at the current one.
        val from = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: 0
        SavedSession.save(
            (from until player.mediaItemCount).map { i ->
                val media = player.getMediaItemAt(i)
                val meta = media.mediaMetadata
                SavedSession.Entry(
                    id = media.mediaId,
                    title = meta.title?.toString().orEmpty(),
                    channel = meta.artist?.toString().orEmpty(),
                    thumbnail = meta.artworkUri?.toString(),
                    durationSec = (meta.durationMs ?: 0L) / 1000,
                )
            }.filter { it.id.isNotBlank() },
        )
    }
}

/**
 * Service-side saver: every 5 s while playing, and on pause, seek, item change and stop.
 * Runs on the player's (main) looper.
 */
class ResumeSaver(private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var lastId: String? = null
    private val tick = object : Runnable {
        override fun run() {
            saveNow()
            if (player.isPlaying) handler.postDelayed(this, 5_000)
        }
    }

    fun saveNow() {
        val id = player.currentMediaItem?.mediaId ?: return
        lastId = id
        ResumeStore.save(id, player.currentPosition, player.duration)
        val duration = player.duration
        if (duration > 0 && duration != C.TIME_UNSET && player.currentPosition >= duration * WatchedStore.Share) WatchedStore.mark(id)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.let(RecentVideos::add)
        Downloads.playingId = mediaItem?.mediaId
        // Automatic downloads already watched go now that the player has moved off them (Downloads.deleteWatched).
        Downloads.clearWatched(currentId = mediaItem?.mediaId)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, 5_000) else saveNow()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Leaving a video (auto-advance, skip): record where the old one ended.
        val oldId = oldPosition.mediaItem?.mediaId
        if (oldId != null && oldId != newPosition.mediaItem?.mediaId) {
            val duration = player.duration.takeIf { lastId == oldId } ?: 0L
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { ResumeStore.save(oldId, Long.MAX_VALUE, Long.MAX_VALUE); WatchedStore.mark(oldId) }
            else if (duration > 0) {
                ResumeStore.save(oldId, oldPosition.positionMs, duration)
                if (oldPosition.positionMs >= duration * WatchedStore.Share) WatchedStore.mark(oldId)
            }
        } else saveNow()
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { ResumeStore.save(it, Long.MAX_VALUE, Long.MAX_VALUE); WatchedStore.mark(it) }
    }
}

/**
 * Videos watched to the end, or past [Share] of their length (0.6.0): "Hide videos I've watched" on Home and
 * Subscriptions, Watch later's "Hide watched", Subscriptions' "Unwatched only", and Downloads' "Delete after
 * watching". [ResumeStore] forgets a finished video (so it restarts from 0), so it can't answer this. Only what
 * Skipii played counts. Written by the service (main looper), read by the UI.
 */
object WatchedStore {
    const val Share = 0.9f
    private const val MAX = 2_000
    private var prefs: SharedPreferences? = null
    /** Oldest first (for trimming). */
    private val order = ArrayList<String>()
    val ids = mutableStateMapOf<String, Unit>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("watched", Context.MODE_PRIVATE).also { prefs = it }
        p.getString("ids", null)?.split(',')?.filter { it.isNotBlank() }?.forEach { order += it; ids[it] = Unit }
    }

    fun watched(id: String) = id in ids

    fun mark(id: String) {
        if (id.isBlank() || id in ids) return
        order += id
        ids[id] = Unit
        while (order.size > MAX) ids.remove(order.removeAt(0))
        prefs?.edit()?.putString("ids", order.joinToString(","))?.apply()
    }
}

/**
 * The videos opened lately, newest first, with what a card needs (0.6.0): the home-screen widget's Continue
 * watching works signed out and offline from it (Library's comes from the account's history). Written by the
 * service as the player moves to a video.
 */
object RecentVideos {
    private const val MAX = 30
    private var prefs: SharedPreferences? = null
    val items = mutableStateListOf<VideoItem>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("recent", Context.MODE_PRIVATE).also { prefs = it }
        runCatching {
            val arr = JSONArray(p.getString("items", null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += VideoItem(
                    id = o.getString("id"), title = o.optString("title"), channel = o.optString("channel"), channelUrl = null,
                    thumbnail = o.optString("thumbnail").ifBlank { null }, avatar = null, durationSec = o.optLong("durationSec"),
                    views = -1, uploaded = null,
                )
            }
        }.onFailure { p.edit().remove("items").apply() }
    }

    fun add(media: MediaItem) {
        val id = media.mediaId.ifBlank { return }
        val m = media.mediaMetadata
        val title = m.title?.toString().orEmpty()
        if (title.isBlank()) return
        val item = VideoItem(
            id = id, title = title, channel = m.artist?.toString().orEmpty(), channelUrl = null,
            thumbnail = m.artworkUri?.toString() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", avatar = null,
            durationSec = (m.durationMs ?: 0L) / 1000L, views = -1, uploaded = null,
        )
        items.removeAll { it.id == id }
        items.add(0, item)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("thumbnail", it.thumbnail).put("durationSec", it.durationSec)) }
        prefs?.edit()?.putString("items", arr.toString())?.apply()
    }

    /** Started but not finished, newest first: Continue watching. */
    fun unfinished(): List<VideoItem> = items.filter { ResumeStore.progress(it.id) != null }
}
