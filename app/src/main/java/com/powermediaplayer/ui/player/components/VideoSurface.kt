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
 * H/V flip, 90/180/270 rotation, and B&W colour-matrix). The
 * TextureView path activates only while one of those toggles is on
 * in Settings; otherwise we use SurfaceView for the perf win
 * (4K content goes 0 → 350 ms peak frame on TextureView vs ≤80 ms
 * on SurfaceView per dumpsys gfxinfo).
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
    val anyEffectOn = s.videoFlipH || s.videoFlipV || s.videoBw || s.videoRotation != 0

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
                    runCatching {
                        PlaybackService.getExoPlayer()?.setVideoTextureView(view)
                    }
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
                    if (s.videoBw) {
                        val cm = ColorMatrix().apply { setSaturation(0f) }
                        view.setLayerType(
                            android.view.View.LAYER_TYPE_HARDWARE,
                            Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
                        )
                    } else {
                        view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
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
