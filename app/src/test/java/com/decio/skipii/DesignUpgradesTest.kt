package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignUpgradesTest {

    // ── Swipe right to queue (Swipe.kt): a full swipe only ────────────────────

    @Test
    fun onlyARealSwipeQueues() {
        // Short tugs never queue (there's no "Play next" stage any more), however they end.
        assertFalse(swipeQueues(0f, 1000f))
        assertFalse(swipeQueues(200f, 1000f))
        assertFalse(swipeQueues(329f, 1000f))
        // A third of the way across does.
        assertTrue(swipeQueues(330f, 1000f))
        assertTrue(swipeQueues(600f, 1000f))
        // Not measured yet: nothing.
        assertFalse(swipeQueues(100f, 0f))
    }

    @Test
    fun swipeFollowsTheFingerThenResists() {
        // A queue-only card never goes left.
        assertEquals(0f, swipeOffset(0f, -120f, 1000f, right = true, left = false), 0.01f)
        // 1:1 up to the queue line.
        assertEquals(250f, swipeOffset(150f, 100f, 1000f, right = true, left = false), 0.01f)
        assertEquals(330f, swipeOffset(230f, 100f, 1000f, right = true, left = false), 0.01f)
        // Past it, heavier (0.45×) …
        assertEquals(445f, swipeOffset(400f, 100f, 1000f, right = true, left = false), 0.01f)
        // … easy to pull back …
        assertEquals(340f, swipeOffset(400f, -60f, 1000f, right = true, left = false), 0.01f)
        // … and never past 60%.
        assertEquals(600f, swipeOffset(590f, 500f, 1000f, right = true, left = false), 0.01f)
        // A playlist of the account's goes both ways; a queue row only left.
        assertEquals(-200f, swipeOffset(-100f, -100f, 1000f, right = true, left = true), 0.01f)
        assertEquals(0f, swipeOffset(0f, 80f, 1000f, right = false, left = true), 0.01f)
    }

    // ── #4: Living Chapter Ticker ────────────────────────────────────────────

    @Test
    fun chapterTickerSkipsStartupFlashAndFiresOnBoundaryCross() {
        // Initial load near 0s should not flash.
        assertFalse(shouldTriggerChapterTicker(null, "Intro", 500L, isPlaying = true, barAvailable = true))
        // Resuming or seeking into a chapter past 3s triggers.
        assertTrue(shouldTriggerChapterTicker(null, "Build", 15_000L, isPlaying = true, barAvailable = true))
        // Crossing from one chapter to the next triggers.
        assertTrue(shouldTriggerChapterTicker("Intro", "Chapter 2", 42_000L, isPlaying = true, barAvailable = true))
        // Same chapter does not re-trigger.
        assertFalse(shouldTriggerChapterTicker("Intro", "Intro", 42_000L, isPlaying = true, barAvailable = true))
        // Paused or bar busy/unavailable suppresses trigger.
        assertFalse(shouldTriggerChapterTicker("Intro", "Chapter 2", 42_000L, isPlaying = false, barAvailable = true))
        assertFalse(shouldTriggerChapterTicker("Intro", "Chapter 2", 42_000L, isPlaying = true, barAvailable = false))
    }

    // ── #6 & #10: Compact Remaining & Queue Runtime Formatting ───────────────

    @Test
    fun compactDurationRoundsCleanly() {
        assertNull(compactDurationFromMs(0L))
        assertNull(compactDurationFromMs(-1000L))
        assertEquals(CompactDuration(0, 0, underOneMinute = true), compactDurationFromMs(45_000L))
        assertEquals(CompactDuration(0, 1, underOneMinute = false), compactDurationFromMs(60_000L))
        assertEquals(CompactDuration(0, 14, underOneMinute = false), compactDurationFromMs(14 * 60_000L + 10_000L))
        assertEquals(CompactDuration(0, 15, underOneMinute = false), compactDurationFromMs(14 * 60_000L + 40_000L))
        assertEquals(CompactDuration(1, 0, underOneMinute = false), compactDurationFromMs(60 * 60_000L))
        assertEquals(CompactDuration(1, 12, underOneMinute = false), compactDurationFromMs(72 * 60_000L))
    }

    // ── #10: Queue Reordering, Runtime & Up Next Countdown ───────────────────

    @Test
    fun reorderListMovesItemsAndRejectsInvalidIndices() {
        assertEquals(listOf("b", "c", "a"), reorderList(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), reorderList(listOf("a", "b", "c"), 2, 0))
        assertNull(reorderList(listOf("a", "b", "c"), 1, 1))
        assertNull(reorderList(listOf("a", "b", "c"), -1, 1))
        assertNull(reorderList(listOf("a", "b", "c"), 0, 3))
    }

    @Test
    fun queueTotalDurationSumsPositiveDurationsOnly() {
        fun video(id: String, dur: Long) = VideoItem(id, id, "ch", null, null, null, dur, 0, null)
        assertEquals(0L, queueTotalDurationSec(emptyList()))
        assertEquals(720L, queueTotalDurationSec(listOf(video("a", 300), video("b", -1), video("c", 420))))
    }

    @Test
    fun upNextCountdownWindowAndGuards() {
        // Outside final 8s window.
        assertNull(upNextCountdown(positionMs = 50_000L, durationMs = 60_000L, hasNext = true, isPlaying = true, cancelled = false))
        // Exactly entering final 8s window.
        val at8 = upNextCountdown(positionMs = 52_000L, durationMs = 60_000L, hasNext = true, isPlaying = true, cancelled = false)
        assertNotNull(at8)
        assertEquals(8, at8!!.secondsLeft)
        assertEquals(1f, at8.progress, 0.001f)
        // Midway through countdown (4.2s left -> ceiling 5s, progress 0.525).
        val mid = upNextCountdown(positionMs = 55_800L, durationMs = 60_000L, hasNext = true, isPlaying = true, cancelled = false)
        assertNotNull(mid)
        assertEquals(5, mid!!.secondsLeft)
        assertEquals(0.525f, mid.progress, 0.001f)
        // Guarded when cancelled, paused, no next item, or short video (<15s).
        assertNull(upNextCountdown(55_000L, 60_000L, hasNext = true, isPlaying = true, cancelled = true))
        assertNull(upNextCountdown(55_000L, 60_000L, hasNext = true, isPlaying = false, cancelled = false))
        assertNull(upNextCountdown(55_000L, 60_000L, hasNext = false, isPlaying = true, cancelled = false))
        assertNull(upNextCountdown(8_000L, 12_000L, hasNext = true, isPlaying = true, cancelled = false))
    }
}
