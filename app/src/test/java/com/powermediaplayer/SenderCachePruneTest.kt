package com.powermediaplayer

import com.powermediaplayer.service.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit 1.3 — sender caches must evict ids that left every live timeline
 * while never touching live ones (cast artwork + cleartext restore read
 * them), and an empty live set must be a no-op (transient empty-timeline
 * transitions must not wipe the cache).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33]) // repo convention — 4.13 caps below targetSdk 35
class SenderCachePruneTest {

    private fun seed(vararg ids: String) {
        PlaybackService.senderMetadataByMediaId.clear()
        PlaybackService.senderItemByMediaId.clear()
        ids.forEach { id ->
            PlaybackService.senderMetadataByMediaId[id] =
                androidx.media3.common.MediaMetadata.EMPTY
            PlaybackService.senderItemByMediaId[id] =
                androidx.media3.common.MediaItem.Builder().setMediaId(id).build()
        }
    }

    @Test
    fun `prune drops dead ids and keeps live ones in both caches`() {
        seed("live", "dead")
        PlaybackService.pruneSenderCaches(setOf("live"))
        assertEquals(setOf("live"), PlaybackService.senderMetadataByMediaId.keys)
        assertEquals(setOf("live"), PlaybackService.senderItemByMediaId.keys)
    }

    @Test
    fun `empty live set keeps everything`() {
        seed("a", "b")
        PlaybackService.pruneSenderCaches(emptySet())
        assertEquals(setOf("a", "b"), PlaybackService.senderMetadataByMediaId.keys)
        assertEquals(setOf("a", "b"), PlaybackService.senderItemByMediaId.keys)
    }
}
