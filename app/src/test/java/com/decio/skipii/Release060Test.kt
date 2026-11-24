package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.0: search (filters, results), comment order, feed filters. Fixtures are real anonymous captures. */
class Release060Test {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    // ── Search filters: YouTube's own params (search.json's filter menu, 2026-10) ──

    @Test fun filterParamsMatchYouTubesMenu() {
        assertNull(SearchFilters().params())
        assertEquals("EgIQAQ==", SearchFilters(type = SearchType.Videos).params())
        assertEquals("EgIQAg==", SearchFilters(type = SearchType.Channels).params())
        assertEquals("EgIQAw==", SearchFilters(type = SearchType.Playlists).params())
        assertEquals("EgIIAg==", SearchFilters(date = SearchDate.Today).params())
        assertEquals("EgIIBQ==", SearchFilters(date = SearchDate.Year).params())
        assertEquals("EgIYBA==", SearchFilters(length = SearchLength.Short).params())
        assertEquals("EgIYBQ==", SearchFilters(length = SearchLength.Medium).params())
        assertEquals("EgIYAg==", SearchFilters(length = SearchLength.Long).params())
        assertEquals("CAM=", SearchFilters(sort = SearchSort.Popularity).params())
    }

    @Test fun everyMenuParamIsOneWeCanMake() {
        // Every option of the Type / Duration / Upload date / Prioritize groups the menu offers that Skipii uses.
        val offered = HashMap<String, String>()
        fixture("search.json").walk { key, obj ->
            if (key == "searchFilterRenderer") {
                val label = obj.text("label") ?: return@walk
                obj.optJSONObject("navigationEndpoint")?.optJSONObject("searchEndpoint")?.optString("params")
                    ?.let { offered[label] = java.net.URLDecoder.decode(it, "UTF-8") }
            }
        }
        assertEquals(offered["Videos"], SearchFilters(type = SearchType.Videos).params())
        assertEquals(offered["Today"], SearchFilters(date = SearchDate.Today).params())
        assertEquals(offered["This week"], SearchFilters(date = SearchDate.Week).params())
        assertEquals(offered["This month"], SearchFilters(date = SearchDate.Month).params())
        assertEquals(offered["Under 3 minutes"], SearchFilters(length = SearchLength.Short).params())
        assertEquals(offered["3 - 20 minutes"], SearchFilters(length = SearchLength.Medium).params())
        assertEquals(offered["Over 20 minutes"], SearchFilters(length = SearchLength.Long).params())
        assertEquals(offered["Popularity"], SearchFilters(sort = SearchSort.Popularity).params())
    }

    @Test fun combinedFiltersMakeOneMessage() {
        // Probed live 2026-11-23: answered with 20 videos.
        assertEquals("EgYIBBABGAI=", SearchFilters(type = SearchType.Videos, date = SearchDate.Month, length = SearchLength.Long).params())
        assertEquals("CAMSAhAB", SearchFilters(type = SearchType.Videos, sort = SearchSort.Popularity).params())
        // Channels / playlists drop the video-only filters, as YouTube's page does.
        assertEquals("EgIQAg==", SearchFilters(type = SearchType.Channels, date = SearchDate.Today, sort = SearchSort.Popularity).params())
    }

    // ── Search results ──

    @Test fun mixedResultsInOrder() {
        val page = InnerTube.parseSearch(fixture("search.json"))
        assertTrue(page.hits.size >= 15)
        assertNotNull(page.next)
        val channel = page.hits.filterIsInstance<SearchHit.Channel>().first()
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", channel.channel.id)
        assertEquals("@kurzgesagt", channel.handle)
        assertEquals(25_600_000L, channel.subscribers)
        assertTrue(channel.channel.avatar!!.startsWith("https://"))
        // Shorts shelves are left out; videos keep YouTube's order around the channel.
        assertTrue(page.hits.filterIsInstance<SearchHit.Video>().size >= 10)
        assertTrue(page.hits.indexOf(channel) > 0)
        assertEquals(page.hits.size, page.hits.map { it.key }.toSet().size)
    }

    @Test fun channelResults() {
        val page = InnerTube.parseSearch(fixture("search_channels.json"))
        val channels = page.hits.filterIsInstance<SearchHit.Channel>()
        assertTrue(channels.size >= 10)
        assertTrue(channels.all { it.channel.id.startsWith("UC") && it.channel.name.isNotBlank() })
        assertTrue(channels.count { it.subscribers > 0 } >= channels.size / 2)
    }

    @Test fun playlistResults() {
        val page = InnerTube.parseSearch(fixture("search_playlists.json"))
        val lists = page.hits.filterIsInstance<SearchHit.Playlist>()
        assertTrue(lists.size >= 10)
        val first = lists.first()
        assertEquals("PLQP48LF88GGHp4YsZpAceooSHf55it_0M", first.playlist.id)
        assertEquals("Home Barista Series | Workflows", first.playlist.title)
        assertEquals("8 videos", first.playlist.subtitle)
        assertEquals("TJ", first.channel)
    }

    @Test fun nextPageResults() {
        val page = InnerTube.parseSearch(fixture("search_more.json"))
        assertTrue(page.hits.filterIsInstance<SearchHit.Video>().size >= 10)
        assertNotNull(page.next)
    }

    // ── Comments: Top / Newest (#60) ──

    @Test fun commentSortsFromTheHeader() {
        val page = CommentFeed.parsePage(fixture("comments.json"))
        assertEquals(2, page.sorts.size)
        val top = page.sorts.first { !it.newest }
        val newest = page.sorts.first { it.newest }
        assertTrue(top.selected)
        assertFalse(newest.selected)
        assertTrue(newest.token.startsWith("Eg0SC"))
        assertTrue(top.token != newest.token)
    }

    // ── Feed filters ──

    private fun video(id: String, title: String, seconds: Long, channel: String = "UCaaaaaaaaaaaaaaaaaaaaaa") = VideoItem(
        id = id, title = title, channel = "Some Channel", channelUrl = "https://www.youtube.com/channel/$channel",
        thumbnail = null, avatar = null, durationSec = seconds, views = 0, uploaded = null,
    )

    @Test fun feedFiltersHideByWordLengthChannelAndWatched() {
        val a = video("aaaaaaaaaaa", "Reação ao novo trailer", 600)
        val b = video("bbbbbbbbbbb", "A calm walk", 40)
        val c = video("ccccccccccc", "Unknown length", 0)
        val d = video("ddddddddddd", "Muted channel", 900, channel = "UCmutedmutedmutedmutedmu")
        val e = video("eeeeeeeeeee", "Seen it", 900)
        FeedFilters.addWord("reacao")
        FeedFilters.chooseMinSeconds(60)
        FeedFilters.muteChannelOf(d)
        FeedFilters.chooseHideWatched(true)
        WatchedStore.mark(e.id)
        try {
            assertEquals(listOf(c), FeedFilters.apply(listOf(a, b, c, d, e)))
        } finally {
            FeedFilters.removeWord("reacao")
            FeedFilters.chooseMinSeconds(0)
            FeedFilters.mutedChannels.forEach(FeedFilters::unmute)
            FeedFilters.chooseHideWatched(false)
        }
        assertEquals(listOf(a, b, c, d, e), FeedFilters.apply(listOf(a, b, c, d, e)))
    }

    @Test fun channelIdFromLink() {
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", video("x", "t", 1, "UCsXVk37bltHxD1rDPwtNM8Q").channelId())
        assertNull(video("x", "t", 1).copy(channelUrl = "https://www.youtube.com/@someone").channelId())
    }
}
