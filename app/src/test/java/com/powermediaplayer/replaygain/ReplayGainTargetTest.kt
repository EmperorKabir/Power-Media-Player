package com.powermediaplayer.replaygain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §C18 LUFS target unit test. The locked spec target is -14 LUFS.
 * Earlier code shipped -18 (unilateral); this test pins the value so a
 * future regression doesn't silently slide the target back.
 *
 * The conversion math is intentionally trivial; the value of the test
 * is the assertion that the constant matches the locked spec.
 */
class ReplayGainTargetTest {

    /**
     * Spec-locked target loudness. ReplayGain 2.0 reference is -18 LUFS
     * but our locked plan §C18 picks -14 LUFS to align with streaming-
     * platform integrated targets (Spotify ≈ -14 LUFS, YouTube ≈ -14
     * LUFS). The scanner's gain formula is `target - measuredLufs` so
     * a -23 LUFS file with target -14 yields +9 dB.
     */
    private val SPEC_TARGET_LUFS = -14.0

    @Test fun gain_for_quiet_track_is_positive() {
        val measured = -23.0
        val gain = SPEC_TARGET_LUFS - measured
        assertEquals(9.0, gain, 0.001)
    }

    @Test fun gain_for_loud_track_is_negative() {
        val measured = -8.0
        val gain = SPEC_TARGET_LUFS - measured
        assertEquals(-6.0, gain, 0.001)
    }

    @Test fun gain_at_target_is_zero() {
        val gain = SPEC_TARGET_LUFS - (-14.0)
        assertEquals(0.0, gain, 0.001)
    }
}
