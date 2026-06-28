package com.powermediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M2 — service-side BT chapter navigation maths (single-file M4B). */
class BtChapterNavTest {

    // 0, 5, 10, 15 minutes (ms).
    private val starts = listOf(0L, 300_000L, 600_000L, 900_000L)

    @Test fun next_fromFirstChapter_goesToSecond() {
        assertEquals(300_000L, BtChapterNav.nextStart(starts, posMs = 10_000L))
    }

    @Test fun next_fromMiddle_goesToFollowing() {
        assertEquals(600_000L, BtChapterNav.nextStart(starts, posMs = 310_000L))
    }

    @Test fun next_inLastChapter_isNull_soCallerAdvancesItem() {
        assertNull(BtChapterNav.nextStart(starts, posMs = 950_000L))
    }

    @Test fun next_handlesUnsortedInput() {
        val unsorted = listOf(600_000L, 0L, 300_000L)
        assertEquals(300_000L, BtChapterNav.nextStart(unsorted, posMs = 10_000L))
    }

    @Test fun prev_pastGrace_restartsCurrentChapter() {
        // 10 s into chapter 1 (>3 s grace) → restart chapter 1.
        assertEquals(300_000L, BtChapterNav.prevTarget(starts, posMs = 310_000L))
    }

    @Test fun prev_withinGrace_goesToPreviousChapter() {
        // 1 s into chapter 1 (<3 s grace) → previous chapter start (0).
        assertEquals(0L, BtChapterNav.prevTarget(starts, posMs = 301_000L))
    }

    @Test fun prev_atFirstChapterStart_isNull_soCallerFallsBack() {
        // 1 s into chapter 0, cur=0 → null (caller does prev item / pos 0).
        assertNull(BtChapterNav.prevTarget(starts, posMs = 1_000L))
    }

    @Test fun emptyChapters_bothNull() {
        assertNull(BtChapterNav.nextStart(emptyList(), 5_000L))
        assertNull(BtChapterNav.prevTarget(emptyList(), 5_000L))
    }
}
