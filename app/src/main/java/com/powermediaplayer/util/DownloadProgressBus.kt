package com.powermediaplayer.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.InputStream

/**
 * Process-wide download progress, keyed by a stable id (podcast episode guid OR
 * Drive file id). The download paths report bytes as they copy; UI rows collect
 * [flow] (aggregate) or [progressFor] (a single id) and render a progress bar +
 * "12.0 / 45.3 MB" while a download is live. An entry is removed when the
 * download finishes (success or failure).
 */
object DownloadProgressBus {
    data class Prog(val done: Long, val total: Long) {
        val fraction: Float
            get() = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _flow = MutableStateFlow<Map<String, Prog>>(emptyMap())
    val flow: StateFlow<Map<String, Prog>> = _flow

    /** Progress for a SINGLE id — point consumers (the player button, a Last
     *  Played row) collect this so they only recompose on THEIR download's ticks,
     *  not on every other in-flight download's 256 KB chunk. */
    fun progressFor(id: String): Flow<Prog?> =
        _flow.map { it[id] }.distinctUntilChanged()

    /** Human label per in-flight id (file/episode title) so a Manage Downloads
     *  "In progress" row can name the download, not just show its opaque id. */
    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels
    fun label(id: String, name: String) {
        if (name.isNotBlank()) _labels.update { it + (id to name) }
    }

    /** Ids the user has asked to cancel mid-download. The shared copy paths poll
     *  this set and throw [DownloadCancelledException] so the provider's existing
     *  partial-file cleanup (catch { cacheFile.delete() }) fires. */
    private val cancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    // Atomic CAS updates: CloudViewModel.saveFolderOffline fans out N parallel
    // downloads, each reporting a distinct id from its own IO thread — a plain
    // `value = value + x` read-modify-write would drop a racing sibling's entry.
    fun update(id: String, done: Long, total: Long) {
        _flow.update { it + (id to Prog(done, total)) }
    }

    fun clear(id: String) {
        _flow.update { it - id }
        _labels.update { it - id }
        cancelled.remove(id)
    }

    /** Signal that the in-flight download for [id] should abort at the next chunk.
     *  Guarded to a REGISTERED download (progress or label present) so a stray tap
     *  with nothing in flight can't leave a stale flag that aborts a later same-id
     *  download before its first chunk. */
    fun requestCancel(id: String) {
        if (_flow.value.containsKey(id) || _labels.value.containsKey(id)) cancelled.add(id)
    }
    fun isCancelled(id: String): Boolean = cancelled.contains(id)
    /** Throw if [id] was cancelled — called from the copy loops between chunks. */
    fun throwIfCancelled(id: String?) {
        if (id != null && cancelled.contains(id)) throw DownloadCancelledException(id)
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
