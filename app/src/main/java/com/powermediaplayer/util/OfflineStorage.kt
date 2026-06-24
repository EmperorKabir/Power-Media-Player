package com.powermediaplayer.util

import android.content.Context
import java.io.File

/**
 * Durable home for "Download for offline use" copies when the user has NOT
 * configured a SAF offline folder.
 *
 * The download paths fetch into the app **cache** (`cacheDir/drive_<id.hashCode()>_full`).
 * Leaving an offline copy there is a trap on two counts:
 *   1. `cacheDir` is cleared by Android under storage pressure → the "downloaded"
 *      file silently disappears and playback falls back to slow re-streaming.
 *   2. The Drive metadata enricher's temp download for the SAME file uses the
 *      IDENTICAL name (`drive_<id.hashCode()>_full`) and DELETES it after reading
 *      the tags — so enriching a cache-stored offline copy destroys it.
 *
 * [toDurable] moves the finished download into `filesDir/offline/` — persistent
 * (never OS-evicted) and a different directory + name than the enricher's temp,
 * so the offline copy survives both hazards.
 */
object OfflineStorage {

    /**
     * Move [cacheFile] from the volatile app-cache into persistent `filesDir/offline/`.
     * Uses `renameTo` (atomic, no copy) since cache + files share the internal
     * filesystem; copy+delete only if the rename fails. Returns `(path, bytes)`;
     * falls back to the original cache path only if the move itself errors.
     */
    fun toDurable(context: Context, cacheFile: File, driveId: String, name: String): Pair<String, Long> =
        runCatching {
            val dir = File(context.filesDir, "offline").apply { mkdirs() }
            val ext = name.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            val dest = File(dir, driveId.hashCode().toString() + (ext?.let { ".$it" } ?: ""))
            if (dest.exists()) dest.delete()
            if (!cacheFile.renameTo(dest)) {
                cacheFile.copyTo(dest, overwrite = true)
                cacheFile.delete()
            }
            dest.absolutePath to dest.length()
        }.getOrDefault(cacheFile.absolutePath to cacheFile.length())
}
