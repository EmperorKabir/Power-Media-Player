package com.powermediaplayer.audio

import com.powermediaplayer.audio.EqualizerEffectController.EqSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #9 — constructing EqualizerViewModel (e.g. when the Audio settings group is
 * expanded) must NOT clobber a live per-track EQ override. The fix is a
 * live-state ownership guard on EqualizerEffectController: a construction-time
 * restore applies only when nothing deliberate is already live. Pure JVM — the
 * controller's @Inject ctor is no-arg, so no Hilt/Robolectric needed.
 */
class EqualizerLiveStateTest {

    private val override = listOf(0, 0, 0, 0, 0, 600, 0, 0, 0, 0)
    private val preset = listOf(300, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test fun overrideThenConstructRestore_keepsOverride() {
        val c = EqualizerEffectController()
        c.setLive(override, EqSource.LIVE_OVERRIDE)   // active per-track override
        c.restoreIfUntouched(preset)                  // VM construction restore
        assertEquals("override must survive a construction-time restore", override, c.bandLevels.value)
    }

    @Test fun freshProcess_constructRestoreApplies() {
        val c = EqualizerEffectController()            // untouched default
        c.restoreIfUntouched(preset)
        assertEquals("clean start → restore applies", preset, c.bandLevels.value)
    }

    @Test fun userSelectAlwaysApplies() {
        val c = EqualizerEffectController()
        c.setLive(override, EqSource.LIVE_OVERRIDE)
        val flat = List(10) { 0 }
        c.setLive(flat, EqSource.USER)                 // deliberate user choice
        assertEquals("a user selection always wins", flat, c.bandLevels.value)
    }

    @Test fun overrideMarksSource_blocksRestore() {
        val c = EqualizerEffectController()
        c.setLive(override, EqSource.LIVE_OVERRIDE)     // mirrors the router write
        c.restoreIfUntouched(List(10) { 0 })
        assertEquals(override, c.bandLevels.value)
    }
}
