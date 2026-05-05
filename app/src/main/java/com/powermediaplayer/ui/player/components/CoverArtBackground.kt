package com.powermediaplayer.ui.player.components

import android.graphics.Bitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen cover art background.
 *
 * Three rendering paths:
 *   1. [artworkBytes] non-null → decode with BitmapFactory off the main
 *      thread, render via Image. Bypasses Coil entirely (its data(ByteArray)
 *      path silently fails on Audible-style M4B JPEGs).
 *   2. [artworkUri] non-null → AsyncImage via Coil for URI/file fetching.
 *   3. neither → OLED-black fallback.
 */
@Composable
fun CoverArtBackground(
    artworkUri: Any?,
    hasCoverArt: Boolean,
    onColorsExtracted: (CoverArtColors?) -> Unit = {},
    artworkBytes: ByteArray? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Decode off-main so a 688 KB JPEG doesn't stutter the frame clock.
        val decoded by produceState<Bitmap?>(
            initialValue = null,
            key1 = artworkBytes
        ) {
            val bytes = artworkBytes
            value = if (bytes != null && bytes.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        .onFailure {
                            android.util.Log.w(
                                "PowerMediaPlayer",
                                "Cover decode failed for ${bytes.size} bytes",
                                it
                            )
                        }
                        .getOrNull()
                }.also {
                    android.util.Log.i(
                        "PowerMediaPlayer",
                        "Cover decoded: bytes=${bytes.size} bitmap=${it != null} " +
                            "size=${it?.width}x${it?.height}"
                    )
                }
            } else {
                null
            }
        }

        if (decoded != null) {
            LaunchedEffect(decoded) {
                runCatching { PaletteHelper.extractColorSet(decoded!!) }
                    .getOrNull()
                    .let(onColorsExtracted)
            }
            Image(
                bitmap = decoded!!.asImageBitmap(),
                contentDescription = "Album cover art",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        if (hasCoverArt && artworkUri != null) {
            val context = LocalContext.current
            android.util.Log.i(
                "PMP_DIAG",
                "CoverArt AsyncImage building uri=$artworkUri"
            )
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .allowHardware(false)
                    .build(),
                contentDescription = "Album cover art",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    android.util.Log.i("PMP_DIAG", "CoverArt AsyncImage onSuccess uri=$artworkUri")
                    runCatching {
                        val bm = (result.result as? SuccessResult)?.image?.toBitmap()
                        onColorsExtracted(bm?.let { PaletteHelper.extractColorSet(it) })
                    }.onFailure { onColorsExtracted(null) }
                },
                onError = { err ->
                    android.util.Log.w(
                        "PMP_DIAG",
                        "CoverArt AsyncImage onError uri=$artworkUri throwable=${err.result.throwable}"
                    )
                    onColorsExtracted(null)
                }
            )
        } else {
            android.util.Log.i(
                "PMP_DIAG",
                "CoverArt skip: hasCoverArt=$hasCoverArt artworkUri=$artworkUri"
            )
            onColorsExtracted(null)
        }
    }
}
