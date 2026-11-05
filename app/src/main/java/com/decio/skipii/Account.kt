package com.decio.skipii

import android.content.Context
import android.util.Log
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

/** A playlist a video can be saved to, and whether it's in it ([contains] null: not known). */
@Immutable
data class PlaylistChoice(val id: String, val title: String, val contains: Boolean?, val privacy: String?)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?)

/**
 * One page of a feed plus the token for the next page, if any. [chips]: the category chips
 * YouTube put over the feed (Home's first page only; empty everywhere else).
 */
@Immutable
data class FeedPage(val videos: List<VideoItem>, val next: String?, val chips: List<FeedChip> = emptyList())

/**
 * One of Home's category chips ("All", "Music", "Gaming", "Recently uploaded"…), titled as
 * YouTube sent it. [token] is the continuation that loads the chip's own feed; null for the
 * chip that stands for the plain feed ("All", the one selected on the first page).
 */
@Immutable
data class FeedChip(val title: String, val token: String?)

/** A page of playlists (a channel's Playlists tab) and the token for the next one. */
@Immutable
data class PlaylistPage(val playlists: List<PlaylistRef>, val next: String?)

/**
 * The channel page's tabs Skipii shows (GitHub #53), by YouTube's English titles. Posts, Store,
 * Playables, Live and the rest hold nothing Skipii plays.
 */
enum class ChannelTab(val title: String) { Home("Home"), Videos("Videos"), Shorts("Shorts"), Playlists("Playlists") }

/** Where a channel shelf's "See all" goes: one of the channel's tabs, or a playlist page. */
sealed interface ShelfLink {
    data class Tab(val tab: ChannelTab) : ShelfLink
    data class Playlist(val ref: PlaylistRef) : ShelfLink
}

/**
 * One row of a channel's Home tab, as the creator arranged it ("Videos", "Popular videos", a
 * playlist's row, "Shorts", "Featured channels"). Usually one kind of item per shelf.
 */
@Immutable
data class ChannelShelf(
    val title: String,
    val videos: List<VideoItem> = emptyList(),
    val shorts: List<VideoItem> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val channels: List<ChannelRef> = emptyList(),
    val link: ShelfLink? = null,
)

/**
 * A channel page's Home tab: which tabs the channel has (each with its browse params), the
 * featured video and the shelves. [subscribed] only signed in (null otherwise or not shown).
 */
@Immutable
data class ChannelHome(
    val tabs: Map<ChannelTab, String>,
    val featured: VideoItem?,
    val shelves: List<ChannelShelf>,
    val subscribed: Boolean? = null,
)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(val like: LikeStatus, val subscribed: Boolean?, val channelId: String?, val bell: Boolean? = null)

/** One channel of the account's subscriptions list, with its bell (true = "All"; null = not given). */
data class SubscribedChannel(val ref: ChannelRef, val bell: Boolean?)

class NotSignedInException : IOException("Not signed in")

/**
 * YouTube answered a signed-in request with an error status. [message] stays English (it's logged, and
 * Feeds shows messages that start with "YouTube"); [getLocalizedMessage] is the same in the app's language.
 */
class YouTubeRefusedException(val code: Int) : IOException(
    if (code == 401 || code == 403) "YouTube rejected the session ($code). Try signing in again." else "YouTube returned $code",
) {
    override fun getLocalizedMessage(): String =
        if (code == 401 || code == 403) str(R.string.app_error_session_rejected, code.toString())
        else str(R.string.app_error_youtube_returned, code.toString())
}

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
        // A fallback name is stored with the session, so it stays in the language of the sign-in.
        val fallbackName = str(R.string.app_account_fallback_name)
        menu.walk { key, obj ->
            if (key == "activeAccountHeaderRenderer" && found == null) {
                found = Profile(
                    name = obj.text("accountName") ?: fallbackName,
                    handle = obj.text("channelHandle"),
                    avatar = obj.optJSONObject("accountPhoto")?.thumbnails()?.lastOrNull(),
                )
            }
        }
        val p = found ?: Profile(fallbackName, null, null)
        store?.apply {
            put(KEY_NAME, p.name); put(KEY_HANDLE, p.handle); put(KEY_AVATAR, p.avatar)
        }
        withContext(Dispatchers.Main) { profile = p }
    }

    fun signOut() {
        synchronized(cookies) { cookies.clear() }
        store?.clear()
        profile = null
        // The bells are the account's; signed out there are none (and no new-video notifications).
        Bells.clear()
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
            if (!r.isSuccessful) throw YouTubeRefusedException(r.code)
            return JSONObject(text)
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    suspend fun browse(browseId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", browseId)
        parseFeed(post("browse", body))
    }

    /**
     * Home. [next] is either the feed's own next page or a category chip's token: a chip loads
     * as a continuation of FEwhat_to_watch (its answer replaces the feed's body, and its own
     * "load more" pages on from there). The first page also carries the chips.
     */
    suspend fun home(next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (next != null) JSONObject().put("continuation", next) else JSONObject().put("browseId", "FEwhat_to_watch")
        val root = post("browse", body)
        val page = parseFeed(root)
        if (next == null) page.copy(chips = parseHomeChips(root)) else page
    }

    /**
     * A channel's uploads (Videos tab), members-only ones filtered out like everywhere else. The
     * first page carries the tab's sort as chips (Latest · Popular · Oldest); a chip's token is a
     * continuation that reloads the list in that order, read like any next page.
     */
    suspend fun channelVideos(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_VIDEOS_TAB)
        val root = postAny("browse", body)
        val page = parseFeed(root)
        if (continuation == null) page.copy(chips = parseHomeChips(root)) else page
    }

    /** The channel page's Videos tab selector (as on youtube.com, 2026-10-06). */
    private const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"

    /**
     * A channel page's Home tab (the page itself, no params), anonymous when signed out: the
     * tabs it has, the featured video and the creator's shelves. Signed in, the subscribe
     * button's state comes along and its bell goes to [Bells].
     */
    suspend fun channelHome(channelId: String, name: String?, avatar: String?): ChannelHome = withContext(Dispatchers.IO) {
        val root = postAny("browse", JSONObject().put("browseId", channelId))
        val home = parseChannelHome(root, channelId)
        if (!Account.signedIn) return@withContext home
        var subscribed: Boolean? = null
        var bell: Boolean? = null
        root.walk { key, obj ->
            if ((key == "subscribeButtonRenderer" || key == "subscriptionStateEntity") && obj.has("subscribed") && subscribed == null) {
                subscribed = obj.optBoolean("subscribed")
            }
            if (key == "subscriptionNotificationToggleButtonRenderer" && bell == null) bell = parseBell(obj)
        }
        Bells.learn(channelId, subscribed, bell, name, avatar)
        home.copy(subscribed = subscribed)
    }

    /** A channel's Shorts tab, a page at a time ([params]: the tab's, from [ChannelHome.tabs]). */
    suspend fun channelShorts(channelId: String, params: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", params)
        parseChannelShorts(postAny("browse", body))
    }

    /** A channel's Playlists tab, a page at a time. */
    suspend fun channelPlaylists(channelId: String, params: String, continuation: String? = null): PlaylistPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", params)
        val root = postAny("browse", body)
        PlaylistPage(parsePlaylists(root), parseFeed(root).next)
    }

    suspend fun subscriptionsFeed(next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (next != null) JSONObject().put("continuation", next) else JSONObject().put("browseId", "FEsubscriptions")
        parseSubscriptionsFeed(post("browse", body))
    }
    suspend fun history(next: String? = null) = browse("FEhistory", next)

    /** A playlist's videos; anonymous when signed out, so a channel's public playlists open too. */
    suspend fun playlist(id: String, next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (next != null) JSONObject().put("continuation", next) else JSONObject().put("browseId", "VL$id")
        parseFeed(postAny("browse", body))
    }

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
        parsePlaylists(post("browse", JSONObject().put("browseId", "FEplaylist_aggregation"))).filterNot { it.id == "WL" || it.id == "LL" }
    }

    /** Every playlist a response lists (old grid/list renderers or lockups), in order. */
    internal fun parsePlaylists(root: JSONObject): List<PlaylistRef> {
        val out = LinkedHashMap<String, PlaylistRef>()
        root.walk { key, obj -> playlistOf(key, obj)?.let { out.putIfAbsent(it.id, it) } }
        return out.values.toList()
    }

    private fun playlistOf(key: String, obj: JSONObject): PlaylistRef? = when (key) {
        "gridPlaylistRenderer", "playlistRenderer" -> obj.optString("playlistId").ifBlank { null }?.let { id ->
            PlaylistRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl), obj.text("videoCountText") ?: obj.text("videoCountShortText"))
        }
        "lockupViewModel" -> if (obj.optString("contentType") != "LOCKUP_CONTENT_TYPE_PLAYLIST") null
        else obj.optString("contentId").ifBlank { null }?.let { id ->
            val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            PlaylistRef(
                id = id,
                title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
                thumbnail = obj.firstImageSource(),
                subtitle = obj.collectStrings("text").firstOrNull { it.contains("video", ignoreCase = true) },
            )
        }
        else -> null
    }

    /**
     * Like state, subscription and bell for a video, from the signed-in watch page data. The bell
     * it reports is kept in [Bells] (every page that shows it keeps the local copy current).
     */
    suspend fun videoState(videoId: String): VideoAccountState = withContext(Dispatchers.IO) {
        val next = post("next", JSONObject().put("videoId", videoId))
        val state = parseVideoState(next)
        val owner = next.findObject("videoOwnerRenderer")
        state.channelId?.let { Bells.learn(it, state.subscribed, state.bell, owner?.text("title"), owner?.thumbnails()?.lastOrNull()?.let(::absoluteUrl)) }
        state
    }

    internal fun parseVideoState(next: JSONObject): VideoAccountState {
        var like = LikeStatus.None
        var subscribed: Boolean? = null
        var channelId: String? = null
        var bell: Boolean? = null
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
                "subscriptionNotificationToggleButtonRenderer" -> if (bell == null) bell = parseBell(obj)
                "videoOwnerRenderer" -> obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { if (channelId == null) channelId = it }
            }
        }
        return VideoAccountState(like, subscribed, channelId, bell)
    }

    /**
     * A subscribe button's bell (`subscriptionNotificationToggleButtonRenderer`): true = "All",
     * false = "Personalized" or "None", null = can't tell. Read from the current state's icon
     * (NOTIFICATIONS_ACTIVE = All), else its id (2 All, 3 Personalized, 0 None).
     */
    internal fun parseBell(toggle: JSONObject): Boolean? {
        val current = toggle.optInt("currentStateId", -1)
        val states = toggle.optJSONArray("states")
        for (i in 0 until (states?.length() ?: 0)) {
            val s = states?.optJSONObject(i) ?: continue
            if (s.optInt("stateId", -2) != current) continue
            when (s.collectStrings("iconType").firstOrNull()) {
                "NOTIFICATIONS_ACTIVE" -> return true
                "NOTIFICATIONS_NONE", "NOTIFICATIONS_OFF" -> return false
            }
        }
        return when (current) {
            2 -> true
            0, 3 -> false
            else -> null
        }
    }

    /**
     * Every channel the account follows with its bell, from the subscriptions page (FEchannels),
     * all its pages. Null if a page failed (a partial list mustn't turn bells off).
     */
    suspend fun subscriptions(): List<SubscribedChannel>? = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, SubscribedChannel>()
        var page: JSONObject? = post("browse", JSONObject().put("browseId", "FEchannels"))
        var pages = 0
        while (page != null) {
            val (channels, next) = parseSubscriptions(page)
            channels.forEach { out.putIfAbsent(it.ref.id, it) }
            if (next == null) break
            if (++pages >= 50) return@withContext null
            page = runCatching { post("browse", JSONObject().put("continuation", next)) }.getOrElse {
                Log.w("Skipii", "bells: subscriptions page ${pages + 1} failed (${it.message})")
                return@withContext null
            }
        }
        out.values.toList()
    }

    internal fun parseSubscriptions(root: JSONObject): Pair<List<SubscribedChannel>, String?> {
        val out = LinkedHashMap<String, SubscribedChannel>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "channelRenderer", "gridChannelRenderer" -> {
                    val id = obj.optString("channelId").ifBlank { return@walk }
                    out.putIfAbsent(id, SubscribedChannel(
                        ChannelRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl)),
                        obj.findObject("subscriptionNotificationToggleButtonRenderer")?.let(::parseBell),
                    ))
                }
                "continuationItemRenderer", "continuationItemViewModel" -> obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return out.values.toList() to next
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

    /** The channel's bell: [all] = "All" (every upload), else "Personalized", YouTube's default. */
    suspend fun setBell(channelId: String, all: Boolean) = withContext(Dispatchers.IO) {
        post("notification/modify_channel_preference", JSONObject().put("params", bellParams(channelId, if (all) BELL_ALL else BELL_PERSONALIZED)))
        Unit
    }

    private const val BELL_PERSONALIZED = 1
    private const val BELL_ALL = 2

    /**
     * What the bell menu's items carry (modifyChannelNotificationPreferenceEndpoint.params, a
     * protobuf): 1 = channel id, 2 = { 1 = preference }, 3 = 0, 4 = 4, URL-encoded base64 as in the
     * answer. Preferences per YouTube.js: 1 Personalized, 2 All, 3 None. youtube.com's own items
     * add 10 = the watch page's video; the request works without (YouTube.js leaves it out).
     * Built here rather than taken from the menu: the menu isn't at hand everywhere (the
     * subscriptions list, Shorts), and not every menu lists "All" (next.json's, for a channel not
     * subscribed, has only Personalized / None). Written by hand; no protobuf library.
     */
    internal fun bellParams(channelId: String, preference: Int): String {
        val id = channelId.toByteArray()
        val bytes = byteArrayOf(0x0A, id.size.toByte()) + id +
            byteArrayOf(0x12, 0x02, 0x08, preference.toByte(), 0x18, 0x00, 0x20, 0x04)
        return java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(bytes), "UTF-8")
    }

    suspend fun setInWatchLater(videoId: String, add: Boolean) = setInPlaylist("WL", videoId, add)

    suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        post("browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", JSONArray().put(action)))
        Unit
    }

    /**
     * The account's playlists for [videoId], Watch later first, each saying whether the video is
     * in it: what youtube.com's "Save to…" dialog lists (GitHub issue #12). If that answer can't
     * be read, the Library's playlists stand in, without knowing which hold the video.
     */
    suspend fun saveChoices(videoId: String): List<PlaylistChoice> = withContext(Dispatchers.IO) {
        val answer = post(
            "playlist/get_add_to_playlist",
            JSONObject().put("videoIds", JSONArray().put(videoId)).put("excludeWatchLater", false),
        )
        parseSaveChoices(answer).ifEmpty {
            Log.w("Skipii", "playlists: no choices in the Save dialog's answer, using the Library's list")
            playlists().map { PlaylistChoice(it.id, it.title, contains = null, privacy = null) }
        }
    }

    /** A new playlist holding [videoId]; returns its id. [privacy]: PRIVATE, UNLISTED or PUBLIC. */
    suspend fun createPlaylist(title: String, privacy: String, videoId: String): String = withContext(Dispatchers.IO) {
        val answer = post(
            "playlist/create",
            JSONObject().put("title", title).put("privacyStatus", privacy).put("videoIds", JSONArray().put(videoId)),
        )
        answer.optString("playlistId").ifBlank { throw IOException("YouTube didn't create the playlist") }
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
                // Members-only videos can't be played (anonymous extraction), live and upcoming
                // streams neither (no live playback, as in search), and full movies/TV aren't
                // wanted (Derek), so none of them is ever shown.
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isLive(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { videos.putIfAbsent(it.id, it) }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" && !isMembersOnly(obj) && !isLive(obj) && !isMovie(obj)) {
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

    /**
     * Subscriptions in upload order, newest first (Derek; GitHub #50). YouTube's web feed opens with a
     * "Most relevant" shelf of its own picks (out of date order) before the uploads; that shelf is left
     * out. Each page is then kept newest first by its ages ("3 hours ago"; stable, so videos of the same
     * age keep YouTube's order, and one without an age keeps its place after the one before it).
     */
    internal fun parseSubscriptionsFeed(root: JSONObject): FeedPage {
        root.walk { key, shelf ->
            if (key != "richShelfRenderer" && key != "shelfRenderer") return@walk
            val title = shelf.text("title") ?: shelf.optJSONObject("header")?.collectStrings("content")?.firstOrNull()
            // Emptied in place: walk() goes into it next and finds nothing.
            if (title?.trim().equals(MOST_RELEVANT, ignoreCase = true)) shelf.keys().asSequence().toList().forEach(shelf::remove)
        }
        val page = parseFeed(root)
        var last = 0L
        val ages = page.videos.associate { v -> v.id to (NewVideos.ageMs(v.uploaded) ?: last).also { last = it } }
        return page.copy(videos = page.videos.sortedBy { ages[it.id] })
    }

    private const val MOST_RELEVANT = "Most relevant"

    /**
     * Home's category chips: `feedFilterChipBarRenderer` (the grid's header) → `chipCloudChipRenderer`
     * {text, isSelected, navigationEndpoint.continuationCommand.token}, as captured 2026-11-02;
     * the view-model form (`chipBarViewModel` → `chipViewModel` {text, selected,
     * tapCommand.innertubeCommand.continuationCommand.token}) is read too in case Home moves to it.
     * The selected chip is the feed already shown ("All"), so its token is null. Chips that load
     * nothing by continuation (a drop-down, a link elsewhere) are left out, and so are
     * [SKIPPED_CHIPS]. YouTube is always asked in English, so the titles are stable keys.
     */
    internal fun parseHomeChips(root: JSONObject): List<FeedChip> {
        val out = mutableListOf<FeedChip>()
        fun add(title: String?, selected: Boolean, token: String?) {
            val name = title?.trim()?.ifBlank { null } ?: return
            if (SKIPPED_CHIPS.any { it.equals(name, ignoreCase = true) }) return
            if (out.any { it.title == name }) return
            when {
                selected -> out += FeedChip(name, null)
                token != null -> out += FeedChip(name, token)
            }
        }
        fun tokenOf(command: JSONObject?) = command?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
        val bar = root.findObject("feedFilterChipBarRenderer")
        if (bar != null) {
            bar.walk { key, chip ->
                if (key == "chipCloudChipRenderer") add(chip.text("text"), chip.optBoolean("isSelected"), tokenOf(chip.optJSONObject("navigationEndpoint")))
            }
            return out
        }
        val grid = root.findObject("richGridRenderer")?.optJSONObject("header")?.optJSONObject("chipBarViewModel") ?: return out
        grid.walk { key, chip ->
            if (key != "chipViewModel" || chip.optString("displayType").endsWith("DROP_DOWN")) return@walk
            add(chip.optString("text"), chip.optBoolean("selected"), tokenOf(chip.optJSONObject("tapCommand")?.optJSONObject("innertubeCommand")))
        }
        return out
    }

    /**
     * Home chips Skipii can't fill: "Your custom feed" is a prompt box with no videos (Derek,
     * 6632.png), and "Live" is all live streams, which aren't played (6633.png).
     */
    private val SKIPPED_CHIPS = listOf("Your custom feed", "Live")

    /**
     * A channel page (its Home tab selected): `twoColumnBrowseResultsRenderer.tabs[].tabRenderer`
     * {title, endpoint.browseEndpoint.params} for each tab, and the Home tab's sections:
     * `channelVideoPlayerRenderer` (the featured video), `shelfRenderer` (title, items in
     * `content`, "See all" in `endpoint`) and `reelShelfRenderer` (Shorts). Captured anonymously
     * 2026-11-04. Shelves with nothing playable (Posts) are left out.
     */
    internal fun parseChannelHome(root: JSONObject, channelId: String): ChannelHome {
        val tabs = LinkedHashMap<ChannelTab, String>()
        var homeContent: JSONObject? = null
        val list = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
        for (i in 0 until (list?.length() ?: 0)) {
            val tab = list?.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
            val kind = ChannelTab.entries.firstOrNull { it.title.equals(tab.optString("title"), ignoreCase = true) } ?: continue
            val params = tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("params")?.ifBlank { null } ?: continue
            tabs.putIfAbsent(kind, params)
            if (kind == ChannelTab.Home) homeContent = tab.optJSONObject("content")
        }
        var featured: VideoItem? = null
        val shelves = mutableListOf<ChannelShelf>()
        homeContent?.walk { key, obj ->
            when (key) {
                "channelVideoPlayerRenderer" -> if (featured == null) featured = parseFeatured(obj)
                "shelfRenderer", "reelShelfRenderer" -> parseShelf(obj, key == "reelShelfRenderer", channelId, tabs.keys)?.let(shelves::add)
            }
        }
        return ChannelHome(tabs, featured, shelves)
    }

    private fun parseFeatured(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").takeIf { it.length == 11 } ?: return null
        return VideoItem(
            id = id,
            title = o.text("title") ?: return null,
            channel = "", channelUrl = null,
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = null, durationSec = 0,
            views = parseViews(o.text("viewCountText")),
            uploaded = o.text("publishedTimeText"),
        )
    }

    private fun parseShelf(o: JSONObject, reel: Boolean, channelId: String, tabs: Set<ChannelTab>): ChannelShelf? {
        val title = o.text("title")?.trim()?.ifBlank { null } ?: return null
        val videos = LinkedHashMap<String, VideoItem>()
        val shorts = LinkedHashMap<String, VideoItem>()
        val playlists = LinkedHashMap<String, PlaylistRef>()
        val channels = LinkedHashMap<String, ChannelRef>()
        (o.optJSONObject("content") ?: o).walk { key, obj ->
            when (key) {
                "videoRenderer", "gridVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isLive(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { videos.putIfAbsent(it.id, it) }
                "lockupViewModel" -> when (obj.optString("contentType")) {
                    "LOCKUP_CONTENT_TYPE_VIDEO" -> if (!isMembersOnly(obj) && !isLive(obj) && !isMovie(obj)) parseLockup(obj)?.let { videos.putIfAbsent(it.id, it) }
                    else -> playlistOf(key, obj)?.let { playlists.putIfAbsent(it.id, it) }
                }
                "shortsLockupViewModel", "reelItemRenderer" -> parseShortLockup(obj)?.let { shorts.putIfAbsent(it.id, it) }
                "gridPlaylistRenderer", "playlistRenderer" -> playlistOf(key, obj)?.let { playlists.putIfAbsent(it.id, it) }
                "gridChannelRenderer", "channelRenderer" -> obj.optString("channelId").takeIf { it.startsWith("UC") }?.let { id ->
                    channels.putIfAbsent(id, ChannelRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl)))
                }
            }
        }
        if (videos.isEmpty() && shorts.isEmpty() && playlists.isEmpty() && channels.isEmpty()) return null
        val browse = o.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
        val target = browse?.optString("browseId").orEmpty()
        val link = when {
            target.startsWith("VL") -> ShelfLink.Playlist(PlaylistRef(target.removePrefix("VL"), title, videos.values.firstOrNull()?.thumbnail, null))
            target == channelId -> tabOfParams(browse?.optString("params"))?.takeIf { it in tabs }?.let { ShelfLink.Tab(it) }
            reel && ChannelTab.Shorts in tabs -> ShelfLink.Tab(ChannelTab.Shorts)
            else -> null
        }
        return ChannelShelf(title, videos.values.toList(), shorts.values.toList(), playlists.values.toList(), channels.values.toList(), link)
    }

    /**
     * Which tab a channel browse's params open: they're a protobuf whose field 2 is the tab's
     * name ("videos", "shorts", "playlists"…), base64 (sometimes URL-safe, sometimes %-escaped).
     */
    internal fun tabOfParams(params: String?): ChannelTab? {
        val raw = params?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.ifBlank { null } ?: return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(raw.replace('-', '+').replace('_', '/').trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }) }
            .getOrNull() ?: return null
        if (bytes.size < 2 || bytes[0] != 0x12.toByte() || bytes[1] + 2 > bytes.size) return null
        return when (String(bytes, 2, bytes[1].toInt())) {
            "videos" -> ChannelTab.Videos
            "shorts" -> ChannelTab.Shorts
            "playlists" -> ChannelTab.Playlists
            "featured" -> ChannelTab.Home
            else -> null
        }
    }

    /** A channel's Shorts tab page: its `shortsLockupViewModel`s and the next page's token. */
    internal fun parseChannelShorts(root: JSONObject): FeedPage {
        val shorts = LinkedHashMap<String, VideoItem>()
        root.walk { key, obj -> if (key == "shortsLockupViewModel" || key == "reelItemRenderer") parseShortLockup(obj)?.let { shorts.putIfAbsent(it.id, it) } }
        return FeedPage(shorts.values.toList(), parseFeed(root).next)
    }

    /**
     * A short's tile: `shortsLockupViewModel` {onTap…reelWatchEndpoint.videoId, overlayMetadata
     * {primaryText = title, secondaryText = "739K views"}, thumbnailViewModel (9:16)}; or the older
     * `reelItemRenderer` {videoId, headline, viewCountText, thumbnail}.
     */
    private fun parseShortLockup(o: JSONObject): VideoItem? {
        val reel = o.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")
        val id = (reel?.optString("videoId") ?: o.optString("videoId")).takeIf { it.length == 11 } ?: return null
        val meta = o.optJSONObject("overlayMetadata")
        val title = meta?.optJSONObject("primaryText")?.optString("content")?.ifBlank { null } ?: o.text("headline") ?: return null
        val views = meta?.optJSONObject("secondaryText")?.optString("content")?.ifBlank { null } ?: o.text("viewCountText")
        val frame = (o.optJSONObject("thumbnailViewModel") ?: o.optJSONObject("thumbnail"))?.collectStrings("url")?.firstOrNull()
        return VideoItem(
            id = id, title = title, channel = "", channelUrl = null,
            thumbnail = frame?.let(::absoluteUrl) ?: "https://i.ytimg.com/vi/$id/oar2.jpg",
            avatar = null, durationSec = 0, views = parseViews(views), uploaded = null,
        )
    }

    /**
     * A live or upcoming stream (Skipii plays neither, as search already drops them). Lockups: a
     * LIVE badge on the THUMBNAIL ("LIVE" / "Now playing"); an avatar's `liveData` only means the
     * channel is live now and its other videos play fine. Older renderers: BADGE_STYLE_TYPE_LIVE_NOW,
     * the time overlay styled LIVE / UPCOMING, or `upcomingEventData` (the markers NewPipe reads).
     */
    internal fun isLive(o: JSONObject): Boolean {
        if (o.has("upcomingEventData")) return true
        if (o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_LIVE_NOW" || it == "LIVE" || it == "UPCOMING" }) return true
        val image = o.optJSONObject("contentImage") ?: return false
        return image.collectStrings("badgeStyle").any { it == "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE" } ||
            image.collectStrings("text").any { it.equals("UPCOMING", ignoreCase = true) }
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
            feedback = feedbackOf(o),
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
            feedback = feedbackOf(o),
        )
    }

    /**
     * A card's "Not interested" and "Don't recommend channel" (#16): items of its ⋮ menu that carry a
     * feedbackEndpoint — a lockup's sheet (listItemViewModel) or an older renderer's menuServiceItemRenderer.
     * Known by YouTube's icon (NOT_INTERESTED / REMOVE), else by the English label (hl=en). Signed out, the
     * menu holds only Add to queue and Share, so cards have none.
     */
    internal fun feedbackOf(o: JSONObject): CardFeedback? {
        var notInterested: String? = null
        var dontRecommend: String? = null
        o.walk { key, item ->
            val label: String?
            val icon: String?
            val endpoint: JSONObject?
            when (key) {
                "listItemViewModel" -> {
                    label = item.optJSONObject("title")?.optString("content")
                    icon = item.optJSONObject("leadingImage")?.collectStrings("imageName")?.firstOrNull()
                    endpoint = item.optJSONObject("rendererContext")?.optJSONObject("commandContext")?.optJSONObject("onTap")
                        ?.optJSONObject("innertubeCommand")?.optJSONObject("feedbackEndpoint")
                }
                "menuServiceItemRenderer" -> {
                    label = item.text("text")
                    icon = item.optJSONObject("icon")?.optString("iconType")
                    endpoint = item.optJSONObject("serviceEndpoint")?.optJSONObject("feedbackEndpoint")
                }
                else -> return@walk
            }
            val token = endpoint?.optString("feedbackToken")?.ifBlank { null } ?: return@walk
            val words = label.orEmpty().replace('\u2019', '\'')
            when {
                icon == "NOT_INTERESTED" || words.equals("Not interested", ignoreCase = true) -> if (notInterested == null) notInterested = token
                icon == "REMOVE" || words.startsWith("Don't recommend", ignoreCase = true) -> if (dontRecommend == null) dontRecommend = token
            }
        }
        return if (notInterested == null && dontRecommend == null) null else CardFeedback(notInterested, dontRecommend)
    }

    /** Sends a card's feedback token (Not interested / Don't recommend channel) to the account. */
    suspend fun sendFeedback(token: String) = withContext(Dispatchers.IO) {
        val answer = post(
            "feedback",
            JSONObject()
                .put("feedbackTokens", JSONArray().put(token))
                .put("isFeedbackTokenUnencrypted", false)
                .put("shouldMerge", false),
        )
        val processed = answer.optJSONArray("feedbackResponses")?.optJSONObject(0)?.optBoolean("isProcessed", true) ?: true
        check(processed) { "feedback not processed" }
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

/** The first object stored under [key] anywhere below this one (depth first, as [walk] visits). */
internal fun JSONObject.findObject(key: String): JSONObject? {
    var found: JSONObject? = null
    walk { k, o -> if (found == null && k == key) found = o }
    return found
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

/** The Save dialog's playlists (`playlistAddToOptionRenderer`s, Watch later among them). */
internal fun parseSaveChoices(answer: JSONObject): List<PlaylistChoice> {
    val out = LinkedHashMap<String, PlaylistChoice>()
    answer.walk { key, obj ->
        if (key != "playlistAddToOptionRenderer") return@walk
        val id = obj.optString("playlistId").ifBlank { return@walk }
        out.getOrPut(id) {
            PlaylistChoice(
                id = id,
                title = obj.text("title") ?: "",
                contains = when (obj.optString("containsSelectedVideos")) {
                    "ALL" -> true
                    "NONE" -> false
                    else -> null
                },
                privacy = obj.optString("privacy").ifBlank { null },
            )
        }
    }
    return out.values.toList()
}
