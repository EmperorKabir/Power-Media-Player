package com.powermediaplayer.offline

import android.content.Context
import com.powermediaplayer.util.ArtworkCache
import com.powermediaplayer.util.ChapterCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #3 — offline-remove evicts the orphaned cover + chapter caches; ArtworkCache
 *  LRU-trims to its byte cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineCacheEvictionTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test fun artwork_evict_deletesByKey() {
        val key = "content://drive/file-A"
        assertNotNull(ArtworkCache.write(ctx, key, ByteArray(1024) { 7 }))
        assertNotNull(ArtworkCache.uriFor(ctx, key))
        ArtworkCache.evict(ctx, key)
        assertNull("artwork file gone after evict", ArtworkCache.uriFor(ctx, key))
    }

    @Test fun chapter_evict_deletesByUri() {
        val cache = ChapterCache(maxEntries = 4)
        val dir = java.nio.file.Files.createTempDirectory("evict").toFile()
        cache.attachDiskStore(dir)
        cache.put("uriX", "?", android.os.Bundle())
        assertNotNull(cache.get("uriX", "?"))
        cache.evict(dir, "uriX")
        assertNull("chapter entry gone after evict", cache.get("uriX", "?"))
        val store = java.io.File(dir, "chapter-cache")
        assertTrue((store.listFiles()?.size ?: 0) == 0)
    }

    @Test fun artwork_trimToCap_evictsOldestOverBudget() {
        val big = ByteArray((ArtworkCache.CAP_BYTES / 4).toInt()) { 1 }
        ArtworkCache.write(ctx, "k1", big); Thread.sleep(5)
        ArtworkCache.write(ctx, "k2", big); Thread.sleep(5)
        ArtworkCache.write(ctx, "k3", big); Thread.sleep(5)
        ArtworkCache.write(ctx, "k4", big); Thread.sleep(5)
        ArtworkCache.write(ctx, "k5", big) // 5×(cap/4) = 1.25×cap → trim oldest
        assertNull("oldest cover trimmed when over cap", ArtworkCache.uriFor(ctx, "k1"))
    }
}
