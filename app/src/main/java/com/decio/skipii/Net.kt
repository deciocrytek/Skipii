package com.decio.skipii

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app: extractor requests and media playback share it. */
object Net {
    // NewPipe uses a desktop Firefox UA; YouTube serves the web client pages it parses.
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Idle connections to the video servers stay warm between videos.
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * The extractor's requests (watch page, player answers): each gives up after [EXTRACTOR_CALL_TIMEOUT_S] in all.
     * Without a limit a request on a connection that died with the network sat there until the read timed out —
     * per read — and the open video's spinner with it: 20 s and more with no line in the log, 44 s for one video's
     * links (problem reports, 2026-11-26). Same pool and dispatcher as [client].
     */
    val extractorClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(EXTRACTOR_CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
    }
    private const val EXTRACTOR_CALL_TIMEOUT_S = 20L

    /** Extractor requests under way, so a network switch can drop them ([networkChanged]). */
    internal val extractorCalls: MutableSet<okhttp3.Call> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Counts connections coming up after none, or a switch to another network. The player tries again on each when
     * the open video failed for want of a connection or on YouTube's bot check (its advice is airplane mode on and
     * off: the network coming back is that moment).
     */
    val networkUps = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * The phone's network changed (or went): connections opened on the old one are dead but don't know it yet.
     * Idle ones are dropped so the next request opens a fresh one, and the extractor's requests under way are
     * cancelled so the load can be tried again at once ([onceMoreAfterStall]) instead of waiting out a timeout. The
     * player's own media requests are left to finish or fail by themselves: it retries those on its own.
     */
    fun networkChanged(up: Boolean, first: Boolean = false) {
        // The first network the app sees has nothing old to drop: cancelling then only broke healthy startup loads.
        if (!first) {
            client.connectionPool.evictAll()
            extractorCalls.forEach { it.cancel() }
        }
        if (up) networkUps.value++
    }

    /**
     * When an extractor request last stalled or was cancelled. NewPipe swallows a failed VISIONOS request (the one
     * every stream link comes from) and returns the video anyway, with no separate streams: [StreamCache.getOrLoad]
     * checks this so such a load counts as a stall (tried again) instead of being kept for hours without its streams.
     */
    @Volatile var lastStallAt = 0L
        private set

    internal fun noteStall() { lastStallAt = android.os.SystemClock.elapsedRealtime() }

    /**
     * [load], tried once more if it stalled — a timeout, a cancel from [networkChanged], a reset connection — with
     * the idle connections dropped first. A second stall stands.
     */
    fun <T> onceMoreAfterStall(what: String, load: () -> T): T = try {
        load()
    } catch (e: Exception) {
        if (!isStall(e)) throw e
        AppLog.w("Skipii", "$what stalled (${e.javaClass.simpleName}: ${e.message}); trying once more on a fresh connection")
        client.connectionPool.evictAll()
        load()
    }

    /** A request that hung or lost its connection, as opposed to an answer (bot check, unavailable) or no network at all. */
    fun isStall(e: Throwable): Boolean = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.any {
        it is java.io.InterruptedIOException || it is java.net.SocketException ||
            (it is java.io.IOException && it.message.equals("Canceled", ignoreCase = true))
    }

    /** The video-server host YouTube last handed out; usually the same one next time. */
    @Volatile private var mediaHost: String? = null

    fun noteMediaHost(url: String?) {
        url?.toHttpUrlOrNull()?.host?.let { mediaHost = it }
    }

    /**
     * Opens (DNS + TLS) a connection to the likely video server while the stream data is
     * still being extracted, so the player's first requests skip ~0.7 s of setup. A cheap
     * request to /generate_204; if YouTube picks another host this time, nothing is lost.
     */
    fun prewarmMediaConnection() {
        val host = mediaHost ?: return
        val request = okhttp3.Request.Builder().url("https://$host/generate_204").head().header("User-Agent", USER_AGENT).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }
}

/** Bridges NewPipeExtractor's HTTP abstraction onto OkHttp. */
class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", Net.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val call = client.newCall(builder.build())
        Net.extractorCalls += call
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // A slow or failed request is named in the notebook: a load that hung 20+ s used to leave no line at all.
        fun took() = android.os.SystemClock.elapsedRealtime() - startedAt
        val what = { "${request.httpMethod()} ${call.request().url.host}${call.request().url.encodedPath}" }
        // The body is read inside: a stall while it arrives is cancelled and timed like one before the headers.
        val (code, message, headers, text, finalUrl) = try {
            call.execute().use { response ->
                // YouTube answers rate-limited clients with 429 + a captcha page.
                if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                Answer(response.code, response.message, response.headers.toMultimap(), response.body?.string(), response.request.url.toString())
            }.also { if (took() > SLOW_MS) AppLog.w("Skipii", "extractor: ${what()} answered ${it.code} after ${took()} ms") }
        } catch (e: java.io.IOException) {
            if (Net.isStall(e)) Net.noteStall()
            AppLog.w("Skipii", "extractor: ${what()} failed after ${took()} ms (${e.javaClass.simpleName}: ${e.message})")
            throw e
        } finally {
            Net.extractorCalls -= call
        }
        return Response(
            code,
            message,
            headers,
            if (PlayerFallback.handles(request.url())) PlayerFallback.substitute(request.url(), request.dataToSend(), text) else text,
            finalUrl,
        )
    }

    private data class Answer(val code: Int, val message: String, val headers: Map<String, List<String>>, val text: String?, val url: String)

    companion object {
        private const val SLOW_MS = 4_000L
        val instance: NewPipeDownloader by lazy { NewPipeDownloader(Net.extractorClient) }
    }
}
