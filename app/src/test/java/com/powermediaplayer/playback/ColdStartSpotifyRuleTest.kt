package com.powermediaplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdStartSpotifyRuleTest {
    @Test fun autoplayOffNeverSendsPlay() = assertFalse(ColdStartSpotifyRule.sendsPlayCommand(false))
    @Test fun autoplayOnSendsPlay() = assertTrue(ColdStartSpotifyRule.sendsPlayCommand(true))
}
