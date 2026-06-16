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
 * Stored under cacheDir/coverart/<sha256>.img. Audit hardening: a full SHA-256
 * key (not the 32-bit String.hashCode, which collides) and an atomic temp-then-
 * rename write (no torn file if two enrichers race the same key).
 */
object ArtworkCache {
    private fun dir(context: Context) = File(context.cacheDir, "coverart").apply { mkdirs() }

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
        Uri.fromFile(f)
    }.getOrNull()

    /** The file:// uri for [key] if a non-empty cache file exists, else null. */
    fun uriFor(context: Context, key: String): Uri? {
        val f = fileFor(context, key)
        return if (f.exists() && f.length() > 0) Uri.fromFile(f) else null
    }
}
