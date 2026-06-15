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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    val pickerRootsVisible: Boolean = false,

    /**
     * Spotify Connect device picker — populated by [refreshSpotifyConnectDevices]
     * when the user taps the "Spotify Connect" button. Each entry is
     * (deviceId, deviceName) from /me/player/devices. Visible whenever the
     * list is non-empty AND the picker bottom-sheet is open.
     */
    val spotifyConnectDevices: List<Pair<String, String>> = emptyList(),
    val spotifyConnectPickerVisible: Boolean = false,

    /**
     * Currently-active Spotify device name + playing state, mirrored
     * from /me/player. Used by the Connect picker sheet to show a
     * "Now playing on X" banner and a Pause button.
     */
    val spotifyActiveDeviceName: String? = null,
    val spotifyIsPlaying: Boolean = false
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val driveProvider: GoogleDriveProvider,
    private val driveOAuthProvider: com.powermediaplayer.cloud.DriveOAuthProvider,
    private val spotifyProvider: SpotifyProvider,
    private val playbackConnection: PlaybackConnection,
    private val settingsDataStore: SettingsDataStore,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    val mediaOverrideDao: com.powermediaplayer.data.db.dao.MediaOverrideDao,
    private val offlineCopyDao: com.powermediaplayer.data.db.dao.OfflineCopyDao
) : ViewModel() {

    /**
     * §C28 — current snapshot of {driveFileId → cachedAbsolutePath} for
     * offline Drive copies. Compose-friendly StateFlow so row chips +
     * long-press menu visibility can react instantly.
     */
    val offlineDrivePairs: kotlinx.coroutines.flow.StateFlow<Map<String, String>> =
        settingsDataStore.offlineDrivePairs
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                emptyMap()
            )

    fun hasOfflineCopy(driveId: String): Boolean = offlineDrivePairs.value.containsKey(driveId)

    fun saveDriveOffline(item: CloudMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val file: java.io.File? = runCatching {
                if (item.id.startsWith("content://"))
                    driveProvider.downloadFullToCache(item)
                else driveOAuthProvider.downloadFullToCache(item)
            }.getOrNull()
            if (file != null && file.exists()) {
                settingsDataStore.upsertOfflineDrive(item.id, file.absolutePath)
                offlineCopyDao.upsert(
                    com.powermediaplayer.data.db.entity.OfflineCopyEntity(
                        driveFileId = item.id,
                        localPath = file.absolutePath,
                        byteSize = file.length()
                    )
                )
                evictOfflineLruIfOverLimit()
                _uiState.update {
                    it.copy(errorMessage = "Saved offline: ${item.name}")
                }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "C28 saved offline id=${item.id} path=${file.absolutePath} size=${file.length()}"
                )
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Couldn't save offline — try again on Wi-Fi.")
                }
            }
        }
    }

    fun removeDriveOffline(driveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = offlineDrivePairs.value[driveId]
            if (path != null) {
                runCatching { java.io.File(path).delete() }
            }
            settingsDataStore.removeOfflineDrive(driveId)
            offlineCopyDao.delete(driveId)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "C28 removed offline id=$driveId")
        }
    }

    /**
     * §C28 — evict oldest unstarred offline copies until total bytes
     * fall under the user-configured limit (default 5 GB; 0 =
     * unlimited).
     */
    /**
     * §C16 — Cloud-tab refresh-on-open. Re-runs the active provider's
     * listing if the data is older than [thresholdMs]. Cheaper than
     * always-refresh because the user often hops between tabs without
     * needing a network roundtrip.
     */
    @Volatile private var lastCloudRefreshMs: Long = 0L
    fun refreshIfStale(thresholdMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastCloudRefreshMs < thresholdMs) return
        lastCloudRefreshMs = now
        val st = _uiState.value
        when (st.activeProvider) {
            com.powermediaplayer.cloud.CloudProviderType.GOOGLE_DRIVE -> {
                val (id, label) = st.folderStack.lastOrNull() ?: (null to "Root")
                browseDrive(id, label)
            }
            com.powermediaplayer.cloud.CloudProviderType.SPOTIFY ->
                browseSpotify()
            else -> {}
        }
    }

    /**
     * Bypass-the-stale-threshold refresh. Called from
     * (a) every picker-launcher return — guarantees the screen reflects
     *     whatever happened in the picker (file picked, folder picked,
     *     user cancelled, picker errored).
     * (b) the ON_RESUME lifecycle observer in the Cloud screen — covers
     *     returning to the tab after adding files via Drive web in a
     *     different app, OAuth flow completing in a Custom Tab, etc.
     *
     * Also re-pulls the driveLoggedIn / spotifyLoggedIn flags so the
     * provider cards swap from "Sign in" to "Sign out" promptly after
     * any external OAuth flow returns.
     */
    fun forceRefresh() {
        lastCloudRefreshMs = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            // Pull the current sign-in state from each provider so the
            // cards swap from "Sign in" to "Sign out" promptly after an
            // external OAuth flow returns.
            val drive = driveOAuthProvider.isLoggedIn.value
            _uiState.update { it.copy(driveLoggedIn = drive) }
        }
        val st = _uiState.value
        when (st.activeProvider) {
            com.powermediaplayer.cloud.CloudProviderType.GOOGLE_DRIVE -> {
                val (id, label) = st.folderStack.lastOrNull() ?: (null to "Root")
                browseDrive(id, label)
            }
            com.powermediaplayer.cloud.CloudProviderType.SPOTIFY -> browseSpotify()
            else -> {
                // At top-level provider selection: if Drive is signed
                // in + has picked folders, deep-link straight to the
                // populated state so the user doesn't see the empty
                // sign-in cards while we know the data is there.
                if (driveOAuthProvider.isLoggedIn.value) {
                    browseDrive(null, "Root")
                }
            }
        }
    }

    private suspend fun evictOfflineLruIfOverLimit() {
        val limit = settingsDataStore.offlineStorageLimitBytes.first()
        if (limit <= 0) return
        var total = offlineCopyDao.totalBytes()
        if (total <= limit) return
        val lru = offlineCopyDao.lruSnapshot()
        for (row in lru) {
            if (total <= limit) break
            if (row.isStarred) continue
            runCatching { java.io.File(row.localPath).delete() }
            offlineCopyDao.delete(row.driveFileId)
            settingsDataStore.removeOfflineDrive(row.driveFileId)
            total -= row.byteSize
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "C28 LRU evicted id=${row.driveFileId} freed=${row.byteSize}B"
            )
        }
    }

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
        // Spotify mirror auto-record: when the polled spotifyState
        // reveals a track that wasn't initiated from our app (e.g. user
        // started playback on a desktop / Google Home and we picked up
        // the Connect mirror), synthesise a recordPlay so the bookmark→
        // Last Played mirror works for that listen too. Triggered only
        // when no session is active yet (currentSessionId == null) AND
        // a non-blank trackUri appears — and re-fired on each fresh
        // trackUri so consecutive mirrored tracks each get a Recents
        // row matching the user's "every fresh play = new row" model.
        // Mirror Spotify playback state (active device + playing flag)
        // into uiState so the Connect picker sheet can show "Now playing
        // on X" + the Pause control.
        viewModelScope.launch {
            spotifyProvider.spotifyState.collect { s ->
                _uiState.update {
                    it.copy(
                        spotifyActiveDeviceName = s?.deviceName,
                        spotifyIsPlaying = s?.isPlaying == true
                    )
                }
            }
        }
        viewModelScope.launch {
            spotifyProvider.spotifyState
                .map { it?.trackUri.orEmpty() }
                .distinctUntilChanged()
                .collect { trackUri ->
                    if (trackUri.isBlank()) return@collect
                    if (lastPlayedRepo.currentSessionId.value != null) return@collect
                    val s = spotifyProvider.spotifyState.value ?: return@collect
                    runCatching {
                        lastPlayedRepo.recordPlay(
                            com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                                mediaUri = s.trackUri,
                                title = s.title.ifBlank { "Spotify" },
                                subtitle = s.artist.ifBlank { s.album },
                                artworkUri = s.artworkUrl,
                                source = "SPOTIFY",
                                mediaKindOrdinal = 0,
                                lastPositionMs = s.positionMs.coerceAtLeast(0L),
                                durationMs = s.durationMs.coerceAtLeast(0L),
                                lastPlayedAt = System.currentTimeMillis()
                            )
                        )
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Spotify mirror first-emit synthesised session uri=$trackUri title='${s.title}'"
                        )
                    }
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
            // vc32: new play intent — supersedes any in-flight slow resume.
            com.powermediaplayer.playback.ResumeGate.end(
                com.powermediaplayer.playback.ResumeGate.begin()
            )
            // Pause any local playback so the two streams don't overlap.
            runCatching { playbackConnection.pause() }
            // vc32: provisional mirror AT TAP TIME — controls route
            // to Spotify immediately; UI shows the requested track.
            spotifyProvider.armProvisionalMirror(
                com.powermediaplayer.cloud.SpotifyPlaybackState(
                    title = name,
                    artist = "",
                    album = "",
                    artworkUrl = null,
                    positionMs = 0L,
                    durationMs = 0L,
                    isPlaying = true,
                    trackUri = uri,
                    deviceName = null
                )
            )
            val r = spotifyProvider.playTrackOnConnectDevice(uri, contextUri = null)
            r.onSuccess {
                // vc32: user-initiated play → arm the handoff grace
                // + hold the overlay for the requested track.
                spotifyProvider.startPlaybackPolling(
                    expectPlayback = true, expectedTrack = uri
                )
                // Record this play in Last Played so the Recents tab + the
                // session-bookmark dropdowns know about it. Previously
                // missing — Spotify tracks tapped from the favourites
                // strip never wrote a history row, so they never appeared
                // in Last Played and never got bookmark dropdowns.
                val item = CloudMediaItem(
                    id = uri,
                    name = name,
                    mimeType = "audio/spotify",
                    size = 0L,
                    downloadUrl = uri,
                    sourceProvider = CloudProviderType.SPOTIFY,
                    isFolder = false,
                    parentId = null
                )
                recordCloudPlay(item)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Playing on Spotify: $name"
                )
                onPlaybackStarted()
            }.onFailure { ex ->
                // vc32: never leave a provisional mirror for a
                // track that failed to play.
                spotifyProvider.clearProvisionalMirror()
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
    fun buildSpotifyAuthIntent(): Intent {
        // Bug fix (user-reported "Spotify sign-in resumes playback"):
        // mark OAuth in-flight so PlaybackService.handleAudioFocusChange
        // ignores the loss-then-gain pair caused by the browser Custom
        // Tab stealing audio focus. Cleared in handleSpotifyResult or
        // by the 60s safety timer below.
        com.powermediaplayer.service.PlaybackService.oauthInFlight = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(60_000)
            com.powermediaplayer.service.PlaybackService.oauthInFlight = false
        }
        return spotifyProvider.buildAuthIntent()
    }
    fun buildDriveOAuthSignInIntent(): Intent = driveOAuthProvider.buildSignInIntent()

    /**
     * One-shot: true after the user has seen and acknowledged the
     * "pick a FOLDER, not a file" warning that fires before the very
     * first Drive Picker launch. Subsequent picks skip the dialog.
     */
    val driveFirstPickWarningSeen: kotlinx.coroutines.flow.Flow<Boolean> =
        settingsDataStore.driveFirstPickWarningSeen

    fun markDriveFirstPickWarningSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsDataStore.markDriveFirstPickWarningSeen()
        }
    }

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
    /**
     * Pauses Spotify playback on whatever device is currently active.
     * Used by the Connect picker's "Stop playing" / Disconnect row so
     * the user can silence playback on an Echo / Sonos / etc. without
     * needing the official Spotify app.
     */
    fun pauseSpotify() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { spotifyProvider.pause() }
        }
    }

    /**
     * Pin the CURRENT cloud folder as an album to Last Played →
     * Pinned (shared 10-cap). Snapshots every audio item visible in
     * the folder right now; library churn on the bridge won't break
     * the pin. Mirrors LibraryViewModel.pinAlbum's contract.
     */
    suspend fun pinCurrentFolderAsAlbum(): Result<Unit> {
        val st = _uiState.value
        val (_, label) = st.folderStack.lastOrNull() ?: (null to "Folder")
        // Filter the current items to audio + skip folders.
        val audioItems = st.items.filter {
            !it.isFolder && it.mimeType.startsWith("audio/")
        }
        if (audioItems.isEmpty()) {
            return Result.failure(
                IllegalStateException("No audio files in this folder to pin")
            )
        }
        val albumKey = "cloud|||${label.lowercase()}|||${audioItems.size}"
        val tracks = audioItems.map {
            com.powermediaplayer.data.repository.LastPlayedRepository.AlbumTrackToPin(
                mediaUri = it.downloadUrl,
                title = it.name,
                durationMs = 0L
            )
        }
        return lastPlayedRepo.pinAlbum(
            albumKey = albumKey,
            title = label,
            artist = "",
            artworkUri = null,
            tracks = tracks
        )
    }

    fun rememberPickedDriveFolder(folderId: String, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            driveOAuthProvider.rememberPickedFolder(folderId, folderName)
            lastCloudRefreshMs = System.currentTimeMillis()
            // vc32: the old refresh ran only when the user was
            // ALREADY inside Drive, and silently — newly added folders
            // went unnoticed. Now: confirm via the snackbar channel and
            // browse straight INTO the new folder regardless of where the
            // user was.
            _uiState.update {
                it.copy(
                    activeProvider = CloudProviderType.GOOGLE_DRIVE,
                    errorMessage = "Added \"$folderName\" — opening it"
                )
            }
            browseDrive(folderId, folderName)
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
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Cloud.handleSpotifyResult data=${data != null}")
        // Clear the OAuth-in-flight flag immediately on result so
        // AudioFocus handling resumes its normal pause/duck/ignore
        // policy starting from the next focus event.
        com.powermediaplayer.service.PlaybackService.oauthInFlight = false
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

    /**
     * Open the Spotify Connect device picker. Refreshes the list from
     * /me/player/devices and shows the bottom sheet — every Connect-
     * compatible target the user's Spotify account currently sees,
     * including Google Home / Nest speakers when the user has linked
     * their Google account to Spotify in the Google Home app.
     */
    fun openSpotifyConnectPicker() {
        viewModelScope.launch {
            val devices = spotifyProvider.listConnectDevices()
            val phoneOnly = devices.size <= 1
            _uiState.update {
                it.copy(
                    spotifyConnectDevices = devices,
                    spotifyConnectPickerVisible = true,
                    errorMessage = if (devices.isEmpty())
                        "No Spotify Connect devices found. Tap the Wake-Spotify button below."
                    else it.errorMessage
                )
            }
            // Auto-bounce on first open when only the phone (or nothing)
            // is visible — Spotify's public Web API only lists devices
            // recently active on the user's account, so without poking
            // the Spotify app the picker stays empty. Bounce only ONCE
            // per picker open to avoid loops.
            if (phoneOnly && !alreadyBouncedThisOpen) {
                alreadyBouncedThisOpen = true
                wakeSpotifyForDeviceDiscovery()
            }
        }
    }

    private var alreadyBouncedThisOpen: Boolean = false

    /**
     * Bounce out to the Spotify app for ~1.5s and come back. Spotify's
     * public Web API /me/player/devices only lists devices that have
     * registered with Connect via the Spotify SDK; opening the app
     * causes Google Home / Fire Stick / Sonos to publish themselves to
     * the user's Connect network, after which a re-fetch picks them up.
     */
    fun wakeSpotifyForDeviceDiscovery() {
        // Suppress audio focus pause/gain across the bounce so the
        // current playback doesn't auto-resume on return.
        com.powermediaplayer.service.PlaybackService.oauthInFlight = true
        spotifyProvider.wakeSpotifyAndReturn()
        // Poll /me/player/devices every 2s for 16s after the bounce.
        // Spotify's Web API takes seconds-to-many-seconds to enumerate
        // Google Home / Fire Stick / Sonos after the Spotify app
        // re-registers them with Connect. Take the largest result.
        viewModelScope.launch {
            var bestList: List<Pair<String, String>> = emptyList()
            for (attempt in 0 until 8) {
                kotlinx.coroutines.delay(2_000)
                val devices = spotifyProvider.listConnectDevices()
                if (devices.size > bestList.size) {
                    bestList = devices
                    _uiState.update { it.copy(spotifyConnectDevices = devices) }
                }
            }
            com.powermediaplayer.service.PlaybackService.oauthInFlight = false
        }
    }

    fun dismissSpotifyConnectPicker() {
        _uiState.update { it.copy(spotifyConnectPickerVisible = false) }
        alreadyBouncedThisOpen = false
    }

    /**
     * Transfer Spotify playback to the chosen Connect device. The next
     * playTrackOnConnectDevice call will land on this device.
     */
    fun selectSpotifyConnectDevice(deviceId: String, deviceName: String) {
        viewModelScope.launch {
            val result = spotifyProvider.transferPlaybackTo(deviceId)
            _uiState.update { state ->
                state.copy(
                    spotifyConnectPickerVisible = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: if (result.isSuccess) null else "Couldn't switch to $deviceName"
                )
            }
        }
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
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Cloud.openItem name=${item.name} provider=${item.sourceProvider} folder=${item.isFolder} mime=${item.mimeType}"
        )
        if (!item.isFolder) {
            // vc32: new play intent — supersedes any in-flight slow resume.
            com.powermediaplayer.playback.ResumeGate.end(
                com.powermediaplayer.playback.ResumeGate.begin()
            )
        }
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
                        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Cloud.openItem container loaded n=${list.size} first=${list.firstOrNull()?.name}")
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
                // vc32: provisional mirror AT TAP TIME.
                spotifyProvider.armProvisionalMirror(
                    com.powermediaplayer.cloud.SpotifyPlaybackState(
                        title = item.name,
                        artist = "",
                        album = "",
                        artworkUrl = item.thumbnailUri?.toString(),
                        positionMs = 0L,
                        durationMs = 0L,
                        isPlaying = true,
                        trackUri = spotifyUri,
                        deviceName = null
                    )
                )
                val r = spotifyProvider.playTrackOnConnectDevice(spotifyUri, item.contextUri)
                r.onSuccess {
                    // vc32: user-initiated play → arm the handoff
                    // grace + hold the overlay for the requested track.
                    spotifyProvider.startPlaybackPolling(
                        expectPlayback = true, expectedTrack = spotifyUri
                    )
                    recordCloudPlay(item)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Playing on Spotify: ${item.name}"
                    )
                    onPlaybackStarted()
                }.onFailure { ex ->
                    // vc32: never leave a provisional mirror for a
                    // track that failed to play.
                    spotifyProvider.clearProvisionalMirror()
                    _uiState.value = _uiState.value.copy(
                        errorMessage = ex.message ?: "Spotify playback failed"
                    )
                }
            }
            return
        }
        viewModelScope.launch {
            try {
                heldThisOpen = false
                if (openItemInternal(item)) {
                    // A "hold" (item already loaded) is a resume, not a fresh
                    // play — don't record a new Recents row or re-adopt session.
                    if (!heldThisOpen) recordCloudPlay(item)
                    onPlaybackStarted()
                }
            } catch (t: Throwable) {
                com.powermediaplayer.util.Diag.e("PowerMediaPlayer", "openItem failed", t)
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
    // Sticky cache of embedded tags/art already extracted this session, keyed
    // by Drive file id. Without it, re-opening a Drive item (e.g. returning
    // from a cast, which re-fires openItem) reset the filename placeholder AND
    // re-downloaded the whole file (hundreds of MB) to re-extract — the
    // metadata visibly "went away" then slowly came back. With it, a re-open
    // restores the enriched title/artist/album/art instantly and skips the
    // re-download.
    private val enrichedByMediaId =
        java.util.concurrent.ConcurrentHashMap<String, LocalMetadataOverride>()

    // Set true by openItemInternal when it took the "hold" fast-path (the
    // tapped item was already loaded). openItem reads it to skip recordCloudPlay
    // so a mere resume doesn't spawn a duplicate Recents row or re-adopt the
    // session — only a genuine (re)load records a play.
    private var heldThisOpen = false

    private suspend fun openItemInternal(item: CloudMediaItem): Boolean {
        // Drive (or other non-Spotify) playback starts → stop the
        // Spotify mirror so the Player tab swaps over cleanly instead
        // of leaving Spotify metadata visible while local audio plays.
        if (item.sourceProvider != CloudProviderType.SPOTIFY &&
            spotifyProvider.spotifyState.value != null
        ) {
            // PAUSE — NOT togglePlayPause. toggle reads the (possibly stale)
            // mirror state and can RESUME Spotify instead of pausing it
            // (observed: PUT /me/player/play → 403 while Spotify was already
            // playing → Spotify kept playing UNDER the new Drive/local track
            // = the "two audio streams at once" bug). Library + LastPlayed
            // already pause() unconditionally on a source switch; match them.
            runCatching { spotifyProvider.pause() }
            spotifyProvider.stopPlaybackPolling()
        }
        // §C28 — if a Drive item has an offline copy on disk, route to
        // the local file URI immediately. Skips the bearer-token /
        // network-fetch path entirely and works without Internet.
        val offlinePath = if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE) {
            offlineDrivePairs.value[item.id]
        } else null

        // Build a MediaItem and hand it to the playback connection. The
        // PlaybackService DataSource pipeline injects the Drive bearer
        // token automatically for googleapis.com URLs.
        run {
            val streamResult: kotlin.Result<android.net.Uri> = if (offlinePath != null) {
                val f = java.io.File(offlinePath)
                if (f.exists()) kotlin.Result.success(android.net.Uri.fromFile(f))
                else when (item.sourceProvider) {
                    CloudProviderType.GOOGLE_DRIVE ->
                        if (item.id.startsWith("content://")) driveProvider.getMediaStreamUri(item)
                        else driveOAuthProvider.getMediaStreamUri(item)
                    CloudProviderType.SPOTIFY -> spotifyProvider.getMediaStreamUri(item)
                    else -> return false
                }
            } else when (item.sourceProvider) {
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
            // ── "Hold" fast-path ─────────────────────────────────────
            // If this EXACT item is already loaded in the player, don't rebuild
            // it: rebuilding re-prepares the stream, re-runs Drive enrichment,
            // and briefly resets the metadata. Just resume + show. mediaId is
            // the stream URI (stable per item — the Drive files URL, no
            // per-request token). When the current item is DIFFERENT (a cast
            // round-trip rebuilds the queue, switching provider/track, cold
            // start) this falls through to the normal full load, so those paths
            // are unaffected — worst case it simply doesn't optimise.
            val loadedPlayer = playbackConnection.getPlayer()
            if (loadedPlayer != null && loadedPlayer.mediaItemCount > 0 &&
                loadedPlayer.currentMediaItem?.mediaId == uri.toString()
            ) {
                if (!loadedPlayer.playWhenReady) loadedPlayer.play()
                heldThisOpen = true
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "openItem: '${item.name}' already loaded → HOLD (no rebuild, no reload)"
                )
                return true
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
            com.powermediaplayer.util.Diag.i(
                "PowerMediaPlayer",
                "openItem: name=${item.name} ext=$nameExt mime=${item.mimeType} → isVideo=$isVideo"
            )
            val extras = android.os.Bundle().apply {
                putBoolean("is_video_hint", isVideo)
            }
            // Reverse mode: Drive audio up to the 50 MB guard (download
            // cost — cached after the first run). Falls back to forward
            // playback with a snackbar when the file is too large.
            val playUri = if (!isVideo &&
                settingsDataStore.audioReverseLocal.first()
            ) {
                com.powermediaplayer.audio.ReverseAudio
                    .ensureReversedWav(context, uri)
                    .map { android.net.Uri.fromFile(it) }
                    .onFailure { t ->
                        _uiState.value = _uiState.value.copy(
                            errorMessage = t.message ?: "Reverse mode failed — playing forward"
                        )
                    }
                    .getOrDefault(uri)
            } else uri
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(playUri)
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
            // Placeholder metadata. If this item was already enriched this
            // session (e.g. returning from a cast re-fires openItem), reuse the
            // cached embedded tags/art instantly instead of flashing the
            // filename — and skip the re-download below. Otherwise: filename +
            // Drive's auto-thumbnail, replaced when the extraction finishes.
            val cachedEnriched = enrichedByMediaId[item.id]
            playbackConnection.setLocalMetadata(
                cachedEnriched ?: LocalMetadataOverride(
                    title = item.name,
                    artworkUri = item.thumbnailUri
                )
            )

            // Drive: chapters + metadata + artwork live INSIDE the file
            // (moov box for MP4/M4B, ID3 for MP3, etc.) and MediaExtractor /
            // MediaMetadataRetriever cannot reach an authenticated HTTPS URL
            // directly. Download to cache once, run BOTH parsers, push the
            // results to the player. Streaming continues unaffected. Skipped
            // when we already have this item's tags cached this session.
            if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder &&
                cachedEnriched == null) {
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
        com.powermediaplayer.util.Diag.i(
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
                    val override = LocalMetadataOverride(
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
                    playbackConnection.setLocalMetadata(override)
                    // Cache for instant, no-re-download restore on the next
                    // open of this item this session (cast-return, re-tap).
                    enrichedByMediaId[item.id] = override
                    if (artBytes != null) found = true
                }
                com.powermediaplayer.util.Diag.i(
                    "PowerMediaPlayer",
                    "MMR result: title=$title artist=$artist album=$album " +
                        "artBytes=${artBytes?.size ?: 0}"
                )
            }
        }
        runCatching {
            val bundle = M4bChapterParser.extractChaptersAsBundle(context, tempUri)
            val count = bundle.getInt("chapter_count", 0)
            com.powermediaplayer.util.Diag.i("PowerMediaPlayer", "M4B parser: chapter_count=$count")
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
                provider == CloudProviderType.GOOGLE_DRIVE ->
                    coroutineScope {
                        // SAF walk and Drive REST search are independent —
                        // run together (audit 5.3).
                        val saf = async {
                            driveProvider.searchFiles(query).getOrDefault(emptyList())
                        }
                        val oauth = async {
                            driveOAuthProvider.searchFiles(query).getOrDefault(emptyList())
                        }
                        saf.await() + oauth.await()
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
