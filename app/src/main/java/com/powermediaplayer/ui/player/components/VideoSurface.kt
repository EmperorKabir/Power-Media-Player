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
 * Video surface using Media3 PlayerView via AndroidView.
 *
 * Key design decisions:
 * - Only rendered when BOTH player is non-null AND isVideoContent is true.
 * - player is passed as reactive state (collected from playerFlow StateFlow),
 *   so it recomposes correctly after the async MediaController connect.
 * - PlayerView.useController = false — we use our own Compose transport controls.
 * - RESIZE_MODE_FIT maintains aspect ratio; letterboxes/pillarboxes as needed.
 * - onRelease detaches cleanly WITHOUT releasing the player (lifecycle owned elsewhere).
 */
@Composable
fun VideoSurface(
    player: Player?,
    isVideoContent: Boolean,
    modifier: Modifier = Modifier
) {
    if (player == null || !isVideoContent) {
        // Black fill when video isn't ready — prevents flash of garbage
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(OledBlack)
        )
        return
    }

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
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                // Re-attach when the player reference changes (e.g. after reconnect)
                if (view.player !== player) {
                    view.player = player
                }
            },
            onRelease = { view ->
                // Detach cleanly; do NOT call player.release() — owned by PlaybackService
                view.player = null
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
