package com.powermediaplayer.util

/**
 * Extracts the embedded cover image (iTunes `covr` atom) from a TAIL FRAGMENT of an
 * MP4-family file (m4b/m4a/mp4). A moov-at-end audiobook's cover cannot be read by
 * MediaMetadataRetriever from a fragment (no ftyp, mdat truncated), but the cover
 * bytes live inside moov/udta/meta/ilst/covr/data — all of which sit inside a tail
 * window when the moov is at the end. So: fetch the last few MB by HTTP Range, scan
 * for a plausible `moov` box header, and walk the container chain with strict bounds
 * checks. A false 'moov' match inside mdat noise fails the structural checks, returns
 * null for that candidate, and the scan resumes at the next occurrence.
 *
 * Pure JVM (no android.*) — unit-testable with synthesized boxes.
 */
object Mp4CoverTailParser {

    /** Sanity cap: no real cover is larger than this. */
    private const val MAX_COVER_BYTES = 20 * 1024 * 1024
    private const val MIN_COVER_BYTES = 32

    fun extractCoverFromBuffer(buf: ByteArray): ByteArray? {
        var searchFrom = 4 // a box header needs 4 size bytes before the fourcc
        while (true) {
            val i = indexOfFourcc(buf, "moov", searchFrom)
            if (i < 0) return null
            val boxStart = i - 4
            val cover = runCatching { walkBox(buf, boxStart, buf.size, "moov") }.getOrNull()
            if (cover != null) return cover
            searchFrom = i + 1
        }
    }

    /**
     * Walks the box at [boxStart] (which must be of type [expectType]) and its
     * relevant descendants; returns the covr image bytes or null. Throws on any
     * structural violation — the caller treats that as "false match, keep scanning".
     */
    private fun walkBox(buf: ByteArray, boxStart: Int, limit: Int, expectType: String): ByteArray? {
        val (payloadStart, payloadEnd) = boxBounds(buf, boxStart, limit, expectType) ?: return null
        return walkChildren(buf, payloadStart, payloadEnd)
    }

    private fun walkChildren(buf: ByteArray, start: Int, end: Int): ByteArray? {
        var off = start
        while (off + 8 <= end) {
            val size = readU32(buf, off)
            val type = fourcc(buf, off + 4)
            // 64-bit largesize / to-end boxes are legal MP4 but never used for the
            // small metadata containers we walk; treat as structural mismatch.
            if (size < 8L || off + size > end) throw IllegalStateException("bad child box")
            val childEnd = (off + size).toInt()
            when (type) {
                "udta", "ilst" -> {
                    if (off + 8 < childEnd) {
                        walkChildren(buf, off + 8, childEnd)?.let { return it }
                    }
                }
                "meta" -> {
                    // ISO/iTunes meta carries a 4-byte version/flags word before its
                    // children; QuickTime .mov writes a BARE meta without one. Try
                    // both layouts, each locally contained so a misparse of one
                    // offset cannot abort the whole moov candidate.
                    for (payloadStart in intArrayOf(off + 12, off + 8)) {
                        if (payloadStart >= childEnd) continue
                        runCatching { walkChildren(buf, payloadStart, childEnd) }
                            .getOrNull()?.let { return it }
                    }
                }
                "covr" -> extractDataPayload(buf, off + 8, childEnd)?.let { return it }
            }
            off = childEnd
        }
        return null
    }

    /** Inside covr: a `data` box whose payload (after 4B type + 4B locale) is the image. */
    private fun extractDataPayload(buf: ByteArray, start: Int, end: Int): ByteArray? {
        var off = start
        while (off + 8 <= end) {
            val size = readU32(buf, off)
            val type = fourcc(buf, off + 4)
            if (size < 8L || off + size > end) return null
            if (type == "data") {
                val imgStart = off + 16 // 8 header + 4 data-type + 4 locale
                val imgEnd = (off + size).toInt()
                val len = imgEnd - imgStart
                if (len in MIN_COVER_BYTES..MAX_COVER_BYTES && looksLikeImage(buf, imgStart)) {
                    return buf.copyOfRange(imgStart, imgEnd)
                }
                return null
            }
            off = (off + size).toInt()
        }
        return null
    }

    /** Bounds of the payload of the box at [boxStart], or null if it isn't [expectType]. */
    private fun boxBounds(buf: ByteArray, boxStart: Int, limit: Int, expectType: String): Pair<Int, Int>? {
        if (boxStart < 0 || boxStart + 8 > limit) return null
        if (fourcc(buf, boxStart + 4) != expectType) return null
        val size = readU32(buf, boxStart)
        if (size < 16L) return null // a real moov is far bigger than a bare header
        // Clamp: the tail window may truncate the front of a huge moov's mdat-side
        // sibling, but the moov we matched must at least START inside the buffer.
        val payloadEnd = minOf(boxStart + size, limit.toLong()).toInt()
        return (boxStart + 8) to payloadEnd
    }

    private fun looksLikeImage(buf: ByteArray, off: Int): Boolean {
        if (off + 12 > buf.size) return false
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> true                    // JPEG
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> true      // PNG
            b0 == 'G'.code && b1 == 'I'.code && b2 == 'F'.code -> true         // GIF
            b0 == 'B'.code && b1 == 'M'.code -> true                           // BMP
            b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code &&              // WEBP
                fourcc(buf, off + 8) == "WEBP" -> true
            else -> false
        }
    }

    private fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun fourcc(buf: ByteArray, off: Int): String {
        if (off + 4 > buf.size) return ""
        return String(buf, off, 4, Charsets.ISO_8859_1)
    }

    private fun indexOfFourcc(buf: ByteArray, type: String, from: Int): Int {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        var i = maxOf(from, 0)
        val last = buf.size - t.size
        outer@ while (i <= last) {
            for (j in t.indices) {
                if (buf[i + j] != t[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
