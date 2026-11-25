package com.decio.skipii

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Skipii's own notebook (GitHub #102, 2026-11-24: "the red play button stays, it never plays", and nothing
 * on the phone to tell us why). Every line the app logs (tags "Skipii" / "SkipiiTime") goes through here
 * too: kept in memory and in filesDir/diagnostics/log.txt (it outlives a force stop, so the run before the
 * report is still there), the newest [KeptLines]. Lines are cleaned as they're written ([clean]): links lose
 * their query (googlevideo links carry the phone's address), e-mail addresses and cookies are blanked.
 * Read only by [ProblemReport], which the person shares themselves.
 */
object AppLog {
    private const val KeptLines = 2_500
    /** The file is rewritten from memory once it grows past this. */
    private const val FileMaxBytes = 700_000L
    private const val LineMax = 1_200

    private val lock = Any()
    private val lines = ArrayDeque<String>(KeptLines)
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "skipii-log").apply { isDaemon = true } }
    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Loads what the last runs noted, then appends from here on. Right after [CrashLog.install]. */
    fun init(context: Context) {
        val f = File(File(context.filesDir, "diagnostics").apply { mkdirs() }, "log.txt")
        val earlier = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())
        synchronized(lock) {
            val now = lines.toList()
            lines.clear()
            earlier.takeLast(KeptLines).forEach(lines::addLast)
            lines.addLast("${stamp()} - ── Skipii ${BuildConfig.VERSION_NAME} started ──")
            now.forEach(lines::addLast)
            while (lines.size > KeptLines) lines.removeFirst()
            file = f
        }
        rewrite()
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        add("I", tag, message, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        add("W", tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        add("E", tag, message, error)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        add("D", tag, message, null)
    }

    /** What's kept, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    private fun add(level: String, tag: String, message: String, error: Throwable?) {
        val text = buildString {
            append(stamp()).append(' ').append(level).append(' ')
            if (tag != "Skipii") append(tag).append(": ")
            append(message)
            if (error != null) append(" — ").append(describe(error))
        }
        val line = clean(text).take(LineMax)
        val f: File?
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > KeptLines) lines.removeFirst()
            f = file
        }
        if (f != null) writer.execute {
            runCatching {
                if (f.length() > FileMaxBytes) rewriteNow() else f.appendText(line + "\n")
            }
        }
    }

    private fun rewrite() = writer.execute { runCatching { rewriteNow() } }

    private fun rewriteNow() {
        val (f, all) = synchronized(lock) { file to lines.toList() }
        f ?: return
        val tmp = File(f.parentFile, "log.tmp")
        tmp.writeText(all.joinToString("\n", postfix = "\n"))
        tmp.renameTo(f)
    }

    private fun stamp(): String = synchronized(stampFormat) { stampFormat.format(Date()) }

    /** An error and its causes on one line, with where it was thrown from (a few frames). */
    fun describe(error: Throwable): String = buildString {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) append(" ← caused by ")
            append(t.javaClass.simpleName)
            t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(300)) }
            if (t is HttpDataSource.InvalidResponseCodeException) append(" [HTTP ${t.responseCode}]")
            t = t.cause.takeIf { it !== t }
            depth++
        }
        error.stackTrace.take(3).takeIf { it.isNotEmpty() }?.let { frames ->
            append(" @ ").append(frames.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
        }
    }

    private val UrlQuery = Regex("(https?://[^\\s?#\"'<>]+)[?#][^\\s\"'<>]*")
    private val IpParam = Regex("\\b(ip|ipbits|cpn|pot|sig|lsig|n)=[^&\\s\"']+")
    private val Email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val Cookie = Regex("\\b(SAPISID|__Secure-[0-9A-Za-z-]+|SID|HSID|SSID|APISID|LOGIN_INFO|VISITOR_INFO1_LIVE)=[^;\\s\"']+")
    private val Ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    /** Nothing that points at the person: no link queries (addresses, tokens), e-mails, cookies or IP addresses. */
    internal fun clean(text: String): String = text
        .replace(UrlQuery, "$1?…")
        .replace(IpParam, "$1=…")
        .replace(Email, "<email>")
        .replace(Cookie, "$1=…")
        .replace(Ipv4, "<ip>")
}

/**
 * The player's side of the notebook: what the playback engine did that it otherwise keeps to itself. A load
 * that fails and is retried, a request that never finishes, which formats and decoders came up, and a line
 * every so often while it sits loading ([StallWatch]) — #102's spinner showed no error at all, so these are
 * the lines that would say why.
 */
@OptIn(UnstableApi::class)
internal class PlaybackDiagnostics(private val player: ExoPlayer) : AnalyticsListener {
    private class OpenLoad(val startedAt: Long, val what: String)
    private val open = HashMap<Long, OpenLoad>()
    private var bytesLoaded = 0L
    private var lastEstimateKbps = 0L
    private var lastLoadError: String? = null
    private var loadErrors = 0
    private var itemId: String? = null
    private val main = Handler(Looper.getMainLooper())

    private var stallStartedAt = 0L
    private var stallBytesAtStart = 0L
    private var stallReports = 0
    private val stallWatch = object : Runnable {
        override fun run() {
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
            val now = SystemClock.elapsedRealtime()
            val forMs = now - stallStartedAt
            val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            val oldest = open.values.minByOrNull { it.startedAt }
            AppLog.w(
                "Skipii",
                "player: ${itemId} still loading after ${forMs / 1000} s at ${player.currentPosition / 1000}s · " +
                    "${ahead / 1000.0} s loaded ahead · ${(bytesLoaded - stallBytesAtStart) / 1024} KB arrived meanwhile · " +
                    "estimate ${lastEstimateKbps} kbps · ${open.size} request(s) open" +
                    (oldest?.let { " (oldest ${(now - it.startedAt) / 1000} s, ${it.what})" } ?: "") +
                    " · loading=${player.isLoading}" + (lastLoadError?.let { " · last load error: $it" } ?: ""),
            )
            stallReports++
            if (stallReports < 8) main.postDelayed(this, if (stallReports < 3) 10_000 else 30_000)
        }
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        itemId = mediaItem?.mediaId
        loadErrors = 0
        lastLoadError = null
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val id = player.currentMediaItem?.mediaId
        itemId = id
        val name = when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "loading"
            Player.STATE_READY -> "ready"
            else -> "ended"
        }
        AppLog.i("Skipii", "player: $id $name at ${player.currentPosition / 1000}s (${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000} s ahead, playWhenReady=${player.playWhenReady})")
        main.removeCallbacks(stallWatch)
        if (state == Player.STATE_BUFFERING) {
            stallStartedAt = SystemClock.elapsedRealtime()
            stallBytesAtStart = bytesLoaded
            stallReports = 0
            main.postDelayed(stallWatch, 10_000)
        }
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            main.removeCallbacks(stallWatch)
            stallStartedAt = SystemClock.elapsedRealtime()
            stallBytesAtStart = bytesLoaded
            stallReports = 0
            main.postDelayed(stallWatch, 10_000)
        }
    }

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
        open[info.loadTaskId] = OpenLoad(SystemClock.elapsedRealtime(), what(info, data))
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
        open.remove(info.loadTaskId)
        bytesLoaded += info.bytesLoaded
        // A request that took this long is worth a line even though it finished (a crawl, not a failure).
        if (info.loadDurationMs > 8_000) {
            AppLog.w("Skipii", "player: slow load ${what(info, data)}: ${info.bytesLoaded / 1024} KB in ${info.loadDurationMs / 1000.0} s")
        }
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
        open.remove(info.loadTaskId)
        bytesLoaded += info.bytesLoaded
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        open.remove(info.loadTaskId)
        bytesLoaded += info.bytesLoaded
        val http = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        lastLoadError = "${http?.let { "HTTP $it " } ?: ""}${error.javaClass.simpleName}"
        loadErrors++
        // Retried quietly by the player; only the first few per video, the stall line counts the rest.
        if (loadErrors <= 6) {
            AppLog.w("Skipii", "player: load error #$loadErrors ${what(info, data)} after ${info.loadDurationMs} ms, ${info.bytesLoaded / 1024} KB${if (wasCanceled) ", canceled" else ""}", error)
        }
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        lastEstimateKbps = bitrateEstimate / 1000
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        AppLog.i("Skipii", "player: video format ${describe(format)}")
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        AppLog.i("Skipii", "player: audio format ${describe(format)}")
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        AppLog.i("Skipii", "player: video decoder $decoderName (${initializationDurationMs} ms)")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        AppLog.i("Skipii", "player: audio decoder $decoderName (${initializationDurationMs} ms)")
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        AppLog.w("Skipii", "player: video decoder error", videoCodecError)
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        AppLog.w("Skipii", "player: audio decoder error", audioCodecError)
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        AppLog.w("Skipii", "player: audio output error", audioSinkError)
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        main.removeCallbacks(stallWatch)
        AppLog.w("Skipii", "player: error ${error.errorCodeName} for ${player.currentMediaItem?.mediaId} at ${player.currentPosition / 1000}s", error)
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (droppedFrames >= 30) AppLog.w("Skipii", "player: $droppedFrames frames dropped in $elapsedMs ms")
    }

    private fun what(info: LoadEventInfo, data: MediaLoadData): String {
        val type = when (data.trackType) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            else -> "media"
        }
        val kind = when (data.dataType) {
            C.DATA_TYPE_MEDIA -> ""
            C.DATA_TYPE_MEDIA_INITIALIZATION -> " init"
            C.DATA_TYPE_MANIFEST -> " manifest"
            else -> ""
        }
        val fmt = data.trackFormat?.let { f -> listOfNotNull(f.codecs, f.height.takeIf { it > 0 }?.let { "${it}p" }).joinToString(" ").takeIf { it.isNotEmpty() }?.let { " ($it)" } }.orEmpty()
        val host = info.dataSpec.uri.host?.let { " from $it" }.orEmpty()
        val range = if (info.dataSpec.length != C.LENGTH_UNSET.toLong()) " bytes ${info.dataSpec.position}+${info.dataSpec.length}" else " from byte ${info.dataSpec.position}"
        return "$type$kind$fmt$range$host"
    }

    private fun describe(format: Format): String = buildString {
        format.codecs?.let { append(it).append(' ') }
        if (format.height > 0) append("${format.width}×${format.height} ")
        if (format.frameRate > 0) append("${format.frameRate.toInt()} fps ")
        if (format.bitrate > 0) append("${format.bitrate / 1000} kbps ")
        format.language?.let { append("lang ").append(it) }
    }.trim()
}

/**
 * The report a person e-mails to the developer (Settings › About › Problem report, the player's error panel, and the note under
 * a video that won't start): the phone, Skipii's settings, the connection, what the player is doing right
 * now, the recent notebook ([AppLog]) and the crash record ([CrashLog]). Markdown, under GitHub's 65,536
 * characters for a comment. Never the account (no name, e-mail or cookies); videos appear only as their
 * IDs in the recent lines, which is what makes a stuck one reproducible.
 */
object ProblemReport {
    /** GitHub refuses a comment longer than 65,536 characters; leave room for the person's own words. */
    private const val MaxChars = 60_000

    /** The player's side, read on the main thread (the MediaController insists). */
    fun playerSummary(player: PlayerState?): String = buildString {
        if (player == null) { appendLine("No player."); return@buildString }
        val item = player.current
        if (item == null) { appendLine("Nothing open."); return@buildString }
        val c = player.controller
        appendLine("- Video: `${item.id}`${if (player.expanded) " (open)" else " (mini-player)"}")
        appendLine("- Position ${formatMs(player.positionMs)} of ${formatMs(player.durationMs)}, loaded to ${formatMs(player.bufferedMs)}")
        appendLine("- Playing ${player.isPlaying}, loading ${player.isBuffering}, details ${if (player.details != null) "in" else if (player.loading) "loading" else "none"}")
        if (c != null) {
            val state = when (c.playbackState) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "loading"; Player.STATE_READY -> "ready"; else -> "ended" }
            appendLine("- Engine: $state, playWhenReady ${c.playWhenReady}, speed ${c.playbackParameters.speed}×, ${c.mediaItemCount} item(s), error ${c.playerError?.errorCodeName ?: "none"}")
        } else appendLine("- Engine: not connected")
        player.playingFormat?.let { appendLine("- Decoding: $it") }
        player.error?.let { appendLine("- Error shown: ${it.replace('\n', ' ')}${if (player.botCheck) " (bot check)" else ""}") }
        player.stuckForMs.takeIf { it > 0 }?.let { appendLine("- Loading for ${it / 1000} s") }
        if (StreamRecovery.recovering != null) appendLine("- Fetching fresh links for `${StreamRecovery.recovering}`")
        BotCheck.waiting?.let { appendLine("- Waiting out a bot check for `$it`") }
        val d = player.details
        if (d != null) {
            appendLine("- Audio tracks: ${if (d.audioTracks.isEmpty()) "one" else d.audioTracks.joinToString { "${it.id}${if (it.isOriginal) " (original)" else if (it.autoDubbed) " (auto-dub)" else ""}" }}; playing ${d.audioTrackId ?: "default"}")
        }
        val downloaded = Downloads.entry(item.id)
        if (downloaded != null) appendLine("- Downloaded: ${if (downloaded.ready) "yes" else "not finished"}")
    }

    /** Everything else; reads files and Android's records, so off the main thread. */
    fun build(context: Context, playerPart: String): String {
        val head = buildString {
            appendLine("**Skipii problem report** · ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine()
            appendLine(CrashLog.header(context))
            appendLine("Settings: quality ${QualityPreference.height?.let { "${it}p" } ?: "Auto"}, audio track ${AudioTrackPreference.mode.name}${AudioTrackPreference.preferredLanguage?.let { " ($it)" }.orEmpty()}, " +
                "background play ${onOff(BackgroundPlay.on)}, language ${AppLanguage.locale.toLanguageTag()}")
            appendLine("Connection: ${connection(context)}")
            appendLine()
            appendLine("## Player now")
            append(playerPart)
            appendLine()
        }
        val tail = buildString {
            appendLine()
            append(CrashLog.sections(context))
        }
        // The notebook gets what's left, newest lines kept.
        val room = (MaxChars - head.length - tail.length - 200).coerceAtLeast(4_000)
        val kept = ArrayList<String>()
        var used = 0
        for (line in fold(AppLog.snapshot()).asReversed()) {
            if (used + line.length + 1 > room) break
            kept += line
            used += line.length + 1
        }
        kept.reverse()
        return buildString {
            append(head)
            appendLine("## Recent notes (${kept.size} lines, oldest first)")
            appendLine("```")
            kept.forEach(::appendLine)
            appendLine("```")
            append(tail)
        }.take(MaxChars)
    }

    private val VideoId = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])")
    private val Digits = Regex("\\d+")
    private val OpenedId = Regex("(?:SkipiiTime: open|media source) ([A-Za-z0-9_-]{11})\\b")

    /**
     * Background work that keeps failing the same way (the Shorts bank under a bot check: three lines every
     * 1.5 s) would fill the report and push out what matters. A line saying what one of the last few lines
     * already said, but for another video or another number, is counted instead: "… 40 similar lines".
 * Lines are "MM-dd HH:mm:ss.SSS L message"; the key drops the stamp.
     */
    internal fun fold(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        val recent = ArrayDeque<String>()
        var skipped = 0
        // Videos the person opened (or the player started): every line about them stays.
        val opened = lines.mapNotNullTo(HashSet()) { OpenedId.find(it)?.groupValues?.get(1) }
        for (line in lines) {
            val key = line.substringAfter(' ').substringAfter(' ').replace(VideoId, "#").replace(Digits, "#")
            // The player's own lines always stay too: a stall's numbers changing over time are the point.
            val keep = line.contains(" player: ") || VideoId.findAll(line).any { it.value in opened }
            if (key in recent && !keep) { skipped++; continue }
            if (skipped > 0) { out += "        … $skipped similar lines"; skipped = 0 }
            out += line
            recent.addLast(key)
            if (recent.size > 6) recent.removeFirst()
        }
        if (skipped > 0) out += "        … $skipped similar lines"
        return out
    }

    @SuppressLint("MissingPermission")
    private fun connection(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        val kinds = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile data")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.ifEmpty { listOf("other") }
        val saver = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on (Skipii allowed)"
            else -> "off"
        }
        val power = context.getSystemService(PowerManager::class.java)
        kinds.joinToString(" + ") +
            ", ${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unmetered" else "metered"}" +
            ", ${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet checked" else "internet NOT confirmed"}" +
            ", ~${caps.linkDownstreamBandwidthKbps / 1000} Mbps down (Android's guess)" +
            ", Data Saver $saver, Battery Saver ${onOff(power?.isPowerSaveMode == true)}"
    }.getOrElse { "unknown (${it.javaClass.simpleName})" }

    private fun formatMs(ms: Long) = "%d:%02d".format(Locale.US, ms / 60_000, (ms / 1000) % 60)
    private fun onOff(on: Boolean) = if (on) "on" else "off"
}

/**
 * One tap → the person's e-mail app, addressed to the developer ([DeveloperEmail]) with the report filled in under
 * a line for their own words (Derek, 2026-11-24: reports have to reach him, and the share sheet let them go
 * anywhere). SEND with a mailto: selector so only e-mail apps answer and they read the subject and body (several
 * ignore EXTRA_TEXT on a plain SENDTO). No e-mail app: the share sheet, so the report can still go somewhere.
 * The player's part is read here, on the main thread; the rest is built on IO.
 */
@Composable
fun rememberProblemReportSender(player: PlayerState?): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = androidx.compose.ui.res.stringResource(R.string.settings_crash_share)
    val prompt = androidx.compose.ui.res.stringResource(R.string.report_email_prompt)
    return remember(player, context, scope, title, prompt) {
        {
            val playerPart = ProblemReport.playerSummary(player)
            scope.launch {
                val report = withContext(Dispatchers.IO) {
                    ProblemReport.build(context, playerPart).also {
                        // Debug builds keep the last one where adb can read it (run-as, cache/last-report.md).
                        if (BuildConfig.DEBUG) runCatching { File(context.cacheDir, "last-report.md").writeText(it) }
                    }
                }
                val subject = "Skipii ${BuildConfig.VERSION_NAME} problem report"
                val email = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(DeveloperEmail))
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, "$prompt\n\n\n────────\n$report")
                    .apply { selector = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(email)
                    AppLog.i("Skipii", "problem report: opened in the e-mail app")
                } catch (_: android.content.ActivityNotFoundException) {
                    AppLog.i("Skipii", "problem report: no e-mail app; share sheet instead")
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, subject)
                        .putExtra(Intent.EXTRA_TEXT, report)
                    // Not Skipii itself (it takes shared text for YouTube links).
                    val chooser = Intent.createChooser(share, title)
                        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, MainActivity::class.java)))
                    context.openOrSay(chooser) { player?.actionMessage = it }
                }
                CrashLog.markShared()
            }
        }
    }
}

/**
 * Under a video that has shown the spinner for [StuckAfterMs] ([PlayerState.stuck]): it says so and offers
 * the report. Empty (no height) otherwise; it grows in and folds away once the video starts or fails.
 */
@Composable
fun StuckNote(state: PlayerState, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val c = androidx.compose.material3.MaterialTheme.colorScheme
    val send = rememberProblemReportSender(state)
    androidx.compose.animation.AnimatedVisibility(
        visible = state.stuck && state.error == null,
        modifier = modifier,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f)) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) + androidx.compose.animation.fadeOut(),
    ) {
        androidx.compose.foundation.layout.Column {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(14.dp))
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .background(c.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.HourglassTop, null,
                    tint = c.primary, modifier = androidx.compose.ui.Modifier.size(20.dp),
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(R.string.report_stuck),
                    color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(10.dp))
                PillButton(androidx.compose.ui.res.stringResource(R.string.report_send), send)
            }
        }
    }
}
