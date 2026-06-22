package com.powermediaplayer.cloud

import android.content.Context
import android.net.Uri
import com.powermediaplayer.data.db.dao.OfflineCopyDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §C28 — maps a Drive item's STREAM mediaUri (the Recents/cold-start key) to its
 * downloaded offline copy, so EVERY resume path (Last Played tap, cold-start
 * auto-resume) can play the LOCAL file — instant start, works offline, and the
 * enricher reads tags/chapters locally. Mirrors PodcastOfflineResolver.
 */
@Singleton
class DriveOfflineResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val offlineCopyDao: OfflineCopyDao
) {
    /** Local uri for [mediaUri] if a readable offline copy exists, else null. */
    suspend fun localUriFor(mediaUri: String): Uri? {
        if (mediaUri.isBlank()) return null
        val driveId = driveIdFor(mediaUri) ?: return null
        val row = offlineCopyDao.get(driveId) ?: return null
        val path = row.localPath.takeIf { it.isNotBlank() } ?: return null
        val readable = runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
            } else {
                java.io.File(path).let { it.exists() && it.canRead() }
            }
        }.getOrDefault(false)
        if (!readable) return null
        return if (path.startsWith("content://")) Uri.parse(path)
        else Uri.fromFile(java.io.File(path))
    }

    /**
     * Map a Recents/stream mediaUri back to the offline_copy key (driveFileId):
     * OAuth Drive stores `https://…/files/{id}?alt=media` → the {id}; SAF stores
     * the `content://` uri itself (== the driveFileId).
     */
    private fun driveIdFor(mediaUri: String): String? = when {
        mediaUri.startsWith("content://") -> mediaUri
        else -> Regex("/files/([^?]+)").find(mediaUri)?.groupValues?.getOrNull(1)
    }
}
