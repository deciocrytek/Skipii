package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chapters read off the watch page (CommentFeed.parseChapters), on real captured responses. */
class ChaptersParseTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())

    @Test
    fun autoChapters() {
        // "The Deserved Downfall of Dr Phil": YouTube's auto chapters only, which NewPipe skips.
        val chapters = CommentFeed.parseChapters(fixture("next_auto_chapters"))
        assertEquals(13, chapters.size)
        assertEquals(Chapter(0, "The decline of an empire"), chapters[0])
        assertEquals(Chapter(86_080, "Questionable credentials"), chapters[1])
        assertEquals(chapters.sortedBy { it.startMs }, chapters)
    }

    @Test
    fun descriptionChapters() {
        val chapters = CommentFeed.parseChapters(fixture("next"))
        assertEquals(true, chapters.isNotEmpty())
        assertEquals(0L, chapters.first().startMs)
    }

    @Test
    fun noChapters() {
        assertEquals(emptyList<Chapter>(), CommentFeed.parseChapters(JSONObject("{\"contents\":{}}")))
    }
}
