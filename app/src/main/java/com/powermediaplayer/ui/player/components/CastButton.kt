package com.powermediaplayer.ui.player.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Chromecast / Google Cast media-route button. Wraps the framework
 * [MediaRouteButton] so it lives in the Compose tree alongside the
 * rest of the player UI.
 *
 * Tapping the button opens the system Cast device picker; selecting a
 * device triggers [PlaybackService.switchPlayer] via the cast SDK's
 * SessionAvailabilityListener and migrates playback transparently.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).apply {
                CastButtonFactory.setUpMediaRouteButton(ctx, this)
            }
        },
        modifier = modifier
    )
}
