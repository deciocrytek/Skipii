package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * The bell: its state on a watch page (next.json, Veritasium, not subscribed: "Personalized"), the
 * hand-built preference params against the ones youtube.com's own menu carries, and a channel's
 * RSS feed (channel_feed.xml, Veritasium's, captured 2026-11-01).
 */
class BellTest {
    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** Not subscribed, YouTube still sends the bell (on its default, Personalized); Bells ignores it then. */
    @Test fun watchPageBell() {
        val state = InnerTube.parseVideoState(json("next"))
        assertEquals(false, state.subscribed)
        assertEquals("UCHnyfMqiRRG1u-2MsSQLbXA", state.channelId)
        assertEquals("Personalized is not All", false, state.bell)
    }

    @Test fun bellStates() {
        fun toggle(current: Int, icon: String) = JSONObject(
            """{"currentStateId": $current, "states": [{"stateId": $current, "state": {"buttonRenderer": {"icon": {"iconType": "$icon"}}}}]}""",
        )
        assertEquals(true, InnerTube.parseBell(toggle(2, "NOTIFICATIONS_ACTIVE")))
        assertEquals(false, InnerTube.parseBell(toggle(3, "NOTIFICATIONS_NONE")))
        assertEquals(false, InnerTube.parseBell(toggle(0, "NOTIFICATIONS_OFF")))
        assertEquals(true, InnerTube.parseBell(JSONObject("""{"currentStateId": 2}""")))
        assertNull(InnerTube.parseBell(JSONObject("""{"currentStateId": 7}""")))
    }

    /** youtube.com's Personalized / None items = these params plus its field 10 (the watch page's video). */
    @Test fun paramsMatchYouTubes() {
        val menu = mutableMapOf<String, String>()
        json("next").walk { key, obj ->
            if (key != "menuServiceItemRenderer") return@walk
            val params = obj.optJSONObject("serviceEndpoint")?.optJSONObject("modifyChannelNotificationPreferenceEndpoint")?.optString("params")
            if (!params.isNullOrBlank()) menu[obj.text("text")!!] = params
        }
        fun bytes(params: String) = Base64.getDecoder().decode(URLDecoder.decode(params, "UTF-8"))
        for ((label, preference) in listOf("Personalized" to 1, "None" to 3)) {
            val theirs = bytes(menu.getValue(label))
            val ours = bytes(InnerTube.bellParams("UCHnyfMqiRRG1u-2MsSQLbXA", preference))
            assertArrayEquals(label, theirs.copyOf(ours.size), ours)
            assertEquals("then field 10", 0x52, theirs[ours.size].toInt())
        }
        assertTrue("URL-encoded like YouTube's", InnerTube.bellParams("UCHnyfMqiRRG1u-2MsSQLbXA", 2).endsWith("%3D%3D"))
    }

    @Test fun channelFeed() {
        val (title, entries) = NewVideos.parseFeed(text("channel_feed.xml"))
        assertEquals("Veritasium", title)
        assertEquals(15, entries.size)
        entries.forEach { assertEquals(11, it.id.length); assertTrue(it.title.isNotBlank()) }
        assertEquals("newest first", entries.sortedByDescending { it.publishedMs }, entries)
        assertEquals(11, entries.count { it.short })
        assertFalse(entries.first { !it.short }.title.contains("&amp;"))
    }

    /** The Subscriptions feed's ages, used when a channel's RSS feed fails. */
    @Test fun ages() {
        assertEquals(3 * 3_600_000L, NewVideos.ageMs("3 hours ago"))
        assertEquals(2 * 86_400_000L, NewVideos.ageMs("Streamed 2 days ago"))
        assertEquals(60_000L, NewVideos.ageMs("1 minute ago"))
        assertNull(NewVideos.ageMs("Premieres in 3 hours"))
        assertNull(NewVideos.ageMs(null))
    }

    /** Read only up to a seen video: the cut-off entry is dropped, everything above it is whole. */
    @Test fun cutFeed() {
        val xml = text("channel_feed.xml")
        val full = NewVideos.parseFeed(xml).second
        val cut = xml.substring(0, xml.indexOf("<yt:videoId>${full[3].id}</yt:videoId>") + 30)
        val entries = NewVideos.parseFeed(cut).second
        assertEquals(full.take(3), entries)
    }
}
