package com.powermediaplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [isNearEndAutoAdvance] — the Web-API fallback's auto-advance-vs-user-skip
 * discriminator (bug 2026-08-25: short tracks + last-window user skips misclassified).
 * epsilon = 4000 ms (AUTOPLAY_END_EPSILON_MS).
 */
class SpotifyAutoAdvanceTest {
    private val eps = 4000L

    @Test fun trackEnd_isAutoAdvance() =
        assertTrue(isNearEndAutoAdvance(prevPositionMs = 179_800, prevDurationMs = 180_000, epsilon = eps))

    @Test fun midTrackSkip_isNotAutoAdvance() =
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 40_000, prevDurationMs = 180_000, epsilon = eps))

    @Test fun shortTrackUnderEpsilon_neverAutoAdvance() {
        // A 2 s track: prevDur - eps is negative → old code said "always near end" → wrongly paused a skip.
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 0, prevDurationMs = 2_000, epsilon = eps))
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 1_900, prevDurationMs = 2_000, epsilon = eps))
    }

    @Test fun exactlyEpsilonDuration_notAutoAdvance() =
        // prevDur == epsilon → floored out (prevDur > epsilon is false).
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 4_000, prevDurationMs = 4_000, epsilon = eps))

    @Test fun justOverEpsilon_atEnd_isAutoAdvance() =
        assertTrue(isNearEndAutoAdvance(prevPositionMs = 4_100, prevDurationMs = 4_100, epsilon = eps))

    @Test fun justOverEpsilon_atStart_notAutoAdvance() =
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 0, prevDurationMs = 5_000, epsilon = eps))

    @Test fun zeroDuration_notAutoAdvance() =
        assertFalse(isNearEndAutoAdvance(prevPositionMs = 0, prevDurationMs = 0, epsilon = eps))
}
