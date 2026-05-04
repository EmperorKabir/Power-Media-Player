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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.service.PlaybackService
import com.powermediaplayer.ui.settings.SettingsViewModel
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Video surface — chooses between SurfaceView (default, hardware
 * overlay, smooth) and TextureView (slower but lets us apply
 * H/V flip, 90/180/270 rotation, B&W, sepia and invert colour
 * matrices). The TextureView path activates only while one of those
 * toggles is on in Settings; otherwise we use SurfaceView for the
 * perf win (4K content goes 0 → 350 ms peak frame on TextureView vs
 * ≤80 ms on SurfaceView per dumpsys gfxinfo).
 */
private fun buildColorMatrix(bw: Boolean, sepia: Boolean, invert: Boolean): ColorMatrix? {
    if (!bw && !sepia && !invert) return null
    val cm = ColorMatrix()
    if (bw) cm.setSaturation(0f)
    if (sepia) {
        // Standard sepia matrix — applied AFTER B&W if both are on
        // since sepia tones a desaturated source most pleasantly.
        val sepiaCm = ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        ))
        cm.postConcat(sepiaCm)
    }
    if (invert) {
        // Channel-wise negation: out = 1 - in for each of R/G/B.
        val invCm = ColorMatrix(floatArrayOf(
            -1f, 0f,  0f,  0f, 255f,
            0f,  -1f, 0f,  0f, 255f,
            0f,  0f,  -1f, 0f, 255f,
            0f,  0f,  0f,  1f, 0f
        ))
        cm.postConcat(invCm)
    }
    return cm
}
@Composable
fun VideoSurface(
    isVideoContent: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier
) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val s by settingsVm.uiState.collectAsStateWithLifecycle()
    val anyEffectOn = s.videoFlipH || s.videoFlipV || s.videoBw ||
        s.videoSepia || s.videoInvert || s.videoRotation != 0

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

        if (anyEffectOn) {
            // TextureView path with effects.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = true
                        runCatching { PlaybackService.getExoPlayer()?.setVideoTextureView(this) }
                    }
                },
                update = { view ->
                    // Apply the transform AND force-refresh the surface
                    // inside view.post so they run AFTER the view has
                    // been measured (otherwise width/height are 0 and
                    // the matrix's scale-around-center pivot is wrong,
                    // pushing the flipped frame off-screen → black).
                    // Also re-attach the player to the texture after
                    // each effect change so a paused frame redraws
                    // through the new transform — without this, the
                    // user sees black on first toggle while paused.
                    view.post {
                        val matrix = android.graphics.Matrix()
                        val w = view.width.toFloat().takeIf { it > 0 } ?: 1f
                        val h = view.height.toFloat().takeIf { it > 0 } ?: 1f
                        val sx = if (s.videoFlipH) -1f else 1f
                        val sy = if (s.videoFlipV) -1f else 1f
                        matrix.postScale(sx, sy, w / 2f, h / 2f)
                        if (s.videoRotation != 0) {
                            matrix.postRotate(s.videoRotation.toFloat(), w / 2f, h / 2f)
                        }
                        view.setTransform(matrix)
                        // Combine BW + sepia + invert into one color matrix.
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
                        // Force a frame refresh through the new transform
                        // by re-attaching the player. Cheap (~1 ms) and
                        // fixes the paused-flip black-screen bug.
                        runCatching {
                            PlaybackService.getExoPlayer()?.setVideoTextureView(view)
                        }
                    }
                },
                onRelease = { view ->
                    runCatching { PlaybackService.getExoPlayer()?.clearVideoTextureView(view) }
                },
                modifier = aspectMod
            )
        } else {
            // SurfaceView fast path — no effects, hardware overlay.
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        runCatching { PlaybackService.getExoPlayer()?.setVideoSurfaceView(this) }
                    }
                },
                update = { view ->
                    runCatching {
                        PlaybackService.getExoPlayer()?.setVideoSurfaceView(view)
                    }
                },
                onRelease = { view ->
                    runCatching { PlaybackService.getExoPlayer()?.clearVideoSurfaceView(view) }
                },
                modifier = aspectMod
            )
        }
    }
}
