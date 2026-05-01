package com.powermediaplayer.cloud

import android.net.Uri

enum class CloudProviderType {
    GOOGLE_DRIVE,
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
    val parentId: String? = null
)
