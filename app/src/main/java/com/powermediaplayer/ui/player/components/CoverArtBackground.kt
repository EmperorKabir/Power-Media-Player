package com.powermediaplayer.ui.player.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.util.CoverArtColors
import com.powermediaplayer.util.PaletteHelper

/**
 * Full-screen cover art background with OLED black fallback.
 *
 * - When raw bytes are supplied (e.g. from `mmr.embeddedPicture`) we decode
 *   them with `BitmapFactory.decodeByteArray` and render via `Image`.
 *   Coil 3.1's data(ByteArray) path silently returned no image for the
 *   Audible-converted Harry Potter M4Bs (688 KB JPEG inside a ByteArray);
 *   manual decode is bulletproof and frees us from any Coil quirks.
 * - When only a URI is supplied (Drive thumbnail, MediaStore album art),
 *   AsyncImage handles the network/IO load.
 *
 * @param artworkUri  remote/file URI to load via Coil — used when bytes absent
 * @param artworkBytes raw image bytes (preferred when present)
 * @param hasCoverArt whether ANY artwork is expected — gates the empty fallback
 * @param onColorsExtracted Palette callback once a Bitmap is available
 */
@Composable
fun CoverArtBackground(
    artworkUri: Any?,
    hasCoverArt: Boolean,
    onColorsExtracted: (CoverArtColors?) -> Unit = {},
    artworkBytes: ByteArray? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // ── Bytes path: decode + Image (bypasses Coil) ──────────────────
        val decodedBitmap = remember(artworkBytes) {
            artworkBytes?.let { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        if (decodedBitmap != null) {
            // Palette extraction on decoded bitmap — same UX as URI path.
            LaunchedEffect(decodedBitmap) {
                runCatching { PaletteHelper.extractColorSet(decodedBitmap) }
                    .getOrNull()
                    .let(onColorsExtracted)
            }
            Image(
                bitmap = decodedBitmap.asImageBitmap(),
                contentDescription = "Album cover art",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        // ── URI path: Coil AsyncImage ──────────────────────────────────
        if (hasCoverArt && artworkUri != null) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .allowHardware(false) // need software bitmap for Palette
                    .build(),
                contentDescription = "Album cover art",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    runCatching {
                        val bm = (result.result as? SuccessResult)?.image?.toBitmap()
                        onColorsExtracted(bm?.let { PaletteHelper.extractColorSet(it) })
                    }.onFailure { onColorsExtracted(null) }
                },
                onError = { onColorsExtracted(null) }
            )
        } else {
            // OLED-black fallback (already painted by the parent Box bg)
            onColorsExtracted(null)
        }
    }
}
