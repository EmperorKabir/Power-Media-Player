package com.powermediaplayer.ui.player.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.service.PlaybackService
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Build a stacked color filter from independent toggles.
 * Returns null when all toggles are off (caller drops the layer).
 */
private fun buildColorMatrix(bw: Boolean, sepia: Boolean, invert: Boolean): ColorMatrix? {
    if (!bw && !sepia && !invert) return null
    val cm = ColorMatrix()
    if (bw) cm.setSaturation(0f)
    if (sepia) {
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        )))
    }
    if (invert) {
        cm.postConcat(ColorMatrix(floatArrayOf(
            -1f, 0f,  0f,  0f, 255f,
            0f,  -1f, 0f,  0f, 255f,
            0f,  0f,  -1f, 0f, 255f,
            0f,  0f,  0f,  1f, 0f
        )))
    }
    return cm
}

/**
 * Video surface — single TextureView path, with effects applied via
 * two layers:
 *   - Flip / rotation via Compose's [graphicsLayer] (correct pivot,
 *     no race with view measurement, no setTransform needed).
 *   - B&W / sepia / invert via the TextureView's hardware ColorFilter
 *     layer.
 *
 * Why no SurfaceView fast-path any more: switching between SurfaceView
 * and TextureView on every effect-toggle caused the player surface to
 * detach + re-attach, leaving stale frames mid-screen and the
 * setTransform pivot to fall back to (0,0) → off-frame video. A
 * single TextureView is slightly slower at 4K but eliminates the
 * entire class of path-switching bugs.
 */
@Composable
fun VideoSurface(
    isVideoContent: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier
) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val s by settingsVm.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize().background(OledBlack),
        contentAlignment = Alignment.Center
    ) {
        if (!isVideoContent) return@Box

        val aspectMod = if (videoWidth > 0 && videoHeight > 0) {
            Modifier.aspectRatio(videoWidth.toFloat() / videoHeight.toFloat())
        } else {
            Modifier.fillMaxSize()
        }

        // Compose-level transform: pivot is layout-correct because
        // graphicsLayer applies AFTER the layout pass with the
        // composable's own size. No race with view.post or
        // view.width / view.height fallbacks.
        val transformMod = Modifier.graphicsLayer(
            scaleX = if (s.videoFlipH) -1f else 1f,
            scaleY = if (s.videoFlipV) -1f else 1f,
            rotationZ = s.videoRotation.toFloat(),
            transformOrigin = TransformOrigin.Center
        )

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    runCatching { PlaybackService.getExoPlayer()?.setVideoTextureView(this) }
                }
            },
            update = { view ->
                // Re-apply ONLY the color filter on settings change.
                // Flip / rotation are now handled by graphicsLayer
                // above so we don't touch view.setTransform any more.
                val cm = buildColorMatrix(
                    bw = s.videoBw,
                    sepia = s.videoSepia,
                    invert = s.videoInvert
                )
                if (cm != null) {
                    view.setLayerType(
                        android.view.View.LAYER_TYPE_HARDWARE,
                        Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
                    )
                } else {
                    view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                }
                // No setVideoTextureView re-attach — the player was
                // bound in factory() and stays bound for the life of
                // this composable. Re-attaching on every toggle was
                // the cause of the off-frame / frozen-frame artefacts.
            },
            onRelease = { view ->
                runCatching { PlaybackService.getExoPlayer()?.clearVideoTextureView(view) }
            },
            modifier = aspectMod.then(transformMod)
        )
    }
}
