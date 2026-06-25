package com.powermediaplayer.service

/**
 * #18 — warm-reopen session surfacing.
 *
 * On every (re)open the Player tab + mini-bar must reflect the SERVICE
 * truth: surface the live/last session when the service holds one, and
 * show a clean empty state when it does not — never a synthesised ghost
 * of the previously-playing track.
 *
 * The service truth always wins over whatever the UI happens to be
 * showing, so:
 *  - service has a session, UI came up empty (warm reopen) → surface it;
 *  - service cleared its queue, UI still shows the old track (swipe-stop
 *    ghost) → drop to a clean empty state.
 *
 * Pure + side-effect-free so it carries a JVM unit test ([SessionSurfaceTest]).
 * The decision depends only on the service's session presence; the UI
 * argument documents intent and lets the test express both transitions.
 */
object SessionSurface {
    fun shouldSurfaceSession(serviceHasSession: Boolean, uiHasSession: Boolean): Boolean =
        serviceHasSession
}
