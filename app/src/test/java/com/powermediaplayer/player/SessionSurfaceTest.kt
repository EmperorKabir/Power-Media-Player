package com.powermediaplayer.player

import com.powermediaplayer.service.SessionSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #18 — the warm-reopen surfacing decision: the service session is the
 * source of truth on every reopen, regardless of the UI's current state.
 */
class SessionSurfaceTest {

    @Test fun warmReopen_serviceHasSession_uiEmpty_surfaces() {
        // Service holds a live/last session, the UI came up empty on a warm
        // reopen → surface it (Player tab + mini-bar reflect the session).
        assertTrue(
            SessionSurface.shouldSurfaceSession(serviceHasSession = true, uiHasSession = false)
        )
    }

    @Test fun ongoing_bothPresent_keepsSurfacing() {
        assertTrue(
            SessionSurface.shouldSurfaceSession(serviceHasSession = true, uiHasSession = true)
        )
    }

    @Test fun swipeStop_serviceEmpty_uiGhost_dropsToEmpty() {
        // Service cleared its queue but the UI still shows the previous
        // track → service truth wins, drop to a clean empty state.
        assertFalse(
            SessionSurface.shouldSurfaceSession(serviceHasSession = false, uiHasSession = true)
        )
    }

    @Test fun bothEmpty_staysEmpty() {
        assertFalse(
            SessionSurface.shouldSurfaceSession(serviceHasSession = false, uiHasSession = false)
        )
    }
}
