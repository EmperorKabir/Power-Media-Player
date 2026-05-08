package com.powermediaplayer.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §C12 — snoozeRestartFromStart roundtrips through serialize/deserialize.
 * Earlier the field existed in the data class but never affected fire-
 * time behaviour; this test pins the persistence shape so the value
 * actually round-trips through DataStore.
 */
class AlarmRecordSnoozeTest {
    @Test fun snooze_restart_true_round_trips() {
        val r = AlarmRecord(
            id = 7, hour = 6, minute = 30, days = 0,
            mediaUri = "", enabled = true,
            snoozeRestartFromStart = true
        )
        val parsed = AlarmRecord.deserialize(r.serialize())
        assertTrue(parsed!!.snoozeRestartFromStart)
    }

    @Test fun snooze_restart_default_false() {
        val r = AlarmRecord(id = 1, hour = 7, minute = 0, days = 0,
            mediaUri = "", enabled = true)
        assertFalse(r.snoozeRestartFromStart)
    }

    @Test fun every_stop_method_round_trips() {
        AlarmRecord.StopMethod.values().forEach { sm ->
            val r = AlarmRecord(id = 1, hour = 7, minute = 0, days = 0,
                mediaUri = "", enabled = true, stopMethod = sm)
            assertEquals(sm, AlarmRecord.deserialize(r.serialize())?.stopMethod)
        }
    }

    @Test fun max_snoozes_negative_one_means_unlimited() {
        val r = AlarmRecord(id = 1, hour = 7, minute = 0, days = 0,
            mediaUri = "", enabled = true, maxSnoozes = -1)
        assertEquals(-1, AlarmRecord.deserialize(r.serialize())?.maxSnoozes)
    }
}
