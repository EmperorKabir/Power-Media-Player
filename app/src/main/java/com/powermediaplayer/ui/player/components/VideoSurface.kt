package com.powermediaplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Video surface that renders the ExoPlayer video output using Media3 PlayerView.
 *
 * IMPORTANT: PlayerView must be created via AndroidView because it is a View-based
 * component — there is no native Compose equivalent for hardware-accelerated video
 * rendering in Media3 as of the current API version.
 *
 * @param player The ExoPlayer/MediaController instance to attach. Null if not yet connected.
 * @param isVideoContent True when the current media item has a video track.
 */
@Composable
fun VideoSurface(
    player: Player?,
    isVideoContent: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isVideoContent || player == null) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    // Hide the built-in controls — we use our own Compose controls
                    useController = false
                    // Maintain aspect ratio (letterbox/pillarbox as needed)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                // Re-attach player on recomposition (e.g. after config change)
                if (view.player != player) {
                    view.player = player
                }
            },
            onRelease = { view ->
                // Detach cleanly but do NOT release the player — lifecycle managed elsewhere
                view.player = null
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
