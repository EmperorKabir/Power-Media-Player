package com.powermediaplayer.cloud

import android.net.Uri

enum class CloudProviderType {
    GOOGLE_DRIVE,
    SPOTIFY,
    ONE_DRIVE,
    DROPBOX
}

data class CloudMediaItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val downloadUrl: String, // Or API endpoint for streaming
    val thumbnailUri: Uri? = null,
    val sourceProvider: CloudProviderType,
    val isFolder: Boolean = false,
    val parentId: String? = null,
    // Spotify-only: the spotify:album:XX or spotify:playlist:XX URI
    // the track was discovered in. Passed as context_uri to
    // /me/player/play so /next and /previous work within the album
    // or playlist instead of stopping after this single track.
    val contextUri: String? = null
)
