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

    @Test
    fun putDeletesStaleDiskSiblings() {
        // Audit 5.11 — a re-parse after the source file changed (new
        // mtime/size token) must delete the now-unreachable old entry,
        // or the disk tier accretes one orphan per file change forever.
        val cache = ChapterCache(maxEntries = 4)
        val dir = java.nio.file.Files.createTempDirectory("chapcache").toFile()
        cache.attachDiskStore(dir)
        cache.put("uri1", "57:1000", android.os.Bundle())
        cache.put("uri1", "58:2000", android.os.Bundle()) // file changed
        val store = java.io.File(dir, "chapter-cache")
        val entries = store.listFiles()?.map { it.name } ?: emptyList()
        org.junit.Assert.assertEquals(
            "stale sibling must be deleted on put", 1, entries.size
        )
    }
}
