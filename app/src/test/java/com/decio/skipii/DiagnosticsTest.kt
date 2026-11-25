package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The problem report's notebook (Diagnostics.kt) never carries what points at the person. */
class DiagnosticsTest {
    @Test
    fun linksLoseTheirQuery() {
        val line = AppLog.clean(
            "load error from https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ip=203.0.113.9&id=o-AB&itag=137&sig=XYZ done",
        )
        assertEquals("load error from https://rr3---sn-abc.googlevideo.com/videoplayback?… done", line)
    }

    @Test
    fun addressesEmailsAndCookiesBlanked() {
        val line = AppLog.clean("ip=198.51.100.7 from 10.0.0.12 by someone@example.com with SAPISID=abc123; __Secure-3PSID=zzz")
        assertFalse(line.contains("198.51.100.7"))
        assertFalse(line.contains("10.0.0.12"))
        assertFalse(line.contains("someone@example.com"))
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("zzz"))
        assertTrue(line.contains("<email>"))
    }

    @Test
    fun repeatsFold() {
        val lines = listOf(
            "11-24 21:00:16.660 W bot check for l7y2Rn_nJKc; giving up",
            "11-24 21:00:16.789 W shorts bank: couldn't bank l7y2Rn_nJKc",
            "11-24 21:00:18.092 W bot check for OpFyNK-DNAs; giving up",
            "11-24 21:00:18.194 W shorts bank: couldn't bank OpFyNK-DNAs",
            "11-24 21:00:19.000 W player: wAvbaRzvvsQ still loading after 10 s",
            "11-24 21:00:29.000 W player: wAvbaRzvvsQ still loading after 20 s",
            "11-24 21:00:30.000 I comments for wAvbaRzvvsQ in 1297 ms",
        )
        assertEquals(
            listOf(lines[0], lines[1], "        … 2 similar lines", lines[4], lines[5], lines[6]),
            ProblemReport.fold(lines),
        )
    }

    @Test
    fun theOpenedVideoNeverFolds() {
        val lines = listOf(
            "11-24 20:59:42.278 I SkipiiTime: open wAvbaRzvvsQ",
            "11-24 20:59:48.620 W bot check for 1YxlQHCLxU8; giving up",
            "11-24 20:59:49.000 W bot check for wAvbaRzvvsQ; giving up",
            "11-24 20:59:50.000 W bot check for ay-8BmdLKuk; giving up",
        )
        assertEquals(listOf(lines[0], lines[1], lines[2], "        … 1 similar lines"), ProblemReport.fold(lines))
    }

    @Test
    fun ordinaryLinesUntouched() {
        val line = "links for wAvbaRzvvsQ in 2140 ms: video 1080/720/480p, 12 audio (3 track(s)), 1 combined; picked itag 136 720p + itag 140 [en-US.4]"
        assertEquals(line, AppLog.clean(line))
    }
}
