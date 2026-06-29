package com.powermediaplayer.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

/**
 * Pure byte-range mapping for the streaming faststart cast proxy (T302).
 *
 * Casting a moov-at-end Drive audiobook used to freeze: the receiver had to read
 * the `moov` (at the END of a 1.64 GB file) over the Drive→phone→TV relay before it
 * could seek, then stream a ~1.4 GB tail → never finished buffering. Instead of
 * downloading + remuxing the whole file, the relay serves a SYNTHESISED faststart
 * stream `[ftyp][moov'][mdat]` (same total size as the original `[ftyp][mdat][moov]`):
 * only the small head (ftyp + the patched moov) is fetched once and held in memory;
 * the mdat is proxied live from Drive.
 *
 * This maps a receiver's HTTP Range on the synth stream to the byte segments that
 * satisfy it. A synth byte in the mdat region maps to the original Drive byte at
 * `synth - moovSize`: the receiver added `+moovSize` to every chunk offset (via the
 * patched `stco`/`co64` in `moov'`), and that cancels here. Pure (no Android) so it
 * is unit-testable on the JVM.
 */
object FaststartStream {

    /** ftyp + (patched) moov live in memory; mdat is proxied from Drive. */
    data class Layout(val ftypSize: Long, val moovSize: Long, val total: Long) {
        val headLen: Long get() = ftypSize + moovSize
    }

    sealed class Seg {
        abstract val len: Long
        /** Bytes `[headOffset, headOffset+len)` of the in-memory head array (ftyp+moov'). */
        data class Head(val headOffset: Long, override val len: Long) : Seg()
        /** Bytes `[originalStart, originalStart+len)` of the ORIGINAL Drive file. */
        data class Drive(val originalStart: Long, override val len: Long) : Seg()
    }

    /**
     * Map an inclusive synth-stream range `[s, e]` to the ordered segments that
     * serve it. Three cases: entirely in the in-memory head, entirely in the
     * proxied mdat, or spanning the head→mdat boundary (head slice + drive slice).
     */
    fun segments(s: Long, e: Long, layout: Layout): List<Seg> {
        val headLen = layout.headLen
        return when {
            e < headLen -> listOf(Seg.Head(s, e - s + 1))
            s >= headLen -> listOf(Seg.Drive(s - layout.moovSize, e - s + 1))
            else -> {
                val driveOrigStart = headLen - layout.moovSize    // == ftypSize
                val driveOrigEnd = e - layout.moovSize
                listOf(
                    Seg.Head(s, headLen - s),
                    Seg.Drive(driveOrigStart, driveOrigEnd - driveOrigStart + 1)
                )
            }
        }
    }

    /**
     * Open an InputStream for the synth range `[s, e]`: head slices come from [head]
     * (in memory); mdat slices come from [driveOpener]`(originalStart, len)`, which the
     * relay backs with an OkHttp `Range` GET to Drive. The returned stream yields exactly
     * the synth bytes `[s, e]` in order. Closing it closes the underlying drive streams.
     */
    fun open(
        head: ByteArray,
        layout: Layout,
        s: Long,
        e: Long,
        driveOpener: (originalStart: Long, len: Long) -> InputStream
    ): InputStream {
        val streams = segments(s, e, layout).map { seg ->
            when (seg) {
                is Seg.Head -> ByteArrayInputStream(head, seg.headOffset.toInt(), seg.len.toInt())
                is Seg.Drive -> driveOpener(seg.originalStart, seg.len)
            }
        }
        return SequenceInputStream(Collections.enumeration(streams))
    }
}
