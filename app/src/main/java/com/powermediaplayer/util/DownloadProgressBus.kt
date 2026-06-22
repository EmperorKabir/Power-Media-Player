package com.powermediaplayer.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * Process-wide download progress, keyed by a stable id (podcast episode guid OR
 * Drive file id). The download paths report bytes as they copy; UI rows collect
 * [flow] and render a progress bar + "12.0 / 45.3 MB" while a download is live.
 * An entry is removed when the download finishes (success or failure).
 */
object DownloadProgressBus {
    data class Prog(val done: Long, val total: Long) {
        val fraction: Float
            get() = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _flow = MutableStateFlow<Map<String, Prog>>(emptyMap())
    val flow: StateFlow<Map<String, Prog>> = _flow

    /** Ids the user has asked to cancel mid-download. The shared copy paths poll
     *  this set and throw [DownloadCancelledException] so the provider's existing
     *  partial-file cleanup (catch { cacheFile.delete() }) fires. */
    private val cancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    fun update(id: String, done: Long, total: Long) {
        _flow.value = _flow.value + (id to Prog(done, total))
    }

    fun clear(id: String) {
        if (_flow.value.containsKey(id)) _flow.value = _flow.value - id
        cancelled.remove(id)
    }

    /** Signal that the in-flight download for [id] should abort at the next chunk. */
    fun requestCancel(id: String) { cancelled.add(id) }
    fun isCancelled(id: String): Boolean = cancelled.contains(id)
    /** Throw if [id] was cancelled — called from the copy loops between chunks. */
    fun throwIfCancelled(id: String?) {
        if (id != null && cancelled.contains(id)) throw DownloadCancelledException(id)
    }

    /**
     * Copy [input] → [output] while reporting progress for [id]. [total] is the
     * content length (0 if unknown → indeterminate bar). Returns bytes copied.
     * Throttled to ~every 256 KB so the StateFlow isn't spammed.
     */
    fun copy(id: String, input: InputStream, output: OutputStream, total: Long): Long {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = -1L
        update(id, 0L, total)
        while (true) {
            throwIfCancelled(id)
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (lastReport < 0 || done - lastReport >= 256L * 1024) {
                update(id, done, total)
                lastReport = done
            }
        }
        output.flush()
        update(id, done, total)
        return done
    }
}

/** Thrown by the shared download copy paths when the user cancels a download. */
class DownloadCancelledException(id: String) : java.io.IOException("Download cancelled: $id")

/**
 * Wraps a source [InputStream] so every byte read is counted and reported to
 * [DownloadProgressBus] for [id]. Lets the existing copy paths (copyTo /
 * openOutputStream / SAF writeStream) stay untouched while still showing
 * progress. [total] = content length (0/negative → indeterminate bar).
 */
class ProgressInputStream(
    private val delegate: InputStream,
    private val id: String,
    private val total: Long
) : InputStream() {
    private var done = 0L
    private var lastReport = -1L

    private fun bump(n: Long) {
        if (n <= 0) return
        done += n
        if (lastReport < 0 || done - lastReport >= 256L * 1024) {
            DownloadProgressBus.update(id, done, total)
            lastReport = done
        }
    }

    override fun read(): Int {
        DownloadProgressBus.throwIfCancelled(id)
        val b = delegate.read()
        if (b >= 0) bump(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        DownloadProgressBus.throwIfCancelled(id)
        val n = delegate.read(b, off, len)
        bump(n.toLong())
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}
