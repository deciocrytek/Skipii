package com.decio.skipii

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The fixes for the problem reports of 2026-11-26 (see CLAUDE.md, 0.6.6). */
class ProblemReportsFixTest {
    @Test
    fun decoderLimitsLeaveOutTheFailedFormatAndAnythingTaller() {
        DecoderLimits.resetForTest()
        // A Redmi: 1080p60 wouldn't start, 720p60 played.
        assertTrue(DecoderLimits.allows(1080, 60))
        assertTrue(DecoderLimits.noteFailed(1080, 60))
        assertFalse(DecoderLimits.allows(1080, 60))
        assertFalse(DecoderLimits.allows(1440, 30))
        assertFalse(DecoderLimits.allows(2160, 60))
        assertTrue(DecoderLimits.allows(1080, 30))
        assertTrue(DecoderLimits.allows(720, 60))
        // The same failure again: nothing new to leave out, so the service lets the error stand.
        assertFalse(DecoderLimits.noteFailed(1080, 60))
        assertFalse(DecoderLimits.noteFailed(1440, 60))
        assertTrue(DecoderLimits.noteFailed(1080, 30))
        // A slower stream of the same height may still start; anything taller stays out.
        assertTrue(DecoderLimits.allows(1080, 25))
        assertTrue(DecoderLimits.allows(720, 60))
        DecoderLimits.resetForTest()
    }

    @Test
    fun stallsAreToldApartFromAnswersAndNoNetwork() {
        assertTrue(Net.isStall(SocketTimeoutException("Read timed out")))
        assertTrue(Net.isStall(InterruptedIOException("timeout")))
        assertTrue(Net.isStall(IOException("Canceled")))
        assertTrue(Net.isStall(SocketException("Connection reset")))
        // NewPipe wraps the downloader's exception.
        assertTrue(Net.isStall(RuntimeException("Could not get page", SocketTimeoutException("timeout"))))
        assertFalse(Net.isStall(UnknownHostException("Unable to resolve host")))
        assertFalse(Net.isStall(org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException("bot")))
    }

    @Test
    fun aStallIsTriedOnceMore() {
        var calls = 0
        val result = Net.onceMoreAfterStall("test") { if (++calls == 1) throw SocketTimeoutException("timeout") else "ok" }
        assertTrue(result == "ok" && calls == 2)
        calls = 0
        val second = runCatching { Net.onceMoreAfterStall("test") { calls++; throw SocketTimeoutException("timeout") } }
        assertTrue(second.isFailure && calls == 2)
        calls = 0
        val answer = runCatching { Net.onceMoreAfterStall("test") { calls++; throw UnknownHostException("offline") } }
        assertTrue(answer.isFailure && calls == 1)
    }
}
