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
}
