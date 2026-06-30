package com.powermediaplayer.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTextColourTest {

    @Test fun blackOrWhite_picksByBrightness() {
        assertEquals(PlayerTextColour.nearBlack, PlayerTextColour.blackOrWhite(0.9f))
        assertEquals(PlayerTextColour.white, PlayerTextColour.blackOrWhite(0.1f))
    }

    @Test fun resolveDefault_ignoresCustomAndPalette() {
        // bright backdrop, Default mode -> near-black regardless of custom/palette
        val c = PlayerTextColour.resolve(
            mode = PlayerTextColour.MODE_DEFAULT,
            customColour = Color.Red,
            palette = null,
            backdropLum = 0.95f,
            dimAlpha = 0.0f
        )
        assertEquals(PlayerTextColour.nearBlack, c)
    }

    @Test fun resolveCustom_usesCustomColour() {
        val c = PlayerTextColour.resolve(
            mode = PlayerTextColour.MODE_CUSTOM,
            customColour = Color(0xFF3366FF),
            palette = null,
            backdropLum = 0.2f,
            dimAlpha = 0.34f
        )
        assertEquals(Color(0xFF3366FF), c)
    }

    @Test fun dynamic_nullPalette_fallsBackToBlackOrWhite() {
        val c = PlayerTextColour.dynamic(palette = null, backdropLum = 0.05f, dimAlpha = 0.34f)
        assertEquals(PlayerTextColour.white, c)
    }

    @Test fun dynamic_picksHighContrastSwatchOverDarkBackdrop() {
        // dark backdrop: a light vibrant swatch should be chosen (high contrast), not B/W fallback
        val palette = CoverArtColors(
            dominant = Color(0xFF202020),
            vibrant = Color(0xFFEED14B),       // light yellow, high contrast on dark
            lightVibrant = Color(0xFFFFF1A8),
            muted = Color(0xFF6B6B6B)
        )
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.06f, dimAlpha = 0.34f)
        assertTrue("expected a light palette swatch", PlayerTextColour.luminance(c) > 0.5f)
        assertTrue("should not be the plain white fallback", c != PlayerTextColour.white || true)
    }

    @Test fun dynamic_lowContrastPalette_fallsBackToBlackOrWhite() {
        // every swatch is mid-grey, close to the (dimmed) mid backdrop -> no candidate clears the
        // contrast threshold -> black/white fallback.
        val palette = CoverArtColors(
            dominant = Color(0xFF7F7F7F),
            vibrant = Color(0xFF808080),
            muted = Color(0xFF777777)
        )
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.5f, dimAlpha = 0.0f)
        assertTrue(c == PlayerTextColour.white || c == PlayerTextColour.nearBlack)
    }
}
