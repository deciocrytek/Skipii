package com.decio.skipii

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Not interested" / "Don't recommend channel" (#16). There's no signed-in capture yet: the cards are
 * REAL anonymous captures (Home lockups, search videoRenderers), whose menus hold only Add to queue and
 * Share; the signed-in items are added in the shape those menus already use (YouTube.js documents the
 * feedbackEndpoint they carry). Replace with a signed-in capture once there is one.
 */
class CardFeedbackParseTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())

    /** Every object under [key] in the tree (including ones directly in arrays). */
    private fun JSONObject.all(key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun visit(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> val c = v.opt(k); if (k == key && c is JSONObject) out += c; visit(c) }
                is JSONArray -> for (i in 0 until v.length()) visit(v.opt(i))
            }
        }
        visit(this)
        return out
    }

    private fun sheetItem(label: String, image: String, token: String) = JSONObject().put(
        "listItemViewModel",
        JSONObject()
            .put("title", JSONObject().put("content", label))
            .put("leadingImage", JSONObject().put("sources", JSONArray().put(JSONObject().put("clientResource", JSONObject().put("imageName", image)))))
            .put(
                "rendererContext",
                JSONObject().put(
                    "commandContext",
                    JSONObject().put("onTap", JSONObject().put("innertubeCommand", JSONObject().put("feedbackEndpoint", JSONObject().put("feedbackToken", token)))),
                ),
            ),
    )

    private fun menuItem(label: String, icon: String, token: String) = JSONObject().put(
        "menuServiceItemRenderer",
        JSONObject()
            .put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", label))))
            .put("icon", JSONObject().put("iconType", icon))
            .put("serviceEndpoint", JSONObject().put("feedbackEndpoint", JSONObject().put("feedbackToken", token))),
    )

    @Test fun anonymousCardsHaveNone() {
        val page = InnerTube.parseFeed(fixture("home_chip_feed"))
        assertTrue(page.videos.isNotEmpty())
        page.videos.forEach { assertNull("feedback on ${it.id}", it.feedback) }
    }

    @Test fun lockupSheet() {
        val root = fixture("home_chip_feed")
        val first = root.all("lockupViewModel").first()
        val items = first.all("listViewModel").first().getJSONArray("listItems")
        items.put(sheetItem("Not interested", "NOT_INTERESTED", "tokNI"))
        items.put(sheetItem("Don’t recommend channel", "REMOVE", "tokDR"))
        val video = InnerTube.parseFeed(root).videos.first { it.id == first.getString("contentId") }
        assertEquals(CardFeedback("tokNI", "tokDR"), video.feedback)
        // The other cards are untouched.
        assertEquals(1, InnerTube.parseFeed(root).videos.count { it.feedback != null })
    }

    @Test fun olderRendererMenu() {
        val root = fixture("search")
        val renderer = root.all("videoRenderer").first { it.has("menu") }
        val items = renderer.all("menuRenderer").first().getJSONArray("items")
        items.put(menuItem("Not interested", "NOT_INTERESTED", "a"))
        items.put(menuItem("Don't recommend channel", "REMOVE", "b"))
        val video = InnerTube.parseFeed(root).videos.first { it.id == renderer.getString("videoId") }
        assertEquals(CardFeedback("a", "b"), video.feedback)
    }
}
