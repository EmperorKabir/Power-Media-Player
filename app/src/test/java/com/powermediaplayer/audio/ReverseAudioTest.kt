package com.powermediaplayer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the reversal core: a WAV whose frames are the source PCM in
 * exact reverse order, with a correct header — chunked from the tail so
 * the chunk-boundary maths is what these tests actually exercise.
 */
class ReverseAudioTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun framesComeOutInExactReverseOrder() {
        // 40,000 stereo frames — crosses several 16k-frame chunks so the
        // boundary stitching is exercised, not just one pass.
        val frames = 40_000
        val pcm = tmp.newFile("in.pcm")
        val bb = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            bb.putShort(i.toShort())            // L carries the frame index
            bb.putShort((i xor 0x55AA).toShort()) // R derived from it
        }
        pcm.writeBytes(bb.array())

        val wav = tmp.newFile("out.wav")
        ReverseAudio.writeReversedWav(pcm, wav, sampleRate = 48_000, channels = 2)

        val bytes = wav.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val expect = (frames - 1 - i)
            assertEquals("frame $i L", expect.toShort(), data.short)
            assertEquals("frame $i R", (expect xor 0x55AA).toShort(), data.short)
        }
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = tmp.newFile("in.pcm")
        pcm.writeBytes(ByteArray(4_000)) // 1000 stereo frames of silence
        val wav = tmp.newFile("out.wav")
        ReverseAudio.writeReversedWav(pcm, wav, sampleRate = 44_100, channels = 2)

        val h = ByteBuffer.wrap(wav.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { h.get(it) }
        assertArrayEquals("RIFF".toByteArray(), riff)
        h.int // riff size
        val wave = ByteArray(4).also { h.get(it) }
        assertArrayEquals("WAVE".toByteArray(), wave)
        val fmt = ByteArray(4).also { h.get(it) }
        assertArrayEquals("fmt ".toByteArray(), fmt)
        assertEquals(16, h.int)
        assertEquals(1, h.short.toInt())        // PCM
        assertEquals(2, h.short.toInt())        // channels
        assertEquals(44_100, h.int)             // sample rate
        assertEquals(44_100 * 4, h.int)         // byte rate
        assertEquals(4, h.short.toInt())        // block align
        assertEquals(16, h.short.toInt())       // bits
        val dataTag = ByteArray(4).also { h.get(it) }
        assertArrayEquals("data".toByteArray(), dataTag)
        assertEquals(4_000, h.int)
    }
}
