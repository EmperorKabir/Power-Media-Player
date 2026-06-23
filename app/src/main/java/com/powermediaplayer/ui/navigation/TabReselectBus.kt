package com.powermediaplayer.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fires when a bottom-nav tab is tapped while it is ALREADY the selected tab.
 * Screens collect this to reset to their top level (e.g. Cloud → provider
 * picker) — the standard "tap the active tab again to go home" behaviour.
 */
object TabReselectBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun reselected(route: String) { _events.tryEmit(route) }
}
