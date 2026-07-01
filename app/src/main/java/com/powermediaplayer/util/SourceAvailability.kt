package com.powermediaplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Cheap "is this playback source still reachable?" probe. Used before auto-resuming the most
 * recent item on launch: a local file the user has since deleted or moved would otherwise be
 * handed to ExoPlayer, raising FILE_NOT_FOUND and a banner. Remote streams are treated as
 * available (they cannot be probed cheaply; the network and HTTP error paths handle their
 * failures). Unknown schemes are not blocked.
 */
object SourceAvailability {

    fun exists(context: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "file" -> runCatching { File(uri.path ?: return false).exists() }.getOrDefault(false)
        "content" -> {
            // Prefer an AFD probe; fall back to openInputStream so a provider that supports streams
            // but not asset-file-descriptors is not mis-read as "gone" (a false negative would
            // silently skip an otherwise-playable auto-resume).
            val viaAfd = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            }.getOrDefault(false)
            if (viaAfd) true
            else runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } == true
            }.getOrDefault(false)
        }
        "http", "https" -> true
        null -> runCatching { File(uri.toString()).exists() }.getOrDefault(false)
        else -> true
    }
}
