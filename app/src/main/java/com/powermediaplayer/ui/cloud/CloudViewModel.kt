package com.powermediaplayer.ui.cloud

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.powermediaplayer.cloud.CloudMediaItem
import com.powermediaplayer.cloud.CloudProviderType
import com.powermediaplayer.cloud.GoogleDriveProvider
import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.data.preferences.DriveFavouriteFolder
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.service.LocalMetadataOverride
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.util.M4bChapterParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudUiState(
    val driveLoggedIn: Boolean = false,
    val spotifyLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val activeProvider: CloudProviderType? = null,
    val items: List<CloudMediaItem> = emptyList(),
    val folderStack: List<Pair<String?, String>> = listOf(null to "Root"),
    val driveFavourites: List<DriveFavouriteFolder> = emptyList(),
    val driveFavouriteTracks: List<DriveFavouriteFolder> = emptyList(),
    val spotifyFavTracks: List<com.powermediaplayer.data.preferences.SpotifyFavourite> = emptyList(),
    val spotifyFavAlbums: List<com.powermediaplayer.data.preferences.SpotifyFavourite> = emptyList(),
    val spotifyFavPodcasts: List<com.powermediaplayer.data.preferences.SpotifyFavourite> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<CloudMediaItem> = emptyList(),
    val spotifySection: com.powermediaplayer.cloud.SpotifySection? = null,
    /**
     * Snapshot of source-picker roots (Drive, OneDrive, internal
     * storage, USB-OTG, …) populated when the user taps "Pick a
     * folder" so the cloud screen can show a chooser instead of
     * relying on the SAF picker's drawer (which is unreachable on
     * some Samsung / fold builds).
     */
    val pickerRoots: List<GoogleDriveProvider.CloudRoot> = emptyList(),
    val pickerRootsVisible: Boolean = false
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val driveProvider: GoogleDriveProvider,
    private val driveOAuthProvider: com.powermediaplayer.cloud.DriveOAuthProvider,
    private val spotifyProvider: SpotifyProvider,
    private val playbackConnection: PlaybackConnection,
    private val settingsDataStore: SettingsDataStore,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository
) : ViewModel() {

    private fun recordCloudPlay(item: CloudMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val source = when (item.sourceProvider) {
                    CloudProviderType.SPOTIFY -> "SPOTIFY"
                    CloudProviderType.GOOGLE_DRIVE -> "DRIVE"
                    else -> "DRIVE"
                }
                val uri = if (item.downloadUrl.isNotBlank()) item.downloadUrl else item.id
                lastPlayedRepo.recordPlay(
                    com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                        mediaUri = uri,
                        title = item.name,
                        subtitle = source,
                        artworkUri = item.thumbnailUri?.toString(),
                        source = source,
                        mediaKindOrdinal = 0,
                        lastPositionMs = 0L,
                        durationMs = 0L,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        // Six concurrent collectors all writing _uiState — switched to
        // StateFlow.update {} to atomically read-modify-write so two
        // simultaneous emissions can't drop each other's changes.
        viewModelScope.launch {
            combine(
                driveProvider.isLoggedIn,
                driveOAuthProvider.isLoggedIn,
                spotifyProvider.isLoggedIn
            ) { saf, drive, sp -> Triple(saf || drive, sp, Unit) }
                .collect { (anyDriveSource, sp, _) ->
                    _uiState.update { it.copy(driveLoggedIn = anyDriveSource, spotifyLoggedIn = sp) }
                }
        }
        viewModelScope.launch {
            settingsDataStore.driveFavouriteFolders.collect { favs ->
                _uiState.update { it.copy(driveFavourites = favs) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.driveFavouriteTracks.collect { favs ->
                _uiState.update { it.copy(driveFavouriteTracks = favs) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.spotifyFavouriteTracks.collect { favs ->
                _uiState.update { it.copy(spotifyFavTracks = favs) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.spotifyFavouriteAlbums.collect { favs ->
                _uiState.update { it.copy(spotifyFavAlbums = favs) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.spotifyFavouritePodcasts.collect { favs ->
                _uiState.update { it.copy(spotifyFavPodcasts = favs) }
            }
        }
    }

    fun toggleDriveFavouriteTrack(item: CloudMediaItem) {
        if (item.isFolder || item.sourceProvider != CloudProviderType.GOOGLE_DRIVE) return
        viewModelScope.launch(Dispatchers.IO) {
            settingsDataStore.toggleDriveFavouriteTrack(item.id, item.name)
        }
    }

    /**
     * Play a Drive favourite track from the Cloud root strip. The
     * favourite entry only has `id + name` so we build a full
     * [CloudMediaItem] either from the SAF content URI directly (id
     * starts with `content://`) or by fetching metadata via the Drive
     * REST API.
     */
    fun playDriveFavouriteTrack(
        id: String,
        name: String,
        onPlaybackStarted: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = if (id.startsWith("content://")) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = when {
                    ext in setOf("mp3","flac","ogg","oga","opus","wav","aac",
                        "m4a","m4b","m4p","aiff","aif","ape","wma") -> "audio/${ext}"
                    ext in setOf("mp4","m4v","mkv","webm","mov","avi","wmv","flv",
                        "ts","3gp","3g2") -> "video/${ext}"
                    else -> ""
                }
                CloudMediaItem(
                    id = id, name = name, mimeType = mime, size = 0L,
                    downloadUrl = id,
                    sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                    isFolder = false, parentId = null
                )
            } else {
                driveOAuthProvider.getFileMetadata(id)
            }
            if (item == null) {
                _uiState.update { it.copy(errorMessage = "Couldn't load $name — it may have been removed from Drive.") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                openItem(item, onPlaybackStarted = onPlaybackStarted)
            }
        }
    }
    /** Tear off a starred Spotify URI by its `id` (= the spotify:… URI). */
    fun unstarSpotifyFavourite(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            when {
                uri.startsWith("spotify:track") ->
                    settingsDataStore.toggleSpotifyFavouriteTrack(uri, "")
                uri.startsWith("spotify:album") ->
                    settingsDataStore.toggleSpotifyFavouriteAlbum(uri, "")
                uri.startsWith("spotify:show") || uri.startsWith("spotify:episode") ->
                    settingsDataStore.toggleSpotifyFavouritePodcast(uri, "")
            }
        }
    }

    /**
     * Play a starred Spotify track from the section-picker favourites
     * strip. Routes through Spotify Connect (auto-launch + transfer
     * fallback handled inside SpotifyProvider).
     */
    fun playSpotifyFavourite(
        uri: String,
        name: String,
        @Suppress("UNUSED_PARAMETER") kind: String,
        onPlaybackStarted: () -> Unit = {}
    ) {
        viewModelScope.launch {
            // Pause any local playback so the two streams don't overlap.
            runCatching { playbackConnection.pause() }
            val r = spotifyProvider.playTrackOnConnectDevice(uri, contextUri = null)
            r.onSuccess {
                spotifyProvider.startPlaybackPolling()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Playing on Spotify: $name"
                )
                onPlaybackStarted()
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = ex.message ?: "Spotify playback failed"
                )
            }
        }
    }

    /**
     * Drill into a starred Spotify album / playlist / show from the
     * section-picker favourites strip — same code path as tapping a
     * folder inside a section listing.
     */
    fun openSpotifyContainer(containerUri: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val r = spotifyProvider.listContainer(containerUri)
            val list = r.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isLoading = false,
                    items = list,
                    activeProvider = CloudProviderType.SPOTIFY,
                    spotifySection = com.powermediaplayer.cloud.SpotifySection.SAVED_ALBUMS,
                    folderStack = listOf(null to "Spotify Library", containerUri to name),
                    errorMessage = r.exceptionOrNull()?.message
                )
            }
        }
    }

    fun toggleSpotifyFav(item: CloudMediaItem) {
        if (item.sourceProvider != CloudProviderType.SPOTIFY) return
        val uri = if (item.downloadUrl.startsWith("spotify:")) item.downloadUrl
            else "spotify:${if (item.isFolder) item.mimeType.substringAfter("application/spotify-") else "track"}:${item.id}"
        viewModelScope.launch(Dispatchers.IO) {
            when {
                uri.startsWith("spotify:track") ->
                    settingsDataStore.toggleSpotifyFavouriteTrack(uri, item.name)
                uri.startsWith("spotify:album") ->
                    settingsDataStore.toggleSpotifyFavouriteAlbum(uri, item.name)
                uri.startsWith("spotify:show") || uri.startsWith("spotify:episode") ->
                    settingsDataStore.toggleSpotifyFavouritePodcast(uri, item.name)
                else -> {}
            }
        }
    }

    /**
     * Toggle a Drive folder's favourite status. Used by the star icon
     * next to each folder row in the Drive browser.
     */
    fun toggleDriveFavourite(item: CloudMediaItem) {
        if (!item.isFolder || item.sourceProvider != CloudProviderType.GOOGLE_DRIVE) return
        viewModelScope.launch(Dispatchers.IO) {
            settingsDataStore.toggleDriveFavouriteFolder(item.id, item.name)
        }
    }

    /**
     * Open a previously-favourited Drive folder.
     */
    fun openDriveFavourite(fav: DriveFavouriteFolder) {
        browseDrive(fav.id, fav.name)
    }

    fun buildDriveSignInIntent(): Intent = driveProvider.buildSignInIntent()
    fun buildSpotifyAuthIntent(): Intent = spotifyProvider.buildAuthIntent()
    fun buildDriveOAuthSignInIntent(): Intent = driveOAuthProvider.buildSignInIntent()

    /** Process the Google Sign-In result. Returns true on success. */
    suspend fun handleDriveOAuthResult(data: Intent?): Boolean {
        val r = driveOAuthProvider.handleSignInResult(data)
        if (r.isFailure) {
            _uiState.update { it.copy(errorMessage = r.exceptionOrNull()?.message) }
        }
        return r.isSuccess
    }

    /**
     * Fetch a Drive access token off-Main so the picker activity can
     * inject it into the WebView. Returns null if not signed in.
     */
    suspend fun fetchDriveAccessToken(): String? = withContext(Dispatchers.IO) {
        driveOAuthProvider.fetchAccessTokenBlocking()
    }

    /** Persist a folder picked via the Drive Picker WebView. */
    fun rememberPickedDriveFolder(folderId: String, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            driveOAuthProvider.rememberPickedFolder(folderId, folderName)
        }
    }

    /**
     * Populate [CloudUiState.pickerRoots] with every available
     * DocumentsProvider root so the Cloud screen can show a chooser.
     * The chooser is the supported entry point on devices whose SAF
     * picker hides the source drawer; tapping a root deep-links the
     * picker straight into that source via EXTRA_INITIAL_URI.
     */
    fun openPickerChooser() {
        viewModelScope.launch(Dispatchers.IO) {
            val roots = runCatching { driveProvider.queryDocumentRoots() }.getOrDefault(emptyList())
            _uiState.update { it.copy(pickerRoots = roots, pickerRootsVisible = true) }
        }
    }

    fun dismissPickerChooser() {
        _uiState.update { it.copy(pickerRootsVisible = false) }
    }

    fun buildDeepLinkedDriveIntent(root: GoogleDriveProvider.CloudRoot): Intent =
        driveProvider.buildDeepLinkedSignInIntent(root.initialUri())

    fun handleDriveResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = driveProvider.handleSignInResult(data)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) browseDrive(null, "Root")
        }
    }

    fun handleSpotifyResult(data: Intent?) {
        android.util.Log.i("PMP_DIAG", "Cloud.handleSpotifyResult data=${data != null}")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = spotifyProvider.handleAuthResponse(data)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) browseSpotify()
        }
    }

    fun browseDrive(folderId: String?, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                activeProvider = CloudProviderType.GOOGLE_DRIVE
            )
            val result = if (folderId == null) {
                // Root view: union of SAF tree URIs + Drive OAuth picked
                // folders. Each appears as a virtual folder entry the
                // user can tap into.
                val safRoots = driveProvider.listFiles(null).getOrDefault(emptyList())
                val driveRoots = driveOAuthProvider.listFiles(null).getOrDefault(emptyList())
                Result.success(safRoots + driveRoots)
            } else {
                pickerForId(folderId).listFiles(folderId)
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = if (folderId == null) listOf(null to "Root")
                else _uiState.value.folderStack + (folderId to label)
            )
        }
    }

    /**
     * Decide which underlying provider owns [folderId]. Storage Access
     * Framework tree URIs always start with `content://`; everything
     * else (alphanumeric Drive IDs returned by the Picker) goes to the
     * Drive REST provider. Used by browseDrive/search/openItem so the
     * single CloudUiState can mix entries from both backends without
     * the UI having to know the difference.
     */
    private fun pickerForId(folderId: String): com.powermediaplayer.cloud.CloudStorageProvider =
        if (folderId.startsWith("content://")) driveProvider else driveOAuthProvider

    fun browseSpotify() {
        // Land on the section picker — empty items + spotifySection=null
        // signals the UI to render the section cards.
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            activeProvider = CloudProviderType.SPOTIFY,
            items = emptyList(),
            spotifySection = null,
            errorMessage = null,
            folderStack = listOf(null to "Spotify Library")
        )
    }

    fun openSpotifySection(section: com.powermediaplayer.cloud.SpotifySection) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                activeProvider = CloudProviderType.SPOTIFY,
                spotifySection = section
            )
            val result = spotifyProvider.listSection(section)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = listOf(null to "Spotify Library", null to section.label)
            )
        }
    }

    fun spotifyBackToSections() {
        _uiState.value = _uiState.value.copy(
            spotifySection = null,
            items = emptyList(),
            folderStack = listOf(null to "Spotify Library")
        )
    }

    fun navigateUp() {
        val stack = _uiState.value.folderStack
        // Spotify drill-down (album/playlist/show contents) → pop the
        // top folder and re-load the section listing.
        if (_uiState.value.activeProvider == CloudProviderType.SPOTIFY &&
            _uiState.value.spotifySection != null && stack.size > 2
        ) {
            val section = _uiState.value.spotifySection ?: return
            openSpotifySection(section)
            return
        }
        // Spotify section view → back to section picker (one extra level).
        if (_uiState.value.activeProvider == CloudProviderType.SPOTIFY &&
            _uiState.value.spotifySection != null
        ) {
            spotifyBackToSections()
            return
        }
        // At root of a provider → leave the provider, go back to the
        // provider-selection screen so the user can pick a different one.
        if (stack.size <= 1) {
            _uiState.value = _uiState.value.copy(
                activeProvider = null,
                items = emptyList(),
                folderStack = listOf(null to "Root")
            )
            return
        }
        val parent = stack.dropLast(1).last()
        val active = _uiState.value.activeProvider
        if (active == CloudProviderType.GOOGLE_DRIVE) {
            viewModelScope.launch(Dispatchers.IO) {
                val result = if (parent.first == null) {
                    val safRoots = driveProvider.listFiles(null).getOrDefault(emptyList())
                    val driveRoots = driveOAuthProvider.listFiles(null).getOrDefault(emptyList())
                    Result.success(safRoots + driveRoots)
                } else {
                    pickerForId(parent.first!!).listFiles(parent.first)
                }
                _uiState.value = _uiState.value.copy(
                    items = result.getOrDefault(emptyList()),
                    folderStack = stack.dropLast(1)
                )
            }
        }
    }

    /**
     * @param onPlaybackStarted invoked ONLY when the item actually starts
     *   playing — used by the UI to navigate to the Player tab. Failures
     *   (Spotify previews removed, Drive 401, etc.) do not navigate.
     */
    fun openItem(item: CloudMediaItem, onPlaybackStarted: () -> Unit = {}) {
        android.util.Log.i(
            "PMP_DIAG",
            "Cloud.openItem name=${item.name} provider=${item.sourceProvider} folder=${item.isFolder} mime=${item.mimeType}"
        )
        if (item.isFolder) {
            when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> browseDrive(item.id, item.name)
                CloudProviderType.SPOTIFY -> {
                    // Drill into the album / playlist / show — load its
                    // tracks (or episodes) into the items list and push
                    // a new folder-stack entry so the back-arrow returns
                    // to the section view.
                    val containerUri = if (item.downloadUrl.startsWith("spotify:")) item.downloadUrl
                        else "spotify:${item.mimeType.substringAfter("application/spotify-")}:${item.id}"
                    viewModelScope.launch(Dispatchers.IO) {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                        val r = spotifyProvider.listContainer(containerUri)
                        val list = r.getOrDefault(emptyList())
                        android.util.Log.i("PMP_DIAG", "Cloud.openItem container loaded n=${list.size} first=${list.firstOrNull()?.name}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            items = list,
                            folderStack = _uiState.value.folderStack + (containerUri to item.name),
                            errorMessage = r.exceptionOrNull()?.message
                        )
                    }
                }
                else -> { }
            }
            return
        }
        // Spotify track: free-tier preview URLs are mostly null in 2026,
        // and Premium full-track playback needs the Spotify Connect API
        // (PUT /v1/me/player/play). Premium users tap → the track is sent
        // to their currently-active Spotify device. If no device is
        // active they get a clear error explaining they need to open
        // Spotify on a device first.
        if (item.sourceProvider == CloudProviderType.SPOTIFY) {
            viewModelScope.launch {
                // Stop the local ExoPlayer FIRST so the user doesn't get
                // two streams playing at once. The previous behaviour
                // left the local file audible behind the Spotify
                // Connect track and the Player tab's pause button only
                // paused Spotify (because isSpotifyActive=true).
                runCatching { playbackConnection.pause() }
                val spotifyUri = if (item.downloadUrl.startsWith("spotify:")) {
                    item.downloadUrl
                } else {
                    "spotify:track:${item.id}"
                }
                val r = spotifyProvider.playTrackOnConnectDevice(spotifyUri, item.contextUri)
                r.onSuccess {
                    spotifyProvider.startPlaybackPolling()
                    recordCloudPlay(item)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Playing on Spotify: ${item.name}"
                    )
                    onPlaybackStarted()
                }.onFailure { ex ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = ex.message ?: "Spotify playback failed"
                    )
                }
            }
            return
        }
        viewModelScope.launch {
            try {
                if (openItemInternal(item)) {
                    recordCloudPlay(item)
                    onPlaybackStarted()
                }
            } catch (t: Throwable) {
                android.util.Log.e("PowerMediaPlayer", "openItem failed", t)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Couldn't play: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    /**
     * Returns true iff playback actually started. False means an error was
     * recorded into [_uiState.errorMessage] and the caller should NOT
     * navigate away from the cloud screen.
     */
    private suspend fun openItemInternal(item: CloudMediaItem): Boolean {
        // Drive (or other non-Spotify) playback starts → stop the
        // Spotify mirror so the Player tab swaps over cleanly instead
        // of leaving Spotify metadata visible while local audio plays.
        if (item.sourceProvider != CloudProviderType.SPOTIFY &&
            spotifyProvider.spotifyState.value != null
        ) {
            spotifyProvider.stopPlaybackPolling()
            runCatching { spotifyProvider.togglePlayPause() }
        }
        // Build a MediaItem and hand it to the playback connection. The
        // PlaybackService DataSource pipeline injects the Drive bearer
        // token automatically for googleapis.com URLs.
        run {
            val streamResult = when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE ->
                    if (item.id.startsWith("content://")) driveProvider.getMediaStreamUri(item)
                    else driveOAuthProvider.getMediaStreamUri(item)
                CloudProviderType.SPOTIFY -> spotifyProvider.getMediaStreamUri(item)
                else -> return false
            }
            // If the provider failed to produce a playable URI (e.g. Spotify
            // track without a preview clip), surface the reason so the user
            // isn't left staring at a blank player.
            streamResult.exceptionOrNull()?.let { ex ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = ex.message ?: "Cannot play this item"
                )
                return false
            }
            val uri = streamResult.getOrNull() ?: run {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "No playable URL for this item"
                )
                return false
            }
            // mediaId MUST be the URI string and requestMetadata MUST carry
            // the URI — MediaController IPC strips localConfiguration.uri
            // before the service receives the item, so the service-side
            // PlayerSessionCallback.onAddMediaItems recovers it from one of
            // those two preserved fields.
            // Drive labels MP4-container files (including .m4b audiobooks)
            // with mime "video/mp4" because the container CAN hold video.
            // The file extension is the authoritative signal — without
            // this override, M4Bs were being treated as video, triggering
            // the 32-s auto-hide and replacing the cover-art surface with
            // an empty video surface. Audio extensions force isVideo=false.
            val audioExts = setOf(
                "m4b", "m4a", "m4p", "m4r", "mp3", "flac", "ogg", "oga",
                "opus", "wav", "wave", "aac", "aiff", "aif", "ape", "wma"
            )
            val nameExt = item.name.substringAfterLast('.', "").lowercase()
            val isVideo = when {
                nameExt in audioExts -> false
                else -> item.mimeType.startsWith("video/")
            }
            android.util.Log.i(
                "PowerMediaPlayer",
                "openItem: name=${item.name} ext=$nameExt mime=${item.mimeType} → isVideo=$isVideo"
            )
            val extras = android.os.Bundle().apply {
                putBoolean("is_video_hint", isVideo)
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(uri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setExtras(extras)
                        .build()
                )
                .build()
            playbackConnection.setMediaItems(listOf(mediaItem), 0)
            // Use the same extension-first decision used for is_video_hint.
            playbackConnection.setVideoModeHint(isVideo)
            // Instant placeholder metadata: filename as title + Drive's
            // thumbnail (auto-generated for many files, no auth needed).
            // Replaced by the post-download tags + embedded artwork when
            // the background extraction finishes.
            playbackConnection.setLocalMetadata(
                LocalMetadataOverride(
                    title = item.name,
                    artworkUri = item.thumbnailUri
                )
            )

            // Drive: chapters + metadata + artwork live INSIDE the file
            // (moov box for MP4/M4B, ID3 for MP3, etc.) and MediaExtractor /
            // MediaMetadataRetriever cannot reach an authenticated HTTPS URL
            // directly. Download to cache once, run BOTH parsers, push the
            // results to the player. Streaming continues unaffected.
            if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder) {
                viewModelScope.launch(Dispatchers.IO) {
                    playbackConnection.setCloudFetchInProgress(true)
                    // Three-pass strategy:
                    //   (1) head 32 MB — fast; works for moov-at-front
                    //   (2) full file (≤1 GB) — slow but reliable;
                    //       MediaMetadataRetriever needs a complete MP4
                    //       structure (ftyp + moov + mdat), so partial
                    //       tail-only downloads can't actually be parsed.
                    //   (3) skip — file too big or already extracted
                    val isSafItem = item.id.startsWith("content://")
                    var found = false
                    var tempFile = try {
                        if (isSafItem) driveProvider.downloadToCache(item)
                        else driveOAuthProvider.downloadToCache(item)
                    } catch (_: Throwable) { null }
                    if (tempFile != null) {
                        found = parseAndApply(item, tempFile)
                        runCatching { tempFile.delete() }
                    }
                    if (!found) {
                        tempFile = try {
                            if (isSafItem) driveProvider.downloadFullToCache(item)
                            else driveOAuthProvider.downloadFullToCache(item)
                        } catch (_: Throwable) { null }
                        if (tempFile != null) {
                            parseAndApply(item, tempFile)
                            runCatching { tempFile.delete() }
                        }
                    }
                    playbackConnection.setCloudFetchInProgress(false)
                }
            }
        }
        // Reaching here means setMediaItems was called — playback has been
        // handed to the service and (network permitting) will start.
        return true
    }

    /**
     * Run MediaMetadataRetriever + M4B chapter parser against a downloaded
     * Drive byte range. Pushes any extracted artwork / tags / chapters to
     * the player state. Returns true iff at least one was extracted.
     */
    private fun parseAndApply(item: CloudMediaItem, tempFile: java.io.File): Boolean {
        var found = false
        val tempUri = android.net.Uri.fromFile(tempFile)
        android.util.Log.i(
            "PowerMediaPlayer",
            "parseAndApply: file=${tempFile.absolutePath} bytes=${tempFile.length()}"
        )
        runCatching {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, tempUri)
                val title = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
                )
                val artist = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
                ) ?: mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
                )
                val album = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
                )
                val artBytes = mmr.embeddedPicture
                if (!title.isNullOrBlank() || !artist.isNullOrBlank() ||
                    !album.isNullOrBlank() || artBytes != null) {
                    playbackConnection.setLocalMetadata(
                        LocalMetadataOverride(
                            title = title ?: item.name,
                            artist = artist,
                            album = album,
                            // Preserve the Drive thumbnail as a fallback when
                            // the file has no embedded picture — otherwise
                            // updating with artworkBytes=null would wipe the
                            // placeholder we set instantly on play.
                            artworkUri = item.thumbnailUri,
                            artworkBytes = artBytes
                        )
                    )
                    if (artBytes != null) found = true
                }
                android.util.Log.i(
                    "PowerMediaPlayer",
                    "MMR result: title=$title artist=$artist album=$album " +
                        "artBytes=${artBytes?.size ?: 0}"
                )
            }
        }
        runCatching {
            val bundle = M4bChapterParser.extractChaptersAsBundle(context, tempUri)
            val count = bundle.getInt("chapter_count", 0)
            android.util.Log.i("PowerMediaPlayer", "M4B parser: chapter_count=$count")
            if (count > 0) {
                val chapters = (0 until count).mapNotNull { i ->
                    val title = bundle.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
                    val start = bundle.getLong("chapter_start_$i", -1)
                    val end = bundle.getLong("chapter_end_$i", -1)
                    if (start >= 0) ChapterInfo(title, start, end, i) else null
                }
                playbackConnection.setLocalChapters(chapters)
                found = true
            }
        }
        return found
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Search the active provider. Debounced 300 ms so a fast typist's
     * keystrokes don't trigger an API call per character.
     */
    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val provider = _uiState.value.activeProvider
            val results = when {
                query.isBlank() -> emptyList()
                provider == CloudProviderType.GOOGLE_DRIVE -> {
                    val safMatches = driveProvider.searchFiles(query).getOrDefault(emptyList())
                    val driveMatches = driveOAuthProvider.searchFiles(query).getOrDefault(emptyList())
                    safMatches + driveMatches
                }
                provider == CloudProviderType.SPOTIFY ->
                    spotifyProvider.search(query).getOrDefault(emptyList())
                else -> emptyList()
            }
            _uiState.value = _uiState.value.copy(searchResults = results)
        }
    }

    /**
     * Called by the UI after rendering an error message (Toast) so a
     * repeated tap on the same failing item re-triggers a new event.
     */
    fun clearError() {
        if (_uiState.value.errorMessage != null) {
            _uiState.value = _uiState.value.copy(errorMessage = null)
        }
    }

    fun signOutDrive() {
        viewModelScope.launch {
            driveProvider.signOut()
            driveOAuthProvider.signOut()
        }
    }

    /**
     * Forget a single picked folder. Routes to the SAF or Drive REST
     * provider based on the item's id format. After removal, the
     * active folder list refreshes so the row disappears immediately.
     */
    fun forgetPickedFolder(item: CloudMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (item.id.startsWith("content://")) {
                driveProvider.forgetPickedRoot(item.id)
            } else {
                driveOAuthProvider.forgetPickedFolder(item.id)
            }
            // Refresh the root list if it's currently visible.
            if (_uiState.value.folderStack.size <= 1 &&
                _uiState.value.activeProvider == CloudProviderType.GOOGLE_DRIVE
            ) {
                val safRoots = driveProvider.listFiles(null).getOrDefault(emptyList())
                val driveRoots = driveOAuthProvider.listFiles(null).getOrDefault(emptyList())
                _uiState.update { it.copy(items = safRoots + driveRoots) }
            }
        }
    }

    fun signOutSpotify() {
        viewModelScope.launch { spotifyProvider.signOut() }
    }
}
