package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Test

/** Description links: cut-short addresses shown whole, and where each kind of link goes. */
class DescriptionLinksTest {
    private val current = "dQw4w9WgXcQ"

    @Test fun cutAddressShownWhole() {
        val url = "https://play.google.com/store/apps/details?id=com.example.clock"
        assertEquals(url, shownText("https://play.google.com/store/apps/de...", url))
        assertEquals("sbck.me/lumina", shownText("sbck.me/lumina", "https://sbck.me/lumina"))
        assertEquals("github.com/a/expressive-clock", shownText("github.com/a/expr…", "https://www.github.com/a/expressive-clock"))
        // Words that aren't the address stay as YouTube wrote them.
        assertEquals("01:10", shownText("01:10", "https://www.youtube.com/watch?v=$current&t=70s"))
        assertEquals("#android", shownText("#android", "https://www.youtube.com/hashtag/android"))
    }

    @Test fun targets() {
        assertEquals(LinkTarget.Seek(70_000), linkTarget("https://www.youtube.com/watch?v=$current&t=70s", current))
        assertEquals(LinkTarget.Seek(3_723_000), linkTarget("https://youtu.be/$current?t=1h2m3s", current))
        assertEquals(LinkTarget.Video("abcdefghijk"), linkTarget("https://www.youtube.com/watch?v=abcdefghijk&t=5", current))
        assertEquals(LinkTarget.Video("abcdefghijk"), linkTarget("https://youtube.com/shorts/abcdefghijk", current))
        assertEquals(LinkTarget.Channel("https://www.youtube.com/@mkbhd"), linkTarget("https://www.youtube.com/@mkbhd/videos", current))
        assertEquals(
            LinkTarget.Channel("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"),
            linkTarget("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ", current),
        )
        assertEquals(LinkTarget.Search("#android"), linkTarget("https://www.youtube.com/hashtag/android", current))
        assertEquals(LinkTarget.Web("https://sbck.me/remy"), linkTarget("https://sbck.me/remy", current))
        assertEquals(
            LinkTarget.Web("https://example.com/a?b=c"),
            linkTarget("https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.com%2Fa%3Fb%3Dc", current),
        )
        assertEquals(LinkTarget.Web("https://www.youtube.com/playlist?list=PL123"), linkTarget("https://www.youtube.com/playlist?list=PL123", current))
    }

    @Test fun startTimes() {
        assertEquals(70_000L, parseStartTime("70"))
        assertEquals(70_000L, parseStartTime("1m10s"))
        assertEquals(null, parseStartTime("abc"))
    }
}
