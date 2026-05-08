package com.powermediaplayer.data.db.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOverrideEntityTest {
    @Test fun isEmpty_when_every_axis_is_null() {
        val e = MediaOverrideEntity(mediaUri = "u")
        assertTrue(e.isEmpty())
        assertFalse(e.hasAnyAudio())
        assertFalse(e.hasAnyVideo())
        assertFalse(e.hasAnySpeed())
    }

    @Test fun audio_axis_flips_only_audio_flag() {
        val e = MediaOverrideEntity(mediaUri = "u", reverbPreset = 2)
        assertTrue(e.hasAnyAudio())
        assertFalse(e.hasAnyVideo())
        assertFalse(e.hasAnySpeed())
        assertFalse(e.isEmpty())
    }

    @Test fun video_axis_flips_only_video_flag() {
        val e = MediaOverrideEntity(mediaUri = "u", videoBw = true)
        assertFalse(e.hasAnyAudio())
        assertTrue(e.hasAnyVideo())
        assertFalse(e.hasAnySpeed())
    }

    @Test fun speed_axis_flips_only_speed_flag() {
        val e = MediaOverrideEntity(mediaUri = "u", playbackSpeed = 1.25f)
        assertFalse(e.hasAnyAudio())
        assertFalse(e.hasAnyVideo())
        assertTrue(e.hasAnySpeed())
    }
}
