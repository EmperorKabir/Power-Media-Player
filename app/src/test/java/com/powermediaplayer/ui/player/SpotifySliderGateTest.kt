package com.powermediaplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifySliderGateTest {
    @Test fun realDurationEnablesAndStamps() {
        val (en, at) = SpotifySliderGate.enabled(227_893L, nowMs = 10_000L, lastRealDurationAtMs = 0L)
        assertTrue(en); assertEquals(10_000L, at)
    }

    @Test fun pollGapWithinGraceStaysEnabled() {   // the phone-logged 0.5–1.6 s dur=0 window
        val (en, at) = SpotifySliderGate.enabled(0L, nowMs = 11_600L, lastRealDurationAtMs = 10_000L)
        assertTrue(en); assertEquals(10_000L, at)
    }

    @Test fun longGapDisables() {
        val (en, _) = SpotifySliderGate.enabled(0L, nowMs = 13_100L, lastRealDurationAtMs = 10_000L)
        assertFalse(en)
    }

    @Test fun gapShowsTheLastRealPairNotZeros() {   // A2: a tap in the gap must not seek to 0
        val h = SpotifySliderGate.hold(0L, 0L, 227_893L, 127_273L, nowMs = 11_000L, lastRealDurationAtMs = 10_000L)
        assertTrue(h.enabled); assertEquals(227_893L, h.durationMs); assertEquals(127_273L, h.positionMs)
        val live = SpotifySliderGate.hold(183_973L, 1_133L, 227_893L, 127_273L, nowMs = 12_000L, lastRealDurationAtMs = 10_000L)
        assertEquals(183_973L, live.durationMs); assertEquals(1_133L, live.positionMs)
        val expired = SpotifySliderGate.hold(0L, 0L, 227_893L, 127_273L, nowMs = 14_000L, lastRealDurationAtMs = 10_000L)
        assertFalse(expired.enabled); assertEquals(0L, expired.durationMs)
    }

    @Test fun neverHadADurationIsDisabled() {
        val (en, _) = SpotifySliderGate.enabled(0L, nowMs = 5_000L, lastRealDurationAtMs = Long.MIN_VALUE / 2)
        assertFalse(en)
    }
}
