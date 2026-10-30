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

    @Test fun neverHadADurationIsDisabled() {
        val (en, _) = SpotifySliderGate.enabled(0L, nowMs = 5_000L, lastRealDurationAtMs = Long.MIN_VALUE / 2)
        assertFalse(en)
    }
}
