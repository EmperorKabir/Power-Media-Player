package com.powermediaplayer.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * qt-faststart for MP4/ISO-BMFF (incl. `.m4b`): relocate the `moov` atom to BEFORE
 * `mdat` so a streaming/cast receiver can read the sample tables at the FRONT of the
 * file instead of fetching them from the END (T302 — a 1.64 GB moov-at-end Drive
 * audiobook made the Cast receiver request a ~1.4 GB tail and never finish buffering).
 *
 * Pure: operates on [File]s only (no Android Context/Uri) so it is unit-testable on the
 * JVM. Reuses the box-walk technique already proven in [M4bChapterParser] (32-bit size,
 * `size==1` → 64-bit largesize, `size==0` → to-EOF).
 *
 * Output layout = `ftyp` + patched `moov` + every other top-level atom in original
 * order (mdat, free, …). Because `moov` is inserted right after `ftyp`, everything
 * after `ftyp` shifts down by exactly `moov.size`, so every `stco`/`co64` chunk offset
 * is incremented by `moov.size`. `free`/`skip` atoms are kept (not dropped) so the
 * delta stays exactly `moovSize` — avoiding qt-faststart's free-atom accounting.
 *
 * FAIL-SAFE: any parse anomaly returns [Result.NOT_MP4]/[Result.UNSUPPORTED] (never
 * throws), so the caller keeps its existing behaviour and never crashes the cast path.
 */
object Mp4Faststart {

    enum class Result { ALREADY_FASTSTART, REMUXED, NOT_MP4, UNSUPPORTED }

    /** moov larger than this is rejected (UNSUPPORTED) rather than buffered in RAM. */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    private data class Atom(val type: String, val offset: Long, val size: Long, val headerLen: Long)

    /** A parsed ISO-BMFF box header. [size] is 0 for a size-0 (to-EOF) box; the caller
     *  resolves that against the known file total. */
    data class BoxHeader(val type: String, val size: Long, val headerLen: Int)

    /**
     * Parse a box header at [off] in [b]. Returns null if there aren't enough bytes
     * for the header. Used by the streaming faststart proxy to walk a moov-at-end
     * Drive file's top-level atoms via tiny Range reads (T302).
     */
    fun parseBoxHeader(b: ByteArray, off: Int): BoxHeader? {
        if (off + 8 > b.size) return null
        val size32 = readU32(b, off)
        val type = String(b, off + 4, 4, Charsets.US_ASCII)
        return when (size32) {
            1L -> { if (off + 16 > b.size) return null; BoxHeader(type, readU64(b, off + 8), 16) }
            0L -> BoxHeader(type, 0L, 8)
            else -> BoxHeader(type, size32, 8)
        }
    }

    /**
     * Patch a complete in-memory `moov` box (header + children): add [delta] to every
     * `stco`/`co64` chunk offset. Returns a PATCHED COPY ([moovBytes] is never mutated),
     * or null on any anomaly (not a moov box, malformed child, or a 32-bit `stco` offset
     * that would exceed `0xFFFFFFFF` after `+delta`). Used by the streaming faststart
     * proxy and (refactored out of) [faststart].
     */
    fun patchMoovBox(moovBytes: ByteArray, delta: Long): ByteArray? {
        val h = parseBoxHeader(moovBytes, 0) ?: return null
        if (h.type != "moov") return null
        val copy = moovBytes.copyOf()
        return if (patchChildren(copy, h.headerLen, copy.size, delta)) copy else null
    }

    /**
     * Relocate moov→front of [input] into [output]. Returns [Result.REMUXED] on success,
     * [Result.ALREADY_FASTSTART] if moov already precedes mdat (no output written),
     * or a failure result (no output written) leaving the caller to fall back.
     */
    fun faststart(input: File, output: File): Result {
        return try {
            RandomAccessFile(input, "r").use { raf ->
                val atoms = walkTopLevel(raf) ?: return Result.NOT_MP4
                if (atoms.firstOrNull()?.type != "ftyp") return Result.NOT_MP4
                val moov = atoms.firstOrNull { it.type == "moov" } ?: return Result.NOT_MP4
                val mdat = atoms.firstOrNull { it.type == "mdat" } ?: return Result.NOT_MP4
                if (moov.offset < mdat.offset) return Result.ALREADY_FASTSTART
                if (moov.size <= 0 || moov.size > MAX_MOOV_BYTES) return Result.UNSUPPORTED

                val moovBytes = ByteArray(moov.size.toInt())
                raf.seek(moov.offset)
                raf.readFully(moovBytes)

                // shift every stco/co64 by +moovSize (moov inserted after ftyp)
                val patched = patchMoovBox(moovBytes, moov.size) ?: return Result.UNSUPPORTED
                writeOutput(input, output, atoms, moov, patched)
                Result.REMUXED
            }
        } catch (_: Throwable) {
            runCatching { File(output.parent, output.name + ".tmp").delete() }
            Result.UNSUPPORTED
        }
    }

    private fun walkTopLevel(raf: RandomAccessFile): List<Atom>? {
        val atoms = ArrayList<Atom>()
        val len = raf.length()
        var pos = 0L
        while (pos + 8 <= len) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var size = size32
            var headerLen = 8L
            when (size32) {
                1L -> { size = raf.readLong(); headerLen = 16L }
                0L -> size = len - pos
            }
            if (size < headerLen || pos + size > len) return null
            atoms.add(Atom(type, pos, size, headerLen))
            pos += size
        }
        return if (atoms.isEmpty()) null else atoms
    }

    /** Recursively descend container boxes inside [buf] (in `[start,end)`), patching
     *  every `stco`/`co64` chunk offset by [delta]. Returns false on any anomaly. */
    private fun patchChildren(buf: ByteArray, start: Int, end: Int, delta: Long): Boolean {
        var p = start
        while (p + 8 <= end) {
            val size32 = readU32(buf, p)
            val type = String(buf, p + 4, 4, Charsets.US_ASCII)
            var headerLen = 8
            var size = size32
            when (size32) {
                1L -> { if (p + 16 > end) return false; size = readU64(buf, p + 8); headerLen = 16 }
                0L -> size = (end - p).toLong()
            }
            if (size < headerLen) return false
            val bodyStart = p + headerLen
            val boxEnd = p + size.toInt()
            if (boxEnd > end || boxEnd < bodyStart) return false
            when (type) {
                "trak", "mdia", "minf", "stbl" ->
                    if (!patchChildren(buf, bodyStart, boxEnd, delta)) return false
                "stco" -> if (!patchStco(buf, bodyStart, boxEnd, delta)) return false
                "co64" -> if (!patchCo64(buf, bodyStart, boxEnd, delta)) return false
            }
            p = boxEnd
        }
        return true
    }

    private fun patchStco(buf: ByteArray, bodyStart: Int, boxEnd: Int, delta: Long): Boolean {
        var q = bodyStart + 4 // version+flags
        if (q + 4 > boxEnd) return false
        val count = readU32(buf, q); q += 4
        var i = 0L
        while (i < count) {
            if (q + 4 > boxEnd) return false
            val nv = readU32(buf, q) + delta
            if (nv > 0xFFFFFFFFL) return false // would need co64 promotion → fail safe
            writeU32(buf, q, nv)
            q += 4; i++
        }
        return true
    }

    private fun patchCo64(buf: ByteArray, bodyStart: Int, boxEnd: Int, delta: Long): Boolean {
        var q = bodyStart + 4
        if (q + 4 > boxEnd) return false
        val count = readU32(buf, q); q += 4
        var i = 0L
        while (i < count) {
            if (q + 8 > boxEnd) return false
            writeU64(buf, q, readU64(buf, q) + delta)
            q += 8; i++
        }
        return true
    }

    private fun writeOutput(src: File, output: File, atoms: List<Atom>, moov: Atom, moovBytes: ByteArray) {
        val tmp = File(output.parentFile, output.name + ".tmp")
        FileInputStream(src).use { fis ->
            val inCh = fis.channel
            FileOutputStream(tmp).use { fos ->
                val outCh = fos.channel
                val ftyp = atoms.first { it.type == "ftyp" }
                transfer(inCh, outCh, ftyp.offset, ftyp.size)          // ftyp first
                outCh.write(ByteBuffer.wrap(moovBytes))                // patched moov second
                for (a in atoms) {                                     // everything else, in order
                    if (a.type == "ftyp" || a.offset == moov.offset) continue
                    transfer(inCh, outCh, a.offset, a.size)
                }
                fos.fd.sync()
            }
        }
        if (!tmp.renameTo(output)) {
            tmp.delete()
            throw java.io.IOException("faststart rename failed")
        }
    }

    private fun transfer(inCh: FileChannel, outCh: FileChannel, position: Long, count: Long) {
        var transferred = 0L
        while (transferred < count) {
            val n = inCh.transferTo(position + transferred, count - transferred, outCh)
            if (n <= 0) break
            transferred += n
        }
        if (transferred != count) throw java.io.IOException("short transfer $transferred/$count")
    }

    private fun readU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun readU64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (b[o + i].toLong() and 0xFF)
        return r
    }

    private fun writeU64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
}
