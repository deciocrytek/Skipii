package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.5.117's quick batch: links from other apps, comment timestamps, sharper thumbnails, word-by-word captions. */
class QolBatchTest {
    private val current = "dQw4w9WgXcQ"

    @Test fun commentTimestampsBecomeSeeks() {
        val text = "the part at 4:21 where it slips, and 1:02:33 too. Score was 3:2:1 lol"
        val d = commentLinks(text, current)
        val stamps = d.links.map { text.substring(it.start, it.end) to linkTarget(it.url, current) }
        assertEquals(
            listOf("4:21" to LinkTarget.Seek(261_000), "1:02:33" to LinkTarget.Seek(3_753_000)),
            stamps,
        )
    }

    @Test fun commentTimesThatAreNotTimestamps() {
        // 4:75 isn't a time; with no video there's nothing to seek.
        assertEquals(emptyList<DescriptionLink>(), commentLinks("ratio 4:75", current).links)
        assertEquals(emptyList<DescriptionLink>(), commentLinks("at 4:21", null).links)
    }

    @Test fun commentWebLinks() {
        val text = "source: https://example.com/page."
        val d = commentLinks(text, current)
        assertEquals(listOf("https://example.com/page"), d.links.map { it.url })
    }

    @Test fun incomingLinks() {
        assertEquals("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", linkPlaylistId("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
        assertNull(linkPlaylistId("https://www.youtube.com/watch?v=$current&list=PLx"))
        assertNull(linkPlaylistId("https://music.youtube.com/playlist?list=PLx"))
        assertEquals(43_000L, linkStartMs("https://youtu.be/$current?t=43"))
        assertEquals(90_000L, linkStartMs("https://m.youtube.com/watch?v=$current&t=1m30s"))
        assertNull(linkStartMs("https://youtu.be/$current"))
        assertEquals(LinkTarget.Video("abcdefghijk"), linkTarget("https://www.youtube.com/live/abcdefghijk?si=x", current))
        assertEquals(LinkTarget.Seek(12_000), linkTarget("https://www.youtube.com/shorts/$current?t=12", current))
    }

    @Test fun sharperThumbnails() {
        assertEquals("https://i.ytimg.com/vi/$current/hq720.jpg", sharperThumbnail("https://i.ytimg.com/vi/$current/hqdefault.jpg"))
        assertEquals("https://i.ytimg.com/vi/$current/hq720.jpg", sharperThumbnail("https://i.ytimg.com/vi/$current/hqdefault.jpg?sqp=abc&rs=x"))
        assertNull(sharperThumbnail("https://i.ytimg.com/vi/$current/hq720.jpg"))
        assertNull(sharperThumbnail("https://yt3.ggpht.com/avatar=s88"))
        assertNull(sharperThumbnail(null))
    }

    @Test fun wordByWordShowsOnlyWhatWasSaid() {
        val first = CaptionCue(1_000, 3_000, "hello there world", listOf(CaptionWord(0, "hello"), CaptionWord(400, " there"), CaptionWord(900, " world")))
        val second = CaptionCue(3_200, 5_000, "second line", listOf(CaptionWord(0, "second"), CaptionWord(300, " line")))
        val pages = captionPages(listOf(first, second))
        assertEquals(1, pages.size)
        fun at(t: Long) = captionPageAt(pages, t, wordByWord = true).map { it.text }
        assertEquals(listOf("hello"), at(1_100))
        assertEquals(listOf("hello there"), at(1_500))
        assertEquals(listOf("hello there world", "second"), at(3_300))
        // Off: both whole lines from the page's start.
        assertEquals(listOf("hello there world", "second line"), captionPageAt(pages, 1_100).map { it.text })
    }

    @Test fun freshCaptionTracks() {
        val answer = JSONObject(
            """{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
              {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=srv1&sig=a","languageCode":"en"},
              {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr&sig=b","languageCode":"en","kind":"asr"}]}}}""",
        )
        val tracks = CaptionRescue.parseTracks(answer)
        assertEquals(
            listOf(
                CaptionTrack("en", false, "https://www.youtube.com/api/timedtext?v=x&lang=en&sig=a&fmt=srv3"),
                CaptionTrack("en", true, "https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr&sig=b&fmt=srv3"),
            ),
            tracks,
        )
        assertEquals(emptyList<CaptionTrack>(), CaptionRescue.parseTracks(JSONObject("{}")))
    }
}
