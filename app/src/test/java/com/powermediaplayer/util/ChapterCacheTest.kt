package com.powermediaplayer.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * vc32 (E11): the 75.9 s Drive resume was ~100% chapter parse over https,
 * repeated on EVERY resume because nothing cached the result — including
 * the empty result (the measured book has zero chapters).
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33]) // repo convention — 4.13 caps below targetSdk 35
class ChapterCacheTest {
    @Test
    fun missThenHit() {
        val cache = ChapterCache(maxEntries = 4)
        assertNull(cache.get("uri1", "57:1000"))
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertNotNull(cache.get("uri1", "57:1000"))
    }

    @Test
    fun staleTokenMisses() {
        val cache = ChapterCache(maxEntries = 4)
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertNull(cache.get("uri1", "58:2000")) // size/mtime changed → re-parse
    }

    @Test
    fun emptyResultIsCachedToo() {
        val cache = ChapterCache(maxEntries = 4)
        cache.put("uri1", "?", android.os.Bundle()) // 0 chapters
        assertNotNull(cache.get("uri1", "?"))
    }
}
