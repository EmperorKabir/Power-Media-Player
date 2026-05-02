package com.powermediaplayer.ui.player.components

import android.view.SurfaceView
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
 * Video surface — raw SurfaceView wired to ExoPlayer.setVideoSurfaceView.
 *
 * SurfaceView (not TextureView) is the right primitive for video. It hands
 * decoded frames to a hardware overlay plane, so the display compositor
 * scales them for free. TextureView, by contrast, uploads each frame as a
 * GPU texture and downscales per frame on the app's GL thread — for 4K
 * content on a 1080p-ish display that means a multi-hundred-MB outstanding
 * BufferQueue and visible 200–350 ms frame stalls (confirmed via
 * `dumpsys gfxinfo`: 24% janky frames, worst-case 350 ms).
 *
 * The trade-off: SurfaceView is rendered in its own window layer beneath
 * the Compose surface, which means Compose UI overlaid on top still
 * composes normally. We do NOT use any z-ordering hacks because the
 * overlay scrim/controls are siblings in the parent Box; SurfaceFlinger
 * composites the SurfaceView under them automatically.
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
