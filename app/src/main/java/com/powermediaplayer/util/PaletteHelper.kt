package com.powermediaplayer.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

/**
 * Helper for extracting colors from cover art bitmaps using the Palette API.
 * Used for dynamic status bar, notification, and UI theming.
 */
object PaletteHelper {

    /**
     * Extract the dominant vibrant color from a bitmap.
     * Falls back to muted, then dominant swatch.
     */
    fun extractDominantColor(bitmap: Bitmap): Color {
        val palette = Palette.from(bitmap).generate()

        val color = palette.getVibrantColor(
            palette.getMutedColor(
                palette.getDominantColor(0xFF009688.toInt())
            )
        )

        return Color(color)
    }

    /**
     * Extract a dark vibrant color suitable for status bar.
     */
    fun extractStatusBarColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).generate()

        return palette.getDarkVibrantColor(
            palette.getDarkMutedColor(
                0xFF000000.toInt()
            )
        )
    }

    /**
     * Extract a full color set from cover art for dynamic theming.
     * One generate() serves every field — the status-bar colour used to
     * run a SECOND full quantisation pass over the same bitmap
     * (audit 4.3: tens of ms ×2 per track change, on Main).
     */
    fun extractColorSet(bitmap: Bitmap): CoverArtColors {
        val palette = Palette.from(bitmap).generate()

        return CoverArtColors(
            dominant = Color(palette.getDominantColor(0xFF009688.toInt())),
            vibrant = palette.vibrantSwatch?.let { Color(it.rgb) },
            darkVibrant = palette.darkVibrantSwatch?.let { Color(it.rgb) },
            lightVibrant = palette.lightVibrantSwatch?.let { Color(it.rgb) },
            muted = palette.mutedSwatch?.let { Color(it.rgb) },
            darkMuted = palette.darkMutedSwatch?.let { Color(it.rgb) },
            statusBarColor = palette.getDarkVibrantColor(
                palette.getDarkMutedColor(0xFF000000.toInt())
            )
        )
    }
}

/**
 * Color set extracted from cover art for dynamic UI theming.
 */
data class CoverArtColors(
    val dominant: Color,
    val vibrant: Color? = null,
    val darkVibrant: Color? = null,
    val lightVibrant: Color? = null,
    val muted: Color? = null,
    val darkMuted: Color? = null,
    val statusBarColor: Int = 0xFF000000.toInt()
)
