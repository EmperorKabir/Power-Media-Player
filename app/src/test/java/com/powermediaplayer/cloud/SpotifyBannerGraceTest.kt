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

    /** The handoff hides only the OUTGOING track (Spotify's /me/player lags
     *  PUT /play ~11 s, reporting the previous song). ANY other track — the
     *  requested one OR a shuffled/skipped one — emits immediately, so
     *  navigation/shuffle metadata is never frozen (the prior "==expected" gate
     *  froze a shuffled track for the whole grace = "very slow metadata"). */
    @Test
    fun outgoingTrackSuppressedButAnyOtherEmitsImmediately() {
        // OLD = the outgoing track being left → suppressed during grace.
        assertFalse(shouldEmitSnap("spotify:track:OLD", "spotify:track:OLD", 1_000L, 45_000L))
        // the requested track lands → emit immediately.
        assertTrue(shouldEmitSnap("spotify:track:NEW", "spotify:track:OLD", 2_000L, 45_000L))
        // a shuffled/skipped track differing from the outgoing one → emit NOW
        // (this is the slow-metadata fix).
        assertTrue(shouldEmitSnap("spotify:track:SHUFFLED", "spotify:track:OLD", 1_500L, 45_000L))
        // grace-expiry failsafe — even the outgoing track emits after grace.
        assertTrue(shouldEmitSnap("spotify:track:OLD", "spotify:track:OLD", 50_000L, 45_000L))
        // nothing to suppress → emit.
        assertTrue(shouldEmitSnap("spotify:track:OLD", null, 0L, 0L))
    }
}
