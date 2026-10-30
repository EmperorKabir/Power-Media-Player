package com.powermediaplayer.ui.library

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-PERM: each Library tab gates on and asks for its own media permission (API 33+). */
class LibraryPermissionsTest {
    private val A = Manifest.permission.READ_MEDIA_AUDIO
    private val V = Manifest.permission.READ_MEDIA_VIDEO

    @Test fun freshInstallAsksForBothAtOnce() {
        assertArrayEquals(arrayOf(A, V), LibraryPermissions.toRequest(0, hasAudio = false, hasVideo = false, sdk = 34))
        assertArrayEquals(arrayOf(A, V), LibraryPermissions.toRequest(1, hasAudio = false, hasVideo = false, sdk = 34))
    }

    @Test fun videoTabWithOnlyAudioGrantedAsksForVideoOnly() {   // the user's 2026-10-30 case
        assertArrayEquals(arrayOf(V), LibraryPermissions.toRequest(1, hasAudio = true, hasVideo = false, sdk = 34))
        assertFalse(LibraryPermissions.granted(1, hasAudio = true, hasVideo = false, sdk = 34))
        assertEquals("Photos and videos permission required", LibraryPermissions.title(1, sdk = 34))
    }

    @Test fun audioTabWithOnlyAudioGrantedAsksNothing() {
        assertEquals(0, LibraryPermissions.toRequest(0, hasAudio = true, hasVideo = false, sdk = 34).size)
        assertTrue(LibraryPermissions.granted(0, hasAudio = true, hasVideo = false, sdk = 34))
    }

    @Test fun audioTabWithOnlyVideoGrantedAsksForAudioOnly() {
        assertArrayEquals(arrayOf(A), LibraryPermissions.toRequest(0, hasAudio = false, hasVideo = true, sdk = 34))
    }

    @Test fun preApi33UsesTheSingleStoragePermissionForBothTabs() {
        val s = Manifest.permission.READ_EXTERNAL_STORAGE
        assertEquals(s, LibraryPermissions.permissionFor(1, sdk = 32))
        assertArrayEquals(arrayOf(s), LibraryPermissions.toRequest(1, hasAudio = false, hasVideo = false, sdk = 32))
        assertTrue(LibraryPermissions.granted(1, hasAudio = true, hasVideo = false, sdk = 32))
    }
}
