package com.powermediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc32 (E12/E13): guards must survive ViewModel recreation; a newer play
 * intent must invalidate any in-flight resume. Evidence: destination-scoped
 * ViewModels are cleared on back-stack pop (Android docs) and both
 * ghost-bug taps logged attempt=1 — instance-field guards reset.
 */
class ResumeGateTest {
    @Test
    fun newIntentInvalidatesOlderToken() {
        val t1 = ResumeGate.begin()
        val t2 = ResumeGate.begin()
        assertFalse(ResumeGate.isCurrent(t1))
        assertTrue(ResumeGate.isCurrent(t2))
        ResumeGate.end(t1)
        ResumeGate.end(t2)
    }

    @Test
    fun activeCountTracksInFlight() {
        val before = ResumeGate.activeCount()
        val t = ResumeGate.begin()
        assertEquals(before + 1, ResumeGate.activeCount())
        ResumeGate.end(t)
        assertEquals(before, ResumeGate.activeCount())
    }

    @Test
    fun endIsIdempotent() {
        val t = ResumeGate.begin()
        ResumeGate.end(t)
        ResumeGate.end(t) // double-end must not go negative
        assertTrue(ResumeGate.activeCount() >= 0)
    }
}
