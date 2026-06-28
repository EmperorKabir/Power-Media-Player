package com.powermediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Graceful resume-error messaging (the live Drive-403-after-wipe case). */
class PlayerErrorMessageTest {

    @Test fun driveBadHttp_promptsReauth() {
        assertEquals(
            PlayerErrorMessage.DRIVE_REAUTH,
            PlayerErrorMessage.resolve(isDriveBadHttp = true, isNetwork = false, rawFallback = "x")
        )
    }

    @Test fun network_showsNetworkMessage() {
        assertEquals(
            PlayerErrorMessage.NETWORK,
            PlayerErrorMessage.resolve(isDriveBadHttp = false, isNetwork = true, rawFallback = "x")
        )
    }

    @Test fun otherError_fallsBackToRaw() {
        assertEquals(
            "ERROR_CODE_DECODING_FAILED: boom",
            PlayerErrorMessage.resolve(false, false, "ERROR_CODE_DECODING_FAILED: boom")
        )
    }

    @Test fun driveUriDetection() {
        assertTrue(PlayerErrorMessage.isDriveUri("https://www.googleapis.com/drive/v3/files/abc?alt=media"))
        assertTrue(PlayerErrorMessage.isDriveUri("https://drive.google.com/x"))
        assertFalse(PlayerErrorMessage.isDriveUri("https://i.scdn.co/image/abc"))
        assertFalse(PlayerErrorMessage.isDriveUri("/storage/emulated/0/Music/song.mp3"))
    }
}
