package com.decio.skipii

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Posting comments and replies (signed in). Comments are *read* by [CommentFeed]; posting
 * needs per-video and per-thread parameters that only the signed-in web
 * response carries:
 *  - `createCommentParams` in the comments header, for a new top-level comment;
 *  - `createReplyParams` inside each thread's toolbar entity, which the thread's
 *    commentViewModel points to by `toolbarSurfaceKey` (signed out, the same slot holds a
 *    "Sign in to continue" prompt).
 * Pages are walked lazily and cached per video until a reply target is found.
 */
object CommentPosting {
    private class VideoParams(
        var createComment: String? = null,
        val reply: MutableMap<String, String> = HashMap(),
        /** commentId → (like action, unlike action), from each thread's toolbar. */
        val like: MutableMap<String, Pair<String?, String?>> = HashMap(),
        var next: String? = null,
        var started: Boolean = false,
    )

    private val cache = HashMap<String, VideoParams>()
    private val lock = Mutex()
    private const val MAX_PAGES = 8

    fun forget(videoId: String) { cache.remove(videoId) }

    suspend fun comment(videoId: String, text: String) = withContext(Dispatchers.IO) {
        val params = lock.withLock {
            val vp = cache.getOrPut(videoId) { VideoParams() }
            if (!vp.started) loadFirstPage(videoId, vp)
            vp.createComment
        } ?: throw IOException(str(R.string.comments_error_cant_post))
        checkPosted(
            InnerTube.post("comment/create_comment", JSONObject().put("commentText", text).put("createCommentParams", params)),
        )
    }

    suspend fun reply(videoId: String, threadId: String, text: String) = withContext(Dispatchers.IO) {
        val params = lock.withLock {
            val vp = cache.getOrPut(videoId) { VideoParams() }
            if (!vp.started) loadFirstPage(videoId, vp)
            var pages = 1
            while (vp.reply[threadId] == null && vp.next != null && pages < MAX_PAGES) {
                loadPage(vp, vp.next!!)
                pages++
            }
            vp.reply[threadId]
        } ?: throw IOException(str(R.string.comments_error_reply_not_found))
        checkPosted(
            InnerTube.post("comment/create_comment_reply", JSONObject().put("commentText", text).put("createReplyParams", params)),
        )
    }

    /** Likes or unlikes a comment. Looks further into the thread pages if needed. */
    suspend fun setLiked(videoId: String, commentId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        val action = lock.withLock {
            val vp = cache.getOrPut(videoId) { VideoParams() }
            if (!vp.started) loadFirstPage(videoId, vp)
            var pages = 1
            while (vp.like[commentId] == null && vp.next != null && pages < MAX_PAGES) {
                loadPage(vp, vp.next!!)
                pages++
            }
            val (likeAction, unlikeAction) = vp.like[commentId] ?: (null to null)
            if (liked) likeAction else unlikeAction
        } ?: throw IOException(str(R.string.comments_error_like_not_found))
        InnerTube.post("comment/perform_comment_action", JSONObject().put("actions", JSONArray().put(action)))
        Unit
    }

    private fun loadFirstPage(videoId: String, vp: VideoParams) {
        vp.started = true
        val next = InnerTube.post("next", JSONObject().put("videoId", videoId))
        var token: String? = null
        next.forEachObject { obj ->
            if (token == null && obj.optString("sectionIdentifier") == "comment-item-section") {
                token = obj.collectStrings("token").firstOrNull()
            }
        }
        token?.let { loadPage(vp, it) }
    }

    private fun loadPage(vp: VideoParams, token: String) {
        val parsed = parsePage(InnerTube.post("next", JSONObject().put("continuation", token)))
        if (vp.createComment == null) vp.createComment = parsed.createComment
        vp.reply.putAll(parsed.replyParams)
        vp.like.putAll(parsed.likeActions)
        vp.next = parsed.next
    }

    internal class ParsedPage(
        val createComment: String?,
        val replyParams: Map<String, String>,
        /** Every thread id whose toolbar entity was found (reply params present only signed in). */
        val threadsWithToolbar: Set<String>,
        val likeActions: Map<String, Pair<String?, String?>> = emptyMap(),
        val next: String?,
    )

    internal fun parsePage(page: JSONObject): ParsedPage {
        // Entity payloads by key, then each thread's toolbar → its reply params.
        val entities = HashMap<String, JSONObject>()
        // Mutations are array elements, so this needs forEachObject (walk skips those).
        page.forEachObject { obj ->
            val key = obj.optString("entityKey")
            val payload = obj.optJSONObject("payload")
            if (key.isNotEmpty() && payload != null) entities[key] = payload
        }
        val replies = HashMap<String, String>()
        val toolbars = HashSet<String>()
        val likes = HashMap<String, Pair<String?, String?>>()
        page.walk { key, obj ->
            if (key == "commentViewModel" && obj.has("commentId")) {
                val id = obj.optString("commentId")
                val toolbar = entities[obj.optString("toolbarSurfaceKey")] ?: return@walk
                toolbars += id
                toolbar.collectStrings("createReplyParams").firstOrNull()?.let { replies[id] = it }
                // Signed in, likeCommand/unlikeCommand carry performCommentActionEndpoint.action.
                val likeAction = toolbar.optJSONObject("likeCommand")?.collectStrings("action")?.firstOrNull()
                val unlikeAction = toolbar.optJSONObject("unlikeCommand")?.collectStrings("action")?.firstOrNull()
                if (likeAction != null || unlikeAction != null) likes[id] = likeAction to unlikeAction
            }
        }
        // The page's own "more comments" token: the trailing item of the list that holds
        // the comment threads (thread-level reply loaders also use continuationItemRenderer).
        var next: String? = null
        page.walk { key, obj ->
            if (key == "reloadContinuationItemsCommand" || key == "appendContinuationItemsAction") {
                val items = obj.optJSONArray("continuationItems") ?: return@walk
                if (!items.hasThreads()) return@walk
                items.optJSONObject(items.length() - 1)?.optJSONObject("continuationItemRenderer")
                    ?.collectStrings("token")?.firstOrNull()?.let { next = it }
            }
        }
        return ParsedPage(
            createComment = page.collectStrings("createCommentParams").firstOrNull(),
            replyParams = replies, threadsWithToolbar = toolbars, likeActions = likes, next = next,
        )
    }

    private fun JSONArray.hasThreads() = (0 until length()).any { optJSONObject(it)?.has("commentThreadRenderer") == true }

    private fun checkPosted(response: JSONObject) {
        val text = response.toString()
        if ("STATUS_FAILED" in text || !(text.contains("createCommentAction") || text.contains("createCommentReplyAction") || text.contains("STATUS_SUCCEEDED"))) {
            Log.w("Skipii", "comment post response: ${text.take(600)}")
            throw IOException(str(R.string.comments_error_not_accepted))
        }
    }
}
