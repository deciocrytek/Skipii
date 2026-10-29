package com.decio.skipii

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

@Immutable
data class Profile(val name: String, val handle: String?, val avatar: String?)

@Immutable
data class PlaylistRef(val id: String, val title: String, val thumbnail: String?, val subtitle: String?)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?)

/** One page of a feed plus the token for the next page, if any. */
@Immutable
data class FeedPage(val videos: List<VideoItem>, val next: String?)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(val like: LikeStatus, val subscribed: Boolean?, val channelId: String?)

class NotSignedInException : IOException("Not signed in")

/**
 * The signed-in Google session (user chose full sync, 2026-10-04).
 *
 * Sign-in happens on Google's own page in a WebView; afterwards only the youtube.com
 * cookies are kept, encrypted with a Keystore key ([SecureStore]). Requests are authorized
 * the way youtube.com itself does it (SAPISIDHASH). Cookies YouTube rotates in responses
 * are merged back in so the session stays alive.
 */
object Account {
    var profile by mutableStateOf<Profile?>(null)
        private set
    val signedIn get() = profile != null

    private var store: SecureStore? = null
    private val cookies = linkedMapOf<String, String>()
    private var clientVersion = FALLBACK_CLIENT_VERSION
    private var visitorData: String? = null

    fun init(context: Context) {
        val s = SecureStore(context, "account").also { store = it }
        s.get(KEY_COOKIES)?.let { parseCookieHeader(it, cookies) }
        s.get(KEY_CLIENT_VERSION)?.let { clientVersion = it }
        visitorData = s.get(KEY_VISITOR)
        val name = s.get(KEY_NAME)
        if (name != null && cookies.containsKey("SAPISID")) {
            profile = Profile(name, s.get(KEY_HANDLE), s.get(KEY_AVATAR))
        }
    }

    /** True once the WebView's cookies prove a completed YouTube sign-in. */
    fun looksSignedIn(cookieHeader: String?): Boolean {
        val names = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        return "SAPISID" in names && "LOGIN_INFO" in names
    }

    /** Takes over the WebView session: stores the cookies, then loads the account's name. */
    suspend fun completeSignIn(cookieHeader: String) = withContext(Dispatchers.IO) {
        synchronized(cookies) {
            cookies.clear()
            parseCookieHeader(cookieHeader, cookies)
        }
        persistCookies()
        refreshClientConfig()
        val menu = InnerTube.post("account/account_menu", JSONObject())
        var found: Profile? = null
        menu.walk { key, obj ->
            if (key == "activeAccountHeaderRenderer" && found == null) {
                found = Profile(
                    name = obj.text("accountName") ?: "YouTube account",
                    handle = obj.text("channelHandle"),
                    avatar = obj.optJSONObject("accountPhoto")?.thumbnails()?.lastOrNull(),
                )
            }
        }
        val p = found ?: Profile("YouTube account", null, null)
        store?.apply {
            put(KEY_NAME, p.name); put(KEY_HANDLE, p.handle); put(KEY_AVATAR, p.avatar)
        }
        withContext(Dispatchers.Main) { profile = p }
    }

    fun signOut() {
        synchronized(cookies) { cookies.clear() }
        store?.clear()
        profile = null
        android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
    }

    /** youtube.com embeds the current web client version; requests with a stale one get odd results. */
    private fun refreshClientConfig() {
        runCatching {
            val request = Request.Builder().url("https://www.youtube.com/?hl=en")
                .header("User-Agent", Net.USER_AGENT)
                .header("Cookie", cookieHeader())
                .build()
            Net.client.newCall(request).execute().use { r ->
                absorbSetCookies(r.headers("Set-Cookie"))
                val html = r.body?.string().orEmpty()
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { visitorData = it }
            }
            store?.put(KEY_CLIENT_VERSION, clientVersion)
            store?.put(KEY_VISITOR, visitorData)
        }
    }

    internal fun clientVersion() = clientVersion
    internal fun visitorData() = visitorData

    internal fun cookieHeader(): String = synchronized(cookies) { cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } }

    /** youtube.com's own request signature: SHA-1 of "timestamp SAPISID origin". */
    internal fun authorization(origin: String = ORIGIN): String? {
        val ts = System.currentTimeMillis() / 1000
        fun hash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$ts $secret $origin".toByteArray())
            return "${ts}_" + digest.joinToString("") { "%02x".format(it) }
        }
        val (sapisid, p1, p3) = synchronized(cookies) {
            Triple(cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"], cookies["__Secure-1PAPISID"], cookies["__Secure-3PAPISID"])
        }
        sapisid ?: return null
        return buildString {
            append("SAPISIDHASH ").append(hash(sapisid))
            p1?.let { append(" SAPISID1PHASH ").append(hash(it)) }
            p3?.let { append(" SAPISID3PHASH ").append(hash(it)) }
        }
    }

    /** Merges rotated cookies from a response (Google refreshes __Secure-*PSIDTS regularly). */
    internal fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        var changed = false
        synchronized(cookies) {
            setCookies.forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                if (cookies[name] != value) { cookies[name] = value; changed = true }
            }
        }
        if (changed) persistCookies()
    }

    private fun persistCookies() = store?.put(KEY_COOKIES, cookieHeader())

    private fun parseCookieHeader(header: String, into: MutableMap<String, String>) {
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) into[name] = part.substringAfter('=', "").trim()
        }
    }

    const val ORIGIN = "https://www.youtube.com"
    private const val FALLBACK_CLIENT_VERSION = "2.20260901.00.00"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_CLIENT_VERSION = "client_version"
    private const val KEY_VISITOR = "visitor"
    private const val KEY_NAME = "name"
    private const val KEY_HANDLE = "handle"
    private const val KEY_AVATAR = "avatar"
}

/**
 * YouTube's internal web API ("InnerTube"), signed in. Reads: home, subscriptions,
 * channels, history, playlists. Writes: like/dislike, subscribe, Watch later, watch history
 * and watch time.
 */
object InnerTube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private val JSON_TYPE = "application/json".toMediaType()

    /** Signed in when there's a session, otherwise anonymous (public pages like channels). */
    internal fun postAny(endpoint: String, body: JSONObject): JSONObject =
        if (Account.signedIn) post(endpoint, body) else post(endpoint, body, anonymous = true)

    internal fun post(endpoint: String, body: JSONObject, anonymous: Boolean = false): JSONObject {
        val auth = if (anonymous) null else Account.authorization() ?: throw NotSignedInException()
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", Account.clientVersion())
                    .put("hl", "en")
                    .put("gl", "US")
                    .apply { Account.visitorData()?.let { put("visitorData", it) } },
            ),
        )
        val request = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", Net.USER_AGENT)
            .apply { if (auth != null) { header("Cookie", Account.cookieHeader()); header("Authorization", auth) } }
            .header("Origin", Account.ORIGIN)
            .header("X-Origin", Account.ORIGIN)
            .header("Referer", Account.ORIGIN + "/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", Account.clientVersion())
            .apply { Account.visitorData()?.let { header("X-Goog-Visitor-Id", it) } }
            .build()
        Net.client.newCall(request).execute().use { r ->
            if (auth != null) Account.absorbSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("YouTube rejected the session (${r.code}). Try signing in again.")
            if (!r.isSuccessful) throw IOException("YouTube returned ${r.code}")
            return JSONObject(text)
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    suspend fun browse(browseId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", browseId)
        parseFeed(post("browse", body))
    }

    suspend fun home(next: String? = null) = browse("FEwhat_to_watch", next)

    /** A channel's uploads (Videos tab), members-only ones filtered out like everywhere else. */
    suspend fun channelVideos(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_VIDEOS_TAB)
        parseFeed(postAny("browse", body))
    }

    /** The channel page's Videos tab selector (as on youtube.com, 2026-10-06). */
    private const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
    suspend fun subscriptionsFeed(next: String? = null) = browse("FEsubscriptions", next)
    suspend fun history(next: String? = null) = browse("FEhistory", next)
    suspend fun playlist(id: String, next: String? = null) = browse("VL$id", next)

    suspend fun channels(): List<ChannelRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, ChannelRef>()
        post("browse", JSONObject().put("browseId", "FEchannels")).walk { key, obj ->
            if (key == "channelRenderer" || key == "gridChannelRenderer") {
                val id = obj.optString("channelId").ifBlank { return@walk }
                out.getOrPut(id) {
                    ChannelRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl))
                }
            }
        }
        out.values.toList()
    }

    suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, PlaylistRef>()
        post("browse", JSONObject().put("browseId", "FEplaylist_aggregation")).walk { key, obj ->
            when (key) {
                "gridPlaylistRenderer", "playlistRenderer" -> {
                    val id = obj.optString("playlistId").ifBlank { return@walk }
                    out.getOrPut(id) {
                        PlaylistRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl), obj.text("videoCountText") ?: obj.text("videoCountShortText"))
                    }
                }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    val id = obj.optString("contentId").ifBlank { return@walk }
                    val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    out.getOrPut(id) {
                        PlaylistRef(
                            id = id,
                            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
                            thumbnail = obj.firstImageSource(),
                            subtitle = obj.collectStrings("text").firstOrNull { it.contains("video", ignoreCase = true) },
                        )
                    }
                }
            }
        }
        out.values.filterNot { it.id == "WL" || it.id == "LL" }
    }

    /** Like state and subscription for a video, from the signed-in watch page data. */
    suspend fun videoState(videoId: String): VideoAccountState = withContext(Dispatchers.IO) {
        val next = post("next", JSONObject().put("videoId", videoId))
        var like = LikeStatus.None
        var subscribed: Boolean? = null
        var channelId: String? = null
        next.walk { key, obj ->
            when (key) {
                "likeStatusEntity" -> like = when (obj.optString("likeStatus")) {
                    "LIKE" -> LikeStatus.Like
                    "DISLIKE" -> LikeStatus.Dislike
                    else -> like
                }
                "subscribeButtonRenderer" -> {
                    if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                    obj.optString("channelId").ifBlank { null }?.let { channelId = it }
                }
                "subscriptionStateEntity" -> if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                "videoOwnerRenderer" -> obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { if (channelId == null) channelId = it }
            }
        }
        VideoAccountState(like, subscribed, channelId)
    }

    /** Whether the account follows [channelId] (from the channel page's subscribe button). */
    suspend fun channelSubscribed(channelId: String): Boolean? = withContext(Dispatchers.IO) {
        var subscribed: Boolean? = null
        post("browse", JSONObject().put("browseId", channelId)).walk { key, obj ->
            if ((key == "subscribeButtonRenderer" || key == "subscriptionStateEntity") && obj.has("subscribed") && subscribed == null) {
                subscribed = obj.optBoolean("subscribed")
            }
        }
        subscribed
    }

    // ── Shorts ─────────────────────────────────────────────────────────────────

    /**
     * One page of the Shorts feed; [token] null for the first. YouTube's Shorts feed is no browse
     * page but the "reel sequence" that follows one short (reel/reel_watch_sequence, as on
     * youtube.com/shorts). The first page starts from a seed: signed in, the first short on your
     * Home (so the sequence is yours); else, or with none there, one from a public "shorts"
     * search — the sequence then works anonymously (checked 2026-10-28). The seed plays first.
     */
    suspend fun shorts(token: String? = null): FeedPage = withContext(Dispatchers.IO) {
        if (token != null) return@withContext parseShorts(postAny("reel/reel_watch_sequence", JSONObject().put("sequenceParams", token)))
        val seed = shortsSeed() ?: throw IOException("YouTube didn't offer any Shorts right now.")
        val page = parseShorts(postAny("reel/reel_watch_sequence", JSONObject().put("sequenceParams", reelSequenceParams(seed))))
        page.copy(videos = listOf(shortItem(seed, null)) + page.videos.filter { it.id != seed })
    }

    private fun shortsSeed(): String? {
        if (Account.signedIn) {
            runCatching { reelIds(post("browse", JSONObject().put("browseId", "FEwhat_to_watch"))).firstOrNull() }
                .getOrNull()?.let { return it }
        }
        // A different public short each time, so the generic feed doesn't always start the same way.
        return reelIds(postAny("search", JSONObject().put("query", "shorts"))).take(10).randomOrNull()
    }

    /** Every short a response links to (Home's Shorts shelf, search results), in order. */
    private fun reelIds(root: JSONObject): List<String> {
        val ids = LinkedHashSet<String>()
        root.walk { key, obj -> if (key == "reelWatchEndpoint") obj.optString("videoId").takeIf { it.length == 11 }?.let(ids::add) }
        return ids.toList()
    }

    /**
     * The sequence's start, as youtube.com encodes it (the ReelSequence protobuf, per YouTube.js):
     * 1 = the seed's id, 5 = params { 3 = 5 }, 10 = 25. Written by hand; no protobuf library.
     */
    internal fun reelSequenceParams(shortId: String): String {
        val id = shortId.toByteArray()
        val bytes = byteArrayOf(0x0A, id.size.toByte()) + id + byteArrayOf(0x2A, 0x02, 0x18, 0x05, 0x50, 25)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * A reel sequence: each `entries[]` item's command carries a reelWatchEndpoint (the short's
     * id and its 9:16 first frame); the next page is `continuationEndpoint`'s token. Titles and
     * channels aren't in it; they come with the streams (YouTube.details).
     */
    internal fun parseShorts(root: JSONObject): FeedPage {
        val videos = LinkedHashMap<String, VideoItem>()
        val entries = root.optJSONArray("entries")
        for (i in 0 until (entries?.length() ?: 0)) {
            val reel = entries?.optJSONObject(i)?.optJSONObject("command")?.optJSONObject("reelWatchEndpoint") ?: continue
            val id = reel.optString("videoId").takeIf { it.length == 11 } ?: continue
            val frame = reel.optJSONObject("thumbnail")?.collectStrings("url")?.firstOrNull()
            videos.putIfAbsent(id, shortItem(id, frame))
        }
        val next = root.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
        return FeedPage(videos.values.toList(), next)
    }

    /** A short before its details load: just its id and its first frame (9:16). */
    private fun shortItem(id: String, frame: String?) = VideoItem(
        id = id, title = "", channel = "", channelUrl = null,
        thumbnail = frame ?: "https://i.ytimg.com/vi/$id/oar2.jpg",
        avatar = null, durationSec = 0, views = 0, uploaded = null,
    )

    // ── Writes ─────────────────────────────────────────────────────────────────

    suspend fun setLike(videoId: String, status: LikeStatus) = withContext(Dispatchers.IO) {
        val endpoint = when (status) {
            LikeStatus.Like -> "like/like"
            LikeStatus.Dislike -> "like/dislike"
            LikeStatus.None -> "like/removelike"
        }
        post(endpoint, JSONObject().put("target", JSONObject().put("videoId", videoId)))
        Unit
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) = withContext(Dispatchers.IO) {
        post(
            if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)),
        )
        Unit
    }

    suspend fun setInWatchLater(videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        post("browse/edit_playlist", JSONObject().put("playlistId", "WL").put("actions", JSONArray().put(action)))
        Unit
    }

    /** One video being watched: the tracking URL for its watch-time pings. */
    class WatchSession(val videoId: String, val watchtimeUrl: String, val cpn: String)

    /**
     * Starts watching [videoId] on the account, as the web player does: fetch the signed-in
     * player response and ping its playback URL, which puts the video in watch history.
     * Returns the session for [pingWatchtime], or null if YouTube gave no tracking URLs.
     */
    fun startWatch(videoId: String): WatchSession? {
        val tracking = post("player", JSONObject().put("videoId", videoId)).optJSONObject("playbackTracking") ?: return null
        val cpn = (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")
        tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.ifBlank { null }?.let { base ->
            ping(base, "ver=2&cpn=$cpn")
        }
        val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")?.ifBlank { null } ?: return null
        return WatchSession(videoId, watchtime, cpn)
    }

    /**
     * Reports what was played since the last ping: the stretches of the video ([starts] →
     * [ends], seconds), where playback is now ([positionSec]) and how long the session has
     * run ([elapsedSec]). The same parameters the web player sends; YouTube's
     * recommendations are built on this watch time.
     */
    fun pingWatchtime(session: WatchSession, positionSec: Double, starts: List<Double>, ends: List<Double>, playing: Boolean, elapsedSec: Double, final: Boolean) {
        fun f(x: Double) = String.format(java.util.Locale.US, "%.3f", x)
        val rt = f(elapsedSec)
        val query = buildString {
            append("ver=2&cpn=").append(session.cpn)
            append("&cmt=").append(f(positionSec))
            append("&st=").append(starts.joinToString(",") { f(it) })
            append("&et=").append(ends.joinToString(",") { f(it) })
            append("&state=").append(if (playing) "playing" else "paused")
            append("&rt=").append(rt).append("&rtn=").append(rt).append("&rti=").append(elapsedSec.toInt())
            append("&lact=").append(Random.nextInt(800, 4000))
            append("&volume=100&muted=0&fs=0&hl=en_US&cr=US&c=WEB&cver=").append(Account.clientVersion())
            if (final) append("&final=1")
        }
        ping(session.watchtimeUrl, query)
    }

    private fun ping(base: String, query: String) {
        val request = Request.Builder().url(base + (if ('?' in base) "&" else "?") + query)
            .header("User-Agent", Net.USER_AGENT)
            .header("Cookie", Account.cookieHeader())
            .header("Referer", Account.ORIGIN + "/")
            .build()
        Net.client.newCall(request).execute().use { r ->
            Account.absorbSetCookies(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException("stats ping ${r.code}")
        }
    }

    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Pulls every video out of a browse response, whichever renderer YouTube used:
     * the classic videoRenderer family or the newer lockupViewModel. Shorts are skipped.
     */
    internal fun parseFeed(root: JSONObject): FeedPage {
        val videos = LinkedHashMap<String, VideoItem>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                // Members-only videos can't be played (anonymous extraction), and full movies/TV
                // aren't wanted (Derek), so neither is ever shown.
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { videos.putIfAbsent(it.id, it) }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" && !isMembersOnly(obj) && !isMovie(obj)) {
                    parseLockup(obj)?.let { videos.putIfAbsent(it.id, it) }
                }
                // Only the feed's own "load more" item (old and new names); chip filters
                // carry tokens too and must not be mistaken for the next page.
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return FeedPage(videos.values.toList(), next)
    }

    /** YouTube's "Members only" badge: BADGE_MEMBERS_ONLY (lockups) / BADGE_STYLE_TYPE_MEMBERS_ONLY (older renderers). */
    internal fun isMembersOnly(o: JSONObject): Boolean =
        o.collectStrings("badgeStyle").any { it.contains("MEMBERS_ONLY") } ||
            o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_MEMBERS_ONLY" }

    /**
     * Full movies and TV (Primetime Channels like HBO Max / Shudder, YouTube Movies): a paid-offer
     * badge ("Try now", "Buy or rent") or a content rating (R, PG-13, TV-MA), which ordinary
     * uploads never carry. Older renderers: BADGE_STYLE_TYPE_YPC / BADGE_STYLE_TYPE_MEDIA;
     * lockups: a BADGE_DEFAULT whose text is the rating (not every movie has one) or a
     * BADGE_COMMERCE offer — ordinary uploads use that style only for "Fundraiser".
     */
    internal fun isMovie(o: JSONObject): Boolean {
        if (o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_YPC" || it == "BADGE_STYLE_TYPE_MEDIA" }) return true
        var movie = false
        o.walk { key, badge ->
            if (key != "badgeViewModel") return@walk
            val text = badge.optString("badgeText")
            if (CONTENT_RATING.matches(text) ||
                (badge.optString("badgeStyle") == "BADGE_COMMERCE" && !text.equals("Fundraiser", ignoreCase = true))
            ) movie = true
        }
        return movie
    }

    private val CONTENT_RATING = Regex("^(G|PG|PG-13|R|NC-17|NR|TV-(Y|Y7|G|PG|14|MA))$")

    private fun parseVideoRenderer(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").ifBlank { return null }
        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
        val channelRun = byline?.optJSONArray("runs")?.optJSONObject(0)
        val channelId = channelRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        val duration = o.text("lengthText")
            ?: o.optString("lengthSeconds").toLongOrNull()?.let { formatDuration(it * 1000) }
            ?: o.optJSONArray("thumbnailOverlays")?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { arr.optJSONObject(it)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { r -> r.text("text") } }
            }
        // playlistVideoRenderer packs "views • age" into videoInfo runs.
        val infoRuns = o.optJSONObject("videoInfo")?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).map { runs.optJSONObject(it).optString("text") }.filter { it.isNotBlank() && it.trim() != "•" }
        }.orEmpty()
        val avatar = o.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")?.thumbnails()?.lastOrNull()
            ?: o.optJSONObject("channelThumbnail")?.thumbnails()?.lastOrNull()
        return VideoItem(
            id = id,
            title = o.text("title") ?: return null,
            channel = channelRun?.optString("text").orEmpty(),
            channelUrl = channelId?.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = avatar?.let(::absoluteUrl),
            durationSec = parseDuration(duration),
            views = parseViews(o.text("viewCountText") ?: o.text("shortViewCountText") ?: infoRuns.firstOrNull { it.contains("view") }),
            uploaded = o.text("publishedTimeText") ?: infoRuns.firstOrNull { it.contains("ago") },
        )
    }

    private fun parseLockup(o: JSONObject): VideoItem? {
        val id = o.optString("contentId").ifBlank { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content")?.ifBlank { null } ?: return null
        // metadataRows: [ [channel], [views, age] ] as text parts.
        val parts = mutableListOf<String>()
        meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")?.let { rows ->
            for (r in 0 until rows.length()) {
                val ps = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                for (p in 0 until ps.length()) ps.optJSONObject(p)?.optJSONObject("text")?.optString("content")?.ifBlank { null }?.let(parts::add)
            }
        }
        val avatar = meta.optJSONObject("image")?.collectStrings("url")?.firstOrNull()
        val duration = o.optJSONObject("contentImage")?.collectStrings("text")?.firstOrNull { DURATION.matches(it) }
        return VideoItem(
            id = id,
            title = title,
            channel = parts.firstOrNull { !it.contains("view") && !it.contains("ago") }.orEmpty(),
            channelUrl = o.collectStrings("browseId").firstOrNull { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = avatar?.let(::absoluteUrl),
            durationSec = parseDuration(duration),
            views = parseViews(parts.firstOrNull { it.contains("view") }),
            uploaded = parts.firstOrNull { it.contains("ago") },
        )
    }

    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    private fun parseDuration(text: String?): Long {
        val t = text?.trim() ?: return 0
        if (!DURATION.matches(t)) return 0
        return t.split(':').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0) }
    }

    /** "1,234,567 views", "1.2M views", "No views" → count, or -1 when unknown. */
    internal fun parseViews(text: String?): Long {
        val t = text?.lowercase()?.replace(",", "")?.trim() ?: return -1
        if (t.startsWith("no ")) return 0
        val m = Regex("([\\d.]+)\\s*([kmb]?)").find(t) ?: return -1
        val n = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2]) { "k" -> 1e3; "m" -> 1e6; "b" -> 1e9; else -> 1.0 }
        return (n * mult).toLong()
    }

    private fun absoluteUrl(url: String) = if (url.startsWith("//")) "https:$url" else url
}

// ── JSON helpers ───────────────────────────────────────────────────────────────

/** Depth-first visit of every object-valued key in the tree. */
internal fun JSONObject.walk(visit: (key: String, obj: JSONObject) -> Unit) {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        when (val v = opt(k)) {
            is JSONObject -> { visit(k, v); v.walk(visit) }
            is JSONArray -> v.walkArray(visit)
        }
    }
}

private fun JSONArray.walkArray(visit: (key: String, obj: JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.walk(visit)
            is JSONArray -> v.walkArray(visit)
        }
    }
}

/** Every JSONObject in the tree, including objects that sit directly in arrays (which [walk] skips). */
internal fun JSONObject.forEachObject(visit: (JSONObject) -> Unit) {
    visit(this)
    val keys = keys()
    while (keys.hasNext()) {
        when (val v = opt(keys.next())) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

private fun JSONArray.forEachObjectInArray(visit: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

/** YouTube text: {"simpleText": "..."} or {"runs": [{"text": "..."}]}. */
internal fun JSONObject.text(key: String): String? {
    val o = optJSONObject(key) ?: return optString(key).ifBlank { null }
    o.optString("simpleText").ifBlank { null }?.let { return it }
    o.optString("content").ifBlank { null }?.let { return it }
    val runs = o.optJSONArray("runs") ?: return null
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
}

/** URLs from {"thumbnail": {"thumbnails": [...]}} or {"thumbnails": [...]}, smallest first. */
internal fun JSONObject.thumbnails(): List<String>? {
    val arr = optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: optJSONArray("thumbnails") ?: return null
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.ifBlank { null } }
}

private fun JSONObject.firstImageSource(): String? = collectStrings("url").firstOrNull { it.contains("ytimg") || it.contains("ggpht") }

/** Every string value stored under [field] anywhere below this object. */
internal fun JSONObject.collectStrings(field: String): List<String> {
    val out = mutableListOf<String>()
    fun visitObj(o: JSONObject) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.opt(k)) {
                is String -> if (k == field && v.isNotBlank()) out += v
                is JSONObject -> visitObj(v)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let(::visitObj)
            }
        }
    }
    visitObj(this)
    return out
}
