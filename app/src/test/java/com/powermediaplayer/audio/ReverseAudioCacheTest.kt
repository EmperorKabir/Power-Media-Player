package com.powermediaplayer.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ReverseAudio.trimCacheToCap] (audit LRU-cap item, 2026-08-25) — the
 * reverse-cache size guard. Pure file I/O, no device.
 */
class ReverseAudioCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "revcache_test_${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun wav(name: String, bytes: Int, ageMs: Long): File {
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @Test
    fun underCap_deletesNothing() {
        wav("a.wav", 100, 3000)
        wav("b.wav", 100, 1000)
        val freed = ReverseAudio.trimCacheToCap(dir, capBytes = 1000, keep = null)
        assertEquals(0L, freed)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun overCap_deletesOldestFirst() {
        val old = wav("old.wav", 600, ageMs = 5000)
        val mid = wav("mid.wav", 600, ageMs = 3000)
        val new = wav("new.wav", 600, ageMs = 1000)
        // total 1800, cap 1000 → must delete oldest until <=1000: delete old(600)=1200, mid(600)=600.
        val freed = ReverseAudio.trimCacheToCap(dir, capBytes = 1000, keep = null)
        assertEquals(1200L, freed)
        assertFalse("oldest deleted", old.exists())
        assertFalse("mid deleted", mid.exists())
        assertTrue("newest kept", new.exists())
    }

    @Test
    fun keepFile_neverDeletedEvenIfOldest() {
        val keep = wav("keep.wav", 600, ageMs = 9000)   // oldest
        val other = wav("other.wav", 600, ageMs = 1000)
        // total 1200, cap 500 → would delete oldest (keep) but keep is spared → delete other only.
        val freed = ReverseAudio.trimCacheToCap(dir, capBytes = 500, keep = keep)
        assertEquals(600L, freed)
        assertTrue("keep file spared", keep.exists())
        assertFalse("other deleted", other.exists())
    }

    @Test
    fun onlyWavFiles_touched() {
        wav("a.wav", 900, 5000)
        File(dir, "b.pcm.tmp").writeBytes(ByteArray(900))
        val freed = ReverseAudio.trimCacheToCap(dir, capBytes = 500, keep = null)
        // only the .wav counts toward total + is eligible for deletion.
        assertEquals(900L, freed)
        assertTrue("non-wav untouched", File(dir, "b.pcm.tmp").exists())
    }

    @Test
    fun missingDir_returnsZero() {
        val gone = File(dir, "does_not_exist")
        assertEquals(0L, ReverseAudio.trimCacheToCap(gone, capBytes = 100, keep = null))
    }
}
