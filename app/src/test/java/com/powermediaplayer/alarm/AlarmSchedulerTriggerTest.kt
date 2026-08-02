package com.powermediaplayer.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Locks the pure alarm next-trigger maths (audit TEST GAP). Uses a fixed `now`
 * (Wednesday 7 Jan 2026, 10:00) so the weekday/time arithmetic is deterministic.
 */
class AlarmSchedulerTriggerTest {

    private fun at(year: Int, month0: Int, day: Int, hour: Int, min: Int): Calendar =
        Calendar.getInstance().apply {
            clear(); set(year, month0, day, hour, min, 0); set(Calendar.MILLISECOND, 0)
        }

    // Wednesday 7 January 2026, 10:00:00.000
    private val nowWed10 get() = at(2026, Calendar.JANUARY, 7, 10, 0)

    private fun result(hour: Int, minute: Int, days: Int) =
        Calendar.getInstance().apply { timeInMillis = nextAlarmTriggerMs(hour, minute, days, nowWed10) }

    @Test fun oneShot_later_today_fires_today() {
        val r = result(14, 30, days = 0)
        assertEquals(7, r.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, r.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, r.get(Calendar.MINUTE))
        assertEquals(0, r.get(Calendar.SECOND))
    }

    @Test fun oneShot_earlier_today_rolls_to_tomorrow() {
        val r = result(8, 0, days = 0)
        assertEquals(8, r.get(Calendar.DAY_OF_MONTH))  // 7th 08:00 is past → 8th
        assertEquals(8, r.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun recurring_monday_only_from_wednesday_picks_next_monday() {
        val r = result(9, 0, days = 1)  // bit0 = Monday
        assertEquals(Calendar.MONDAY, r.get(Calendar.DAY_OF_WEEK))
        assertEquals(12, r.get(Calendar.DAY_OF_MONTH))  // Mon 12 Jan 2026
        assertEquals(9, r.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun recurring_daily_later_today_fires_today() {
        val r = result(14, 0, days = 127)  // all seven bits
        assertEquals(7, r.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, r.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun recurring_daily_earlier_today_fires_tomorrow() {
        val r = result(8, 0, days = 127)
        assertEquals(8, r.get(Calendar.DAY_OF_MONTH))  // today 08:00 past → tomorrow
    }

    @Test fun recurring_sunday_bit_is_bit6() {
        val r = result(7, 0, days = 64)  // bit6 = Sunday
        assertEquals(Calendar.SUNDAY, r.get(Calendar.DAY_OF_WEEK))
        assertEquals(11, r.get(Calendar.DAY_OF_MONTH))  // Sun 11 Jan 2026
    }
}
