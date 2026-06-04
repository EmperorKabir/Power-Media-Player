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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    // vc29.26 — cache the URI string once. Previously each LazyColumn
    // row called `file.uri.toString()` twice per recomposition
    // (`in favourites` + `in selected`), which on a 1000-track
    // library was 2000 Uri.toString() allocations per scroll frame.
    // No @Transient annotation needed — MediaFileInfo isn't Serializable
    // / Parcelable, and body properties never participate in data
    // class equals/hashCode/copy/componentN.
    val uriStr: String = uri.toString()
}

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
    FAVORITES_FIRST,
    DURATION_DESC,   // longest first
    DURATION_ASC     // shortest first
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
    private val favoriteDao: FavoriteDao,
    private val spotifyProvider: com.powermediaplayer.cloud.SpotifyProvider,
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    val mediaOverrideDao: com.powermediaplayer.data.db.dao.MediaOverrideDao
) : ViewModel() {

    /** §C25 / A9 — write a per-file tag override. */
    fun editTags(uri: String, title: String, artist: String, album: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsDataStore.upsertTagOverride(uri, title, artist, album)
        }
    }

    /**
     * Pin an album from the library view to the Last Played pinned
     * section. Snapshots the member-track list at pin time so library
     * churn doesn't break the pin.
     *
     * Returns Result<Unit> — caller surfaces failure (cap reached) via
     * the existing snackbar host.
     */
    suspend fun pinAlbum(
        albumKey: String,
        title: String,
        artist: String,
        artworkUri: String?,
        tracks: List<MediaFileInfo>
    ): Result<Unit> {
        val payload = tracks.map {
            com.powermediaplayer.data.repository.LastPlayedRepository.AlbumTrackToPin(
                mediaUri = it.uri.toString(),
                title = it.title,
                durationMs = it.duration
            )
        }
        return lastPlayedRepo.pinAlbum(albumKey, title, artist, artworkUri, payload)
    }

    /**
     * §C7 — when the user unfavourites a row, drop any per-file
     * overrides we'd kept for it. The override-popup is starred-or-
     * pinned-only; once that gating disappears, the data should too.
     */
    fun clearOverridesIfUnfavourited(uri: String, willBeFavourite: Boolean) {
        if (!willBeFavourite) {
            viewModelScope.launch(Dispatchers.IO) {
                mediaOverrideDao.clear(uri)
            }
        }
    }

    /**
     * Stop any active Spotify Connect mirror when starting local
     * playback — otherwise the Player tab kept showing Spotify
     * metadata while the local file's audio played underneath.
     * Best-effort pause Spotify Connect side too so the user isn't
     * left with two audio streams.
     */
    private fun stopSpotifyMirrorIfActive() {
        if (spotifyProvider.spotifyState.value != null) {
            // Pause Spotify Connect FIRST (while cached state is still
            // valid — togglePlayPause reads it). Then tear down the poll.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { spotifyProvider.pause() }
            }
            spotifyProvider.stopPlaybackPolling()
        }
    }

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Raw, unsorted scan results — kept separately so changing sort or
    // favorites does not require rescanning the device.
    private var rawAudio: List<MediaFileInfo> = emptyList()
    private var rawVideo: List<MediaFileInfo> = emptyList()

    init {
        // Restore last sort mode from DataStore on startup. Survives
        // app restart, navigating away from the Library tab, etc.
        viewModelScope.launch {
            settingsDataStore.librarySortMode.collect { name ->
                val mode = runCatching { SortMode.valueOf(name) }.getOrDefault(SortMode.NAME_ASC)
                if (_uiState.value.sortMode != mode) {
                    _uiState.value = _uiState.value.copy(sortMode = mode)
                    recomputeDisplayed()
                }
            }
        }
        scanMedia()
        observeFavorites()
        observeHiddenUris()
    }

    private var hiddenUris: Set<String> = emptySet()
    private fun observeHiddenUris() {
        viewModelScope.launch {
            settingsDataStore.hiddenUris.collect { hidden ->
                hiddenUris = hidden
                recomputeDisplayed()
            }
        }
    }

    /** §C27: hide a file from Library lists without deleting it. */
    fun hideUri(uri: String) {
        viewModelScope.launch { settingsDataStore.hideUri(uri) }
    }

    /** Long-press 'Add to queue next' — insert as next track. */
    fun addToQueueNext(file: MediaFileInfo) {
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId(file.uri.toString())
            .setUri(file.uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .build()
            )
            .build()
        playbackConnection.addNext(item)
    }
    /** §C27: undo a hide. */
    fun unhideUri(uri: String) {
        viewModelScope.launch { settingsDataStore.unhideUri(uri) }
    }

    /**
     * Delete a media file from the device. ONLY call after user has
     * confirmed via a dialog — this is irreversible. Uses the
     * ContentResolver delete API which requires the user to grant
     * the deletion (system-managed picker on modern Android).
     * Returns the request to caller via callback so the screen can
     * launch any RecoverableSecurityException it throws.
     */
    fun deleteFile(uri: android.net.Uri, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrElse { false }
            // Refresh the library so the deleted row vanishes.
            if (ok) scanMedia()
            kotlinx.coroutines.withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun setSortMode(mode: SortMode) {
        _uiState.value = _uiState.value.copy(sortMode = mode)
        recomputeDisplayed()
        viewModelScope.launch { settingsDataStore.setLibrarySortMode(mode.name) }
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
                // §C7 — auto-clear per-file overrides when the gating
                // signal disappears. Locked spec: "Override values clear
                // when the file is unstarred / unpinned."
                mediaOverrideDao.clear(key)
            } else {
                favoriteDao.insert(FavoriteEntity(uri = key))
            }
        }
    }

    fun refreshMedia() {
        scanMedia()
        lastRefreshMs = System.currentTimeMillis()
    }

    /**
     * Phase 3 / §C16: stale-aware refresh used by `LibraryScreen`'s
     * `LaunchedEffect(Unit)`. Re-scans MediaStore only if the last
     * refresh was more than 30 s ago — so tab-switch + immediate
     * return doesn't double-scan. New files added on the device are
     * picked up within one tab-switch with no battery cost (no
     * continuous FileObserver).
     */
    fun refreshIfStale(thresholdMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < thresholdMs) return
        scanMedia()
        lastRefreshMs = now
    }

    @Volatile private var lastRefreshMs: Long = 0L

    // ── Multi-select mode (§C26) ──────────────────────────────────
    private val _multiSelectMode = MutableStateFlow(false)
    val multiSelectMode: StateFlow<Boolean> = _multiSelectMode.asStateFlow()
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    fun enterMultiSelect() {
        _multiSelectMode.value = true
    }

    fun exitMultiSelect() {
        _multiSelectMode.value = false
        _selectedUris.value = emptySet()
    }

    fun toggleSelection(uri: String) {
        val cur = _selectedUris.value
        _selectedUris.value = if (uri in cur) cur - uri else cur + uri
    }

    /** §F — first-run deep-scan prompt should show? */
    val firstRunSeen: kotlinx.coroutines.flow.StateFlow<Boolean> = settingsDataStore.firstRunSeen
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    /** §F — user picked Yes to deep-scan: enable + scan + mark seen. */
    fun acceptFirstRunDeepScan() {
        viewModelScope.launch {
            settingsDataStore.setDeepScan(true)
            settingsDataStore.setFirstRunSeen()
            scanMedia()
        }
    }
    /** §F — user picked Skip: just mark seen so the prompt doesn't reappear. */
    fun skipFirstRunDeepScan() {
        viewModelScope.launch { settingsDataStore.setFirstRunSeen() }
    }

    /** §C26 — select-all from the current visible tab. */
    fun selectAllVisible() {
        val visible = if (_uiState.value.selectedTab == 0)
            _uiState.value.audioFiles else _uiState.value.videoFiles
        _selectedUris.value = visible.map { it.uri.toString() }.toSet()
    }

    /** Bulk-favourite every currently-selected URI. Idempotent. */
    fun favouriteSelected() {
        val sel = _selectedUris.value
        viewModelScope.launch(Dispatchers.IO) {
            sel.forEach { uri ->
                runCatching { favoriteDao.insert(FavoriteEntity(uri = uri)) }
            }
        }
    }

    /** Bulk-hide every currently-selected URI. Also exits multi-select. */
    fun hideSelected() {
        val sel = _selectedUris.value
        viewModelScope.launch {
            sel.forEach { uri -> settingsDataStore.hideUri(uri) }
            // Hidden rows vanish from the visible list via
            // recomputeDisplayed; exit multi-select since the user is
            // visibly done with this batch.
            _selectedUris.value = emptySet()
            _multiSelectMode.value = false
        }
    }

    /** Exit multi-select mode after a successful bulk operation. */
    fun favouriteSelectedAndExit() {
        favouriteSelected()
        viewModelScope.launch {
            kotlinx.coroutines.delay(120) // let the IO writes settle
            _selectedUris.value = emptySet()
            _multiSelectMode.value = false
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteDao.observeAllUris().collect { uris ->
                _uiState.update { it.copy(favorites = uris.toSet()) }
                recomputeDisplayed()
            }
        }
    }

    private fun recomputeDisplayed() {
        // Read-modify-write inside update {} so a concurrent
        // search-bar keystroke or sort-change collector can't lose
        // its emission to this rewrite.
        _uiState.update { state ->
            val q = TextNormalizer.normalize(state.searchQuery).lowercase()
            val hidden = hiddenUris
            val filterFn: (MediaFileInfo) -> Boolean = { f ->
                val notHidden = f.uri.toString() !in hidden
                val matchesQuery = if (q.isBlank()) true else {
                    val hay = TextNormalizer.normalize("${f.title} ${f.artist} ${f.album}").lowercase()
                    hay.contains(q)
                }
                notHidden && matchesQuery
            }
            state.copy(
                audioFiles = applySort(rawAudio.filter(filterFn), state.sortMode, state.favorites),
                videoFiles = applySort(rawVideo.filter(filterFn), state.sortMode, state.favorites)
            )
        }
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
            SortMode.DURATION_DESC -> compareByDescending { it.duration }
            SortMode.DURATION_ASC -> compareBy { it.duration }
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
        stopSpotifyMirrorIfActive()
        // vc32: new play intent — supersedes any in-flight slow resume.
        com.powermediaplayer.playback.ResumeGate.end(com.powermediaplayer.playback.ResumeGate.begin())
        // M4bChapterParser inside createMediaItems opens MediaExtractor
        // + reads the entire MP4 box hierarchy synchronously — visible
        // jank on tap when the file is a multi-GB audiobook. Parse off
        // Main, then hop back for the MediaController call.
        viewModelScope.launch {
            // vc32: banner over the pre-player parse phase.
            playbackConnection.setCloudFetchInProgress(true)
            try {
                val (items, idx) = withContext(Dispatchers.IO) {
                    createMediaItems(files, startIndex)
                }
                playbackConnection.setMediaItems(items, idx)
                playbackConnection.setVideoModeHint(files.getOrNull(idx)?.isVideo == true)
                files.getOrNull(idx)?.let { recordLocalPlay(it) }
            } catch (t: Throwable) {
                com.powermediaplayer.util.Diag.e("PowerMediaPlayer", "playFiles failed", t)
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
            }
        }
    }

    /**
     * Single-file playback — for videos we don't want every file in the
     * folder to surface as a "playlist / album" with the Full slider and
     * X / N counter. Audio still uses [playFiles] so albums and audiobook
     * folders work as expected.
     */
    fun playSingle(file: MediaFileInfo) {
        stopSpotifyMirrorIfActive()
        // vc32: new play intent — supersedes any in-flight slow resume.
        com.powermediaplayer.playback.ResumeGate.end(com.powermediaplayer.playback.ResumeGate.begin())
        viewModelScope.launch {
            // vc32: banner over the pre-player parse phase.
            playbackConnection.setCloudFetchInProgress(true)
            try {
                val item = withContext(Dispatchers.IO) {
                    val extras = com.powermediaplayer.util.M4bChapterParser
                        .extractChaptersAsBundle(context, file.uri)
                    extras.putBoolean("is_video_hint", file.isVideo)
                    MediaItem.Builder()
                        .setMediaId(file.uri.toString())
                        .setUri(file.uri)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(file.uri).build()
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
                playbackConnection.setMediaItems(listOf(item), 0)
                playbackConnection.setVideoModeHint(file.isVideo)
                recordLocalPlay(file)
            } catch (t: Throwable) {
                com.powermediaplayer.util.Diag.e("PowerMediaPlayer", "playSingle failed", t)
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
            }
        }
    }

    /**
     * Treat a folder of media files as a single audiobook: sort naturally
     * (so file_2 < file_10), build absolute folder-wide chapters with
     * [FolderChapterAggregator], and play. Cross-file chapter navigation
     * works through PlaybackConnection.seekToFolderChapter.
     */
    fun playFolder(files: List<MediaFileInfo>, startIndex: Int = 0) {
        stopSpotifyMirrorIfActive()
        // vc32: new play intent — supersedes any in-flight slow resume.
        com.powermediaplayer.playback.ResumeGate.end(com.powermediaplayer.playback.ResumeGate.begin())
        viewModelScope.launch {
            // vc32: banner over the pre-player parse phase
            // (folder aggregation parses EVERY file — the slowest path).
            playbackConnection.setCloudFetchInProgress(true)
            try {
                val sorted = FolderChapterAggregator.naturalSort(files)
                val (items, idx) = withContext(Dispatchers.IO) {
                    createMediaItems(sorted, startIndex)
                }
                playbackConnection.setMediaItems(items, idx)
                val chapters = withContext(Dispatchers.IO) {
                    FolderChapterAggregator.aggregate(sorted)
                }
                if (chapters.isNotEmpty()) {
                    playbackConnection.setFolderChapters(chapters)
                }
                sorted.getOrNull(idx)?.let { recordLocalPlay(it) }
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
            }
        }
    }

    private fun recordLocalPlay(file: MediaFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                lastPlayedRepo.recordPlay(
                    com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                        mediaUri = file.uri.toString(),
                        title = file.title.ifBlank { file.uri.lastPathSegment.orEmpty() },
                        subtitle = file.artist.ifBlank { "Local file" },
                        artworkUri = file.albumArtUri?.toString(),
                        source = "LOCAL",
                        mediaKindOrdinal = 0,
                        lastPositionMs = 0L,
                        durationMs = file.duration,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
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
            // Auto-play the picked file — previously the user picked a file
            // and nothing happened; they had to manually find it in the list.
            withContext(Dispatchers.Main) { playSingle(fileInfo) }
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

