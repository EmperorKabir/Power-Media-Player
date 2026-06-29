package com.powermediaplayer.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure-JVM tests for the streaming-faststart byte-range mapping (T302).
 *
 * Synth layout the receiver sees = `[ftyp][moov'][mdat]`; the original Drive file
 * = `[ftyp][mdat][moov]`. ftyp=32, moov=100, total=1000 → headLen=132.
 */
class FaststartStreamTest {

    private val layout = FaststartStream.Layout(ftypSize = 32, moovSize = 100, total = 1000)

    @Test fun memOnlyRangeServedEntirelyFromHead() {
        assertEquals(
            listOf(FaststartStream.Seg.Head(0, 11)),
            FaststartStream.segments(0, 10, layout)
        )
    }

    @Test fun memRangeEndingOneBeforeHeadBoundary() {
        assertEquals(
            listOf(FaststartStream.Seg.Head(0, 132)),
            FaststartStream.segments(0, 131, layout)  // headLen-1
        )
    }

    @Test fun driveOnlyRangeMapsMinusMoovSize() {
        // 200 >= headLen → original = 200-100 = 100, len = 101
        assertEquals(
            listOf(FaststartStream.Seg.Drive(100, 101)),
            FaststartStream.segments(200, 300, layout)
        )
    }

    @Test fun driveRangeStartingExactlyAtHeadBoundary() {
        // 132 >= headLen → original = 132-100 = 32 (== ftypSize), len = 69
        assertEquals(
            listOf(FaststartStream.Seg.Drive(32, 69)),
            FaststartStream.segments(132, 200, layout)
        )
    }

    @Test fun boundarySpanningSplitsHeadThenDrive() {
        val segs = FaststartStream.segments(0, 999, layout)
        assertEquals(
            listOf(FaststartStream.Seg.Head(0, 132), FaststartStream.Seg.Drive(32, 868)),
            segs
        )
        // composite length must equal the requested span
        assertEquals(1000L, segs.sumOf { it.len })
    }

    @Test fun seekIntoMdatCancelsTheMoovSizeOffset() {
        // receiver read moov' at the front, computed an original chunk offset of 500,
        // added moovSize (the patch) → GET Range bytes=600- ; proxy maps back to 500.
        assertEquals(
            listOf(FaststartStream.Seg.Drive(500, 400)),
            FaststartStream.segments(600, 999, layout)
        )
    }

    // ---- open(): stitch head slice + proxied mdat into EXACT synth bytes ----

    /**
     * original = [ftyp 0..31][mdat 32..899][moov 900..999]; the relay holds a 132-byte
     * head (ftyp + patched moov') and proxies mdat from `original`. The synth stream the
     * receiver must see = head ++ original[32..899]. Every Range must yield exactly those
     * bytes regardless of where it falls.
     */
    @Test fun openStitchesHeadAndProxiedMdatIntoExactSynthBytes() {
        val original = ByteArray(1000) { (it % 251).toByte() }      // distinct, deterministic
        val head = ByteArray(132) { (200 + it % 50).toByte() }      // opaque ftyp+moov' stand-in
        // synth = head (132) ++ original mdat [32,900)
        val synth = head + original.copyOfRange(32, 900)
        assertEquals(1000, synth.size)

        val driveOpener = { start: Long, len: Long ->
            ByteArrayInputStream(original, start.toInt(), len.toInt()) as java.io.InputStream
        }

        // mem-only, drive-only, boundary-spanning, seek, and full-file
        val ranges = listOf(0L to 10L, 0L to 131L, 132L to 300L, 0L to 999L, 600L to 999L, 131L to 133L)
        for ((s, e) in ranges) {
            val got = FaststartStream.open(head, layout, s, e, driveOpener).readBytes()
            assertArrayEquals(
                "range $s-$e",
                synth.copyOfRange(s.toInt(), e.toInt() + 1),
                got
            )
        }
    }
}
