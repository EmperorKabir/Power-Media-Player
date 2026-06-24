package com.powermediaplayer.ui.player.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #4 — pure parser for the numeric seek dialog. */
class SeekTimeParserTest {
    @Test fun bareSeconds() = assertEquals(90_000L, parseTimeToMs("90"))
    @Test fun minutesSeconds() = assertEquals(90_000L, parseTimeToMs("1:30"))
    @Test fun hoursMinutesSeconds() = assertEquals(3_600_000L, parseTimeToMs("1:00:00"))
    @Test fun mixed() = assertEquals(5_025_000L, parseTimeToMs("1:23:45"))
    @Test fun secondsFieldOverflowIsNull() = assertNull(parseTimeToMs("1:60"))
    @Test fun minutesFieldOverflowIsNull() = assertNull(parseTimeToMs("1:60:00"))
    @Test fun nonNumericIsNull() = assertNull(parseTimeToMs("abc"))
    @Test fun emptyIsNull() = assertNull(parseTimeToMs(""))
    @Test fun tooManyPartsIsNull() = assertNull(parseTimeToMs("1:2:3:4"))
    @Test fun negativeIsNull() = assertNull(parseTimeToMs("-5"))
    @Test fun whitespaceTrimmed() = assertEquals(90_000L, parseTimeToMs("  1:30  "))
}
