package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Characterisation tests for the Nero `chpl` chapter-atom parse (audit TEST GAP,
 * 2026-08-25). Exercises [M4bChapterParser.parseChplForTest] with synthetic atom bodies
 * so the parse + finalize pipeline is covered without an .m4b fixture or a device.
 *
 * chpl body layout (see M4bChapterParser):
 *   1 byte version (0 or 1); 3 bytes flags; [4 bytes reserved IF version==1];
 *   1 byte count; per chapter: 8 bytes BE start (100-ns ticks), 1 byte titleLen, N bytes UTF-8.
 * startMs = startTicks / 10_000.
 */
class M4bChapterParserTest {

    private fun ticksForMs(ms: Long): Long = ms * 10_000L

    /** Build a synthetic chpl atom body. */
    private fun chpl(
        version: Int,
        chapters: List<Pair<Long, String>>, // startMs to title
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(version and 0xFF)
        out.write(0); out.write(0); out.write(0) // flags
        if (version == 1) { repeat(4) { out.write(0) } } // reserved
        out.write(chapters.size and 0xFF) // count
        for ((startMs, title) in chapters) {
            val ticks = ticksForMs(startMs)
            for (b in 7 downTo 0) out.write(((ticks shr (b * 8)) and 0xFF).toInt())
            val tb = title.toByteArray(Charsets.UTF_8)
            out.write(tb.size and 0xFF)
            out.write(tb)
        }
        return out.toByteArray()
    }

    @Test
    fun version1_twoChapters_startTimesAndTitlesParsed() {
        val body = chpl(1, listOf(0L to "Intro", 60_000L to "Chapter Two"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 120_000L)
        assertEquals(2, result.size)
        assertEquals("Intro", result[0].first)
        assertEquals(0L, result[0].second)
        assertEquals("Chapter Two", result[1].first)
        assertEquals(60_000L, result[1].second)
    }

    @Test
    fun version0_noReservedBytes_parsesCorrectly() {
        // version 0 has NO 4-byte reserved field — a wrong offset would shift every field.
        val body = chpl(0, listOf(0L to "A", 30_000L to "B"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 90_000L)
        assertEquals(2, result.size)
        assertEquals(0L, result[0].second)
        assertEquals(30_000L, result[1].second)
    }

    @Test
    fun endTimes_areNextStart_lastClampedToDuration() {
        val body = chpl(1, listOf(0L to "One", 10_000L to "Two", 20_000L to "Three"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 25_000L)
        assertEquals(3, result.size)
        assertEquals(10_000L, result[0].third) // ends where next starts
        assertEquals(20_000L, result[1].third)
        assertEquals(25_000L, result[2].third) // last clamped to media duration
    }

    @Test
    fun emptyTitle_fallsBackToChapterN() {
        val body = chpl(1, listOf(0L to "", 5_000L to ""))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 60_000L)
        assertEquals(2, result.size)
        assertEquals("Chapter 1", result[0].first)
        assertEquals("Chapter 2", result[1].first)
    }

    @Test
    fun bomAndZeroWidth_strippedFromTitle() {
        // Leading BOM (U+FEFF) must be stripped (mojibake cleaning in parseChpl).
        val body = chpl(1, listOf(0L to "﻿Prologue"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 60_000L)
        assertEquals(1, result.size)
        assertEquals("Prologue", result[0].first)
    }

    @Test
    fun outOfOrderChapters_sortedByStart() {
        val body = chpl(1, listOf(20_000L to "Late", 0L to "Early", 10_000L to "Mid"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 30_000L)
        assertEquals(3, result.size)
        assertEquals("Early", result[0].first)
        assertEquals("Mid", result[1].first)
        assertEquals("Late", result[2].first)
    }

    @Test
    fun chaptersPastMediaDuration_filteredOut() {
        // A chapter starting at/after the media duration is invalid → dropped.
        val body = chpl(1, listOf(0L to "Valid", 50_000L to "PastEnd"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 40_000L)
        assertEquals(1, result.size)
        assertEquals("Valid", result[0].first)
    }

    @Test
    fun truncatedBody_returnsEmptyNotCrash() {
        // A body that claims 2 chapters but is cut short must not throw.
        val full = chpl(1, listOf(0L to "One", 10_000L to "Two"))
        val truncated = full.copyOf(full.size - 4)
        val result = M4bChapterParser.parseChplForTest(truncated, mediaDurationMs = 60_000L)
        assertTrue("truncated parse must not crash + must not fabricate", result.size <= 2)
    }

    @Test
    fun tooShortHeader_returnsEmpty() {
        val result = M4bChapterParser.parseChplForTest(byteArrayOf(1, 0, 0), mediaDurationMs = 60_000L)
        assertEquals(0, result.size)
    }

    @Test
    fun ticksToMs_conversionExact() {
        // 1 chapter at 90_123 ms → ticks 901_230_000 → back to 90_123 ms.
        val body = chpl(1, listOf(90_123L to "Precise"))
        val result = M4bChapterParser.parseChplForTest(body, mediaDurationMs = 200_000L)
        assertEquals(1, result.size)
        assertEquals(90_123L, result[0].second)
    }
}
