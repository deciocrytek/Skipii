package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-in feeds can't be fetched in tests, but they're built from the same renderers
 * as these public responses (captured 2026-10-04): a playlist page (lockupViewModel +
 * continuationItemViewModel), search (videoRenderer + lockupViewModel) and watch-next.
 */
class InnerTubeParseTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())

    @Test fun playlistPage() {
        val page = InnerTube.parseFeed(fixture("playlist"))
        assertTrue("expected ~100 videos, got ${page.videos.size}", page.videos.size >= 90)
        assertNotNull("playlist continuation token", page.next)
        page.videos.forEach { v ->
            assertEquals(11, v.id.length)
            assertTrue("title for ${v.id}", v.title.isNotBlank())
        }
        assertTrue("most items have a duration", page.videos.count { it.durationSec > 0 } >= page.videos.size * 0.9)
    }

    @Test fun searchResults() {
        val page = InnerTube.parseFeed(fixture("search"))
        assertTrue("got ${page.videos.size}", page.videos.size >= 10)
        assertNotNull("search continuation", page.next)
        assertTrue("channel names", page.videos.count { it.channel.isNotBlank() } >= page.videos.size * 0.8)
        assertTrue("view counts", page.videos.count { it.views > 0 } >= page.videos.size * 0.8)
    }

    @Test fun watchNextRelated() {
        val page = InnerTube.parseFeed(fixture("next"))
        assertTrue("got ${page.videos.size}", page.videos.size >= 10)
        assertTrue("channel names", page.videos.count { it.channel.isNotBlank() } >= page.videos.size * 0.8)
        // Khan Academy's "Fundraiser" badge has the same BADGE_COMMERCE style as movie offers.
        assertTrue("fundraiser video kept", page.videos.any { it.id == "-7Gl-yKF6Y4" })
    }

    /** Less Bitter's Videos tab (captured 2026-10-06): its "Insider Edition" uploads are members-only. */
    @Test fun channelVideosDropMembersOnly() {
        val page = InnerTube.parseFeed(fixture("channel_videos"))
        assertTrue("got ${page.videos.size}", page.videos.size >= 10)
        assertTrue("members-only leaked: ${page.videos.filter { "Insider Edition" in it.title }.map { it.title }}",
            page.videos.none { "Insider Edition" in it.title })
        assertNotNull("channel continuation", page.next)
    }

    /**
     * HBO Max's channel page (captured 2026-10-13): 4 movies as gridVideoRenderer ("Try now" + rating)
     * and 4 Primetime lockups (BADGE_COMMERCE + rating), then 4 ordinary search results.
     */
    @Test fun moviesDropped() {
        val page = InnerTube.parseFeed(fixture("movies"))
        assertTrue("movies leaked: ${page.videos.filter { "HBO Max" in it.channel }.map { it.title }}",
            page.videos.none { "HBO Max" in it.channel })
        assertEquals("ordinary videos kept", 4, page.videos.size)
    }

    /** The Shorts feed (reel/reel_watch_sequence), captured anonymously 2026-10-28 (prefetch data stripped). */
    @Test fun shortsSequence() {
        val page = InnerTube.parseShorts(fixture("reel_sequence"))
        assertEquals(listOf("6x4mqgyvCZ8", "0fgRDkGLIkA", "yLMNlEddt1E", "vtmR8DikTx0", "ylMTylT1Hdw", "R-74QNLTgxA"), page.videos.map { it.id })
        page.videos.forEach { assertTrue("first frame for ${it.id}", it.thumbnail!!.startsWith("https://i.ytimg.com/vi/${it.id}/")) }
        assertNotNull("sequence continuation", page.next)
    }

    /** The seed that worked in the 2026-10-28 probe: id, then params { 3 = 5 }, then 10 = 25. */
    @Test fun reelSequenceParams() {
        val bytes = java.util.Base64.getDecoder().decode(InnerTube.reelSequenceParams("m7fHuAcbD0A"))
        assertEquals(listOf(0x0A, 11) + "m7fHuAcbD0A".map { it.code } + listOf(0x2A, 0x02, 0x18, 0x05, 0x50, 25), bytes.map { it.toInt() })
    }

    @Test fun viewCounts() {
        assertEquals(1_234_567L, InnerTube.parseViews("1,234,567 views"))
        assertEquals(1_200_000L, InnerTube.parseViews("1.2M views"))
        assertEquals(36_000L, InnerTube.parseViews("36K views"))
        assertEquals(0L, InnerTube.parseViews("No views"))
        assertEquals(-1L, InnerTube.parseViews(null))
    }
}
