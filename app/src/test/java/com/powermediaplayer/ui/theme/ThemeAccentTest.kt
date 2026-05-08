package com.powermediaplayer.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeAccentTest {
    @Test fun parses_hex6_with_hash() {
        val c = parseHexColor("#FF6B6B")
        assertNotNull(c)
        assertEquals(0xFFFF6B6B.toInt(), c!!.toArgb())
    }

    @Test fun parses_hex6_without_hash() {
        val c = parseHexColor("FF6B6B")
        assertNotNull(c)
        assertEquals(0xFFFF6B6B.toInt(), c!!.toArgb())
    }

    @Test fun parses_hex8_with_alpha() {
        val c = parseHexColor("#80FF6B6B")
        assertNotNull(c)
        assertEquals(0x80FF6B6B.toInt(), c!!.toArgb())
    }

    @Test fun rejects_garbage_returns_null() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("not-a-color"))
        assertNull(parseHexColor("#XYZ"))
        assertNull(parseHexColor("#12345"))   // odd length
    }

    private fun Color.toArgb(): Int =
        (alpha * 255 + 0.5f).toInt().shl(24) or
            (red * 255 + 0.5f).toInt().shl(16) or
            (green * 255 + 0.5f).toInt().shl(8) or
            (blue * 255 + 0.5f).toInt()
}
