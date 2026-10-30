package com.powermediaplayer.ui.player

import com.powermediaplayer.service.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels and chapter chip follow the finger while scrubbing (2026-10-30, video issue 3). */
class ScrubPreviewTest {
    // A single-file book: two chapters, 0–60 s and 60–100 s; the player sits 10 s into chapter 2.
    private val chapters = listOf(
        ChapterInfo("One", 0L, 60_000L, 0),
        ChapterInfo("Two", 60_000L, 100_000L, 1)
    )
    private val base = PositionUi(
        trackProgress = 0.25f,                 // 10 s into the 40 s chapter
        positionFormatted = "0:10", durationFormatted = "0:40", remainingFormatted = "-0:30",
        playlistProgress = 0.7f,               // 70 s of 100 s
        playlistPositionFormatted = "1:10", playlistDurationFormatted = "1:40", playlistRemainingFormatted = "-0:30",
        chapterStartMs = 60_000L, durationMs = 40_000L, totalPlaylistDurationMs = 100_000L, positionMs = 10_000L
    )

    @Test fun noPreviewReturnsTheSameObject() = assertSame(base, applyScrubPreview(base, chapters, null))

    @Test fun trackPreviewMovesBothBarsLabels() {
        val p = applyScrubPreview(base, chapters, ScrubBar.TRACK to 0.5f)   // 20 s into chapter 2 = 80 s absolute
        assertEquals("0:20", p.positionFormatted)
        assertEquals("-0:20", p.remainingFormatted)
        assertEquals(0.5f, p.trackProgress, 0f)
        assertEquals("1:20", p.playlistPositionFormatted)
        assertEquals("-0:20", p.playlistRemainingFormatted)
        assertEquals(0.8f, p.playlistProgress, 1e-6f)
        assertTrue(p.scrubbing)
        assertNull(p.scrubChapterTitle)      // same chapter
    }

    @Test fun fullPreviewLandingInAnotherChapterPreviewsThatChapter() {
        val p = applyScrubPreview(base, chapters, ScrubBar.FULL to 0.3f)    // 30 s absolute = chapter One, 30 s in
        assertEquals("0:30", p.playlistPositionFormatted)
        assertEquals("One", p.scrubChapterTitle)
        assertEquals("0:30", p.positionFormatted)
        assertEquals("1:00", p.durationFormatted)
        assertEquals(0.5f, p.trackProgress, 1e-6f)
    }

    @Test fun fullPreviewAtTheEndStaysInBounds() {
        val p = applyScrubPreview(base, chapters, ScrubBar.FULL to 1f)
        assertEquals("1:40", p.playlistPositionFormatted)
        assertEquals("-0:00", p.playlistRemainingFormatted)
        assertEquals("Two", p.scrubChapterTitle)
    }

    @Test fun fullPreviewOutsideTheCurrentFileLeavesTheTrackBarAlone() {
        // A 3-file queue (300 s): the current file (100 s, no chapters) starts at 100 s; the player is 10 s in.
        val queue = PositionUi(
            trackProgress = 0.1f, positionFormatted = "0:10", durationFormatted = "1:40", remainingFormatted = "-1:30",
            playlistProgress = 110f / 300f, playlistPositionFormatted = "1:50", playlistDurationFormatted = "5:00",
            playlistRemainingFormatted = "-3:10", chapterStartMs = 0L, durationMs = 100_000L,
            totalPlaylistDurationMs = 300_000L, positionMs = 10_000L
        )
        val inside = applyScrubPreview(queue, emptyList(), ScrubBar.FULL to 0.5f)   // 150 s = 50 s into this file
        assertEquals("0:50", inside.positionFormatted)
        assertEquals(0.5f, inside.trackProgress, 1e-6f)
        val outside = applyScrubPreview(queue, emptyList(), ScrubBar.FULL to 0.9f)  // 270 s = the next file
        assertEquals("4:30", outside.playlistPositionFormatted)
        assertEquals(queue.positionFormatted, outside.positionFormatted)
        assertEquals(queue.trackProgress, outside.trackProgress, 0f)
    }
}
