package com.powermediaplayer.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure-JVM tests for [Mp4Faststart] using hand-built minimal MP4 fixtures. */
class Mp4FaststartTest {

    // ---- fixture builders ----
    private fun u32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
    private fun u64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte() }
    private fun rU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun rU64(b: ByteArray, o: Int): Long { var r = 0L; for (i in 0 until 8) r = (r shl 8) or (b[o + i].toLong() and 0xFF); return r }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        u32(out, 0, (8 + body.size).toLong())
        for (i in 0..3) out[4 + i] = type[i].code.toByte()
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }
    private fun cat(vararg a: ByteArray): ByteArray { var r = ByteArray(0); for (x in a) r += x; return r }
    private fun stcoBody(offsets: LongArray): ByteArray {
        val b = ByteArray(8 + offsets.size * 4); u32(b, 4, offsets.size.toLong())
        for (i in offsets.indices) u32(b, 8 + i * 4, offsets[i]); return b
    }
    private fun co64Body(offsets: LongArray): ByteArray {
        val b = ByteArray(8 + offsets.size * 8); u32(b, 4, offsets.size.toLong())
        for (i in offsets.indices) u64(b, 8 + i * 8, offsets[i]); return b
    }
    private fun stbl(chunkBox: ByteArray) = box("trak", box("mdia", box("minf", box("stbl", chunkBox))))

    private fun writeFile(vararg parts: ByteArray): File {
        val f = File.createTempFile("fsin", ".mp4"); f.deleteOnExit()
        f.outputStream().use { for (p in parts) it.write(p) }
        return f
    }
    private fun out() = File.createTempFile("fsout", ".mp4").also { it.deleteOnExit() }
    private fun allIndicesOf(b: ByteArray, needle: String): List<Int> {
        val s = String(b, Charsets.ISO_8859_1); val res = ArrayList<Int>(); var i = s.indexOf(needle)
        while (i >= 0) { res.add(i); i = s.indexOf(needle, i + 1) }; return res
    }

    private val SAMPLE = "POWERMEDIA_SAMPLE_BYTES!".toByteArray()
    private val FTYP = box("ftyp", "isom".toByteArray() + ByteArray(8)) // size 20

    @Test fun movesMoovBeforeMdat() {
        val mdat = box("mdat", SAMPLE)
        val sampleOff = (FTYP.size + 8).toLong()
        val moov = box("moov", stbl(box("stco", stcoBody(longArrayOf(sampleOff)))))
        val o = out()
        assertEquals(Mp4Faststart.Result.REMUXED, Mp4Faststart.faststart(writeFile(FTYP, mdat, moov), o))
        val b = o.readBytes()
        val moovPos = allIndicesOf(b, "moov").first() - 4
        val mdatPos = allIndicesOf(b, "mdat").first() - 4
        assertTrue("moov must precede mdat", moovPos < mdatPos)
    }

    @Test fun adjustsStcoByMoovSizeAndOffsetsStillResolve() {
        val mdat = box("mdat", SAMPLE)
        val sampleOff = (FTYP.size + 8).toLong()           // first sample byte in original file
        val moov = box("moov", stbl(box("stco", stcoBody(longArrayOf(sampleOff)))))
        val o = out()
        assertEquals(Mp4Faststart.Result.REMUXED, Mp4Faststart.faststart(writeFile(FTYP, mdat, moov), o))
        val b = o.readBytes()
        val stcoIdx = allIndicesOf(b, "stco").first()
        val newOff = rU32(b, stcoIdx + 4 + 4 + 4)           // type(4) skip → vf(4) → count(4) → offset
        assertEquals(sampleOff + moov.size, newOff)         // +moovSize
        val resolved = b.copyOfRange(newOff.toInt(), newOff.toInt() + SAMPLE.size)
        assertArrayEquals("new offset must still point at the sample", SAMPLE, resolved)
    }

    @Test fun co64SixtyFourBitOffsets() {
        val mdat = box("mdat", SAMPLE)
        val sampleOff = (FTYP.size + 8).toLong()
        val moov = box("moov", stbl(box("co64", co64Body(longArrayOf(sampleOff)))))
        val o = out()
        assertEquals(Mp4Faststart.Result.REMUXED, Mp4Faststart.faststart(writeFile(FTYP, mdat, moov), o))
        val b = o.readBytes()
        val idx = allIndicesOf(b, "co64").first()
        assertEquals(sampleOff + moov.size, rU64(b, idx + 4 + 4 + 4))
    }

    @Test fun multipleTraksAllPatched() {
        val mdat = box("mdat", SAMPLE)
        val off1 = (FTYP.size + 8).toLong()
        val off2 = (FTYP.size + 12).toLong()
        val trak1 = stbl(box("stco", stcoBody(longArrayOf(off1))))
        val trak2 = stbl(box("stco", stcoBody(longArrayOf(off2))))
        val moov = box("moov", cat(trak1, trak2))
        val o = out()
        assertEquals(Mp4Faststart.Result.REMUXED, Mp4Faststart.faststart(writeFile(FTYP, mdat, moov), o))
        val b = o.readBytes()
        val idxs = allIndicesOf(b, "stco")
        assertEquals(2, idxs.size)
        assertEquals(off1 + moov.size, rU32(b, idxs[0] + 12))
        assertEquals(off2 + moov.size, rU32(b, idxs[1] + 12))
    }

    @Test fun alreadyFaststartReturnsAlready() {
        val mdat = box("mdat", SAMPLE)
        val moov = box("moov", stbl(box("stco", stcoBody(longArrayOf((FTYP.size + 8).toLong())))))
        val o = out()
        assertEquals(Mp4Faststart.Result.ALREADY_FASTSTART, Mp4Faststart.faststart(writeFile(FTYP, moov, mdat), o))
    }

    @Test fun nonMp4FailsSafe() {
        val junk = writeFile("not an mp4 file at all, just text bytes here".toByteArray())
        assertEquals(Mp4Faststart.Result.NOT_MP4, Mp4Faststart.faststart(junk, out()))
        // missing ftyp (starts with a moov) → NOT_MP4, never throws
        val noFtyp = writeFile(box("moov", ByteArray(8)), box("mdat", SAMPLE))
        assertEquals(Mp4Faststart.Result.NOT_MP4, Mp4Faststart.faststart(noFtyp, out()))
    }
}
