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

    /** The opposite colour (channel inversion), used as a vivid fallback candidate. */
    fun complement(c: Color): Color = Color(1f - c.red, 1f - c.green, 1f - c.blue, 1f)

    /**
     * Per-file dynamic colour. [backdropLum] is the raw cover luminance behind the text;
     * [dimAlpha] is the pill's dim, so candidates are scored against the EFFECTIVE background.
     * Picks the palette swatch (or the dominant's complement) with the best contrast that clears
     * [minContrast]; otherwise falls back to black or white.
     */
    fun dynamic(
        palette: CoverArtColors?,
        backdropLum: Float,
        dimAlpha: Float,
        minContrast: Float = 3.0f
    ): Color {
        val effective = backdropLum * (1f - dimAlpha)
        if (palette == null) return blackOrWhite(effective)
        val candidates = listOfNotNull(
            palette.lightVibrant,
            palette.vibrant,
            palette.muted,
            palette.darkVibrant,
            palette.darkMuted,
            complement(palette.dominant)
        )
        val best = candidates.maxByOrNull { contrast(luminance(it), effective) }
        return if (best != null && contrast(luminance(best), effective) >= minContrast) best
        else blackOrWhite(effective)
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
