package com.powermediaplayer.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthesises minimal MP4 box structures (moov/udta/meta/ilst/covr/data) and checks
 * the tail scanner finds the cover, survives leading mdat noise (including a false
 * 'moov' byte sequence), and rejects malformed structures instead of crashing.
 */
class Mp4CoverTailParserTest {

    // A tiny but valid-magic JPEG payload (header + arbitrary bytes), > 32 bytes.
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()
    ) + ByteArray(60) { (it % 251).toByte() }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** data box: 4B type flag (13 = JPEG) + 4B locale + image bytes. */
    private fun dataBox(image: ByteArray): ByteArray =
        box("data", byteArrayOf(0, 0, 0, 13, 0, 0, 0, 0) + image)

    /** meta box carries a 4-byte version/flags word before its children. */
    private fun metaBox(children: ByteArray): ByteArray =
        box("meta", byteArrayOf(0, 0, 0, 0) + children)

    private fun standardMoov(image: ByteArray): ByteArray =
        box(
            "moov",
            // an irrelevant sibling first (mvhd), then the udta chain
            box("mvhd", ByteArray(24)) +
                box("udta", metaBox(box("ilst", box("covr", dataBox(image)))))
        )

    @Test
    fun findsCoverInCleanMoov() {
        assertArrayEquals(jpeg, Mp4CoverTailParser.extractCoverFromBuffer(standardMoov(jpeg)))
    }

    @Test
    fun findsCoverAfterMdatNoise() {
        val noise = ByteArray(4096) { (it * 31 % 253).toByte() }
        assertArrayEquals(
            jpeg,
            Mp4CoverTailParser.extractCoverFromBuffer(noise + standardMoov(jpeg))
        )
    }

    @Test
    fun survivesFalseMoovBytesInNoise() {
        // Plant a bare 'moov' fourcc inside garbage BEFORE the real box — the
        // structural checks must reject it and the scan must continue.
        val garbage = ByteArray(512) { 0x7F } + "moov".toByteArray() + ByteArray(512) { 0x01 }
        assertArrayEquals(
            jpeg,
            Mp4CoverTailParser.extractCoverFromBuffer(garbage + standardMoov(jpeg))
        )
    }

    @Test
    fun pngCoverAccepted() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(64) { 7 }
        assertArrayEquals(png, Mp4CoverTailParser.extractCoverFromBuffer(standardMoov(png)))
    }

    @Test
    fun noCovrMeansNull() {
        val moov = box("moov", box("udta", metaBox(box("ilst", box("free", ByteArray(8))))))
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(moov))
    }

    @Test
    fun nonImagePayloadRejected() {
        val notImage = ByteArray(64) { 0x11 } // no known image magic
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(standardMoov(notImage)))
    }

    @Test
    fun tinyPayloadRejected() {
        val tiny = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // < 32 bytes
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(standardMoov(tiny)))
    }

    @Test
    fun emptyAndGarbageBuffersAreSafe() {
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(ByteArray(0)))
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(ByteArray(1024) { 0x42 }))
    }

    @Test
    fun truncatedMoovDoesNotCrash() {
        // Chop the tail off the real structure — must return null, never throw.
        val whole = standardMoov(jpeg)
        val truncated = whole.copyOfRange(0, whole.size / 2)
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(truncated))
    }

    @Test
    fun metaDirectlyUnderMoovAlsoWorks() {
        // Some encoders skip udta: moov/meta/ilst/covr.
        val moov = box("moov", metaBox(box("ilst", box("covr", dataBox(jpeg)))))
        assertArrayEquals(jpeg, Mp4CoverTailParser.extractCoverFromBuffer(moov))
    }

    @Test
    fun bareQuickTimeMetaWithoutVersionWordAlsoWorks() {
        // QuickTime .mov writes meta with NO 4-byte version/flags word — the
        // children start immediately after the header.
        val bareMeta = box("meta", box("ilst", box("covr", dataBox(jpeg))))
        val moov = box("moov", box("udta", bareMeta))
        assertArrayEquals(jpeg, Mp4CoverTailParser.extractCoverFromBuffer(moov))
    }

    @Test
    fun dataBoxSmallerThanItsOwnHeaderRejected() {
        // A 'data' box whose size (12) is less than header+type+locale (16) must
        // yield a negative length and be rejected, never crash.
        val badData = ByteArray(12).also {
            it[3] = 12
            "data".toByteArray(Charsets.ISO_8859_1).copyInto(it, 4)
        }
        val moov = box("moov", box("udta", metaBox(box("ilst", box("covr", badData)))))
        assertNull(Mp4CoverTailParser.extractCoverFromBuffer(moov))
    }
}
