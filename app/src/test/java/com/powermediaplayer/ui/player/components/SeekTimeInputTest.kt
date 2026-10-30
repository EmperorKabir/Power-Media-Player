package com.powermediaplayer.ui.player.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-10-30 user video, issue 2: typing 1,4,2,6 into the jump dialog produced "14:62". After the
 * mask inserted the colon ("14" + "2" → "1:42"), the field kept the caret at its old index (3),
 * which now sat BEFORE the last digit, so the next digit landed in the middle. The dialog now
 * holds a (text, caret) pair and always places the caret at the end of the masked text. This test
 * simulates the keyboard inserting each digit at the caret, exactly as the IME does.
 */
class SeekTimeInputTest {
    private fun type(seq: String): String {
        var text = ""
        var caret = 0
        for (ch in seq) {
            val raw = text.substring(0, caret) + ch + text.substring(caret)
            val next = timeInputAfterEdit(raw)
            text = next.first
            caret = next.second
        }
        return text
    }

    @Test fun digitsFillFromTheRightInOrder() = assertEquals("14:26", type("1426"))
    @Test fun theVideoCase1374() = assertEquals("13:47", type("1347"))
    @Test fun theVideoCase1212() = assertEquals("12:12", type("1212"))
    @Test fun sixDigitsBecomeHoursMinutesSeconds() = assertEquals("12:34:56", type("123456"))
    @Test fun caretAlwaysAtTheEnd() {
        val (text, caret) = timeInputAfterEdit("1:42")
        assertEquals("1:42", text)
        assertEquals(text.length, caret)
    }
    @Test fun backspaceRemovesTheLastDigit() {
        // "14:26" with the caret at the end, backspace deletes "6" → raw "14:2" → "1:42"
        val (text, caret) = timeInputAfterEdit("14:2")
        assertEquals("1:42", text)
        assertEquals(4, caret)
    }
    @Test fun nonDigitsAreIgnored() = assertEquals("1:23", timeInputAfterEdit("1a2b3").first)
}
