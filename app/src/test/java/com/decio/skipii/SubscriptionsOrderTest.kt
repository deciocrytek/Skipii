package com.decio.skipii

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subscriptions in upload order (#50). No signed-in capture of FEsubscriptions exists: the page is built in
 * its web shape (a richGridRenderer whose first item is the "Most relevant" richShelfRenderer, then the
 * uploads as richItemRenderers) around REAL captured lockups. Replace with a capture once there is one.
 */
class SubscriptionsOrderTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())

    private fun lockups(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun visit(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k ->
                    val c = v.opt(k)
                    if (k == "lockupViewModel" && c is JSONObject && c.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") out += c
                    visit(c)
                }
                is JSONArray -> for (i in 0 until v.length()) visit(v.opt(i))
            }
        }
        visit(fixture("home_chip_feed"))
        return out
    }

    private fun item(lockup: JSONObject) = JSONObject().put("richItemRenderer", JSONObject().put("content", JSONObject().put("lockupViewModel", lockup)))

    private fun page(shelf: List<JSONObject>, uploads: List<JSONObject>): JSONObject {
        val contents = JSONArray()
        contents.put(
            JSONObject().put(
                "richSectionRenderer",
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "richShelfRenderer",
                        JSONObject()
                            .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Most relevant"))))
                            .put("contents", JSONArray().apply { shelf.forEach { put(item(it)) } }),
                    ),
                ),
            ),
        )
        uploads.forEach { contents.put(item(it)) }
        contents.put(JSONObject().put("continuationItemRenderer", JSONObject().put("continuationEndpoint", JSONObject().put("continuationCommand", JSONObject().put("token", "page2")))))
        return JSONObject().put("contents", JSONObject().put("richGridRenderer", JSONObject().put("contents", contents)))
    }

    @Test fun mostRelevantLeftOutAndNewestFirst() {
        val all = lockups()
        assertTrue("need cards to build with, got ${all.size}", all.size >= 6)
        val shelf = all.take(2)
        // Uploads in a scrambled order: the page comes back newest first.
        val uploads = all.drop(2).take(6).reversed()
        val result = InnerTube.parseSubscriptionsFeed(page(shelf, uploads))
        val ids = result.videos.map { it.id }
        shelf.forEach { assertTrue("Most relevant video ${it.getString("contentId")} left out", it.getString("contentId") !in ids) }
        assertEquals(uploads.size, ids.size)
        val ages = result.videos.map { NewVideos.ageMs(it.uploaded) }
        assertTrue("every card has an age: $ages", ages.all { it != null })
        assertEquals("newest first", ages.sortedBy { it }, ages)
        assertEquals("page2", result.next)
    }
}
