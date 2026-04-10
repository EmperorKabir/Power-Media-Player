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
import com.powermediaplayer.service.PlaybackService
import com.powermediaplayer.ui.theme.OledBlack

/**
 * Video surface using Media3 PlayerView.
 *
 * ──────────────────────────────────────────────────────────────
 * CRITICAL: WHY WE USE PlaybackService.getExoPlayer() NOT playerFlow
 * ──────────────────────────────────────────────────────────────
 * The `playerFlow` / `PlaybackConnection.getPlayer()` returns a **MediaController**,
 * which is an IPC proxy over Binder to the PlaybackService running in the background.
 *
 * A MediaController CANNOT deliver decoded video frames to a Surface — that bridge
 * does not exist in the Android media stack. Video frames are produced by the codec
 * and passed directly from ExoPlayer to the attached Surface in the SAME process.
 *
 * Therefore, PlayerView MUST be attached to the literal ExoPlayer object that lives
 * inside PlaybackService. We expose that via PlaybackService.getExoPlayer() using a
 * WeakReference (safe: if the service dies the ref returns null and we show black).
 *
 * @param isVideoContent Whether the currently loaded file has a video track.
 *                       If false, nothing is rendered (caller shows album art instead).
 */
@Composable
fun VideoSurface(
    isVideoContent: Boolean,
    modifier: Modifier = Modifier
) {
    // Always start with black — prevents flicker while the service starts
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack),
        contentAlignment = Alignment.Center
    ) {
        if (isVideoContent) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        // Attach to the REAL ExoPlayer — may be null on first frame
                        player = PlaybackService.getExoPlayer()
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    // Re-query every recompose — the ExoPlayer ref is stable once the
                    // service is running, but may become available after first compose.
                    val exo = PlaybackService.getExoPlayer()
                    if (view.player !== exo) {
                        view.player = exo
                    }
                },
                onRelease = { view ->
                    // Detach without releasing — ExoPlayer lifetime is owned by the service
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
