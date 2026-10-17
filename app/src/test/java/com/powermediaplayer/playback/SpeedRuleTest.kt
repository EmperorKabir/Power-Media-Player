package com.powermediaplayer.playback

import com.powermediaplayer.data.preferences.BtMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PH9 per-kind speed rule (pure parts). */
class SpeedRuleTest {

    @Test fun unsetKind_isOneTimes() {
        assertEquals(1.0f, effectiveKindSpeed(emptyMap(), BtMediaKind.PODCAST))
        assertEquals(1.0f, effectiveKindSpeed(mapOf(BtMediaKind.MUSIC to 1.5f), BtMediaKind.PODCAST))
    }

    @Test fun setKind_isRemembered_perKind() {
        val m = mapOf(BtMediaKind.PODCAST to 1.3f, BtMediaKind.AUDIOBOOK to 1.1f)
        assertEquals(1.3f, effectiveKindSpeed(m, BtMediaKind.PODCAST))
        assertEquals(1.1f, effectiveKindSpeed(m, BtMediaKind.AUDIOBOOK))
        assertEquals(1.0f, effectiveKindSpeed(m, BtMediaKind.MUSIC))   // music never inherits the podcast speed
    }

    @Test fun storedValue_isClampedToPlayerRange() {
        assertEquals(SPEED_MIN, effectiveKindSpeed(mapOf(BtMediaKind.VIDEO to 0.01f), BtMediaKind.VIDEO))
        assertEquals(SPEED_MAX, effectiveKindSpeed(mapOf(BtMediaKind.VIDEO to 9f), BtMediaKind.VIDEO))
    }

    @Test fun speedDiffers_ignoresFloatNoise() {
        assertFalse(speedDiffers(1.0f, 1.0f))
        assertFalse(speedDiffers(1.3f, 1.3000001f))
        assertTrue(speedDiffers(1.0f, 1.25f))
        assertTrue(speedDiffers(1.3f, 1.0f))
    }
}
