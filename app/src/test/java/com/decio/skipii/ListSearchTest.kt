package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSearchTest {
    private fun video(title: String, channel: String) =
        VideoItem(id = "x", title = title, channel = channel, channelUrl = null, thumbnail = null, avatar = null, durationSec = 0, views = 0, uploaded = null)

    @Test
    fun wordsAreFoldedAndSplit() {
        assertEquals(listOf("cafe", "creme"), queryWords("  Café   CRÈME "))
        assertTrue(queryWords("   ").isEmpty())
    }

    @Test
    fun everyWordMustMatchTitleOrChannel() {
        val v = video("I tried every budget mechanical keyboard", "Tech Café")
        assertTrue(matchesQuery(v, queryWords("keyboard")))
        assertTrue(matchesQuery(v, queryWords("tech cafe")))
        assertTrue(matchesQuery(v, queryWords("KEYBOARD café")))
        assertFalse(matchesQuery(v, queryWords("keyboard mouse")))
        assertTrue(matchesQuery(v, emptyList()))
    }
}
