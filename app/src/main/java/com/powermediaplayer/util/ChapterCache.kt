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
                    diskDir = java.io.File(baseDir, "chapter-cache").apply { mkdirs() }
                }
            }
        }
    }

    private fun fileFor(uri: String, token: String): java.io.File? {
        val dir = diskDir ?: return null
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$uri|$token".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$key.json")
    }

    @Synchronized
    fun get(uri: String, validityToken: String): Bundle? {
        map[uri]?.takeIf { it.token == validityToken }?.let { return it.bundle }
        // Memory miss → disk.
        val f = fileFor(uri, validityToken) ?: return null
        if (!f.isFile) return null
        return runCatching {
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
            map[uri] = Entry(validityToken, b)
            b
        }.getOrNull()
    }

    @Synchronized
    fun put(uri: String, validityToken: String, bundle: Bundle) {
        map[uri] = Entry(validityToken, bundle)
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
    }
}
