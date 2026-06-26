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
    // Secondary line for result rows: track/album → artist(s); show →
    // publisher; episode → show name; playlist → owner. Blank when N/A.
    val subtitle: String = "",
    // Spotify-only: the spotify:album:XX or spotify:playlist:XX URI
    // the track was discovered in. Passed as context_uri to
    // /me/player/play so /next and /previous work within the album
    // or playlist instead of stopping after this single track.
    val contextUri: String? = null,
    // Spotify-only result kind for the type tag in search/browse rows:
    // "track" | "single" | "album" | "compilation" | "artist" |
    // "playlist" | "show" | "episode". Null for non-Spotify items.
    val spotifyType: String? = null
)
