package com.powermediaplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Durable on-disk cache for embedded cover art. The 600 KB-1 MB bytes pulled out
 * of an m4b live only in the transient session metadata override, so the cover
 * loaded intermittently (a race) and Last Played thumbnails were blank. Writing
 * the bytes to a stable file keyed by the media uri lets the player, cold-start
 * restore, AND the Last Played list all point at the same persistent image.
 *
 * Stored under filesDir/coverart/<sha256>.img — DURABLE on purpose. The DB
 * (playback_history.artworkUri) bakes in the absolute path, so the file MUST
 * outlive an OS cache-eviction, a user "Clear cache", and an app reinstall
 * (filesDir is in auto-backup; cacheDir is not). It previously lived in cacheDir,
 * so a Drive audiobook's cover vanished while the DB still pointed at it →
 * MusicNote placeholder. This mirrors the offline-audio durability fix
 * (OfflineStorage.toDurable → filesDir). The 32 MB LRU cap (trimToCap) bounds the
 * footprint. Audit hardening: a full SHA-256 key (not the 32-bit String.hashCode,
 * which collides) and an atomic temp-then-rename write (no torn file on a race).
 */
object ArtworkCache {
    private fun dir(context: Context) = File(context.filesDir, "coverart").apply { mkdirs() }

    private fun keyHash(key: String): String =
        runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault(key.hashCode().toString())

    private fun fileFor(context: Context, key: String) =
        File(dir(context), "${keyHash(key)}.img")

    /** Write [bytes] for [key]; returns the file:// uri, or null on failure. */
    fun write(context: Context, key: String, bytes: ByteArray): Uri? = runCatching {
        val f = fileFor(context, key)
        // Skip the rewrite only when the SAME size already exists — a full hash
        // makes a same-size-different-content collision astronomically unlikely.
        if (!(f.exists() && f.length() == bytes.size.toLong())) {
            // Atomic: write to a PER-WRITER-UNIQUE temp then rename, so two
            // writers racing the same key (or a crash mid-write) never leave a
            // half-written .img — and we never f.delete() first, so there is no
            // window where the cover is transiently missing.
            val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
            try {
                tmp.writeBytes(bytes)
                // renameTo onto an existing target fails on some filesystems;
                // copyTo(overwrite) replaces in place without a missing-file gap.
                if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }
        trimToCap(context)
        Uri.fromFile(f)
    }.getOrNull()

    /** The file:// uri for [key] if a non-empty cache file exists, else null. */
    fun uriFor(context: Context, key: String): Uri? {
        val f = fileFor(context, key)
        if (!(f.exists() && f.length() > 0)) return null
        // Touch mtime on READ so the LRU trim (oldest-by-lastModified) treats a
        // currently-DISPLAYED cover as fresh. Without this, a long-lived pinned cover
        // (written early = oldest mtime) was the first evicted when a big Drive-folder
        // browse wrote hundreds of new covers — re-introducing the blank-artwork bug the
        // durable cache exists to prevent (found 2026-08-25). Makes it a true access-LRU.
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return Uri.fromFile(f)
    }

    /** #3 — cover art was unbounded (600 KB-1 MB each, no cap). LRU-trim to this
     *  budget on every write so the filesDir footprint can't grow without limit.
     *  Raised 32→256 MB for the browse-time cover peek: a large Drive folder can
     *  legitimately hold hundreds of covers, and a 32 MB cap evicted most of them
     *  DURING the browse (then favourite-sweep churn re-downloaded full files to
     *  restore evicted favourite art). 256 MB ≈ 250-400 covers. */
    const val CAP_BYTES: Long = 256L * 1024 * 1024

    /** #3 — delete the cached cover for [key] (called on offline-remove so the
     *  600 KB-1 MB file doesn't orphan in cacheDir until the OS evicts it). */
    fun evict(context: Context, key: String) {
        runCatching { fileFor(context, key).delete() }
    }

    /** Keep coverart/ under [CAP_BYTES]; delete oldest-by-lastModified first
     *  (mirrors ChapterCache's disk sweep). Best-effort; never fails a write. */
    fun trimToCap(context: Context) {
        runCatching {
            val files = dir(context).listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= CAP_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= CAP_BYTES) break
                total -= f.length()
                runCatching { f.delete() }
            }
        }
    }
}
