package com.powermediaplayer.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc32 (E3): the loading banner must NOT clear on a null /v1/me/player
 * snap while a user-initiated handoff is still inside its grace window —
 * Spotify legitimately reports "no active device" for many seconds while
 * the target device wakes (logcat 2026-06-04: 32 s gap, banner died ~1 s in).
 */
class SpotifyBannerGraceTest {
    @Test
    fun nullSnap_insideGrace_keepsBanner() {
        assertFalse(shouldClearBannerOnNullSnap(nowMs = 10_000L, graceUntilMs = 45_000L))
    }

    @Test
    fun nullSnap_afterGrace_clearsBanner() {
        assertTrue(shouldClearBannerOnNullSnap(nowMs = 46_000L, graceUntilMs = 45_000L))
    }

    @Test
    fun nullSnap_noHandoff_clearsImmediately() {
        assertTrue(shouldClearBannerOnNullSnap(nowMs = 1L, graceUntilMs = 0L))
    }

    /** vc32 (E14): Spotify's /me/player lagged PUT /play by a measured
     *  11 s, reporting the OLD track — the overlay must hold until the
     *  REQUESTED track is reported (grace expiry as failsafe). */
    @Test
    fun staleSnapSuppressedUntilRequestedTrackArrives() {
        assertFalse(shouldEmitSnap("spotify:track:OLD", "spotify:track:NEW", 1_000L, 45_000L))
        assertTrue(shouldEmitSnap("spotify:track:NEW", "spotify:track:NEW", 2_000L, 45_000L))
        assertTrue(shouldEmitSnap("spotify:track:OLD", "spotify:track:NEW", 50_000L, 45_000L)) // grace expired failsafe
        assertTrue(shouldEmitSnap("spotify:track:OLD", null, 0L, 0L)) // no expectation
    }
}
