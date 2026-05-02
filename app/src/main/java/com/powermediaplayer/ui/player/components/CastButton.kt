package com.powermediaplayer.ui.player.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Chromecast / Google Cast media-route button. Wrapped so any failure
 * during MediaRouteButton construction (Samsung One UI 7+ throws
 * "background can not be translucent" because it walks the theme tree
 * looking for a non-translucent windowBackground) degrades to an
 * invisible empty View instead of crashing the player.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            runCatching {
                MediaRouteButton(ctx).also { btn ->
                    runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, btn) }
                }
            }.getOrElse {
                android.util.Log.w("PowerMediaPlayer", "CastButton init failed", it)
                View(ctx)  // empty stand-in — preserves layout, no crash
            }
        },
        modifier = modifier
    )
}
