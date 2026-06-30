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

    @Test fun dynamic_darkBackdrop_givesLightSaturatedColour() {
        val palette = CoverArtColors(dominant = Color(0xFF202020), vibrant = Color(0xFFE0B020)) // amber
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.06f, dimAlpha = 0.34f)
        assertTrue("expected a LIGHT result on a dark backdrop", PlayerTextColour.luminance(c) > 0.5f)
        assertTrue("expected a COLOURED (saturated) result, not white", PlayerTextColour.hueSat(c).second > 0.1f)
    }

    @Test fun dynamic_lightBackdrop_givesDarkSaturatedColour() {
        val palette = CoverArtColors(dominant = Color(0xFFEEEEEE), vibrant = Color(0xFF2060D0)) // blue
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.95f, dimAlpha = 0.20f)
        assertTrue("expected a DARK result on a light backdrop", PlayerTextColour.luminance(c) < 0.5f)
        assertTrue("expected a COLOURED (saturated) result, not black", PlayerTextColour.hueSat(c).second > 0.1f)
    }

    @Test fun dynamic_midBackdrop_stillColoured_notWhite() {
        // The regression: a vibrant palette on a MID backdrop used to fall back to white. Now it
        // keeps the hue and adjusts lightness, so it stays coloured.
        val palette = CoverArtColors(dominant = Color(0xFF606060), vibrant = Color(0xFFC03030)) // red
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.45f, dimAlpha = 0.34f)
        assertTrue("should not collapse to white/black on a vibrant cover",
            PlayerTextColour.hueSat(c).second > 0.1f)
    }

    @Test fun dynamic_greyscaleCover_fallsBackToBlackOrWhite() {
        val palette = CoverArtColors(dominant = Color(0xFF7F7F7F), vibrant = Color(0xFF808080))
        val c = PlayerTextColour.dynamic(palette, backdropLum = 0.5f, dimAlpha = 0.0f)
        assertTrue(c == PlayerTextColour.white || c == PlayerTextColour.nearBlack)
    }
}
