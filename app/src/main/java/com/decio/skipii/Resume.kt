package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
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
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) ResumeStore.save(oldId, Long.MAX_VALUE, Long.MAX_VALUE)
            else if (duration > 0) ResumeStore.save(oldId, oldPosition.positionMs, duration)
        } else saveNow()
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { ResumeStore.save(it, Long.MAX_VALUE, Long.MAX_VALUE) }
    }
}
