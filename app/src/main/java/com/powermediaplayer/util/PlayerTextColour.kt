package com.powermediaplayer.util

import androidx.compose.ui.graphics.Color

/**
 * Chooses the colour of the player's title and metadata text (the pill text). Three user modes:
 *
 *  - Default: black or white, whichever reads better on the backdrop brightness. Global.
 *  - Custom: a fixed colour the user picked. Global.
 *  - Dynamic: the app chooses per file from the artwork palette, preferring a high-contrast
 *    swatch (or the dominant colour's complement) and falling back to black or white when no
 *    palette colour is legible. The user does not pick anything; it differs per file by nature.
 *
 * Pure logic so the selection is unit-tested.
 */
object PlayerTextColour {
    const val MODE_DEFAULT = 0
    const val MODE_CUSTOM = 1
    const val MODE_DYNAMIC = 2

    val nearBlack = Color(0xFF0E0E0E)
    val white = Color.White

    /** Perceptual luminance 0..1 (sRGB-weighted). */
    fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Contrast ratio (1..21) between two luminances, WCAG form. */
    fun contrast(l1: Float, l2: Float): Float {
        val hi = maxOf(l1, l2) + 0.05f
        val lo = minOf(l1, l2) + 0.05f
        return hi / lo
    }

    /** Black or white, whichever reads better on a backdrop of [effectiveLum]. */
    fun blackOrWhite(effectiveLum: Float): Color = if (effectiveLum > 0.5f) nearBlack else white

    /** Hue (0..360) and saturation (0..1) of a colour. Pure (no Android types). */
    fun hueSat(c: Color): Pair<Float, Float> {
        val r = c.red; val g = c.green; val b = c.blue
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * ((g - b) / d)
            mx == g -> 60f * ((b - r) / d) + 120f
            else -> 60f * ((r - g) / d) + 240f
        }
        val hue = ((h % 360f) + 360f) % 360f
        val sat = if (mx == 0f) 0f else d / mx
        return hue to sat
    }

    /**
     * Per-file dynamic colour. Takes the HUE from the artwork's most colourful swatch and builds a
     * vivid LIGHT version (on a dark backdrop) or DARK version (on a light backdrop), then nudges
     * its lightness until it clears [minContrast] against the effective (dimmed) backdrop. So the
     * result is always a colour drawn from the artwork AND legible, instead of falling back to
     * white whenever a raw swatch happened not to contrast. Only a greyscale cover yields black or
     * white. [backdropLum] is the raw cover luminance; [dimAlpha] is the pill's dim.
     */
    fun dynamic(
        palette: CoverArtColors?,
        backdropLum: Float,
        dimAlpha: Float,
        minContrast: Float = 3.0f
    ): Color {
        val effective = backdropLum * (1f - dimAlpha)
        if (palette == null) return blackOrWhite(effective)
        val src = palette.vibrant ?: palette.lightVibrant ?: palette.darkVibrant
            ?: palette.muted ?: palette.darkMuted ?: palette.dominant
        val (hue, sat) = hueSat(src)
        if (sat < 0.18f) return blackOrWhite(effective)   // greyscale cover, no hue to use
        val darkBackdrop = effective < 0.5f
        // Dark backdrop: a LIGHT tint of the hue (full value, lower saturation as needed for
        // contrast, but capped so it stays a visible pastel, never washing out to white).
        // Light backdrop: a DEEP tint (high saturation, lower value as needed, capped above black).
        return if (darkBackdrop) {
            val v = 1f
            var s = 0.85f
            var c = Color.hsv(hue, s, v)
            var guard = 0
            while (contrast(luminance(c), effective) < minContrast && s > 0.30f && guard < 12) {
                s = (s - 0.08f).coerceAtLeast(0.30f)
                c = Color.hsv(hue, s, v)
                guard++
            }
            c
        } else {
            val s = 0.92f
            var v = 0.55f
            var c = Color.hsv(hue, s, v)
            var guard = 0
            while (contrast(luminance(c), effective) < minContrast && v > 0.20f && guard < 12) {
                v = (v - 0.05f).coerceAtLeast(0.20f)
                c = Color.hsv(hue, s, v)
                guard++
            }
            c
        }
    }

    /** Resolve the final colour for the active mode. [backdropLum] only matters for Default/Dynamic. */
    fun resolve(
        mode: Int,
        customColour: Color?,
        palette: CoverArtColors?,
        backdropLum: Float,
        dimAlpha: Float
    ): Color = when (mode) {
        MODE_CUSTOM -> customColour ?: blackOrWhite(backdropLum * (1f - dimAlpha))
        MODE_DYNAMIC -> dynamic(palette, backdropLum, dimAlpha)
        else -> blackOrWhite(backdropLum * (1f - dimAlpha))
    }
}
