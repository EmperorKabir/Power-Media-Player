package com.powermediaplayer.lastplayed

import com.powermediaplayer.data.repository.StarPositionResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** #19 — fixed-snapshot vs follow-live resume position resolution. */
class StarPositionResolverTest {
    @Test fun fixed_usesSnapshot_ignoresLive() =
        assertEquals(10_000L, StarPositionResolver.resolve(followLive = false, snapshotMs = 10_000L, liveMs = 73_419L))

    @Test fun live_usesLive_whenPresent() =
        assertEquals(73_419L, StarPositionResolver.resolve(followLive = true, snapshotMs = 10_000L, liveMs = 73_419L))

    @Test fun live_fallsBackToSnapshot_whenNoLiveRow() =
        assertEquals(10_000L, StarPositionResolver.resolve(followLive = true, snapshotMs = 10_000L, liveMs = null))

    @Test fun explicitOverride_alwaysWins() =
        assertEquals(42L, StarPositionResolver.resolve(followLive = true, snapshotMs = 10_000L, liveMs = 73_419L, explicitMs = 42L))
}
