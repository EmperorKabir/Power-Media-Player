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
fun CastButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    AndroidView(
        factory = { ctx ->
            // Theme_AppCompat (dark) gives the MediaRouteButton its
            // white-on-dark icon variant — Theme_AppCompat_Light would
            // pick the dark icon, which is invisible on our OLED
            // surface. Solid-windowBackground requirement of
            // MediaRouterThemeHelper is also satisfied either way.
            val themed = ContextThemeWrapper(ctx, AppcompatR.style.Theme_AppCompat_NoActionBar)
            runCatching {
                MediaRouteButton(themed).also { btn ->
                    runCatching { CastButtonFactory.setUpMediaRouteButton(themed, btn) }
                    // Force a visible minimum size — the button's
                    // default intrinsic size can collapse to 0 when
                    // hosted inside a Compose AndroidView, hiding it
                    // even when devices are present.
                    val px = (40 * ctx.resources.displayMetrics.density).toInt()
                    btn.minimumWidth = px
                    btn.minimumHeight = px
                    // Ensure the route picker considers Cast routes
                    // (otherwise the button auto-hides until something
                    // matches its selector). CastButtonFactory above
                    // already wires the Cast selector, but defensively
                    // unconditionally show so the button doesn't vanish
                    // before Cast discovery completes the first scan.
                    btn.visibility = View.VISIBLE
                }
            }.getOrElse {
                com.powermediaplayer.util.Diag.w("PowerMediaPlayer", "CastButton init failed", it)
                View(ctx)
            }
        },
        update = { v ->
            if (v is MediaRouteButton) {
                v.isEnabled = enabled
                // Reuse the DisabledGrey-tint pattern other controls
                // already follow when not actionable.
                v.alpha = if (enabled) 1.0f else 0.4f
            }
        },
        modifier = modifier
    )
}
