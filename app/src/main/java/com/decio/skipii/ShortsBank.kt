package com.decio.skipii

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

/**
 * The Shorts bank (0.5.51, Derek, 2026-10-29: "always keep 10 shorts in memory even when the
 * app is closed … once a short that's saved has been viewed the app forgets it and loads more").
 *
 * RAM doesn't outlive the app (Android drops it whenever the app is closed or killed), so the
 * bank is on disk, where it does: the next [Size] unseen shorts of the feed, each downloaded
 * whole (video + audio, one fixed quality) into a media cache, with what's needed to play it
 * without asking YouTube anything — its details and its two generated DASH manifests, whose
 * stream URLs are the cache keys. A banked short starts from flash at once, even right after
 * the app opens. Seen = forgotten ([forget]); its bytes go once no player holds them
 * ([cleanup]). [ShortsState] keeps it full.
 *
 * Every Shorts player reads through the same cache ([mediaSource]): a short streamed live is
 * cached as it plays and the bank's download picks up where it left off.
 */
@OptIn(UnstableApi::class)
object ShortsBank {
    /** How many unseen shorts are kept ready. */
    const val Size = 10
    /** Downloads go in ranged pieces this big (one whole-file request gets throttled). */
    private const val ChunkBytes = 1L shl 20
    /** The banked quality: 720p (by the short side; a vertical short is 720×1280), lower if a lower one is pinned. */
    private const val BankShortSide = 720

    /** One stream of a short: its URL (also its cache key), its DASH manifest, its size. */
    class Track(val url: String, val manifest: String, val bytes: Long)

    class Entry(val item: VideoItem, val aspect: Float, val video: Track, val audio: Track) {
        val urls get() = listOf(video.url, audio.url)
    }

    private var cache: SimpleCache? = null
    private var indexFile: File? = null
    private val entries = LinkedHashMap<String, Entry>()
    /** The feed's next page after the banked shorts (kept with them, so the feed carries on). */
    var next: String? = null
        private set

    private val upstream by lazy { OkHttpDataSource.Factory(Net.client).setUserAgent(Net.USER_AGENT) }

    fun init(context: Context) {
        runCatching {
            cache = SimpleCache(File(context.cacheDir, "shorts-bank"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        }.onFailure { Log.w("Skipii", "shorts bank: no media cache", it) }
        indexFile = File(context.filesDir, "shorts_bank.json")
        load()
        Log.i("Skipii", "shorts bank: ${entries.size} shorts ready from last time")
    }

    // ── What's banked ──

    fun entries(): List<Entry> = synchronized(entries) { entries.values.toList() }
    fun has(id: String) = synchronized(entries) { entries.containsKey(id) }
    fun get(id: String): Entry? = synchronized(entries) { entries[id] }
    val size get() = synchronized(entries) { entries.size }

    /** Seen: out of the bank for good (its bytes go at the next [cleanup]). */
    fun forget(id: String) {
        val gone = synchronized(entries) { entries.remove(id) } ?: return
        Log.i("Skipii", "shorts bank: ${gone.item.id} seen, forgotten (${size} left)")
        save()
    }

    /** Shorts turned off ([ShortsSetting]): everything banked goes, bytes and all. */
    fun clear() {
        synchronized(entries) { entries.clear() }
        next = null
        save()
        cleanup(emptySet())
        Log.i("Skipii", "shorts bank: cleared (Shorts turned off)")
    }

    fun setNext(token: String?) {
        if (token == next) return
        next = token
        save()
    }

    // ── Playing ──

    /**
     * The two streams the Shorts players play for [info] (and the bank keeps): H.264 video at
     * [BankShortSide] by the short side (lower if pinned lower), M4A audio, each with its manifest.
     * One fixed quality: an adaptive switch mid-short briefly stalled playback (2026-10-29).
     */
    fun tracks(info: StreamInfo): Pair<Track, Track>? {
        val durationSec = info.duration.takeIf { it > 0 } ?: return null
        val side = minOf(BankShortSide, QualityPreference.height ?: BankShortSide)
        fun shortSide(w: Int, h: Int) = if (w > 0) minOf(w, h) else h
        val videos = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null && it.height > 0 }
        val family = videos.filter { it.codec?.startsWith("avc") == true }.ifEmpty { videos.filter { it.format == MediaFormat.WEBM } }.ifEmpty { videos }
        val video = (family.filter { shortSide(it.width, it.height) <= side }.ifEmpty { family })
            .maxWithOrNull(compareBy({ if (shortSide(it.width, it.height) <= side) shortSide(it.width, it.height) else -shortSide(it.width, it.height) }, { it.fps }))
            ?: return null
        val audio = StreamSelection.pick(info).audio?.takeIf { it.itagItem != null } ?: return null
        fun track(s: org.schabi.newpipe.extractor.stream.Stream): Track? = runCatching {
            val itag = s.itagItem!!
            val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(s.content, itag, durationSec)
            val manifest = DashManifestParser().parse(Uri.parse(s.content), xml.byteInputStream())
            // The URL exactly as the player will request it: that string is the cache key.
            val url = manifest.getPeriod(0).adaptationSets[0].representations[0].baseUrls[0].url
            Track(url, xml, itag.contentLength)
        }.getOrNull()
        return (track(video) ?: return null) to (track(audio) ?: return null)
    }

    /** A short's video + audio from its manifests, read through the cache (network only for what isn't in it). */
    fun mediaSource(video: Track, audio: Track, mediaItem: MediaItem): MediaSource {
        val sources = DashMediaSource.Factory(dataSourceFactory())
        fun one(t: Track) = sources.createMediaSource(DashManifestParser().parse(Uri.parse(t.url), t.manifest.byteInputStream()), mediaItem)
        return MergingMediaSource(true, true, one(video), one(audio))
    }

    private fun dataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        val c = cache ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // ── Filling ──

    /** Downloads [item]'s two streams whole, then banks it. Throws if it can't; cancellable between pieces. */
    suspend fun download(item: VideoItem, aspect: Float, video: Track, audio: Track) = withContext(Dispatchers.IO) {
        val c = cache ?: error("no media cache")
        val source = CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).createDataSource()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        for (t in listOf(video, audio)) {
            if (t.bytes <= 0) {
                CacheWriter(source, DataSpec(Uri.parse(t.url)), null, null).cache()
                continue
            }
            var at = 0L
            while (at < t.bytes) {
                ensureActive()
                val length = minOf(ChunkBytes, t.bytes - at)
                if (!c.isCached(t.url, at, length)) {
                    CacheWriter(source, DataSpec.Builder().setUri(t.url).setPosition(at).setLength(length).build(), null, null).cache()
                }
                at += length
            }
        }
        val entry = Entry(item, aspect, video, audio)
        synchronized(entries) { entries[item.id] = entry }
        save()
        Log.i(
            "Skipii",
            "shorts bank: ${item.id} banked (${(video.bytes + audio.bytes) / 1024} KB in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms; $size ready)",
        )
    }

    /** Drops cached bytes nothing needs: neither banked nor held by a player ([inUse] URLs). */
    fun cleanup(inUse: Set<String>) {
        val c = cache ?: return
        val keep = entries().flatMapTo(HashSet()) { it.urls } + inUse
        c.keys.filter { it !in keep }.forEach { runCatching { c.removeResource(it) } }
    }

    // ── On disk ──

    private fun save() {
        val file = indexFile ?: return
        val json = JSONObject()
            .put("next", next)
            .put("entries", JSONArray().apply { entries().forEach { put(it.toJson()) } })
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }.onFailure { Log.w("Skipii", "shorts bank: couldn't save", it) }
    }

    private fun load() {
        val file = indexFile ?: return
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            next = json.optString("next").ifBlank { null }
            val list = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until list.length()) {
                val e = entryFrom(list.getJSONObject(i)) ?: continue
                // Only shorts whose bytes are all still there (the system may clear the cache).
                if (complete(e)) entries[e.item.id] = e
            }
        }.onFailure { Log.w("Skipii", "shorts bank: unreadable index; starting empty", it) }
    }

    private fun complete(e: Entry): Boolean {
        val c = cache ?: return false
        return e.video.bytes > 0 && e.audio.bytes > 0 &&
            c.isCached(e.video.url, 0, e.video.bytes) && c.isCached(e.audio.url, 0, e.audio.bytes)
    }

    private fun Entry.toJson() = JSONObject()
        .put("id", item.id).put("title", item.title).put("channel", item.channel)
        .put("channelUrl", item.channelUrl).put("thumbnail", item.thumbnail).put("avatar", item.avatar)
        .put("durationSec", item.durationSec).put("aspect", aspect.toDouble())
        .put("video", video.toJson()).put("audio", audio.toJson())

    private fun Track.toJson() = JSONObject().put("url", url).put("manifest", manifest).put("bytes", bytes)

    private fun entryFrom(o: JSONObject): Entry? = runCatching {
        fun track(t: JSONObject) = Track(t.getString("url"), t.getString("manifest"), t.getLong("bytes"))
        Entry(
            item = VideoItem(
                id = o.getString("id"), title = o.optString("title"), channel = o.optString("channel"),
                channelUrl = o.optString("channelUrl").ifBlank { null }, thumbnail = o.optString("thumbnail").ifBlank { null },
                avatar = o.optString("avatar").ifBlank { null }, durationSec = o.optLong("durationSec"), views = 0, uploaded = null,
            ),
            aspect = o.optDouble("aspect", 9.0 / 16.0).toFloat(),
            video = track(o.getJSONObject("video")),
            audio = track(o.getJSONObject("audio")),
        )
    }.getOrNull()

}
