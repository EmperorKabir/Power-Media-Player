package com.powermediaplayer.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Durable home for "Download for offline use" copies when the user has NOT
 * configured a SAF offline folder.
 *
 * Issue 5 (2026-07-28): the FULL offline download now stages into
 * `filesDir/offline/tmp/drive_<id.hashCode()>_full` (persistent) rather than
 * `cacheDir`, so a cache-wipe app or OS cache pressure can't destroy an in-flight
 * or finished partial. [toDurable] then renameTo-moves the finished staging file
 * into `filesDir/offline/<sha256(id)>.ext` — the stable, collision-safe home the
 * DB row points at. Both dirs are on the internal volume, so the rename is atomic
 * and instant (no multi-GB copy). A different dir + name than the enricher's
 * `cacheDir/drive_<id.hashCode()>_full` temp, so enrichment never deletes it.
 */
object OfflineStorage {

    /**
     * Move [cacheFile] from the volatile app-cache into persistent `filesDir/offline/`.
     * Uses `renameTo` (atomic, no copy) since cache + files share the internal
     * filesystem; copy+delete only if the rename fails. Returns `(path, bytes)`;
     * falls back to the original cache path only if the move itself errors.
     *
     * `suspend` + `Dispatchers.IO`: this does blocking file IO and, on the rare
     * cross-device-rename failure, an up-to-multi-GB copy — it must never run on Main.
     */
    suspend fun toDurable(context: Context, cacheFile: File, driveId: String, name: String): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "offline").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "")
                    .lowercase()
                    .takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                // SHA-256 (not 32-bit String.hashCode): two distinct Drive ids could
                // collide on a 32-bit hash → the second download would overwrite the
                // first's durable copy (silent data-loss; its DB row then points at the
                // wrong bytes). Matches ChapterCache.fileFor / ArtworkCache.keyHash.
                val dest = File(dir, sha(driveId).take(24) + (ext?.let { ".$it" } ?: ""))
                if (dest.exists()) dest.delete()
                if (!cacheFile.renameTo(dest)) {
                    cacheFile.copyTo(dest, overwrite = true)
                    cacheFile.delete()
                }
                dest.absolutePath to dest.length()
            }.getOrDefault(cacheFile.absolutePath to cacheFile.length())
        }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
