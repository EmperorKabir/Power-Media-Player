package com.powermediaplayer.ui.player.components

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.powermediaplayer.R
import com.powermediaplayer.service.PlaybackService
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Video surface backed by Media3 PlayerView inflated from XML so
 * surface_type=texture_view is applied. TextureView composites into the
 * Compose draw tree (unlike SurfaceView which punches a window hole and
 * is occluded by overlying Compose scrims), so the video picture is
 * visible under transparent scrims and aspect ratio is preserved by
 * AspectRatioFrameLayout.
 *
 * Attached to PlaybackService.getExoPlayer() (real in-process player) —
 * MediaController IPC proxies cannot deliver decoded frames to a Surface.
 */
@Composable
fun VideoSurface(
    isVideoContent: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().background(OledBlack),
        contentAlignment = Alignment.Center
    ) {
        if (!isVideoContent) return@Box

        AndroidView(
            factory = { context ->
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.exo_player_texture, null) as PlayerView
                view.player = PlaybackService.getExoPlayer()
                view
            },
            update = { view ->
                val exo = PlaybackService.getExoPlayer()
                if (view.player !== exo) view.player = exo
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )
    }
}
