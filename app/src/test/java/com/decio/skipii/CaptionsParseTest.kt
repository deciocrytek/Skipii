package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** srv3 caption files (Srv3) and what shows when (captionLinesAt), on real files captured 2026-11-01 (dQw4w9WgXcQ). */
class CaptionsParseTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun creatorTrack() {
        val cues = Srv3.parse(fixture("captions_creator.srv3"), timedWords = false)
        assertTrue(cues.size > 20)
        assertEquals(CaptionCue(1360, 3040, "[♪♪♪]"), cues[0])
        assertTrue(cues[0].sound)
        // Entities decoded.
        assertEquals("♪ We're no strangers to love ♪", cues[1].text)
        assertEquals(18_640L, cues[1].startMs)
        assertNull(cues[1].words)
        assertEquals(cues.sortedBy { it.startMs }, cues)
    }

    @Test
    fun autoTrackHasWordTimes() {
        val cues = Srv3.parse(fixture("captions_auto.srv3"), timedWords = true)
        // The blank `a="1"` line-break markers are dropped.
        assertTrue(cues.none { it.text.isBlank() })
        val first = cues.first { it.words != null }
        assertEquals(18_800L, first.startMs)
        assertEquals("We're no strangers to", first.text)
        assertNotNull(first.words)
        val words = first.words!!
        assertEquals(CaptionWord(0, "We're"), words[0])
        assertEquals(CaptionWord(239, " no"), words[1])
        assertEquals(CaptionWord(559, " strangers"), words[2])
    }

    @Test
    fun autoLinesRollUpWholeLines() {
        val cues = Srv3.parse(fixture("captions_auto.srv3"), timedWords = true)
        // The first sung line shows whole as soon as it starts (not word by word).
        assertEquals(listOf(CaptionLine("We're no strangers to", false, 18_800)), captionLinesAt(cues, 18_800 + 300, rollUp = true))
        // The next line comes in under it.
        val two = captionLinesAt(cues, 21_800 + 1_100, rollUp = true)
        assertEquals(listOf("We're no strangers to", two[1].text), two.map { it.text })
        assertTrue(two[1].text.startsWith("love. You know the rules"))
        // Once a third starts, the first is gone: two lines at most.
        val rolled = captionLinesAt(cues, 25_960 + 100, rollUp = true)
        assertEquals(2, rolled.size)
        assertEquals(two[1], rolled[0])
        // Before anything is said.
        assertEquals(emptyList<CaptionLine>(), captionLinesAt(cues, 0, rollUp = true))
    }

    @Test
    fun creatorLinesShowWhole() {
        val cues = Srv3.parse(fixture("captions_creator.srv3"), timedWords = false)
        assertEquals(listOf(CaptionLine("♪ We're no strangers to love ♪", false, 18_640)), captionLinesAt(cues, 19_000, rollUp = false))
        // Between lines: nothing.
        assertEquals(emptyList<CaptionLine>(), captionLinesAt(cues, 10_000, rollUp = false))
        assertEquals(1, cueIndexAt(cues, 19_000))
        assertEquals(-1, cueIndexAt(cues, 0))
    }

    @Test
    fun breaksAndEntities() {
        val cues = Srv3.parse(
            """<?xml version="1.0"?><timedtext format="3"><body><p t="0" d="1000">One &amp; two<br/>three &#x2014; four</p><p t="1000" d="0">gone</p></body></timedtext>""",
            timedWords = false,
        )
        assertEquals(listOf(CaptionCue(0, 1000, "One & two\nthree — four")), cues)
    }
}
