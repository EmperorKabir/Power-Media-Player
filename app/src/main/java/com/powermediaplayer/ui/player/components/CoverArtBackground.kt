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
import kotlinx.coroutines.launch
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
    val paletteScope = rememberCoroutineScope()
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
            // Clear the previous track's bitmap IMMEDIATELY on a track
            // change. produceState otherwise keeps the last `value` until
            // the new decode finishes, so switching away from an
            // embedded-art track left the OLD cover on screen (the
            // "album art stays when switching tracks" bug) — and because
            // the decoded-bitmap path is checked before the URI path +
            // returns early, the new track's URI art never got a chance.
            value = null
            val bytes = artworkBytes
            value = if (bytes != null && bytes.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        .onFailure {
                            com.powermediaplayer.util.Diag.w(
                                "PowerMediaPlayer",
                                "Cover decode failed for ${bytes.size} bytes",
                                it
                            )
                        }
                        .getOrNull()
                }.also {
                    com.powermediaplayer.util.Diag.i(
                        "PowerMediaPlayer",
                        "Cover decoded: bytes=${bytes.size} bitmap=${it != null} " +
                            "size=${it?.width}x${it?.height}"
                    )
                    // Item 1 (2026-07-09): Diag.* is stripped from release builds,
                    // which left the art path unobservable in the field. DiagLog
                    // writes to the opt-in diagnostic FILE in every build type.
                    com.powermediaplayer.diag.DiagLog.event(
                        "COVER",
                        "decode bytes=${bytes.size} bitmap=${it != null} size=${it?.width}x${it?.height}"
                    )
                }
            } else {
                com.powermediaplayer.diag.DiagLog.event(
                    "COVER",
                    "no embedded bytes; uri=${artworkUri != null} hasCoverArt flag drives URI path"
                )
                null
            }
        }

        // Embedded-bytes path. The stale-carry-over problem (Media3's sticky
        // player.mediaMetadata.artworkData bleeding an audiobook cover onto
        // later tracks) is now fixed at the source in PlaybackConnection —
        // [artworkBytes] only ever carries the CURRENT track's genuine art —
        // so whenever bytes decode, they belong to this track and win.
        if (decoded != null) {
            LaunchedEffect(decoded) {
                // Palette quantisation off Main (audit 4.3); snapshot
                // state writes are thread-safe so the callback can fire
                // from Default.
                val colors = withContext(Dispatchers.Default) {
                    runCatching { PaletteHelper.extractColorSet(decoded!!) }.getOrNull()
                }
                onColorsExtracted(colors)
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
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "CoverArt AsyncImage building uri=$artworkUri"
            )
            // Bug fix (stale album art): MediaStore can report a non-null
            // albumart URI whose FILE IS MISSING (dangling) — so hasCoverArt
            // is true but the load fails with FileNotFoundException. Coil's
            // AsyncImage keeps the PREVIOUS track's painter on error, so the
            // old cover survives onto a track that has none. key(artworkUri)
            // forces a FRESH AsyncImage per URI, so a failed/dangling load
            // has no prior painter to inherit and the OLED-black background
            // shows through instead of stale art. `error`/`fallback` paint
            // that background explicitly as a belt-and-braces clear.
            androidx.compose.runtime.key(artworkUri) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .allowHardware(false)
                    .build(),
                error = androidx.compose.ui.graphics.painter.ColorPainter(OledBlack),
                fallback = androidx.compose.ui.graphics.painter.ColorPainter(OledBlack),
                contentDescription = "Album cover art",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    com.powermediaplayer.util.Diag.i("PMP_DIAG", "CoverArt AsyncImage onSuccess uri=$artworkUri")
                    com.powermediaplayer.diag.DiagLog.event("COVER", "AsyncImage success uri=${com.powermediaplayer.diag.DiagLog.hash(artworkUri.toString())}")
                    val bm = runCatching {
                        (result.result as? SuccessResult)?.image?.toBitmap()
                    }.getOrNull()
                    if (bm == null) {
                        onColorsExtracted(null)
                    } else {
                        // Coil calls back on Main — quantise off it (audit 4.3).
                        paletteScope.launch(Dispatchers.Default) {
                            onColorsExtracted(
                                runCatching { PaletteHelper.extractColorSet(bm) }.getOrNull()
                            )
                        }
                    }
                },
                onError = { err ->
                    com.powermediaplayer.util.Diag.w(
                        "PMP_DIAG",
                        "CoverArt AsyncImage onError uri=$artworkUri throwable=${err.result.throwable}"
                    )
                    onColorsExtracted(null)
                }
            )
            }
        } else {
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "CoverArt skip: hasCoverArt=$hasCoverArt artworkUri=$artworkUri"
            )
            onColorsExtracted(null)
        }
    }
}
