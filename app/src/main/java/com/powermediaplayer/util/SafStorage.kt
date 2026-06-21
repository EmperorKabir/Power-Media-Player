package com.powermediaplayer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * §C10/§C28 — Storage Access Framework WRITE helpers. The app already READS
 * user-picked trees (GoogleDriveProvider). This is the write side: persist a
 * tree grant across process death, materialise nested sub-folders, create /
 * overwrite a child file, stream bytes in, query, and delete.
 *
 * Every call is failure-tolerant — a revoked grant, a deleted tree, or a
 * provider that rejects the op returns null / false / 0 instead of throwing,
 * so a download attempt against stale storage degrades to "not downloaded"
 * rather than crashing the worker or the UI.
 */
object SafStorage {

    /** READ|WRITE flags an ACTION_OPEN_DOCUMENT_TREE result must carry. */
    const val TREE_RW_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Persist a picked tree so the grant survives process death + reboot. */
    fun persistTree(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, TREE_RW_FLAGS)
        true
    }.getOrDefault(false)

    /** True iff we still hold a persisted WRITE grant on [treeUri]. */
    fun hasWriteAccess(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
    }.getOrDefault(false)

    /** Release a persisted grant (when the user re-points or clears a folder). */
    fun releaseTree(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri, TREE_RW_FLAGS)
        }
    }

    /** Find-or-create the nested [segments] folder chain under [treeUri]. */
    fun resolveDir(context: Context, treeUri: Uri, vararg segments: String): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (raw in segments) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val existing = dir.findFile(name)
            dir = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> return null          // a file blocks the folder name
                else -> dir.createDirectory(name) ?: return null
            }
        }
        return dir
    }

    /**
     * Create [displayName] (mime [mime]) in [dir], replacing any existing child
     * of the same name first so re-downloads never accumulate "name (1)" copies.
     */
    fun createChild(dir: DocumentFile, displayName: String, mime: String): DocumentFile? {
        runCatching { dir.findFile(displayName)?.takeIf { it.exists() }?.delete() }
        return runCatching { dir.createFile(mime, displayName) }.getOrNull()
    }

    /** Stream [input] into [fileUri] (truncating); returns bytes written. */
    fun writeStream(context: Context, fileUri: Uri, input: InputStream): Long = runCatching {
        context.contentResolver.openOutputStream(fileUri, "w").use { out ->
            if (out == null) 0L else input.copyTo(out)
        }
    }.getOrDefault(0L)

    /** Delete a previously created document (content:// or file://). */
    fun delete(context: Context, fileUri: Uri): Boolean = runCatching {
        when (fileUri.scheme) {
            "content" -> DocumentFile.fromSingleUri(context, fileUri)?.delete() ?: false
            "file", null -> java.io.File(fileUri.path ?: return false).delete()
            else -> false
        }
    }.getOrDefault(false)

    /** True if [fileUri] still resolves to an existing document. */
    fun exists(context: Context, fileUri: Uri): Boolean = runCatching {
        when (fileUri.scheme) {
            "content" -> DocumentFile.fromSingleUri(context, fileUri)?.exists() == true
            "file", null -> java.io.File(fileUri.path ?: return false).exists()
            else -> false
        }
    }.getOrDefault(false)

    /** Human-readable name of a picked tree, for the settings/per-show UI. */
    fun treeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        DocumentFile.fromTreeUri(context, treeUri)?.name
    }.getOrNull()

    /**
     * Cheap label from a tree uri's document id WITHOUT a provider query (safe
     * to call in composition): "…/tree/primary%3APodcasts" → "Podcasts".
     */
    fun treeLabel(treeUri: Uri): String = runCatching {
        val id = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: treeUri.lastPathSegment ?: return "Custom folder"
        id.substringAfterLast(':').substringAfterLast('/').ifBlank { id }
    }.getOrDefault("Custom folder")
}
