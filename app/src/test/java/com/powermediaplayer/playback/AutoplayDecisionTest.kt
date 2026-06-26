package com.powermediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayDecisionTest {

    // All triggers on, no gating, all kinds allowed.
    private val allOn = AutoplayPrefs(
        onLaunch = true, onBt = true, onWired = true, onCast = true,
        onlyIfWasPlaying = false,
        kindSpoken = true, kindMusic = true, kindVideo = true
    )

    @Test fun triggerOff_neverPlays() {
        val prefs = allOn.copy(onLaunch = false)
        assertFalse(
            AutoplayDecision.shouldAutoPlay(
                AutoplayTrigger.LAUNCH, prefs, wasPlayingAtClose = true, kind = MediaPlayKind.SPOKEN
            )
        )
        // other triggers unaffected
        assertTrue(
            AutoplayDecision.shouldAutoPlay(
                AutoplayTrigger.BLUETOOTH, prefs, true, MediaPlayKind.SPOKEN
            )
        )
    }

    @Test fun eachTrigger_independent() {
        for (t in AutoplayTrigger.values()) {
            val only = AutoplayPrefs(
                onLaunch = t == AutoplayTrigger.LAUNCH,
                onBt = t == AutoplayTrigger.BLUETOOTH,
                onWired = t == AutoplayTrigger.WIRED,
                onCast = t == AutoplayTrigger.CAST,
                onlyIfWasPlaying = false,
                kindSpoken = true, kindMusic = true, kindVideo = true
            )
            assertTrue("trigger $t should fire", AutoplayDecision.shouldAutoPlay(t, only, true, MediaPlayKind.MUSIC))
            for (other in AutoplayTrigger.values().filter { it != t }) {
                assertFalse("only $t enabled, $other must not fire",
                    AutoplayDecision.shouldAutoPlay(other, only, true, MediaPlayKind.MUSIC))
            }
        }
    }

    @Test fun onlyIfWasPlaying_gatesOnState() {
        val prefs = allOn.copy(onlyIfWasPlaying = true)
        assertFalse(
            AutoplayDecision.shouldAutoPlay(AutoplayTrigger.LAUNCH, prefs, wasPlayingAtClose = false, kind = MediaPlayKind.SPOKEN)
        )
        assertTrue(
            AutoplayDecision.shouldAutoPlay(AutoplayTrigger.LAUNCH, prefs, wasPlayingAtClose = true, kind = MediaPlayKind.SPOKEN)
        )
    }

    @Test fun wasPlayingIgnored_whenConditionOff() {
        val prefs = allOn.copy(onlyIfWasPlaying = false)
        assertTrue(
            AutoplayDecision.shouldAutoPlay(AutoplayTrigger.LAUNCH, prefs, wasPlayingAtClose = false, kind = MediaPlayKind.SPOKEN)
        )
    }

    @Test fun perType_gatesByKind() {
        val prefs = allOn.copy(kindSpoken = true, kindMusic = false, kindVideo = false)
        assertTrue(AutoplayDecision.shouldAutoPlay(AutoplayTrigger.BLUETOOTH, prefs, true, MediaPlayKind.SPOKEN))
        assertFalse(AutoplayDecision.shouldAutoPlay(AutoplayTrigger.BLUETOOTH, prefs, true, MediaPlayKind.MUSIC))
        assertFalse(AutoplayDecision.shouldAutoPlay(AutoplayTrigger.BLUETOOTH, prefs, true, MediaPlayKind.VIDEO))
    }

    @Test fun classify_videoWins_thenSpoken_elseMusic() {
        assertEquals(MediaPlayKind.VIDEO, AutoplayDecision.classify(isVideo = true, isSpokenWord = true))
        assertEquals(MediaPlayKind.SPOKEN, AutoplayDecision.classify(isVideo = false, isSpokenWord = true))
        assertEquals(MediaPlayKind.MUSIC, AutoplayDecision.classify(isVideo = false, isSpokenWord = false))
    }

    @Test fun defaultsOutOfBox_noTrigger_noAutoplay() {
        // ship defaults: all triggers OFF
        val defaults = AutoplayPrefs(
            onLaunch = false, onBt = false, onWired = false, onCast = false,
            onlyIfWasPlaying = true, kindSpoken = true, kindMusic = false, kindVideo = false
        )
        for (t in AutoplayTrigger.values()) {
            assertFalse("default: $t must not auto-play",
                AutoplayDecision.shouldAutoPlay(t, defaults, true, MediaPlayKind.SPOKEN))
        }
    }
}
