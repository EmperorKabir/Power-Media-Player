package com.powermediaplayer.util

import android.os.Bundle

/**
 * vc32 (E11): LRU cache of parsed chapter bundles keyed by mediaUri + a
 * validity token ("?" when size/mtime are unknowable, e.g. https). The
 * M4B parse over Drive https cost 75.9 s — a same-session re-resume must
 * never re-stream, INCLUDING when the answer was "no chapters" (the
 * measured book has zero; without caching the empty result every resume
 * re-pays the full cost).
 * In-memory only: process death re-parses once (async — see
 * LastPlayedViewModel's remote fill-in), which is acceptable; persistence
 * is YAGNI until evidence says otherwise.
 */
class ChapterCache(private val maxEntries: Int = 16) {
    private data class Entry(val token: String, val bundle: Bundle)

    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > maxEntries
    }

    @Synchronized
    fun get(uri: String, validityToken: String): Bundle? =
        map[uri]?.takeIf { it.token == validityToken }?.bundle

    @Synchronized
    fun put(uri: String, validityToken: String, bundle: Bundle) {
        map[uri] = Entry(validityToken, bundle)
    }

    companion object {
        val shared = ChapterCache()
    }
}
