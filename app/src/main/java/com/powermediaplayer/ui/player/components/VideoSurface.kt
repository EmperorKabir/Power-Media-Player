package com.powermediaplayer.ui.player.components

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.powermediaplayer.service.PlaybackService
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Video surface — raw TextureView wired directly to ExoPlayer.setVideoTextureView.
 *
 * Why not Media3 PlayerView? PlayerView's surface management has subtle quirks
 * with Compose interop (the inflated child layout doesn't always reach
 * fillMaxSize, and surface_type=texture_view requires a specific XML setup).
 * A bare TextureView is the simplest possible path: ExoPlayer pushes decoded
 * frames straight to it, and Compose lays it out predictably.
 *
 * @param videoWidth source video width (0 → unknown, falls back to fillMaxSize)
 * @param videoHeight source video height
 */
@Composable
fun VideoSurface(
    isVideoContent: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier
) {
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

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    PlaybackService.getExoPlayer()?.setVideoTextureView(this)
                }
            },
            update = { view ->
                val exo = PlaybackService.getExoPlayer() ?: return@AndroidView
                // Re-bind every recompose — cheap, idempotent, and recovers from
                // the case where the service connected after first compose.
                exo.setVideoTextureView(view)
            },
            onRelease = { view ->
                runCatching { PlaybackService.getExoPlayer()?.clearVideoTextureView(view) }
            },
            modifier = aspectMod
        )
    }
}
