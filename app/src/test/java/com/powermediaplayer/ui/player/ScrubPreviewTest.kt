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
    private val base = RawPosition(
        trackProgress = 0.25f,                 // 10 s into the 40 s chapter
        positionMs = 10_000L, durationMs = 40_000L,
        playlistProgress = 0.7f,               // 70 s of 100 s
        playlistPositionMs = 70_000L, playlistDurationMs = 100_000L,
        chapterStartMs = 60_000L
    )

    @Test fun noPreviewReturnsTheSameObject() = assertSame(base, applyScrubPreview(base, chapters, null))

    @Test fun baseFormatsEveryLabelOnce() {
        val p = base.format()
        assertEquals("0:10", p.positionFormatted); assertEquals("0:40", p.durationFormatted)
        assertEquals("-0:30", p.remainingFormatted); assertEquals("1:10", p.playlistPositionFormatted)
        assertEquals("1:40", p.playlistDurationFormatted); assertEquals("-0:30", p.playlistRemainingFormatted)
        assertEquals(10_000L, p.positionMs); assertEquals(100_000L, p.totalPlaylistDurationMs)
    }

    @Test fun trackPreviewMovesBothBarsLabelsButKeepsTheLivePositionForLyrics() {
        val p = applyScrubPreview(base, chapters, ScrubBar.TRACK to 0.5f).format()   // 20 s into chapter 2 = 80 s absolute
        assertEquals("0:20", p.positionFormatted)
        assertEquals("-0:20", p.remainingFormatted)
        assertEquals(0.5f, p.trackProgress, 0f)
        assertEquals("1:20", p.playlistPositionFormatted)
        assertEquals("-0:20", p.playlistRemainingFormatted)
        assertEquals(0.8f, p.playlistProgress, 1e-6f)
        assertTrue(p.scrubbing)
        assertNull(p.scrubChapterTitle)      // same chapter
        assertEquals(10_000L, p.positionMs)  // AU3 #2: the synced-lyrics position stays LIVE
    }

    @Test fun fullPreviewLandingInAnotherChapterPreviewsThatChapter() {
        val p = applyScrubPreview(base, chapters, ScrubBar.FULL to 0.3f).format()    // 30 s absolute = chapter One, 30 s in
        assertEquals("0:30", p.playlistPositionFormatted)
        assertEquals("One", p.scrubChapterTitle)
        assertEquals("0:30", p.positionFormatted)
        assertEquals("1:00", p.durationFormatted)
        assertEquals("-0:30", p.remainingFormatted)
        assertEquals(0.5f, p.trackProgress, 1e-6f)
    }

    @Test fun fullPreviewAtTheEndStaysInBounds() {
        val p = applyScrubPreview(base, chapters, ScrubBar.FULL to 1f).format()
        assertEquals("1:40", p.playlistPositionFormatted)
        assertEquals("-0:00", p.playlistRemainingFormatted)
        assertEquals("Two", p.scrubChapterTitle)
    }

    @Test fun fullPreviewOutsideTheCurrentFileLeavesTheTrackBarAlone() {
        // A 3-file queue (300 s): the current file (100 s, no chapters) starts at 100 s; the player is 10 s in.
        val queue = RawPosition(
            trackProgress = 0.1f, positionMs = 10_000L, durationMs = 100_000L,
            playlistProgress = 110f / 300f, playlistPositionMs = 110_000L, playlistDurationMs = 300_000L,
            chapterStartMs = 0L
        )
        val inside = applyScrubPreview(queue, emptyList(), ScrubBar.FULL to 0.5f).format()   // 150 s = 50 s into this file
        assertEquals("0:50", inside.positionFormatted)
        assertEquals(0.5f, inside.trackProgress, 1e-6f)
        val outside = applyScrubPreview(queue, emptyList(), ScrubBar.FULL to 0.9f).format()  // 270 s = the next file
        assertEquals("4:30", outside.playlistPositionFormatted)
        assertEquals("0:10", outside.positionFormatted)
        assertEquals(0.1f, outside.trackProgress, 0f)
    }

    /** FOLDER-CH: file #2 of a folder book, 10 s in — the bar must show 0:10, not 0:00. */
    @Test fun folderBookChapterPositionUsesThePlaylistPosition() {
        // embedded chapters: file-relative
        assertEquals(10_000L, chapterRelativePosition(false, filePositionMs = 70_000L, playlistPositionMs = 70_000L, chapterStartMs = 60_000L, chapterDurationMs = 40_000L))
        // folder book: file #2 starts at 60 s absolute; the player is 10 s into that file
        assertEquals(10_000L, chapterRelativePosition(true, filePositionMs = 10_000L, playlistPositionMs = 70_000L, chapterStartMs = 60_000L, chapterDurationMs = 40_000L))
        // the old maths (file position minus absolute start) is what pinned it at 0
        assertEquals(0L, chapterRelativePosition(false, filePositionMs = 10_000L, playlistPositionMs = 70_000L, chapterStartMs = 60_000L, chapterDurationMs = 40_000L))
    }

    @Test fun spotifyShapedBasePreviewsTheSingleTrack() {
        // Spotify: one track, no chapters, playlist == track (what computeRawPosition builds).
        val spot = RawPosition(
            trackProgress = 0.2f, positionMs = 40_000L, durationMs = 200_000L,
            playlistProgress = 0.2f, playlistPositionMs = 40_000L, playlistDurationMs = 200_000L,
            chapterStartMs = 0L
        )
        val p = applyScrubPreview(spot, emptyList(), ScrubBar.TRACK to 0.75f).format()
        assertEquals("2:30", p.positionFormatted)
        assertEquals("2:30", p.playlistPositionFormatted)
        assertEquals(0.75f, p.playlistProgress, 1e-6f)
    }
}
