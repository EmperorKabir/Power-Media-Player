package com.powermediaplayer.ui.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a scanned media file from the device.
 */
data class MediaFileInfo(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val isVideo: Boolean,
    val albumArtUri: Uri? = null
)

/**
 * UI state for the media library screen.
 */
data class LibraryUiState(
    val audioFiles: List<MediaFileInfo> = emptyList(),
    val videoFiles: List<MediaFileInfo> = emptyList(),
    val isLoading: Boolean = true,
    val selectedTab: Int = 0 // 0 = Audio, 1 = Video
)

/**
 * ViewModel for the Library screen.
 * Scans the device for audio and video files using MediaStore.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        scanMedia()
    }

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun refreshMedia() {
        scanMedia()
    }

    /**
     * Create Media3 MediaItems from a list of media files for playback.
     */
    fun createMediaItems(files: List<MediaFileInfo>, startIndex: Int = 0): Pair<List<MediaItem>, Int> {
        val items = files.map { file ->
            MediaItem.Builder()
                .setMediaId(file.uri.toString())
                .setUri(file.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(file.title)
                        .setArtist(file.artist)
                        .setAlbumTitle(file.album)
                        .setArtworkUri(file.albumArtUri)
                        .build()
                )
                .build()
        }
        return Pair(items, startIndex)
    }

    private fun scanMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val audioFiles = scanAudioFiles()
            val videoFiles = scanVideoFiles()

            _uiState.value = _uiState.value.copy(
                audioFiles = audioFiles,
                videoFiles = videoFiles,
                isLoading = false
            )
        }
    }

    private fun scanAudioFiles(): List<MediaFileInfo> {
        val files = mutableListOf<MediaFileInfo>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                files.add(
                    MediaFileInfo(
                        id = id,
                        uri = contentUri,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        album = cursor.getString(albumCol) ?: "Unknown Album",
                        duration = cursor.getLong(durationCol),
                        mimeType = cursor.getString(mimeCol) ?: "",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        isVideo = false,
                        albumArtUri = albumArtUri
                    )
                )
            }
        }

        return files
    }

    private fun scanVideoFiles(): List<MediaFileInfo> {
        val files = mutableListOf<MediaFileInfo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Video.Media.TITLE} ASC"

        context.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)

                files.add(
                    MediaFileInfo(
                        id = id,
                        uri = contentUri,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "",
                        album = "",
                        duration = cursor.getLong(durationCol),
                        mimeType = cursor.getString(mimeCol) ?: "",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        isVideo = true
                    )
                )
            }
        }

        return files
    }

    /**
     * Handle a file picked via SAF (ACTION_OPEN_DOCUMENT).
     * Creates a MediaFileInfo from the picked URI and adds it to the appropriate list.
     */
    fun handlePickedFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileInfo = resolvePickedFile(uri) ?: return@launch

            val current = _uiState.value
            if (fileInfo.isVideo) {
                _uiState.value = current.copy(
                    videoFiles = listOf(fileInfo) + current.videoFiles,
                    selectedTab = 1
                )
            } else {
                _uiState.value = current.copy(
                    audioFiles = listOf(fileInfo) + current.audioFiles,
                    selectedTab = 0
                )
            }
        }
    }

    /**
     * Create a single MediaItem from a picked file URI for immediate playback.
     */
    fun createSingleMediaItem(uri: Uri): MediaItem {
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .build()
    }

    /**
     * Resolve metadata from a SAF-picked file URI using ContentResolver.
     */
    private fun resolvePickedFile(uri: Uri): MediaFileInfo? {
        return try {
            val cursor = context.contentResolver.query(
                uri, null, null, null, null
            ) ?: return null

            cursor.use {
                if (!it.moveToFirst()) return null

                val displayName = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    .takeIf { idx -> idx >= 0 }?.let { idx -> it.getString(idx) } ?: "Unknown"

                val size = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    .takeIf { idx -> idx >= 0 }?.let { idx -> it.getLong(idx) } ?: 0L

                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isVideo = mimeType.startsWith("video/")

                // Use MediaMetadataRetriever for duration and tags
                val retriever = android.media.MediaMetadataRetriever()
                var title = displayName
                var artist = ""
                var album = ""
                var duration = 0L

                try {
                    retriever.setDataSource(context, uri)
                    title = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
                    ) ?: displayName
                    artist = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
                    ) ?: ""
                    album = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
                    ) ?: ""
                    duration = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }

                MediaFileInfo(
                    id = uri.hashCode().toLong(),
                    uri = uri,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    mimeType = mimeType,
                    size = size,
                    dateModified = System.currentTimeMillis() / 1000,
                    isVideo = isVideo
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

