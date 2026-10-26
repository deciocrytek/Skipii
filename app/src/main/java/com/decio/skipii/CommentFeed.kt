package com.decio.skipii

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Reading comments straight from YouTube's web `next` pages (signed in when there's a session,
 * so liked state comes along). NewPipeExtractor 0.26.5 dropped every commenter's picture; the
 * raw page has it (`author.avatarThumbnailUrl`, YouTube's own 88 px size, a few KB).
 *
 * Page shape (2026-10): the thread list is in `continuationItems` of
 * `reloadContinuationItemsCommand` / `appendContinuationItemsAction`; each `commentViewModel`
 * names its text, author and counts by `commentKey` → `commentEntityPayload`, and its like /
 * heart state by `toolbarStateKey`. Both live in `frameworkUpdates` entity mutations.
 */
object CommentFeed {
    suspend fun first(videoId: String): CommentsPage = withContext(Dispatchers.IO) {
        var token: String? = null
        val watch = InnerTube.postAny("next", JSONObject().put("videoId", videoId))
        watch.forEachObject { obj ->
            if (token == null && obj.optString("sectionIdentifier") == "comment-item-section") {
                token = obj.collectStrings("token").firstOrNull()
            }
        }
        val chapters = parseChapters(watch)
        // No comments section at all: they're turned off for this video.
        val start = token ?: return@withContext CommentsPage(emptyList(), total = -1, disabled = true, next = null, chapters = chapters)
        parsePage(InnerTube.postAny("next", JSONObject().put("continuation", start))).copy(chapters = chapters)
    }

    /**
     * The video's chapters from the watch page's player bar markers. Unlike NewPipeExtractor
     * (description chapters only), this also reads YouTube's auto-generated chapters
     * (`engagement-panel-macro-markers-auto-chapters`): each marker set is a `chapters` array of
     * `chapterRenderer` {title, timeRangeStartMillis}; the first non-empty set wins.
     */
    internal fun parseChapters(page: JSONObject): List<Chapter> {
        var found: List<Chapter>? = null
        page.forEachObject { obj ->
            if (found != null) return@forEachObject
            val markers = obj.optJSONArray("chapters") ?: return@forEachObject
            val list = (0 until markers.length()).mapNotNull { i ->
                val ch = markers.optJSONObject(i)?.optJSONObject("chapterRenderer") ?: return@mapNotNull null
                val title = ch.text("title")?.trim()?.ifBlank { null } ?: return@mapNotNull null
                Chapter(ch.optLong("timeRangeStartMillis"), title)
            }
            if (list.isNotEmpty()) found = list.sortedBy { it.startMs }
        }
        return found.orEmpty()
    }

    /** The next page of comments, or one thread's replies (same call, different token). */
    suspend fun more(token: String): CommentsPage = withContext(Dispatchers.IO) {
        parsePage(InnerTube.postAny("next", JSONObject().put("continuation", token)))
    }

    internal fun parsePage(page: JSONObject): CommentsPage {
        // Mutations are array elements, so this needs forEachObject (walk skips those).
        val entities = HashMap<String, JSONObject>()
        page.forEachObject { obj ->
            val key = obj.optString("entityKey")
            val payload = obj.optJSONObject("payload")
            if (key.isNotEmpty() && payload != null) entities[key] = payload
        }
        val comments = ArrayList<Comment>()
        var total = -1
        var next: String? = null
        page.walk { key, obj ->
            if (key != "reloadContinuationItemsCommand" && key != "appendContinuationItemsAction") return@walk
            val items = obj.optJSONArray("continuationItems") ?: return@walk
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                when {
                    item.has("commentsHeaderRenderer") -> item.getJSONObject("commentsHeaderRenderer").text("countText")
                        ?.let { InnerTube.parseViews(it) }?.takeIf { it >= 0 }?.let { total = it.toInt() }
                    item.has("commentThreadRenderer") -> {
                        val thread = item.getJSONObject("commentThreadRenderer")
                        val repliesToken = thread.optJSONObject("replies")?.collectStrings("token")?.firstOrNull()
                        thread.viewModel()?.let { vm -> parseComment(vm, entities, repliesToken)?.let(comments::add) }
                    }
                    // Reply pages list the comments themselves.
                    item.has("commentViewModel") -> item.viewModel()?.let { vm -> parseComment(vm, entities, null)?.let(comments::add) }
                    // The trailing loader ("more comments", or "Show more replies" in a thread).
                    item.has("continuationItemRenderer") -> item.collectStrings("token").firstOrNull()?.let { next = it }
                }
            }
        }
        // Pages can overlap at the boundary; ids stay unique for the list keys.
        val unique = comments.distinctBy { it.id }
        return CommentsPage(unique, total, disabled = false, next = next)
    }

    /** The innermost commentViewModel (threads nest it twice: commentViewModel.commentViewModel). */
    private fun JSONObject.viewModel(): JSONObject? {
        var found: JSONObject? = null
        walk { key, obj -> if (found == null && key == "commentViewModel" && obj.has("commentKey")) found = obj }
        return found
    }

    private fun parseComment(vm: JSONObject, entities: Map<String, JSONObject>, repliesToken: String?): Comment? {
        val entity = entities[vm.optString("commentKey")]?.optJSONObject("commentEntityPayload") ?: return null
        val props = entity.optJSONObject("properties") ?: return null
        val author = entity.optJSONObject("author")
        val toolbar = entity.optJSONObject("toolbar")
        val state = entities[vm.optString("toolbarStateKey")]?.optJSONObject("engagementToolbarStateEntityPayload")
        val replyCount = toolbar?.optString("replyCount")?.let { InnerTube.parseViews(it) }?.takeIf { it > 0 }?.toInt() ?: 0
        return Comment(
            id = props.optString("commentId").ifBlank { vm.optString("commentId") }.ifBlank { return null },
            author = author?.optString("displayName").orEmpty(),
            channelId = author?.optString("channelId")?.takeIf { it.startsWith("UC") },
            avatar = author?.optString("avatarThumbnailUrl")?.ifBlank { null }?.let { if (it.startsWith("//")) "https:$it" else it },
            text = props.optJSONObject("content")?.optString("content").orEmpty().trim(),
            likes = toolbar?.optString("likeCountNotliked")?.let { InnerTube.parseViews(it) }?.takeIf { it > 0 }?.toInt() ?: 0,
            ago = props.optString("publishedTime").ifBlank { null },
            pinned = vm.has("pinnedText") || props.has("pinnedText"),
            hearted = state?.optString("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
            replyCount = replyCount,
            repliesToken = repliesToken?.takeIf { replyCount > 0 },
            liked = state?.optString("likeState") == "TOOLBAR_LIKE_STATE_LIKED",
        )
    }
}

/**
 * Commenters' display names. YouTube's comment data only carries @handles, so each name is
 * read from the top of the channel's public feed: the `<title>` is in the first few hundred
 * bytes and the rest is never downloaded (~1 KB per person). Cached for the session; at most
 * four lookups at a time.
 */
object ChannelNames {
    /** channelId → display name, for the UI. */
    val known = mutableStateMapOf<String, String>()
    /** Every id already asked for (also the ones with no name), so each is fetched once. */
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)
    private val TITLE = Regex("<title>([^<]*)</title>")
    private const val HEAD_BYTES = 4096L

    /** Looks up the names not known yet; [known] fills in as they arrive. Call on the main thread. */
    fun resolve(scope: CoroutineScope, channelIds: Collection<String>) {
        channelIds.filter { asked.add(it) }.forEach { id ->
            scope.launch {
                val name = gate.withPermit { withContext(Dispatchers.IO) { runCatching { fetch(id) } } }
                name.onSuccess { if (it != null) known[id] = it }
                    // A network failure may pass; let a later page ask again.
                    .onFailure { asked.remove(id) }
            }
        }
    }

    private fun fetch(channelId: String): String? {
        val request = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        Net.client.newCall(request).execute().use { r ->
            // Removed or hidden channels have no feed: no name, and no point asking again.
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("feed ${r.code}")
            val source = r.body?.source() ?: return null
            // The title is in the first few hundred bytes; closing drops the rest unread.
            source.request(HEAD_BYTES)
            return parseTitle(source.buffer.readByteString(minOf(source.buffer.size, HEAD_BYTES)).utf8())
        }
    }

    internal fun parseTitle(xml: String): String? = TITLE.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifBlank { null }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}

/** What the comments show as the author: the real name once known, else the @handle. */
fun Comment.displayName(): String = name ?: channelId?.let { ChannelNames.known[it] } ?: author
