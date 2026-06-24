package com.powermediaplayer.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #2 — the banner ("loading metadata") must clear as soon as a snap resolves,
 * independent of the (slow, possibly-missing) LRCLib lyrics fetch. This pins the
 * invariant the bug violated: clearBanner + emitState are always true; lyrics
 * are fetched only on a track change.
 */
class SpotifyLyricsDecoupleTest {

    @Test fun bannerClearsOnSnap_regardlessOfLyrics() {
        val s = trackResolveStep(isNewTrack = true)
        assertTrue("state emits now", s.emitState)
        assertTrue("banner clears now", s.clearBanner)
        assertTrue("new track → fetch lyrics async", s.fetchLyrics)
    }

    @Test fun sameTrackSnap_noLyricsRefetch_stillClears() {
        val s = trackResolveStep(isNewTrack = false)
        assertTrue("banner still clears on a same-track snap", s.clearBanner)
        assertTrue(s.emitState)
        assertFalse("same track → no lyrics refetch", s.fetchLyrics)
    }
}
