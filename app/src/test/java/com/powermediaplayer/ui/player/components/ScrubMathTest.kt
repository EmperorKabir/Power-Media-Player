package com.powermediaplayer.ui.player.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precision scrubbing: the slower the finger moves along the bar, the finer the progression. */
class ScrubMathTest {
    @Test fun fastDragFollowsTheFinger() = assertEquals(1f, ScrubMath.gain(1200f), 0f)
    @Test fun fullSpeedIsOneToOne() = assertEquals(1f, ScrubMath.gain(ScrubMath.FULL_SPEED_DP_PER_S), 0f)
    @Test fun halfSpeedHalvesTheGain() = assertEquals(0.5f, ScrubMath.gain(200f), 1e-6f)
    @Test fun crawlClampsAtMinGain() = assertEquals(ScrubMath.MIN_GAIN, ScrubMath.gain(5f), 0f)
    @Test fun directionDoesNotMatter() = assertEquals(ScrubMath.gain(300f), ScrubMath.gain(-300f), 0f)

    @Test fun gainIsMonotonicInSpeed() {
        var last = 0f
        for (v in 0..1000 step 25) {
            val g = ScrubMath.gain(v.toFloat())
            assertTrue("gain must not fall as speed rises ($v)", g >= last)
            last = g
        }
    }

    @Test fun stepAtFullGainMovesWithTheFinger() =
        assertEquals(0.6f, ScrubMath.step(0.5f, 100f, 1000f, 1f), 1e-6f)

    @Test fun stepAtQuarterGainMovesAQuarter() =
        assertEquals(0.525f, ScrubMath.step(0.5f, 100f, 1000f, 0.25f), 1e-6f)

    @Test fun stepClampsToTheBar() {
        assertEquals(1f, ScrubMath.step(0.95f, 500f, 1000f, 1f), 0f)
        assertEquals(0f, ScrubMath.step(0.02f, -500f, 1000f, 1f), 0f)
    }

    @Test fun stepIgnoresAZeroWidthTrack() = assertEquals(0.5f, ScrubMath.step(0.5f, 50f, 0f, 1f), 0f)

    @Test fun smoothingMovesTowardsTheSample() {
        val v = ScrubMath.smoothVelocity(100f, 500f)
        assertTrue(v > 100f && v < 500f)
    }

    @Test fun tapIsBelowSlop() {
        assertTrue(ScrubMath.isTap(3f, 16f))
        assertFalse(ScrubMath.isTap(40f, 16f))
    }

    @Test fun fractionAtAccountsForThumbInset() {
        assertEquals(0f, ScrubMath.fractionAt(10f, 1000f, 10f), 0f)
        assertEquals(1f, ScrubMath.fractionAt(990f, 1000f, 10f), 0f)
        assertEquals(0.5f, ScrubMath.fractionAt(500f, 1000f, 10f), 1e-6f)
        assertEquals(0f, ScrubMath.fractionAt(-50f, 1000f, 10f), 0f)
    }
}
