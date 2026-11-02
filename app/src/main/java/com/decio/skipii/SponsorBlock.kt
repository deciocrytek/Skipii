package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SponsorBlock (sponsor.ajay.app). Segment data is CC BY-NC-SA 4.0: credited in the
 * account sheet, never used commercially.
 *
 * Privacy: lookups send only the first 4 hex chars of SHA-256(videoId); the server returns
 * segments for every video sharing that prefix and the match happens here.
 */
object SponsorBlock {
    enum class Category(val key: String, @StringRes val labelRes: Int, @StringRes val shortRes: Int, val color: Color, val defaultAction: Action) {
        Sponsor("sponsor", R.string.player_sb_sponsor, R.string.player_sb_short_sponsor, Color(0xFF00D400), Action.Auto),
        SelfPromo("selfpromo", R.string.player_sb_selfpromo, R.string.player_sb_short_selfpromo, Color(0xFFFFFF00), Action.Button),
        Interaction("interaction", R.string.player_sb_interaction, R.string.player_sb_short_interaction, Color(0xFFCC00FF), Action.Button),
        Intro("intro", R.string.player_sb_intro, R.string.player_sb_short_intro, Color(0xFF00FFFF), Action.Mark),
        Outro("outro", R.string.player_sb_outro, R.string.player_sb_short_outro, Color(0xFF0202ED), Action.Mark),
        Preview("preview", R.string.player_sb_preview, R.string.player_sb_short_preview, Color(0xFF008FD6), Action.Mark),
        MusicOffTopic("music_offtopic", R.string.player_sb_music_offtopic, R.string.player_sb_short_music_offtopic, Color(0xFFFF9900), Action.Off),
        Filler("filler", R.string.player_sb_filler, R.string.player_sb_short_filler, Color(0xFF7300FF), Action.Off);

        /** The category's name in the app's language (resolved each time: the language can change). */
        val label: String get() = str(labelRes)

        /** For "Skip …" / "Skipped 0:30 …", in the app's language. */
        val short: String get() = str(shortRes)

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }

    /** What to do when playback reaches a segment of a category. */
    enum class Action(@StringRes val labelRes: Int) {
        Auto(R.string.player_sb_action_skip), Button(R.string.player_sb_action_ask), Mark(R.string.player_sb_action_show), Off(R.string.player_sb_action_off);

        /** In the app's language (resolved each time: the language can change). */
        val label: String get() = str(labelRes)
    }

    @Immutable
    data class Segment(val uuid: String, val category: Category, val startMs: Long, val endMs: Long) {
        val action get() = actionFor(category)
        val lengthMs get() = endMs - startMs
    }

    /** The last automatic skip, for the "Skipped · Undo" note. Set on the main thread. */
    @Immutable
    data class Skip(val videoId: String, val segment: Segment, val at: Long = System.currentTimeMillis())

    // ── Settings ─────────────────────────────────────────────────────────────────

    private var prefs: SharedPreferences? = null
    var enabled by mutableStateOf(true)
        private set
    private val actions = mutableStateMapOf<Category, Action>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("sponsorblock", Context.MODE_PRIVATE).also { prefs = it }
        enabled = p.getBoolean("enabled", true)
        Category.entries.forEach { c ->
            actions[c] = p.getString("action_${c.key}", null)?.let { runCatching { Action.valueOf(it) }.getOrNull() } ?: c.defaultAction
        }
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean("enabled", value)?.apply()
    }

    fun actionFor(category: Category): Action = actions[category] ?: category.defaultAction

    fun setAction(category: Category, action: Action) {
        actions[category] = action
        prefs?.edit()?.putString("action_${category.key}", action.name)?.apply()
    }

    // ── Segments ─────────────────────────────────────────────────────────────────

    /** Segments by video id, fetched once per process; read by both the UI and the service. */
    private val cache = ConcurrentHashMap<String, List<Segment>>()
    /** Snapshot for the UI (scrubber marks, skip button). */
    val forUi = mutableStateMapOf<String, List<Segment>>()
    var lastSkip by mutableStateOf<Skip?>(null)

    fun cached(videoId: String): List<Segment>? = cache[videoId]

    /** Blocking; call off the main thread. Never throws — no segments is a valid answer. */
    fun fetch(videoId: String): List<Segment> {
        cache[videoId]?.let { return it }
        val segments = runCatching { request(videoId) }
            .onFailure { Log.w("Skipii", "SponsorBlock lookup failed for $videoId", it) }
            .getOrNull() ?: return emptyList() // not cached: a later open retries
        cache[videoId] = segments
        return segments
    }

    private fun request(videoId: String): List<Segment> {
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)
        val categories = JSONArray(Category.entries.map { it.key }).toString()
        val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=" +
            java.net.URLEncoder.encode(categories, "UTF-8") + "&actionType=skip"
        val request = Request.Builder().url(url).header("User-Agent", "Skipii/${BuildConfig.VERSION_NAME}").build()
        Net.client.newCall(request).execute().use { r ->
            if (r.code == 404) return emptyList() // no video with this prefix has segments
            if (!r.isSuccessful) throw java.io.IOException("SponsorBlock ${r.code}")
            val arr = JSONArray(r.body?.string().orEmpty())
            for (i in 0 until arr.length()) {
                val video = arr.optJSONObject(i) ?: continue
                if (video.optString("videoID") != videoId) continue
                val segs = video.optJSONArray("segments") ?: return emptyList()
                return (0 until segs.length()).mapNotNull { j ->
                    val s = segs.optJSONObject(j) ?: return@mapNotNull null
                    val cat = Category.of(s.optString("category")) ?: return@mapNotNull null
                    val range = s.optJSONArray("segment") ?: return@mapNotNull null
                    val start = (range.optDouble(0) * 1000).toLong()
                    val end = (range.optDouble(1) * 1000).toLong()
                    if (end - start < 1000) null else Segment(s.optString("UUID"), cat, start, end)
                }.sortedBy { it.startMs }
            }
            return emptyList()
        }
    }
}
