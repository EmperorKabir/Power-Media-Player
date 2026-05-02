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
import com.powermediaplayer.data.db.dao.FavoriteDao
import com.powermediaplayer.data.db.entity.FavoriteEntity
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.util.FolderChapterAggregator
import com.powermediaplayer.util.TextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * Sort modes available in the library. NAME uses locale-aware Collator
 * via TextNormalizer so "É" sorts with "E" and curly apostrophes match
 * straight ones.
 */
enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
    TYPE,
    DATE_DESC,
    FAVORITES_FIRST
}

/**
 * UI state for the media library screen. [audioFiles] and [videoFiles]
 * are already sorted/filtered for display; the raw scan results live
 * privately in the ViewModel.
 */
data class LibraryUiState(
    val audioFiles: List<MediaFileInfo> = emptyList(),
    val videoFiles: List<MediaFileInfo> = emptyList(),
    val isLoading: Boolean = true,
    val selectedTab: Int = 0, // 0 = Audio, 1 = Video
    val sortMode: SortMode = SortMode.NAME_ASC,
    val favorites: Set<String> = emptySet(),
    val searchQuery: String = ""
)

/**
 * ViewModel for the Library screen.
 * Scans the device for audio and video files using MediaStore.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackConnection: PlaybackConnection,
    private val favoriteDao: FavoriteDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Raw, unsorted scan results — kept separately so changing sort or
    // favorites does not require rescanning the device.
    private var rawAudio: List<MediaFileInfo> = emptyList()
    private var rawVideo: List<MediaFileInfo> = emptyList()

    init {
        scanMedia()
        observeFavorites()
    }

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun setSortMode(mode: SortMode) {
        _uiState.value = _uiState.value.copy(sortMode = mode)
        recomputeDisplayed()
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        recomputeDisplayed()
    }

    fun toggleFavorite(uri: Uri) {
        val key = uri.toString()
        viewModelScope.launch(Dispatchers.IO) {
            if (favoriteDao.isFavorite(key)) {
                favoriteDao.deleteByUri(key)
            } else {
                favoriteDao.insert(FavoriteEntity(uri = key))
            }
        }
    }

    fun refreshMedia() {
        scanMedia()
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteDao.observeAllUris().collect { uris ->
                _uiState.value = _uiState.value.copy(favorites = uris.toSet())
                recomputeDisplayed()
            }
        }
    }

    private fun recomputeDisplayed() {
        val state = _uiState.value
        val q = TextNormalizer.normalize(state.searchQuery).lowercase()
        val filterFn: (MediaFileInfo) -> Boolean = if (q.isBlank()) { _ -> true } else { f ->
            // Case- and accent-insensitive match across title + artist + album
            val hay = TextNormalizer.normalize("${f.title} ${f.artist} ${f.album}").lowercase()
            hay.contains(q)
        }
        _uiState.value = state.copy(
            audioFiles = applySort(rawAudio.filter(filterFn), state.sortMode, state.favorites),
            videoFiles = applySort(rawVideo.filter(filterFn), state.sortMode, state.favorites)
        )
    }

    private fun applySort(
        files: List<MediaFileInfo>,
        mode: SortMode,
        favorites: Set<String>
    ): List<MediaFileInfo> {
        val byMode: Comparator<MediaFileInfo> = when (mode) {
            SortMode.NAME_ASC ->
                Comparator { a, b -> TextNormalizer.compare(a.title, b.title) }
            SortMode.NAME_DESC ->
                Comparator { a, b -> TextNormalizer.compare(b.title, a.title) }
            SortMode.SIZE_ASC -> compareBy { it.size }
            SortMode.SIZE_DESC -> compareByDescending { it.size }
            SortMode.TYPE -> compareBy({ it.mimeType }, { TextNormalizer.normalize(it.title) })
            SortMode.DATE_DESC -> compareByDescending { it.dateModified }
            SortMode.FAVORITES_FIRST -> Comparator { a, b ->
                val aFav = a.uri.toString() in favorites
                val bFav = b.uri.toString() in favorites
                when {
                    aFav && !bFav -> -1
                    !aFav && bFav -> 1
                    else -> TextNormalizer.compare(a.title, b.title)
                }
            }
        }
        return files.sortedWith(byMode)
    }

    /**
     * Create Media3 MediaItems from a list of media files for playback.
     */
    fun createMediaItems(files: List<MediaFileInfo>, startIndex: Int = 0): Pair<List<MediaItem>, Int> {
        val items = files.mapIndexed { idx, file ->
            // Chapter extraction opens MediaExtractor on each URI — slow
            // when the visible list is 40+ items. Only run it for the
            // tapped item; the rest get a hint-only Bundle so the player
            // starts immediately. Chapters for other queue items will be
            // parsed lazily on item transition (cheaper than blocking the
            // tap-to-play flow).
            val extras = if (idx == startIndex) {
                com.powermediaplayer.util.M4bChapterParser.extractChaptersAsBundle(context, file.uri)
            } else {
                android.os.Bundle()
            }
            // Video hint travels in extras so PlaybackConnection knows the
            // file is video before the player has finished parsing tracks
            // (the existing currentTracks-based detection races with the
            // first compose pass and leaves the UI on the audio layout).
            extras.putBoolean("is_video_hint", file.isVideo)
            MediaItem.Builder()
                .setMediaId(file.uri.toString())
                .setUri(file.uri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(file.uri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(file.title)
                        .setArtist(file.artist)
                        .setAlbumTitle(file.album)
                        .setArtworkUri(file.albumArtUri)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }
        return Pair(items, startIndex)
    }

    /**
     * Start playback of [files] starting at [startIndex].
     * Directly calls PlaybackConnection so nothing is left as a TODO.
     */
    fun playFiles(files: List<MediaFileInfo>, startIndex: Int) {
        try {
            val (items, idx) = createMediaItems(files, startIndex)
            playbackConnection.setMediaItems(items, idx)
            // Authoritative video flag — caller knows the file is video, no
            // need to wait for ExoPlayer track parsing or worry about extras
            // surviving Media3 metadata combine.
            playbackConnection.setVideoModeHint(files.getOrNull(idx)?.isVideo == true)
        } catch (t: Throwable) {
            android.util.Log.e("PowerMediaPlayer", "playFiles failed", t)
        }
    }

    /**
     * Single-file playback — for videos we don't want every file in the
     * folder to surface as a "playlist / album" with the Full slider and
     * X / N counter. Audio still uses [playFiles] so albums and audiobook
     * folders work as expected.
     */
    fun playSingle(file: MediaFileInfo) {
        try {
            val extras = com.powermediaplayer.util.M4bChapterParser
                .extractChaptersAsBundle(context, file.uri)
            extras.putBoolean("is_video_hint", file.isVideo)
            val item = MediaItem.Builder()
                .setMediaId(file.uri.toString())
                .setUri(file.uri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(file.uri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(file.title)
                        .setArtist(file.artist)
                        .setAlbumTitle(file.album)
                        .setArtworkUri(file.albumArtUri)
                        .setExtras(extras)
                        .build()
                )
                .build()
            playbackConnection.setMediaItems(listOf(item), 0)
            playbackConnection.setVideoModeHint(file.isVideo)
        } catch (t: Throwable) {
            android.util.Log.e("PowerMediaPlayer", "playSingle failed", t)
        }
    }

    /**
     * Treat a folder of media files as a single audiobook: sort naturally
     * (so file_2 < file_10), build absolute folder-wide chapters with
     * [FolderChapterAggregator], and play. Cross-file chapter navigation
     * works through PlaybackConnection.seekToFolderChapter.
     */
    fun playFolder(files: List<MediaFileInfo>, startIndex: Int = 0) {
        val sorted = FolderChapterAggregator.naturalSort(files)
        val (items, idx) = createMediaItems(sorted, startIndex)
        playbackConnection.setMediaItems(items, idx)
        // setMediaItems clears any prior override — apply the new one after.
        val chapters = FolderChapterAggregator.aggregate(sorted)
        if (chapters.isNotEmpty()) {
            playbackConnection.setFolderChapters(chapters)
        }
    }

    private fun scanMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)

            rawAudio = scanAudioFiles()
            rawVideo = scanVideoFiles()

            val state = _uiState.value
            _uiState.value = state.copy(
                audioFiles = applySort(rawAudio, state.sortMode, state.favorites),
                videoFiles = applySort(rawVideo, state.sortMode, state.favorites),
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

            if (fileInfo.isVideo) {
                rawVideo = listOf(fileInfo) + rawVideo
                _uiState.value = _uiState.value.copy(selectedTab = 1)
            } else {
                rawAudio = listOf(fileInfo) + rawAudio
                _uiState.value = _uiState.value.copy(selectedTab = 0)
            }
            recomputeDisplayed()
        }
    }

    /**
     * Create a single MediaItem from a picked file URI for immediate playback.
     */
    fun createSingleMediaItem(uri: Uri): MediaItem {
        val extras = com.powermediaplayer.util.M4bChapterParser.extractChaptersAsBundle(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        extras.putBoolean("is_video_hint", mime.startsWith("video/"))
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(extras)
                    .build()
            )
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

