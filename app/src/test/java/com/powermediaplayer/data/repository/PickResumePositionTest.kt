package com.powermediaplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PickResumePositionTest {
    private val dur = 3_808_000L

    @Test fun no_rows_resumes_from_zero() = assertEquals(0L, pickResumePosition(emptyList()))

    @Test fun newest_row_wins_normally() =
        assertEquals(1_200_000L, pickResumePosition(listOf(1_200_000L to dur, 3_752_470L to dur)))

    @Test fun accidental_restart_row_does_not_lose_the_deeper_listen() =
        assertEquals(3_752_470L, pickResumePosition(listOf(0L to dur, 3_752_470L to dur)))

    @Test fun a_small_newest_position_inside_the_first_minute_still_recovers_the_listen() =
        assertEquals(3_752_470L, pickResumePosition(listOf(10_000L to dur, 3_752_470L to dur)))

    @Test fun an_intentional_restart_past_the_first_minute_is_kept() =
        assertEquals(90_000L, pickResumePosition(listOf(90_000L to dur, 3_752_470L to dur)))

    @Test fun finished_rows_never_resume() =
        assertEquals(0L, pickResumePosition(listOf(dur - 2_000L to dur, 0L to dur)))

    @Test fun unknown_duration_rows_resume_at_their_position() =
        assertEquals(500_000L, pickResumePosition(listOf(500_000L to 0L)))
}
