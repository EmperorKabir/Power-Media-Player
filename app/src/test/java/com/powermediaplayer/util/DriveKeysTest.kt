package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 regression guard: the Drive stream/download URL is used as a stable key across the
 * app. vc63 (89b5994) appended `&supportsAllDrives=true` to the STORED url, which
 * silently re-keyed every Drive item across the update. These tests assert that the two
 * URL forms collapse to ONE canonical key (so a future param change can never orphan
 * caches/history again) and that the fetch-param is re-added correctly on the wire.
 */
class DriveKeysTest {

    private val id = "1AbC-dEfG_hIjK"
    private val oldUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
    private val vc63Url = "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true"

    @Test
    fun `both url forms collapse to the same canonical key`() {
        assertEquals(DriveKeys.canonicalKey(oldUrl), DriveKeys.canonicalKey(vc63Url))
        assertEquals("drive:$id", DriveKeys.canonicalKey(oldUrl))
        assertEquals("drive:$id", DriveKeys.canonicalKey(vc63Url))
    }

    @Test
    fun `canonical key leaves non-drive uris unchanged`() {
        assertEquals("content://com.android.providers/doc/1", DriveKeys.canonicalKey("content://com.android.providers/doc/1"))
        assertEquals("spotify:track:xyz", DriveKeys.canonicalKey("spotify:track:xyz"))
        assertEquals("", DriveKeys.canonicalKey(null))
        assertEquals("", DriveKeys.canonicalKey(""))
    }

    @Test
    fun `ensureFetchParams adds supportsAllDrives to a param-free drive url`() {
        val fetched = DriveKeys.ensureFetchParams(oldUrl)
        assertTrue(fetched.contains("supportsAllDrives=true"))
        assertTrue(fetched.contains("alt=media"))
        assertEquals("$oldUrl&supportsAllDrives=true", fetched)
    }

    @Test
    fun `canonicalStoredUrl folds a param-carrying drive url back to the stored key`() {
        assertEquals(oldUrl, DriveKeys.canonicalStoredUrl(vc63Url))
        assertEquals(oldUrl, DriveKeys.canonicalStoredUrl(oldUrl))
        // non-drive uris unchanged
        assertEquals("content://x/1", DriveKeys.canonicalStoredUrl("content://x/1"))
        assertEquals("spotify:track:z", DriveKeys.canonicalStoredUrl("spotify:track:z"))
    }

    @Test
    fun `ensureFetchParams is idempotent and leaves non-drive urls alone`() {
        assertEquals(vc63Url, DriveKeys.ensureFetchParams(vc63Url))
        assertEquals("content://x", DriveKeys.ensureFetchParams("content://x"))
        assertEquals("https://api.spotify.com/v1/me", DriveKeys.ensureFetchParams("https://api.spotify.com/v1/me"))
    }
}
