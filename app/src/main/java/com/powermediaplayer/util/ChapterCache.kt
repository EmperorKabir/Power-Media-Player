package com.powermediaplayer.util

import android.os.Bundle

/**
 * vc32: LRU cache of parsed chapter bundles keyed by mediaUri + a
 * validity token ("?" when size/mtime are unknowable, e.g. https). The
 * M4B box-walk over a remote file can take minutes, so a re-resume must
 * never re-stream — INCLUDING when the answer was "no chapters" (an
 * uncached empty result re-pays the full cost every time).
 * Backed by a small per-entry JSON store on disk so the cache survives
 * process death; memory is the hot tier.
 */
class ChapterCache(private val maxEntries: Int = 16) {
    private data class Entry(val token: String, val bundle: Bundle)

    // LinkedHashMap isn't thread-safe — every access goes through mapLock.
    // The lock guards ONLY the map: SHA hashing and file IO used to sit
    // inside a method-wide @Synchronized, so a slow disk write on one
    // thread blocked an unrelated memory lookup on the resume path
    // (audit 5.11).
    private val mapLock = Any()
    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > maxEntries
    }

    /**
     * vc32: disk write-through — a memory-only cache dies with the
     * process, and a fresh launch then re-pays a multi-minute remote
     * parse. One small JSON file per entry under <dir>/chapter-cache/,
     * read on memory miss, written on put.
     */
    @Volatile private var diskDir: java.io.File? = null

    fun attachDiskStore(baseDir: java.io.File) {
        if (diskDir == null) {
            synchronized(this) {
                if (diskDir == null) {
                    val dir = java.io.File(baseDir, "chapter-cache").apply { mkdirs() }
                    diskDir = dir
                    // One-shot sweep: the disk tier was unbounded and
                    // stale entries (old mtime/size tokens, legacy-format
                    // names) were never deleted (audit 5.11). Oldest-first
                    // down to the cap.
                    runCatching {
                        val files = dir.listFiles() ?: return@runCatching
                        var total = files.sumOf { it.length() }
                        if (total <= DISK_CAP_BYTES) return@runCatching
                        for (f in files.sortedBy { it.lastModified() }) {
                            if (total <= DISK_CAP_BYTES) break
                            total -= f.length()
                            runCatching { f.delete() }
                        }
                    }
                }
            }
        }
    }

    private fun sha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** `<uriHash>-<tokenHash>.json` — the uri-hash prefix makes stale
     *  siblings (same file, older mtime/size token) discoverable so put()
     *  can delete them. */
    private fun fileFor(uri: String, token: String): java.io.File? {
        val dir = diskDir ?: return null
        return java.io.File(dir, "${sha(uri).take(24)}-${sha(token).take(16)}.json")
    }

    fun get(uri: String, validityToken: String): Bundle? {
        synchronized(mapLock) {
            map[uri]?.takeIf { it.token == validityToken }?.let { return it.bundle }
        }
        // Memory miss → disk (hashing + IO deliberately outside the lock).
        val f = fileFor(uri, validityToken) ?: return null
        if (!f.isFile) return null
        val b = runCatching {
            val o = org.json.JSONObject(f.readText())
            val count = o.getInt("count")
            val b = Bundle()
            b.putInt("chapter_count", count)
            val titles = o.getJSONArray("titles")
            val starts = o.getJSONArray("starts")
            val ends = o.getJSONArray("ends")
            for (i in 0 until count) {
                b.putString("chapter_title_$i", titles.getString(i))
                b.putLong("chapter_start_$i", starts.getLong(i))
                b.putLong("chapter_end_$i", ends.getLong(i))
            }
            b
        }.getOrNull() ?: return null
        synchronized(mapLock) { map[uri] = Entry(validityToken, b) }
        return b
    }

    fun put(uri: String, validityToken: String, bundle: Bundle) {
        synchronized(mapLock) { map[uri] = Entry(validityToken, bundle) }
        val f = fileFor(uri, validityToken) ?: return
        runCatching {
            val count = bundle.getInt("chapter_count", 0)
            val o = org.json.JSONObject()
            o.put("count", count)
            val titles = org.json.JSONArray()
            val starts = org.json.JSONArray()
            val ends = org.json.JSONArray()
            for (i in 0 until count) {
                titles.put(bundle.getString("chapter_title_$i") ?: "")
                starts.put(bundle.getLong("chapter_start_$i", 0L))
                ends.put(bundle.getLong("chapter_end_$i", 0L))
            }
            o.put("titles", titles)
            o.put("starts", starts)
            o.put("ends", ends)
            f.writeText(o.toString())
            // Same uri, different token = the file changed on its source;
            // the old entry can never be valid again.
            val prefix = f.name.substringBefore('-') + "-"
            diskDir?.listFiles { c -> c.name.startsWith(prefix) && c.name != f.name }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    // ── vc32: async fill-in dedup — concurrent resumes of the same
    // uri must not fire duplicate multi-minute parses. ────────────────
    private val fillsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True iff the caller acquired the fill slot for [uri]. */
    fun markFilling(uri: String): Boolean = fillsInFlight.add(uri)

    fun unmarkFilling(uri: String) { fillsInFlight.remove(uri) }

    companion object {
        val shared = ChapterCache()
        /** Disk-tier budget. Entries are tiny JSON files; 5MB is years
         *  of audiobooks. */
        private const val DISK_CAP_BYTES = 5L * 1024 * 1024
    }
}
