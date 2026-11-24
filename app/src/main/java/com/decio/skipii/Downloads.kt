package com.decio.skipii

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException

/** One SQLite index for every media cache in the process (the Shorts bank's and the downloads'). */
@OptIn(UnstableApi::class)
object MediaDatabase {
    @Volatile private var provider: StandaloneDatabaseProvider? = null
    fun get(context: Context): StandaloneDatabaseProvider =
        provider ?: synchronized(this) { provider ?: StandaloneDatabaseProvider(context.applicationContext).also { provider = it } }
}

/**
 * Downloads (0.6.0; Derek approved section 1 of docs/design/skipii-2.html, plan in docs/design/skipii-0.6-plan.md).
 *
 * A video (or its sound alone) kept whole on the phone with what it needs to play with no connection: its
 * details, captions and SponsorBlock segments. Bytes live in their own media cache in filesDir (Android
 * never clears it, unlike cacheDir), saved under STABLE keys ([keyOf]: video id + itag) rather than under
 * YouTube's links, which expire after hours — so a download stopped by a kill, a lost network or Wi-Fi
 * going away picks up at the first missing 1 MB piece with fresh links. Playback reads the cache alone
 * ([mediaSource]); nothing is asked of YouTube for a ready download.
 *
 * Manual downloads stay until removed. Automatic ones (the queue, the first five of Watch later) keep to
 * [limitGb] and, with [deleteWatched], go once watched. [DownloadWorker] does the downloading.
 */
@OptIn(UnstableApi::class)
object Downloads {
    private const val TAG = "Skipii"
    /** Downloaded in pieces this big (one whole-file request gets throttled), as the Shorts bank. */
    private const val ChunkBytes = 1L shl 20
    /** Caption files kept per download (the auto track, the creator's, a few languages). */
    private const val MaxCaptionFiles = 6
    const val WatchLaterCount = 5
    private const val WORK = "downloads"

    enum class Status { Queued, Downloading, Done, Failed }

    /** Why a download was added: by hand, or by an automatic rule (which can also take it away). */
    enum class Source { Manual, Queue, WatchLater }

    /** One kept stream: its stable cache key, its manifest (BaseURL = the key), its size, and the itag it came from. */
    @Immutable
    data class Track(val key: String, val itag: Int, val manifest: String, val bytes: Long)

    @Immutable
    data class Entry(
        val item: VideoItem,
        /** The video's height; null = sound only. */
        val height: Int?,
        val source: Source,
        val addedAt: Long,
        val status: Status = Status.Queued,
        val video: Track? = null,
        val audio: Track? = null,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        /** Why it failed, for its row (null otherwise). */
        val error: String? = null,
        /** The saved details ([snapshotOf]); null until the download has started. */
        val snapshot: String? = null,
    ) {
        val id get() = item.id
        val ready get() = status == Status.Done
        val auto get() = source != Source.Manual
        val audioOnly get() = height == null
        val progress get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    // ── Settings (prefs "downloads") ──
    private var prefs: SharedPreferences? = null
    var autoQueue by mutableStateOf(false)
        private set
    var autoWatchLater by mutableStateOf(false)
        private set
    var deleteWatched by mutableStateOf(true)
        private set
    var wifiOnly by mutableStateOf(true)
        private set
    var limitGb by mutableIntStateOf(4)
        private set
    /** Automatic downloads' quality; 0 = sound only. */
    var autoHeight by mutableIntStateOf(720)
        private set
    val limitChoices = listOf(2, 4, 8, 16)
    val heightChoices = listOf(1080, 720, 480, 0)

    /** Every download by video id, for the UI. Written on the main thread only. */
    val entries = mutableStateMapOf<String, Entry>()
    /** The connection is metered right now (mobile data): "Waiting for Wi-Fi" when [wifiOnly]. */
    var metered by mutableStateOf(false)
        private set
    /** There's a connection that reaches the internet (offline: downloads play from their kept details). */
    @Volatile var online = true
        private set

    private var cache: SimpleCache? = null
    private lateinit var dir: File
    private lateinit var indexFile: File
    private lateinit var app: Context
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val upstream by lazy { OkHttpDataSource.Factory(Net.client).setUserAgent(Net.USER_AGENT) }

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences("downloads", Context.MODE_PRIVATE).also { prefs = it }
        autoQueue = p.getBoolean("auto_queue", false)
        autoWatchLater = p.getBoolean("auto_wl", false)
        deleteWatched = p.getBoolean("delete_watched", true)
        wifiOnly = p.getBoolean("wifi_only", true)
        limitGb = p.getInt("limit_gb", 4).takeIf { it in limitChoices } ?: 4
        autoHeight = p.getInt("auto_height", 720).takeIf { it in heightChoices } ?: 720
        dir = File(context.filesDir, "downloads").apply { mkdirs() }
        indexFile = File(context.filesDir, "downloads.json")
        runCatching { cache = SimpleCache(File(dir, "media"), NoOpCacheEvictor(), MediaDatabase.get(context)) }
            .onFailure { Log.w(TAG, "downloads: no media cache", it) }
        load()
        pruneOrphans()
        watchNetwork(context)
        if (entries.values.any { !it.ready && it.status != Status.Failed }) schedule()
        Log.i(TAG, "downloads: ${entries.values.count { it.ready }} ready, ${entries.size} in all")
    }

    // ── Settings writes ──
    fun chooseAutoQueue(on: Boolean) {
        autoQueue = on
        prefs?.edit()?.putBoolean("auto_queue", on)?.apply()
        if (on) syncQueue(lastQueue) else dropAuto(Source.Queue)
    }
    fun chooseAutoWatchLater(on: Boolean) { autoWatchLater = on; prefs?.edit()?.putBoolean("auto_wl", on)?.apply(); if (!on) dropAuto(Source.WatchLater) else schedule() }
    fun chooseDeleteWatched(on: Boolean) { deleteWatched = on; prefs?.edit()?.putBoolean("delete_watched", on)?.apply(); if (on) clearWatched(currentId = playingId) }
    fun chooseWifiOnly(on: Boolean) { wifiOnly = on; prefs?.edit()?.putBoolean("wifi_only", on)?.apply(); schedule(replace = true) }
    fun chooseLimitGb(gb: Int) { limitGb = gb; prefs?.edit()?.putInt("limit_gb", gb)?.apply(); schedule() }
    fun chooseAutoHeight(h: Int) { autoHeight = h; prefs?.edit()?.putInt("auto_height", h)?.apply() }

    // ── Reading ──
    fun entry(id: String): Entry? = entries[id]
    fun isReady(id: String) = entries[id]?.ready == true
    val usedBytes: Long get() = entries.values.sumOf { if (it.ready) it.totalBytes else it.doneBytes }
    val autoBytes: Long get() = entries.values.filter { it.auto }.sumOf { if (it.ready) it.totalBytes else it.doneBytes }
    val waitingForWifi: Boolean get() = wifiOnly && metered

    /** The local thumbnail of a download, if kept. */
    fun thumbnailFile(id: String): File? = File(dir, "thumbs/$id.jpg").takeIf { it.exists() }

    /** [item] with its local thumbnail when downloaded (shows offline). */
    fun withLocalThumb(item: VideoItem): VideoItem = thumbnailFile(item.id)?.let { item.copy(thumbnail = Uri.fromFile(it).toString()) } ?: item

    // ── Asking for downloads ──

    /** By hand from the player's sheet or a card's long-press: [height] null = sound only. */
    fun request(item: VideoItem, height: Int?) {
        val known = entries[item.id]
        if (known != null && known.source == Source.Manual && known.height == height && known.status != Status.Failed) return
        if (known != null && known.height != height) removeBytes(known)
        entries[item.id] = Entry(item, height, Source.Manual, System.currentTimeMillis())
        save()
        schedule()
        Log.i(TAG, "downloads: ${item.id} asked for (${height?.let { "${it}p" } ?: "audio"})")
    }

    /** A failed download tried again. */
    fun retry(id: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(status = Status.Queued, error = null)
        save()
        schedule()
    }

    /** Taken off the phone (bytes, thumbnail, captions). Returns it, for an Undo ([restore]). */
    fun remove(id: String): Entry? {
        val e = entries.remove(id) ?: return null
        save()
        // The bytes go a little later: an Undo within the window keeps them (restore puts the entry back).
        pendingRemoval[id] = e
        main.postDelayed({ dropBytesWhenFree(id) }, UndoWindowMs + 500)
        return e
    }

    fun restore(entry: Entry) {
        pendingRemoval.remove(entry.id)
        entries[entry.id] = entry
        save()
        if (!entry.ready) schedule()
    }

    private val pendingRemoval = HashMap<String, Entry>()

    /** The video the service's player is on (set by its ResumeSaver): its bytes may still be read. */
    @Volatile var playingId: String? = null

    /** A removed download's bytes go once nothing plays it (deleting the video that's playing waits until it's left). */
    private fun dropBytesWhenFree(id: String) {
        if (entries.containsKey(id)) { pendingRemoval.remove(id); return } // put back (Undo)
        if (id == playingId) { main.postDelayed({ dropBytesWhenFree(id) }, 30_000); return }
        pendingRemoval.remove(id)?.let(::removeBytes)
    }

    /**
     * The player's queue changed (PlayerState): with [autoQueue], every queued video gets an automatic download;
     * an automatic one whose video left the queue before it finished downloading is dropped.
     */
    fun syncQueue(queue: List<VideoItem>) {
        lastQueue = queue
        if (!autoQueue) return
        val ids = queue.mapTo(HashSet()) { it.id }
        var changed = false
        entries.values.filter { it.source == Source.Queue && !it.ready && it.id !in ids }.forEach { entries.remove(it.id); removeBytes(it); changed = true }
        queue.forEach { v ->
            if (!entries.containsKey(v.id)) {
                entries[v.id] = Entry(v, autoHeight.takeIf { it > 0 }, Source.Queue, System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) { save(); schedule() }
    }

    /** Watch later's first videos (worker, signed in): automatic downloads for them; ones that left the top are dropped unless done. */
    internal fun syncWatchLater(top: List<VideoItem>) {
        main.post {
            if (!autoWatchLater) return@post
            val ids = top.mapTo(HashSet()) { it.id }
            entries.values.filter { it.source == Source.WatchLater && !it.ready && it.id !in ids }.forEach { entries.remove(it.id); removeBytes(it) }
            top.forEach { v -> if (!entries.containsKey(v.id)) entries[v.id] = Entry(v, autoHeight.takeIf { it > 0 }, Source.WatchLater, System.currentTimeMillis()) }
            save()
        }
    }

    /** The player's queue as last told ([syncQueue]), so turning "Download my queue" on picks it up at once. */
    private var lastQueue: List<VideoItem> = emptyList()

    private fun dropAuto(source: Source) {
        entries.values.filter { it.source == source && !it.ready }.forEach { entries.remove(it.id); removeBytes(it) }
        save()
    }

    /**
     * Automatic downloads that were watched ([WatchedStore]) go, with [deleteWatched] — never the one playing
     * ([currentId]: its bytes may still be read). Called by the service as the player moves on.
     */
    fun clearWatched(currentId: String?) {
        if (!deleteWatched) return
        main.post {
            val gone = entries.values.filter { it.auto && it.ready && it.id != currentId && WatchedStore.watched(it.id) }
            if (gone.isEmpty()) return@post
            gone.forEach { entries.remove(it.id); removeBytes(it) }
            save()
            Log.i(TAG, "downloads: ${gone.size} watched automatic download(s) deleted")
        }
    }

    // ── Playing ──

    /** A ready download as a media source read from the phone alone; null if [mediaItem] isn't one. */
    fun mediaSource(mediaItem: MediaItem): MediaSource? {
        val e = entries[mediaItem.mediaId]?.takeIf { it.ready } ?: return null
        val c = cache ?: return null
        // Cache only: no upstream, no writing. A piece that went missing fails loudly instead of streaming.
        val source = CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        val factory = DashMediaSource.Factory(source)
        fun one(t: Track) = factory.createMediaSource(DashManifestParser().parse(Uri.parse(t.key), t.manifest.byteInputStream()), mediaItem)
        val audio = e.audio ?: return null
        val video = e.video
        Log.i(TAG, "media source ${e.id}: downloaded ${e.height?.let { "${it}p (itag ${video?.itag})" } ?: "audio only"} + audio itag ${audio.itag}")
        return if (video != null) MergingMediaSource(true, true, one(video), one(audio)) else one(audio)
    }

    /** The details kept with a ready download, as the player shows them ([YouTube.details] uses it offline). */
    fun details(id: String): VideoDetails? {
        val e = entries[id]?.takeIf { it.ready } ?: return null
        val s = e.snapshot?.let { runCatching { JSONObject(it) }.getOrNull() }
        return detailsFrom(e, s)
    }

    /** A kept caption file's text by its link (CaptionFiles reads it before asking YouTube). */
    fun captionText(url: String): String? {
        // Caption links carry the video's id (v=…): only that download's files can match. Compared as parsed
        // JSON, never as the snapshot's text (Android's JSONObject writes "/" as "\/").
        val link = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val id = link.getQueryParameter("v") ?: return null
        val s = entries[id]?.takeIf { it.ready }?.snapshot?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val files = s.optJSONArray("captions") ?: return null
        val kept = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
        // The same link, else (online, the live details carry fresh links) the same language and kind.
        val lang = link.getQueryParameter("lang")
        val auto = link.getQueryParameter("kind") == "asr"
        val f = kept.firstOrNull { it.optString("url") == url } ?: kept.firstOrNull { it.optString("lang") == lang && it.optBoolean("auto") == auto } ?: return null
        return File(dir, "captions/${f.optString("file")}").takeIf { it.exists() }?.readText()
    }

    /** Music (plays at 1× with "music at normal speed"), as saved with the download; null when not downloaded. */
    fun isMusic(id: String): Boolean? = entries[id]?.takeIf { it.ready }?.snapshot?.let { runCatching { JSONObject(it).optBoolean("music") }.getOrNull() }

    /** Kept SponsorBlock segments, handed to SponsorBlock before it would ask the network. */
    fun segments(id: String): List<SponsorBlock.Segment>? {
        val s = entries[id]?.takeIf { it.ready }?.snapshot?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val arr = s.optJSONArray("segments") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cat = SponsorBlock.Category.of(o.optString("category")) ?: return@mapNotNull null
            SponsorBlock.Segment(o.optString("uuid"), cat, o.optLong("start"), o.optLong("end"))
        }
    }

    // ── The sheet's choices ──

    /** One choice in the Download sheet: a height (null = sound only) and its size in bytes (approximate when [estimated]). */
    @Immutable
    data class Choice(val height: Int?, val bytes: Long, val estimated: Boolean)

    /** What [info] can be downloaded as: each height up to 1080p it has, then sound only. */
    fun choices(info: StreamInfo): List<Choice> {
        val audio = pickAudio(info) ?: return emptyList()
        val seconds = info.duration.coerceAtLeast(0)
        fun size(s: Stream): Pair<Long, Boolean> {
            val exact = s.itagItem?.contentLength ?: -1
            if (exact > 0) return exact to false
            val bitrate = (s as? VideoStream)?.bitrate ?: (s as? AudioStream)?.averageBitrate?.times(1000) ?: 0
            return (bitrate.toLong() * seconds / 8) to true
        }
        val (aBytes, aEst) = size(audio)
        val heights = info.videoOnlyStreams.filter(::downloadable).map { it.height }.filter { it in 1..1080 }.distinct().sortedDescending()
        val video = heights.mapNotNull { h ->
            val v = pickVideo(info, h) ?: return@mapNotNull null
            val (vBytes, vEst) = size(v)
            Choice(h, vBytes + aBytes, vEst || aEst)
        }
        return video + Choice(null, aBytes, aEst)
    }

    private fun downloadable(s: VideoStream) =
        s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.itagItem != null && s.height > 0

    /**
     * The video stream for [height]: that exact height in H.264 first (plays everywhere), else VP9, else anything —
     * so a 1080p that YouTube has only in VP9 is still 1080p. With no stream that tall (an automatic download's
     * height), the tallest under it, H.264 first.
     */
    private fun pickVideo(info: StreamInfo, height: Int): VideoStream? {
        val all = info.videoOnlyStreams.filter(::downloadable)
        fun rank(s: VideoStream) = when {
            s.codec?.startsWith("avc") == true -> 0
            s.format == MediaFormat.WEBM -> 1
            else -> 2
        }
        all.filter { it.height == height }.minWithOrNull(compareBy<VideoStream>(::rank).thenByDescending { it.fps })?.let { return it }
        val under = all.filter { it.height <= height }
        val family = under.filter { rank(it) == 0 }.ifEmpty { under.filter { rank(it) == 1 } }.ifEmpty { under }
        return family.maxWithOrNull(compareBy({ it.height }, { it.fps }))
    }

    /** The sound the player would play for this video (its own track, as StreamSelection picks it). */
    private fun pickAudio(info: StreamInfo): AudioStream? =
        StreamSelection.pick(info, info.id).audio?.takeIf { it.isUrl && it.itagItem != null && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

    // ── Downloading (DownloadWorker) ──

    /**
     * Set (main thread) by a run the moment it finds nothing left to do; a download asked for after that would be
     * dropped by KEEP while that run is still finishing, so [schedule] queues a run behind it instead.
     */
    @Volatile internal var finishing = false

    /**
     * Starts (or re-plans) the background download run under the network rule. A run that's going keeps going (it
     * picks up anything added meanwhile); only one finishing gets another queued behind it.
     */
    fun schedule(replace: Boolean = false) {
        if (!::app.isInitialized) return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        val policy = when {
            replace -> ExistingWorkPolicy.REPLACE
            // The run found nothing left and is on its way out: KEEP would drop this ask, so it goes behind it.
            finishing -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        // Used once: that queued run picks up everything asked for after this, so later asks KEEP it.
        finishing = false
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, policy, request)
    }

    /** The next download to do, nearest first: manual, then the queue's, then Watch later's. */
    internal fun nextPending(): Entry? = entries.values
        .filter { it.status == Status.Queued || it.status == Status.Downloading }
        .filter { !it.auto || roomForAuto() }
        .minWithOrNull(compareBy({ it.source.ordinal }, { it.addedAt }))

    private fun roomForAuto() = autoBytes < limitGb * 1_000_000_000L

    /** Automatic downloads have used their space ([limitGb]): queued ones wait (their rows say so). */
    val autoFull: Boolean get() = !roomForAuto()

    /** [e] is still the download it was when the run took it (not removed, nor asked for again in another quality). */
    private fun owns(e: Entry) = entries[e.id]?.addedAt == e.addedAt

    internal val anyPending get() = entries.values.any { it.status == Status.Queued || it.status == Status.Downloading }

    /** Downloads [e] whole; throws when it can't (the worker marks it failed and moves on). Cancellable between pieces. */
    internal suspend fun download(e: Entry, report: (Entry) -> Unit) = withContext(Dispatchers.IO) {
        val c = cache ?: error("no media cache")
        val info = BotCheck.withoutWaiting(e.id) { StreamCache.getOrLoad(e.id) }
        val audioStream = pickAudio(info) ?: throw IOException("no audio stream")
        val videoStream = e.height?.let { pickVideo(info, it) ?: throw IOException("no video stream") }
        val seconds = info.duration.takeIf { it > 0 } ?: throw IOException("unknown length")
        // The same itags as last time keep their keys (and the bytes already kept); other itags start over.
        val video = videoStream?.let { trackOf(e.id, it, seconds) }
        val audio = trackOf(e.id, audioStream, seconds)
        listOfNotNull(e.video, e.audio).filter { old -> listOfNotNull(video, audio).none { it.key == old.key } }.forEach { runCatching { c.removeResource(it.key) } }
        val total = (video?.bytes ?: 0) + audio.bytes
        // The height kept is the stream's own (an automatic 1080p on a 720p video is a 720p download).
        var current = e.copy(status = Status.Downloading, height = videoStream?.height ?: e.height, video = video, audio = audio, totalBytes = total, error = null)
        if (!withContext(Dispatchers.Main) { owns(e).also { if (it) { entries[e.id] = current; save() } } }) return@withContext // removed meanwhile
        // Details first (a few KB): even a download cut short opens with its title and description.
        val snapshot = runCatching { snapshotOf(e.id, info) }.onFailure { Log.w(TAG, "downloads: details for ${e.id}", it) }.getOrNull()
        saveThumbnail(e.id, e.item.thumbnail)
        val source = CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).createDataSource()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var lastReport = 0L
        for ((stream, track) in listOfNotNull(videoStream?.let { it to video!! }, audioStream to audio)) {
            var at = 0L
            while (at < track.bytes) {
                ensureActive()
                if (!owns(e)) return@withContext // removed (or asked for again) meanwhile
                val length = minOf(ChunkBytes, track.bytes - at)
                if (!c.isCached(track.key, at, length)) {
                    val spec = DataSpec.Builder().setUri(stream.content).setKey(track.key).setPosition(at).setLength(length).build()
                    try {
                        CacheWriter(source, spec, null, null).cache()
                    } catch (x: HttpDataSource.InvalidResponseCodeException) {
                        // A link that expired (or a new network): fresh links, the same keys, carry on from this piece.
                        if (x.responseCode != 403 && x.responseCode != 404) throw x
                        throw LinksExpired(e.id)
                    }
                }
                at += length
                val done = listOfNotNull(video, audio).sumOf { c.getCachedBytes(it.key, 0, it.bytes) }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReport > 400) {
                    lastReport = now
                    current = current.copy(doneBytes = done)
                    report(current)
                }
            }
        }
        val complete = listOfNotNull(video, audio).all { c.isCached(it.key, 0, it.bytes) }
        if (!complete) throw IOException("bytes missing after download")
        current = current.copy(status = Status.Done, doneBytes = total, snapshot = snapshot?.toString() ?: current.snapshot)
        withContext(Dispatchers.Main) { if (owns(e)) { entries[e.id] = current; save() } }
        Log.i(TAG, "downloads: ${e.id} ready (${total / 1024} KB in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms)")
    }

    /** A run stopped (cancelled, Wi-Fi gone): rows it left on "Downloading" wait again. Main thread. */
    internal fun requeueStuck() {
        val stuck = entries.values.filter { it.status == Status.Downloading }
        if (stuck.isEmpty()) return
        stuck.forEach { entries[it.id] = it.copy(status = Status.Queued) }
        save()
    }

    internal class LinksExpired(id: String) : IOException("links expired for $id")

    internal fun report(e: Entry) = main.post { if (owns(e)) entries[e.id] = e }

    internal fun fail(e: Entry, why: Throwable) = main.post {
        val now = entries[e.id]?.takeIf { owns(e) } ?: return@post
        entries[e.id] = now.copy(status = Status.Failed, error = YouTube.friendlyError(why))
        save()
    }

    /** Debug builds' test seeding (src/debug DebugSeed) only: the media cache and a ready entry. */
    internal fun testCache(): SimpleCache? = cache
    internal fun putReadyForTest(e: Entry) = main.post { entries[e.id] = e; save() }

    private fun keyOf(id: String, itag: Int) = "skipii-dl://$id/$itag"

    /** A stream as kept: its manifest with the stable key as its BaseURL, so playback reads the cache under that key. */
    private fun trackOf(id: String, s: Stream, seconds: Long): Track {
        val itag = s.itagItem ?: throw IOException("no itag")
        val key = keyOf(id, itag.id)
        val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(s.content, itag, seconds)
        val manifest = xml.replace(Regex("<BaseURL>[^<]*</BaseURL>"), "<BaseURL>$key</BaseURL>")
        val bytes = itag.contentLength.takeIf { it > 0 } ?: throw IOException("unknown size for itag ${itag.id}")
        return Track(key, itag.id, manifest, bytes)
    }

    private fun removeBytes(e: Entry) {
        val c = cache ?: return
        listOfNotNull(e.video, e.audio).forEach { runCatching { c.removeResource(it.key) } }
        // Keys of an earlier pick (another itag) may linger too.
        c.keys.filter { it.startsWith("skipii-dl://${e.id}/") }.forEach { runCatching { c.removeResource(it) } }
        File(dir, "thumbs/${e.id}.jpg").delete()
        File(dir, "captions").listFiles()?.filter { it.name.startsWith(e.id + "_") }?.forEach { it.delete() }
    }

    // ── Details kept with a download ──

    private suspend fun snapshotOf(id: String, info: StreamInfo): JSONObject {
        val d = YouTube.details(id, wait = false)
        val o = JSONObject()
            .put("title", d.item.title).put("channel", d.item.channel).put("channelUrl", d.item.channelUrl)
            .put("avatar", d.item.avatar).put("durationSec", d.item.durationSec).put("views", d.item.views).put("uploaded", d.item.uploaded)
            .put("description", d.description.text)
            .put("links", JSONArray().apply { d.description.links.forEach { put(JSONObject().put("s", it.start).put("e", it.end).put("u", it.url)) } })
            .put("likes", d.likes).put("subscribers", d.subscribers)
            .put("aspect", d.aspect?.toDouble())
            .put("music", YouTube.isMusic(info))
            .put("chapters", JSONArray().apply { d.chapters.forEach { put(JSONObject().put("ms", it.startMs).put("t", it.title)) } })
        // Captions: the tracks the player would offer first (creator's, auto, the app's language), files kept.
        val captionDir = File(dir, "captions").apply { mkdirs() }
        val kept = JSONArray()
        d.captions.sortedBy { if (it.auto) 1 else 0 }.take(MaxCaptionFiles).forEachIndexed { i, t ->
            runCatching {
                val body = Net.client.newCall(okhttp3.Request.Builder().url(t.url).header("User-Agent", Net.USER_AGENT).build()).execute()
                    .use { r -> if (!r.isSuccessful) null else r.body?.string() }
                if (!body.isNullOrBlank()) {
                    val file = "${id}_$i.srv3"
                    File(captionDir, file).writeText(body)
                    kept.put(JSONObject().put("lang", t.language).put("auto", t.auto).put("url", t.url).put("file", file))
                }
            }
        }
        o.put("captions", kept)
        val segments = runCatching { SponsorBlock.fetch(id) }.getOrDefault(emptyList())
        o.put("segments", JSONArray().apply { segments.forEach { put(JSONObject().put("uuid", it.uuid).put("category", it.category.key).put("start", it.startMs).put("end", it.endMs)) } })
        return o
    }

    private fun detailsFrom(e: Entry, s: JSONObject?): VideoDetails {
        val item = withLocalThumb(
            if (s == null) e.item else e.item.copy(
                title = s.optString("title").ifBlank { e.item.title },
                channel = s.optString("channel").ifBlank { e.item.channel },
                channelUrl = s.optString("channelUrl").ifBlank { null } ?: e.item.channelUrl,
                avatar = s.optString("avatar").ifBlank { null } ?: e.item.avatar,
                durationSec = s.optLong("durationSec", e.item.durationSec),
                views = s.optLong("views", e.item.views),
                uploaded = s.optString("uploaded").ifBlank { null } ?: e.item.uploaded,
            ),
        )
        val links = s?.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { DescriptionLink(it.optInt("s"), it.optInt("e"), it.optString("u")) } } }.orEmpty()
        val chapters = s?.optJSONArray("chapters")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Chapter(it.optLong("ms"), it.optString("t")) } } }.orEmpty()
        val captions = s?.optJSONArray("captions")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { CaptionTrack(it.optString("lang"), it.optBoolean("auto"), it.optString("url")) } } }.orEmpty()
        return VideoDetails(
            item = item,
            description = Description(s?.optString("description").orEmpty(), links),
            likes = s?.optLong("likes", -1) ?: -1,
            subscribers = s?.optLong("subscribers", -1) ?: -1,
            related = emptyList(),
            playable = true,
            quality = e.height?.let { "${it}p" },
            qualities = listOfNotNull(e.height),
            aspect = s?.optDouble("aspect")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            chapters = chapters,
            captions = captions,
        )
    }

    /** Online, a downloaded video still plays from the phone: the live details, shown with the kept stream's quality. */
    fun overlay(d: VideoDetails): VideoDetails {
        val e = entries[d.item.id]?.takeIf { it.ready } ?: return d
        // One kept sound track: no dub picker for it.
        return d.copy(quality = e.height?.let { "${it}p" }, qualities = listOfNotNull(e.height), playable = true, audioTracks = emptyList(), audioTrackId = null)
    }

    private fun saveThumbnail(id: String, url: String?) {
        val target = File(dir, "thumbs/$id.jpg")
        if (target.exists()) return
        val link = url?.takeIf { it.startsWith("http") } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        runCatching {
            target.parentFile?.mkdirs()
            Net.client.newCall(okhttp3.Request.Builder().url(link).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }.onFailure { target.delete() }
    }

    // ── On disk ──

    private fun save() {
        val all = entries.values.toList()
        val json = JSONArray().apply { all.forEach { put(it.toJson()) } }.toString()
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(indexFile)
        }.onFailure { Log.w(TAG, "downloads: couldn't save", it) }
    }

    private fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                var e = entryFrom(arr.getJSONObject(i)) ?: continue
                // Bytes the system or a crash lost: download the rest again.
                if (e.ready && !complete(e)) e = e.copy(status = Status.Queued)
                if (e.status == Status.Downloading) e = e.copy(status = Status.Queued)
                entries[e.id] = e
            }
        }.onFailure { Log.w(TAG, "downloads: unreadable index; starting empty", it) }
    }

    /**
     * Bytes, thumbnails and caption files no download owns (a removal whose bytes were still waiting on its
     * Undo window or on playback when the app was closed; an earlier quality's piece). The media cache never
     * evicts, so nothing else would ever free them. Off the main thread, from the index as loaded.
     */
    private fun pruneOrphans() {
        val c = cache ?: return
        // Read live for each candidate (a snapshot map is safe to read off the main thread): a download asked for
        // while this runs owns its keys and files by then.
        fun keeps(id: String, key: String?): Boolean {
            val e = entries[id] ?: return false
            // A ready download keeps only its own tracks; one under way may reuse any of its keys.
            return key == null || !e.ready || listOfNotNull(e.video, e.audio).any { it.key == key }
        }
        Thread({
            runCatching {
                var freed = 0L
                c.keys.filter { it.startsWith("skipii-dl://") }.forEach { key ->
                    val id = key.removePrefix("skipii-dl://").substringBefore('/')
                    if (!keeps(id, key)) { freed += c.getCachedBytes(key, 0, Long.MAX_VALUE).coerceAtLeast(0); c.removeResource(key) }
                }
                File(dir, "thumbs").listFiles()?.filter { !keeps(it.nameWithoutExtension, null) }?.forEach { it.delete() }
                File(dir, "captions").listFiles()?.filter { !keeps(it.name.substringBeforeLast('_'), null) }?.forEach { it.delete() }
                if (freed > 0) Log.i(TAG, "downloads: ${freed / 1024} KB no download owned, freed")
            }.onFailure { Log.w(TAG, "downloads: orphan check failed", it) }
        }, "downloads-prune").start()
    }

    private fun complete(e: Entry): Boolean {
        val c = cache ?: return false
        return listOfNotNull(e.video, e.audio).let { tracks -> tracks.isNotEmpty() && e.audio != null && tracks.all { c.isCached(it.key, 0, it.bytes) } }
    }

    private fun Entry.toJson() = JSONObject()
        .put("id", item.id).put("title", item.title).put("channel", item.channel).put("channelUrl", item.channelUrl)
        .put("thumbnail", item.thumbnail).put("avatar", item.avatar).put("durationSec", item.durationSec)
        .put("views", item.views).put("uploaded", item.uploaded)
        .put("height", height ?: 0).put("source", source.name).put("addedAt", addedAt).put("status", status.name)
        .put("video", video?.toJson()).put("audio", audio?.toJson())
        .put("doneBytes", doneBytes).put("totalBytes", totalBytes).put("error", error).put("snapshot", snapshot)

    private fun Track.toJson() = JSONObject().put("key", key).put("itag", itag).put("manifest", manifest).put("bytes", bytes)

    private fun entryFrom(o: JSONObject): Entry? = runCatching {
        fun track(t: JSONObject?) = t?.let { Track(it.getString("key"), it.getInt("itag"), it.getString("manifest"), it.getLong("bytes")) }
        Entry(
            item = VideoItem(
                id = o.getString("id"), title = o.optString("title"), channel = o.optString("channel"),
                channelUrl = o.optString("channelUrl").ifBlank { null }, thumbnail = o.optString("thumbnail").ifBlank { null },
                avatar = o.optString("avatar").ifBlank { null }, durationSec = o.optLong("durationSec"),
                views = o.optLong("views", -1), uploaded = o.optString("uploaded").ifBlank { null },
            ),
            height = o.optInt("height").takeIf { it > 0 },
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.Manual),
            addedAt = o.optLong("addedAt"),
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.Queued),
            video = track(o.optJSONObject("video")),
            audio = track(o.optJSONObject("audio")),
            doneBytes = o.optLong("doneBytes"),
            totalBytes = o.optLong("totalBytes"),
            error = o.optString("error").ifBlank { null },
            snapshot = o.optString("snapshot").ifBlank { null },
        )
    }.getOrNull()

    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun update(caps: NetworkCapabilities?) {
            val m = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            main.post { metered = m }
        }
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = update(null)
            })
        }
    }

    // ── The download notification ──

    internal const val ChannelId = "downloads"

    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ChannelId) != null) return
        nm.createNotificationChannel(NotificationChannel(ChannelId, str(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
    }
}

/** Runs the downloads one at a time while the network rule allows; a foreground job with a progress notification. */
@OptIn(UnstableApi::class)
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) { Downloads.finishing = false }
        Downloads.ensureChannel(applicationContext)
        // Watch later's first videos, when that's on (one signed-in request per run).
        if (Downloads.autoWatchLater && Account.signedIn) {
            runCatching { InnerTube.playlist("WL").videos.take(Downloads.WatchLaterCount) }
                .onSuccess { Downloads.syncWatchLater(it) }
                .onFailure { Log.w("Skipii", "downloads: Watch later not read (${it.message})") }
            kotlinx.coroutines.delay(200) // let the main thread add them
        }
        val expiredOnce = HashSet<String>()
        try {
            while (true) {
                val next = withContext(Dispatchers.Main) { Downloads.nextPending().also { Downloads.finishing = it == null } } ?: break
                // Not allowed in the foreground (Android 12+ may refuse a run started in the background): it carries
                // on quietly, with no progress notification of our own (one we posted couldn't be swiped away).
                val foreground = runCatching { setForeground(foregroundInfo(next, 0f)) }
                    .onFailure { Log.w("Skipii", "downloads: not in the foreground (${it.javaClass.simpleName}); carrying on") }
                    .isSuccess
                try {
                    Downloads.download(next) { e ->
                        Downloads.report(e)
                        if (foreground) runCatching { notificationManager()?.notify(NOTE_ID, notification(e, e.progress)) }
                            .onFailure { Log.w("Skipii", "downloads: progress notification", it) }
                    }
                } catch (x: kotlinx.coroutines.CancellationException) {
                    throw x
                } catch (x: Downloads.LinksExpired) {
                    // Fresh links once per video per run; the kept pieces carry over (same keys).
                    StreamCache.invalidate(next.id)
                    if (!expiredOnce.add(next.id)) { Downloads.fail(next, x); kotlinx.coroutines.delay(200) }
                } catch (x: Exception) {
                    Log.w("Skipii", "downloads: ${next.id} failed", x)
                    Downloads.fail(next, x)
                    kotlinx.coroutines.delay(200)
                }
            }
        } finally {
            // Also when stopped (Wi-Fi gone, storage low, Android's time limit): no notification left behind, and the
            // row it was on waits again instead of saying "Downloading" with nothing running.
            notificationManager()?.cancel(NOTE_ID)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { Downloads.requeueStuck() }
        }
        return Result.success()
    }

    private fun notificationManager() = applicationContext.getSystemService(NotificationManager::class.java)

    private fun notification(e: Downloads.Entry, progress: Float) = NotificationCompat.Builder(applicationContext, Downloads.ChannelId)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(str(R.string.dl_notification_title))
        .setContentText(e.item.title)
        .setProgress(100, (progress * 100).toInt(), progress <= 0f)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                applicationContext, 7, android.content.Intent(applicationContext, MainActivity::class.java).putExtra(EXTRA_OPEN_DOWNLOADS, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun foregroundInfo(e: Downloads.Entry, progress: Float): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTE_ID, notification(e, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTE_ID, notification(e, progress))

    companion object {
        private const val NOTE_ID = 4711
        const val EXTRA_OPEN_DOWNLOADS = "com.skipii.OPEN_DOWNLOADS"
    }
}
