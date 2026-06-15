package com.powermediaplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Durable on-disk cache for embedded cover art. The 600 KB-1 MB bytes pulled out
 * of an m4b live only in the transient session metadata override, so the cover
 * loaded intermittently (a race) and Last Played thumbnails were blank (the row
 * stored the often-null Drive thumbnail). Writing the bytes to a stable file
 * keyed by the media uri lets the player, cold-start restore, AND the Last
 * Played list all point at the same persistent image.
 *
 * Stored under cacheDir/coverart/<hash>.img — cacheDir can be evicted under
 * storage pressure, but it survives normal process death, which is what the
 * resume + list paths need.
 */
object ArtworkCache {
    private fun dir(context: Context) = File(context.cacheDir, "coverart").apply { mkdirs() }
    private fun fileFor(context: Context, key: String) =
        File(dir(context), "${key.hashCode()}.img")

    /** Write [bytes] for [key]; returns the file:// uri, or null on failure. */
    fun write(context: Context, key: String, bytes: ByteArray): Uri? = runCatching {
        val f = fileFor(context, key)
        if (!(f.exists() && f.length() == bytes.size.toLong())) f.writeBytes(bytes)
        Uri.fromFile(f)
    }.getOrNull()

    /** The file:// uri for [key] if a non-empty cache file exists, else null. */
    fun uriFor(context: Context, key: String): Uri? {
        val f = fileFor(context, key)
        return if (f.exists() && f.length() > 0) Uri.fromFile(f) else null
    }
}
