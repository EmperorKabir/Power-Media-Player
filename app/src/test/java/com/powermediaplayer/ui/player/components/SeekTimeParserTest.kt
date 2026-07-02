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

    // #4 — digit-mask that inserts colons for the numeric keypad (no colon key).
    @Test fun maskEmpty() = assertEquals("", maskTimeDigits(""))
    @Test fun maskOneDigit() = assertEquals("5", maskTimeDigits("5"))
    @Test fun maskTwoDigits() = assertEquals("90", maskTimeDigits("90"))
    @Test fun maskThreeDigits() = assertEquals("1:30", maskTimeDigits("130"))
    @Test fun maskFourDigits() = assertEquals("12:30", maskTimeDigits("1230"))
    @Test fun maskFiveDigits() = assertEquals("6:08:13", maskTimeDigits("60813"))
    @Test fun maskSixDigits() = assertEquals("12:34:56", maskTimeDigits("123456"))
    // Re-masking an already-formatted value (what onValueChange receives) is stable.
    @Test fun maskIdempotentOnFormatted() = assertEquals("6:08:13", maskTimeDigits("6:08:13"))
    // Caps at hh:mm:ss (6 digits); extra leading digits are dropped.
    @Test fun maskCapsAtSixDigits() = assertEquals("23:45:67", maskTimeDigits("1234567"))
    // The mask output feeds the parser for the common h:mm:ss case.
    @Test fun maskFeedsParser() = assertEquals(22_093_000L, parseTimeToMs(maskTimeDigits("60813")))
}
