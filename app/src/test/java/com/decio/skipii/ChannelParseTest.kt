package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel page's tabs (GitHub #53), on anonymous captures of 2026-11-04: Veritasium's page
 * (Home tab: featured video, a Videos shelf, playlist shelves, Featured Channels, Posts) and its
 * Videos (with the sort chips), Popular, Shorts and Playlists tabs; Mark Rober's Home (a Shorts shelf).
 */
class ChannelParseTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())
    private val veritasium = "UCHnyfMqiRRG1u-2MsSQLbXA"

    @Test fun homeTabs() {
        val home = InnerTube.parseChannelHome(fixture("channel_home"), veritasium)
        assertEquals(listOf(ChannelTab.Home, ChannelTab.Videos, ChannelTab.Shorts, ChannelTab.Playlists), home.tabs.keys.toList())
        assertEquals("EgZ2aWRlb3PyBgQKAjoA", home.tabs[ChannelTab.Videos])
        assertEquals("AF8d72mA41M", home.featured?.id)
        assertTrue(home.featured!!.title.isNotBlank())
    }

    @Test fun homeShelves() {
        val home = InnerTube.parseChannelHome(fixture("channel_home"), veritasium)
        val titles = home.shelves.map { it.title }
        assertEquals("Videos", titles.first())
        assertTrue("Posts has nothing to play: $titles", "Posts" !in titles)
        val videos = home.shelves.first()
        assertEquals(12, videos.videos.size)
        assertEquals(ShelfLink.Tab(ChannelTab.Videos), videos.link)
        val physics = home.shelves.first { it.title == "Physics" }
        assertEquals("PLkahZjV5wKe9q3K-nk82kVD_pNfPZ140i", (physics.link as ShelfLink.Playlist).ref.id)
        val featured = home.shelves.first { it.title == "Featured Channels" }
        assertTrue(featured.channels.size >= 6)
        featured.channels.forEach { assertTrue(it.id.startsWith("UC")); assertTrue(it.name.isNotBlank()) }
        // "Collaborations" has no See all.
        assertNull(home.shelves.first { it.title == "Collaborations" }.link)
    }

    @Test fun homeShortsShelf() {
        val home = InnerTube.parseChannelHome(fixture("channel_home_shorts"), "UCY1kMZp36IQSyNx_9h4mpCg")
        val shorts = home.shelves.first { it.shorts.isNotEmpty() }
        assertEquals("Shorts", shorts.title)
        assertEquals(ShelfLink.Tab(ChannelTab.Shorts), shorts.link)
        shorts.shorts.forEach {
            assertEquals(11, it.id.length)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.views > 0)
            assertTrue(it.thumbnail!!.startsWith("https://i.ytimg.com/vi/${it.id}/"))
        }
    }

    @Test fun videosSortChips() {
        val root = fixture("channel_videos_sorts")
        val chips = InnerTube.parseHomeChips(root)
        assertEquals(listOf("Latest", "Popular", "Oldest"), chips.map { it.title })
        assertNull("Latest is the list shown", chips[0].token)
        assertTrue(chips.drop(1).all { it.token != null })
        val page = InnerTube.parseFeed(root)
        assertEquals(30, page.videos.size)
        assertNotNull(page.next)
    }

    @Test fun popularOrder() {
        val page = InnerTube.parseFeed(fixture("channel_videos_popular"))
        assertEquals(30, page.videos.size)
        assertNotNull(page.next)
        // Most viewed first.
        assertTrue(page.videos.first().views >= page.videos.last().views)
    }

    @Test fun shortsTab() {
        val page = InnerTube.parseChannelShorts(fixture("channel_shorts"))
        assertEquals(48, page.videos.size)
        assertNotNull(page.next)
        page.videos.forEach { assertTrue("title for ${it.id}", it.title.isNotBlank()) }
    }

    @Test fun playlistsTab() {
        val root = fixture("channel_playlists")
        val playlists = InnerTube.parsePlaylists(root)
        assertEquals(13, playlists.size)
        assertEquals("Computing", playlists[0].title)
        assertEquals("PLkahZjV5wKe8w9GC_n2yyhx6Wj-NKzzeE", playlists[0].id)
        assertEquals("5 videos", playlists[0].subtitle)
        assertNotNull(playlists[0].thumbnail)
    }

    @Test fun tabParams() {
        assertEquals(ChannelTab.Videos, InnerTube.tabOfParams("EgZ2aWRlb3MYAyAAcAPyBg0KCzoEIgIIBKIBAggB"))
        assertEquals(ChannelTab.Shorts, InnerTube.tabOfParams("EgZzaG9ydHPyBgUKA5oBAA%3D%3D"))
        assertEquals(ChannelTab.Playlists, InnerTube.tabOfParams("EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA"))
        assertNull("posts", InnerTube.tabOfParams("EgVwb3N0c_IGBAoCSgA%3D"))
        assertNull(InnerTube.tabOfParams(null))
    }
}
