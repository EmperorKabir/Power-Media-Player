package com.powermediaplayer.ui.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * While the parent composable is in composition, increments
 * [com.powermediaplayer.ui.player.LocalOpenPopupCount] so PlayerScreen's
 * controls auto-hide LaunchedEffect won't tear OverlayContent (and the
 * popup with it) down underneath the user. Decrements on dispose.
 *
 * Call this AT THE TOP of any Composable that is conditional on a
 * popup-open boolean (e.g. inside `if (showSheet) { ... }`).
 */
@Composable
fun PopupOpenGuard() {
    val counter = com.powermediaplayer.ui.player.LocalOpenPopupCount.current
    DisposableEffect(Unit) {
        counter.intValue = counter.intValue + 1
        onDispose { counter.intValue = (counter.intValue - 1).coerceAtLeast(0) }
    }
}
