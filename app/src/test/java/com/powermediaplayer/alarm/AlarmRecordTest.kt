package com.powermediaplayer.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the alarm-record (de)serialiser. */
class AlarmRecordTest {

    @Test
    fun roundtrip_legacy_v1_format_parses_with_defaults() {
        val legacy = "42|7|30|0|content://x|1"
        val record = AlarmRecord.deserialize(legacy)
        assertNotNull(record)
        record!!
        assertEquals(42L, record.id)
        assertEquals(7, record.hour)
        assertEquals(30, record.minute)
        assertEquals(0, record.days)
        assertEquals("content://x", record.mediaUri)
        assertTrue(record.enabled)
        // §C12 defaults
        assertEquals(10, record.startVolumePct)
        assertEquals(80, record.endVolumePct)
        assertEquals(60, record.rampSeconds)
        assertEquals(30, record.holdMinutes)
        assertEquals(60, record.windDownSeconds)
        assertTrue(record.snoozeEnabled)
        assertEquals(5, record.snoozeMinutes)
        assertEquals(5, record.maxSnoozes)
        assertEquals(0, record.skipNextCount)
        assertEquals(AlarmRecord.StopMethod.TAP, record.stopMethod)
        assertTrue(record.vibration)
    }

    @Test
    fun full_format_serialise_then_deserialise_preserves_every_field() {
        val original = AlarmRecord(
            id = 99,
            hour = 6, minute = 45,
            days = 0b0011111, // weekdays
            mediaUri = "file:///sdcard/Music/Wake.mp3",
            enabled = true,
            startVolumePct = 5,
            endVolumePct = 100,
            rampSeconds = 90,
            holdMinutes = -1,
            windDownSeconds = 0,
            snoozeEnabled = false,
            snoozeMinutes = 9,
            maxSnoozes = -1,
            snoozeRestartFromStart = true,
            skipNextCount = 3,
            stopMethod = AlarmRecord.StopMethod.MATH,
            vibration = false,
            displayLabel = "Wake-up song"
        )
        val s = original.serialize()
        val parsed = AlarmRecord.deserialize(s)
        assertEquals(original, parsed)
    }

    @Test
    fun deserialize_garbage_returns_null() {
        assertNull(AlarmRecord.deserialize(""))
        assertNull(AlarmRecord.deserialize("abc"))
        assertNull(AlarmRecord.deserialize("only|three|fields"))
    }

    @Test
    fun media_uri_with_pipe_round_trips_via_url_encoding() {
        val original = AlarmRecord(
            id = 1, hour = 8, minute = 0, days = 0,
            mediaUri = "https://example.com/path?a=1|b=2",
            enabled = true
        )
        val parsed = AlarmRecord.deserialize(original.serialize())
        assertEquals(original.mediaUri, parsed?.mediaUri)
    }

    @Test
    fun days_label_matches_day_mask_bits() {
        val r = AlarmRecord(id = 1, hour = 7, minute = 0, days = 0b0111110,
            mediaUri = "", enabled = true)
        assertEquals("Tue / Wed / Thu / Fri / Sat", r.daysLabel)
    }

    @Test
    fun time_label_zero_pads() {
        val r = AlarmRecord(id = 1, hour = 5, minute = 7, days = 0,
            mediaUri = "", enabled = true)
        assertEquals("05:07", r.timeLabel)
    }
}
