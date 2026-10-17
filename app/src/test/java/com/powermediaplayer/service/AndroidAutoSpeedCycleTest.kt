package com.powermediaplayer.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for the Android Auto speed-cycle button ([nextSpeedPreset]). */
class AndroidAutoSpeedCycleTest {

    @Test fun steps_through_the_cycle_in_order() {
        assertEquals(1.25f, nextSpeedPreset(1.0f))
        assertEquals(1.5f, nextSpeedPreset(1.25f))
        assertEquals(1.75f, nextSpeedPreset(1.5f))
        assertEquals(2.0f, nextSpeedPreset(1.75f))
    }

    @Test fun wraps_back_to_start_after_the_top() {
        assertEquals(1.0f, nextSpeedPreset(2.0f))
        assertEquals(1.0f, nextSpeedPreset(2.5f))   // above the cap → wrap
        assertEquals(1.0f, nextSpeedPreset(3.0f))
    }

    @Test fun below_1x_jumps_to_1x() {
        assertEquals(1.0f, nextSpeedPreset(0.5f))
        assertEquals(1.0f, nextSpeedPreset(0.75f))
    }

    @Test fun an_off_cycle_value_rounds_up_to_the_next_preset() {
        // 1.3× (a phone-only preset) → next cycle step above it is 1.5×.
        assertEquals(1.5f, nextSpeedPreset(1.3f))
        assertEquals(1.75f, nextSpeedPreset(1.6f))
    }

    @Test fun epsilon_prevents_a_float_from_sticking_on_itself() {
        // 1.4999 is effectively 1.5 — must advance to 1.75, not back to 1.5.
        assertEquals(1.75f, nextSpeedPreset(1.4999f))
    }
}
