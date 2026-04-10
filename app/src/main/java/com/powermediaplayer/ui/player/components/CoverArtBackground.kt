package com.powermediaplayer.ui.player.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.util.CoverArtColors
import com.powermediaplayer.util.PaletteHelper

/**
 * Full-screen cover art background with OLED black fallback.
 * Uses ContentScale.Crop to fill edge-to-edge without stretching.
 * Extracts Palette colors when the artwork changes.
 *
 * @param artworkUri URI of the cover art image, or null for no art.
 * @param onColorsExtracted Callback when Palette colors are extracted from art.
 */
@Composable
fun CoverArtBackground(
    artworkUri: Any?,
    hasCoverArt: Boolean,
    onColorsExtracted: (CoverArtColors?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (hasCoverArt && artworkUri != null) {
            val context = LocalContext.current

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .allowHardware(false) // Need software bitmap for Palette
                    .build(),
                contentDescription = "Album cover art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    // Extract Palette colors from the loaded bitmap
                    val drawable = result.result.image
                    // Coil 3 uses Image type; we try to get a Bitmap for Palette
                    try {
                        // Attempt to get bitmap from the platform image
                        val bitmap = (result.result.image as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            val colors = PaletteHelper.extractColorSet(bitmap)
                            onColorsExtracted(colors)
                        }
                    } catch (e: Exception) {
                        onColorsExtracted(null)
                    }
                },
                onError = {
                    onColorsExtracted(null)
                }
            )
        } else {
            // Pure OLED black — disables pixels on OLED displays
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OledBlack)
            )
            onColorsExtracted(null)
        }
    }
}
