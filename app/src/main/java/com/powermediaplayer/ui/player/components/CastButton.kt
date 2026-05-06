package com.powermediaplayer.ui.player.components

import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.R as AppcompatR
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Chromecast / Google Cast media-route button. AndroidX
 * MediaRouteButton's `MediaRouterThemeHelper` constructor walks the
 * theme tree calling `ColorUtils.calculateContrast(controllerColor,
 * windowBackground)` — and Compose's AndroidView propagates the
 * `OledBlack` Compose surface as a translucent windowBackground, so
 * `calculateContrast` throws `IllegalArgumentException: background can
 * not be translucent`. Result: empty View, no Cast button.
 *
 * Fix: wrap the inflation context with a concrete AppCompat theme that
 * has a solid windowBackground. This is the standard workaround
 * documented for the known interaction between MediaRouteButton and
 * Compose-hosted views; the button visually inherits Compose's tint
 * via [Modifier] but its internal theme lookup gets a solid colour.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            val themed = ContextThemeWrapper(ctx, AppcompatR.style.Theme_AppCompat_Light_NoActionBar)
            runCatching {
                MediaRouteButton(themed).also { btn ->
                    runCatching { CastButtonFactory.setUpMediaRouteButton(themed, btn) }
                }
            }.getOrElse {
                com.powermediaplayer.util.Diag.w("PowerMediaPlayer", "CastButton init failed", it)
                View(ctx)
            }
        },
        modifier = modifier
    )
}
