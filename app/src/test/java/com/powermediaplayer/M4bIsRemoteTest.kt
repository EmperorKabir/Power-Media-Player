package com.powermediaplayer

import android.net.Uri
import com.powermediaplayer.util.M4bChapterParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit 5.1 — "remote" must mean network-backed, not just http(s).
 * SAF-picked Drive items arrive as content:// URIs on the Google Docs
 * provider; parsing those inline streams most of a multi-GB file (the
 * 75.9s incident class). Local providers must stay non-remote so the
 * ms-fast inline parse keeps working for them.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33]) // repo convention — 4.13 caps below targetSdk 35
class M4bIsRemoteTest {

    @Test
    fun `http and https are remote`() {
        assertTrue(M4bChapterParser.isRemote(Uri.parse("https://x/y.m4b")))
        assertTrue(M4bChapterParser.isRemote(Uri.parse("http://x/y.m4b")))
    }

    @Test
    fun `drive documents provider is remote`() {
        assertTrue(
            M4bChapterParser.isRemote(
                Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dabc")
            )
        )
    }

    @Test
    fun `local mediastore and external storage are NOT remote`() {
        assertFalse(M4bChapterParser.isRemote(Uri.parse("content://media/external/audio/media/71")))
        assertFalse(
            M4bChapterParser.isRemote(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.m4b")
            )
        )
        assertFalse(M4bChapterParser.isRemote(Uri.parse("file:///sdcard/a.m4b")))
    }
}
