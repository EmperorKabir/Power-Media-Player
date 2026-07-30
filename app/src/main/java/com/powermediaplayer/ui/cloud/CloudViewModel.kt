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
import com.powermediaplayer.service.LocalMetadataOverride
import com.powermediaplayer.service.PlaybackConnection
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val searchInProgress: Boolean = false,
    // Snapshot of the search the user drilled into an album/series FROM, so
    // pressing back restores the search results instead of dropping to the
    // section listing. Blank when the current view wasn't reached via search.
    val priorSearchQuery: String = "",
    val priorSearchResults: List<CloudMediaItem> = emptyList(),
    val spotifySection: com.powermediaplayer.cloud.SpotifySection? = null,
    // Audit B2: the source-picker chooser state (pickerRoots/pickerRootsVisible)
    // was written but never read — the Samsung-drawer workaround it backed was
    // superseded by the Drive Picker WebView path. Removed with its plumbing.

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

/** Enriched display fields for a media row (Title + Artist/Album subtext). */
data class RowMeta(val title: String?, val artist: String?, val album: String?)

@HiltViewModel
class CloudViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val driveProvider: GoogleDriveProvider,
    private val driveOAuthProvider: com.powermediaplayer.cloud.DriveOAuthProvider,
    private val spotifyProvider: SpotifyProvider,
    private val playbackConnection: PlaybackConnection,
    private val settingsDataStore: SettingsDataStore,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    private val driveTagEnricher: com.powermediaplayer.cloud.DriveTagEnricher,
    val mediaOverrideDao: com.powermediaplayer.data.db.dao.MediaOverrideDao,
    private val offlineCopyDao: com.powermediaplayer.data.db.dao.OfflineCopyDao,
    private val playbackHistoryDao: com.powermediaplayer.data.db.dao.PlaybackHistoryDao,
    private val enrichmentCacheDao: com.powermediaplayer.data.db.dao.EnrichmentCacheDao,
    private val podcastDao: com.powermediaplayer.data.db.dao.PodcastDao,
    private val offlineMediaManager: com.powermediaplayer.offline.OfflineMediaManager
) : ViewModel() {

    // #16 — favourite-time enrich: dedup guard + a non-blocking "enriching…"
    // hint (ids currently being enriched in the background).
    private val enrichInFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private val _enrichingIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val enrichingIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _enrichingIds.asStateFlow()

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

    /** {driveId → extracted cover URI} for enriched items, so the Cloud list shows the real
     *  cover (pulled at favourite/browse/play time) instead of Drive's unreliable thumbnail or
     *  a bare icon. Rows whose file:// target was LRU-evicted from ArtworkCache are filtered
     *  out here (a dangling row must render as the icon, and the sweep re-enriches it). */
    val enrichedCovers: kotlinx.coroutines.flow.StateFlow<Map<String, String>> =
        enrichmentCacheDao.observeCovered()
            .map { rows ->
                rows.mapNotNull { r ->
                    val url = r.artworkUrl ?: return@mapNotNull null
                    val alive = !url.startsWith("file:") ||
                        (android.net.Uri.parse(url).path?.let { java.io.File(it).exists() } == true)
                    if (alive) r.cacheKey to url else null
                }.toMap()
            }
            .conflate() // a peek storm emits fast; only the latest map matters
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000) /* audit B2#3: was Eagerly */,
                emptyMap()
            )

    /** A downloaded (offline) Drive file surfaced in the Cloud §1 "Drive favourites
     *  & downloads" section — title/subtext joined from the enrichment cache. */
    data class DownloadedDriveItem(
        val driveFileId: String,
        val driveUri: String,
        val title: String,
        val subtext: String
    )

    /** Offline Drive copies (for Cloud §1 "& downloads"). Mirrors the Library
     *  Downloaded join so the same file reads identically in both surfaces. */
    val downloadedDriveItems: kotlinx.coroutines.flow.StateFlow<List<DownloadedDriveItem>> =
        kotlinx.coroutines.flow.combine(
            offlineCopyDao.observeAll(),
            enrichmentCacheDao.observeEnriched()
        ) { rows, enriched ->
            val meta = enriched.associateBy { it.cacheKey }
            rows.map { r ->
                val driveUri = if (r.driveFileId.startsWith("content://")) r.driveFileId
                    else "https://www.googleapis.com/drive/v3/files/${r.driveFileId}?alt=media"
                val rawName = r.displayName.ifBlank {
                    java.io.File(r.localPath).name.ifBlank { "Drive file" }
                }
                val m = meta[r.driveFileId]
                val kind = com.powermediaplayer.util.MediaClassifier
                    .classifyAudioSubKind(rawName, hasChapters = false, isPodcast = false)
                val d = com.powermediaplayer.util.MediaRowText.of(
                    title = m?.title, artist = m?.artist, album = m?.album,
                    fileName = rawName, kind = kind
                )
                DownloadedDriveItem(r.driveFileId, driveUri, d.primary, d.subtext)
            }
        }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Enriched title/artist/album for a Drive item, keyed by its cacheKey (==
     *  CloudMediaItem.id / DriveFavourite.id). Lets a Drive row show the real
     *  Title + "Artist, Album, Filename" subtext instead of the raw filename. */
    val enrichedMeta: kotlinx.coroutines.flow.StateFlow<Map<String, RowMeta>> =
        enrichmentCacheDao.observeEnriched()
            .map { rows -> rows.associate { it.cacheKey to RowMeta(it.title, it.artist, it.album) } }
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                emptyMap()
            )

    // P7 — persisted expand/collapse of the Cloud-tab favourites/downloads sections.
    // WhileSubscribed: these are only observed while the Cloud tab is on-screen;
    // initialValue=true already equals the DataStore default, so there is no
    // first-frame flash to protect against with Eagerly (audit D2).
    val cloudDriveSectionExpanded: kotlinx.coroutines.flow.StateFlow<Boolean> =
        settingsDataStore.cloudDriveSectionExpanded
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), true)
    val cloudSpotifySectionExpanded: kotlinx.coroutines.flow.StateFlow<Boolean> =
        settingsDataStore.cloudSpotifySectionExpanded
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), true)
    val cloudPodcastSectionExpanded: kotlinx.coroutines.flow.StateFlow<Boolean> =
        settingsDataStore.cloudPodcastSectionExpanded
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), true)
    fun setCloudSectionExpanded(which: String, expanded: Boolean) {
        viewModelScope.launch { settingsDataStore.setCloudSectionExpanded(which, expanded) }
    }

    // P7 §3 — downloaded-podcast titles for the collapsible Downloads preview
    // below the Podcasts area. Read-only preview; playback/management stays in
    // the dedicated Downloads manager (DownloadsScreen), which already owns the
    // podcast resume/position wiring — nothing duplicated here.
    val downloadedPodcastTitles: kotlinx.coroutines.flow.StateFlow<List<String>> =
        podcastDao.observeDownloaded()
            .map { rows -> rows.map { it.title } }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun hasOfflineCopy(driveId: String): Boolean = offlineDrivePairs.value.containsKey(driveId)

    /** Drive ids with an in-flight offline save — drives the row's spinner. */
    private val _savingOffline = MutableStateFlow<Set<String>>(emptySet())
    val savingOffline: StateFlow<Set<String>> = _savingOffline

    /** Recent Cloud/Spotify search queries, most-recent-first (persisted). */
    val recentSearches: StateFlow<List<String>> =
        settingsDataStore.recentSearchesCloud.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList()
        )
    fun clearRecentSearches() {
        viewModelScope.launch { settingsDataStore.clearRecentSearchesCloud() }
    }

    fun saveDriveOffline(item: CloudMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (hasOfflineCopy(item.id)) {
                _uiState.update { it.copy(errorMessage = "Already saved offline: ${item.name}") }
                return@launch
            }
            // P4 — run the download in a FOREGROUND WorkManager worker so it survives
            // leaving the tab / backgrounding the app / process death. Single source of
            // truth: OfflineMediaManager.downloadForeground owns the mobile-data
            // pre-check, the stale-terminal-safe await, and the offline-hang bound.
            _savingOffline.update { it + item.id }
            _uiState.update { it.copy(errorMessage = "Saving offline: ${item.name}") }
            val outcome = offlineMediaManager.downloadForeground(item.downloadUrl, item.name)
            _savingOffline.update { it - item.id }
            _uiState.update {
                it.copy(errorMessage = when (outcome) {
                    is com.powermediaplayer.offline.OfflineDownloadOutcome.Success ->
                        "Saved offline: ${item.name}"
                    is com.powermediaplayer.offline.OfflineDownloadOutcome.Background ->
                        "Downloading in the background: ${item.name}"
                    is com.powermediaplayer.offline.OfflineDownloadOutcome.Failed ->
                        outcome.message
                })
            }
        }
    }

    /** Cancel an in-flight Drive offline save. The shared copy path throws at the
     *  next chunk; the provider deletes the partial cache file; saveDriveOffline's
     *  null branch skips the DB write. No fragment is left behind. */
    fun cancelDownload(id: String) {
        com.powermediaplayer.util.DownloadProgressBus.requestCancel(id)
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "cancelDownload requested id=$id")
    }

    /** §C28 — download every not-yet-offline audio file in the current Drive
     *  folder. Each runs through saveDriveOffline (own spinner + LRU + status). */
    fun saveFolderOffline(items: List<CloudMediaItem>) {
        val targets = items.filter {
            !it.isFolder && it.sourceProvider == CloudProviderType.GOOGLE_DRIVE &&
                it.mimeType.startsWith("audio/") && !hasOfflineCopy(it.id)
        }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Nothing to download (already offline or no audio).") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // One message for the whole folder, before the per-file fan-out.
            if (com.powermediaplayer.util.MobileDataPolicy.downloadsBlocked(context, settingsDataStore)) {
                _uiState.update {
                    it.copy(errorMessage = com.powermediaplayer.util.MobileDataPolicy.BLOCKED_MESSAGE)
                }
                return@launch
            }
            _uiState.update { it.copy(errorMessage = "Downloading ${targets.size} file(s) offline…") }
            targets.forEach { saveDriveOffline(it) }
        }
    }

    /** §C28 — copies saved before v18 have a blank displayName → the Downloads
     *  list shows the opaque cache filename. Look up the real Drive name once
     *  (OAuth REST or SAF DocumentFile) and store it. */
    private fun backfillOfflineNames() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching { offlineCopyDao.observeAll().first() }.getOrNull() ?: return@launch
            rows.filter { it.displayName.isBlank() }.forEach { row ->
                val name = if (row.driveFileId.startsWith("content://")) {
                    runCatching {
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(context, android.net.Uri.parse(row.driveFileId))?.name
                    }.getOrNull()
                } else {
                    driveOAuthProvider.fetchFileName(row.driveFileId)
                }
                if (!name.isNullOrBlank()) {
                    runCatching { offlineCopyDao.setDisplayName(row.driveFileId, name) }
                }
            }
        }
    }

    fun removeDriveOffline(driveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = offlineDrivePairs.value[driveId]
            if (path != null) deleteOfflinePath(path)
            settingsDataStore.removeOfflineDrive(driveId)
            offlineCopyDao.delete(driveId)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "C28 removed offline id=$driveId")
        }
    }

    /** Delete a stored offline copy, branching on uri scheme (SAF vs file). */
    private fun deleteOfflinePath(path: String) {
        if (path.startsWith("content://")) {
            com.powermediaplayer.util.SafStorage.delete(context, android.net.Uri.parse(path))
        } else {
            runCatching { java.io.File(path).delete() }
        }
    }

    // P4/P5.1 — relocateDriveOffline + mimeForName removed: downloads now run through
    // OfflineDownloadWorker → OfflineMediaManager (which owns the identical relocate +
    // SAF logic), so these CloudViewModel copies are dead.

    /** §C28/Part 3.2 — set of currently-pinned offline driveFileIds (reactive). */
    val starredOfflineIds: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        offlineCopyDao.observeAll()
            .map { rows -> rows.filter { it.isStarred }.map { it.driveFileId }.toSet() }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000) /* audit B2#3: was Eagerly */,
                emptySet()
            )

    /** §C28/Part 3.2 — pin/unpin an offline copy so the LRU evictor spares it. */
    fun toggleStarredOffline(driveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = offlineCopyDao.get(driveId) ?: return@launch
            val now = !row.isStarred
            offlineCopyDao.setStarred(driveId, now)
            _uiState.update {
                it.copy(errorMessage = if (now) "Pinned, protected from auto-cleanup" else "Unpinned")
            }
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

    // P4/P5.1 — evictOfflineLruIfOverLimit moved to OfflineMediaManager.evictLruIfOverLimit
    // so every download path (Cloud, Player, Last Played, worker) enforces the cap.

    private fun recordCloudPlay(item: CloudMediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val source = when (item.sourceProvider) {
                    CloudProviderType.SPOTIFY -> "SPOTIFY"
                    CloudProviderType.GOOGLE_DRIVE -> "DRIVE"
                    else -> "DRIVE"
                }
                val uri = if (item.downloadUrl.isNotBlank()) item.downloadUrl else item.id
                // Never store a RAW filename ("…[ASIN].m4b") as the title when a
                // clean enriched title is already known for this item (a prior
                // session's clean row): that raw row would become the "newest"
                // cold-start resumes + Last Played shows. Heal from the sibling,
                // no re-download; falls back to the name on a true first play (the
                // tap-enrich heals every row for the uri shortly after).
                val cleanRow = if (com.powermediaplayer.util.MediaClassifier
                        .looksLikeRawMediaFilename(item.name))
                    lastPlayedRepo.cleanRowForUri(uri) else null
                // Clean the filename before it's PERSISTED, not just displayed: a
                // raw "…[ASIN].m4b" stored here seeds cold-start resume before the
                // enricher heals the row. cleanFileTitle is idempotent on an already
                // clean name, so this is safe when looksLikeRaw was false.
                val title = cleanRow?.title
                    ?: com.powermediaplayer.util.TextNormalizer.cleanFileTitle(item.name)
                // Carry the clean author too (the source label is only a pre-enrich
                // placeholder). P3.2 — for SPOTIFY, use the item's own subtitle (the
                // artist Spotify already resolves) so the Recents row shows the artist
                // instead of the bare "SPOTIFY" label. Scoped to Spotify so the Drive
                // path is byte-identical to before (Drive item.subtitle is blank at play
                // time and is healed by the enricher, which must not change).
                val subtitle = cleanRow?.subtitle?.takeIf { it.isNotBlank() && it != source }
                    ?: if (item.sourceProvider == CloudProviderType.SPOTIFY)
                        item.subtitle.ifBlank { source } else source
                lastPlayedRepo.recordPlay(
                    com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                        mediaUri = uri,
                        title = title,
                        subtitle = subtitle,
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
        backfillOfflineNames()
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
                                // "Artist, Album" to match the browse/favourite subtext.
                                subtitle = listOf(s.artist, s.album)
                                    .filter { it.isNotBlank() }.joinToString(", ")
                                    .ifBlank { s.album },
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
            val nowFavourited = settingsDataStore.toggleDriveFavouriteTrack(item.id, item.name)
            if (nowFavourited) maybeEnrichFavourite(item)
        }
    }

    /**
     * #16 (D6) — when a Drive item is favourited, enrich it in the background so
     * its author/series are searchable BEFORE first play. Always-on (no flag, no
     * Wi-Fi/size gate — user directive). Deduped; the enricher reuses a durable
     * offline copy internally (no re-download). Off the UI thread → no freeze.
     */
    private fun maybeEnrichFavourite(item: CloudMediaItem) {
        val id = item.id
        viewModelScope.launch(Dispatchers.IO) {
            // Backfill first: an earlier PLAY already wrote the cover bytes to the
            // durable ArtworkCache (keyed by downloadUrl) — surface them with zero
            // network by writing the missing enrichment row. This is what fixes the
            // played-before-favourited case (G1): the old skip trusted the history
            // row as "already enriched" although play-time enrichment never wrote
            // the row the Cloud list reads covers from.
            val row = runCatching { enrichmentCacheDao.get(id) }.getOrNull()
            if (row?.artworkUrl == null) {
                com.powermediaplayer.util.ArtworkCache
                    .uriFor(context, item.downloadUrl)
                    ?.let { writeArtRow(id, row, it.toString()) }
            }
            // Skip ONLY on real evidence: a row written by the FULL enricher parse
            // (tags AND art-or-confirmed-no-art). A peek/backfill row (art only, no
            // search tags) still gets the full parse so #16 search keeps working; a
            // legacy artless row (artworkUrl null) retries once and converges to
            // art or the NO_ART sentinel.
            val fresh = runCatching { enrichmentCacheDao.get(id) }.getOrNull()
            val alreadyEnriched = fresh != null && fresh.artworkUrl != null &&
                fresh.provider ==
                com.powermediaplayer.data.db.entity.EnrichmentCacheEntity.PROVIDER_DRIVE_FULL
            val offlinePath = runCatching { offlineCopyDao.get(id)?.localPath }.getOrNull()
            val inFlight = !enrichInFlight.add(id)
            val plan = com.powermediaplayer.cloud.FavouriteEnrichPlanner.plan(
                alreadyEnriched = alreadyEnriched,
                offlineLocalPath = offlinePath,
                inFlight = inFlight
            )
            if (plan == com.powermediaplayer.cloud.EnrichPlan.Skip) {
                if (!inFlight) enrichInFlight.remove(id) // release our own add
                return@launch
            }
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Cloud.favouriteEnrich plan=$plan id=$id")
            _enrichingIds.update { it + id }
            // Non-blocking hint (the Cloud screen surfaces this as a transient
            // status); enrichingIds is also exposed for a per-row indicator.
            _uiState.update { it.copy(errorMessage = "Updating \"${item.name}\" for search…") }
            // enrich() reuses an offline copy when present (FromLocal) or downloads
            // (FromDownload); writeSearchCache stores the extracted tags in
            // enrichment_cache so the item is searchable. onDone clears the
            // in-flight guard + hint when the background job completes.
            driveTagEnricher.enrich(
                viewModelScope, item, item.downloadUrl,
                silent = true,
                writeSearchCache = true,
                onDone = {
                    enrichInFlight.remove(id)
                    _enrichingIds.update { it - id }
                }
            )
        }
    }

    /** Upsert an art-only enrichment row for [id], preserving any existing search
     *  tags. Marked PEEK unless the existing row already came from the full parse. */
    private suspend fun writeArtRow(
        id: String,
        existing: com.powermediaplayer.data.db.entity.EnrichmentCacheEntity?,
        artUrl: String
    ) {
        // Audit (superpowers #4): re-read at write time; the caller snapshot can
        // predate a FULL enrich that landed while a peek fetch was in flight, and
        // merging from the stale snapshot would clobber the full row tags.
        val fresh = runCatching { enrichmentCacheDao.get(id) }.getOrNull() ?: existing
        runCatching {
            enrichmentCacheDao.put(
                com.powermediaplayer.data.db.entity.EnrichmentCacheEntity(
                    cacheKey = id,
                    provider = fresh?.provider
                        ?: com.powermediaplayer.data.db.entity
                            .EnrichmentCacheEntity.PROVIDER_DRIVE_PEEK,
                    title = fresh?.title, artist = fresh?.artist,
                    album = fresh?.album, year = fresh?.year,
                    genre = fresh?.genre,
                    artworkUrl = artUrl,
                    fetchedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Reconciliation sweep — the favourite-art pipeline is state-driven, not
     * event-driven: whatever a tap's timing was (favourited mid-load, fetch died on
     * bad network, cover LRU-evicted), the next Cloud visit converges every
     * favourite to "covered or confirmed artless". Once per PROCESS (companion
     * guard — a ViewModel recreation must not re-run it); silent. Heavy full
     * enriches are capped per pass; an art-only loss on a FULL row heals via the
     * cheap Range peek (tags are intact — no full re-download).
     */
    fun sweepFavouriteCovers() {
        if (!sweepDone.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val favs = runCatching { settingsDataStore.driveFavouriteTracks.first() }
                .getOrNull() ?: return@launch
            var fullEnriches = 0
            for (fav in favs) {
                val row = runCatching { enrichmentCacheDao.get(fav.id) }.getOrNull()
                val art = row?.artworkUrl
                val fileGone = art != null &&
                    art != com.powermediaplayer.data.db.entity.EnrichmentCacheEntity.NO_ART &&
                    art.startsWith("file:") &&
                    android.net.Uri.parse(art).path?.let { !java.io.File(it).exists() } == true
                if (row != null && art != null && !fileGone) continue
                // SAF content:// favourites have no REST metadata endpoint (and no
                // Range API); their covers heal on the play/download paths instead.
                if (fav.id.startsWith("content://")) continue
                val item = runCatching { driveOAuthProvider.getFileMetadata(fav.id) }
                    .getOrNull() ?: continue
                if (fileGone) {
                    // Clear the dangling pointer either way.
                    runCatching { enrichmentCacheDao.put(row!!.copy(artworkUrl = null)) }
                    if (row!!.provider ==
                        com.powermediaplayer.data.db.entity
                            .EnrichmentCacheEntity.PROVIDER_DRIVE_FULL
                    ) {
                        // Tags intact, only the cached image was LRU-evicted —
                        // recover the art with the CHEAP Range peek, not a full
                        // audiobook re-download.
                        peekAttempted.remove(fav.id)
                        peekCoverIfNeeded(item)
                        continue
                    }
                }
                if (fullEnriches >= MAX_SWEEP_FULL_ENRICHES) continue
                fullEnriches++
                maybeEnrichFavourite(item)
            }
        }
    }

    private companion object {
        // Process-wide (NOT per-ViewModel) guards: a hiltViewModel() instance is
        // scoped to the nav destination, so instance fields would re-run the sweep
        // and re-attempt peeks on every recreation.
        val peekAttempted: MutableSet<String> =
            java.util.Collections.synchronizedSet(HashSet<String>())
        val peekSemaphore = Semaphore(2)
        val sweepDone = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Heavy (potentially full-file) favourite enriches allowed per sweep pass;
         *  the remainder converges on later passes — bounds fresh-install churn. */
        const val MAX_SWEEP_FULL_ENRICHES = 3
    }

    /**
     * Extract and persist the embedded cover for a listed (not downloaded, not
     * necessarily favourited) Drive audio file. Bounded: a 4 MB head Range fetch
     * (ID3/FLAC/faststart-MP4 art lives at the start), then for MP4-family files an
     * 8 MB tail Range fetch (moov-at-end `covr`). At most 2 in flight; one attempt
     * per item per process; the outcome is persisted (cover URI or the NO_ART
     * sentinel) so future sessions skip the network entirely. A FAILED fetch
     * persists nothing — it stays retryable via the sweep/favourite/play paths.
     */
    fun peekCoverIfNeeded(item: CloudMediaItem) {
        if (item.isFolder || item.sourceProvider != CloudProviderType.GOOGLE_DRIVE) return
        if (item.id.startsWith("content://")) return // SAF: no Range API; covered on play/download
        if (com.powermediaplayer.util.MediaClassifier.isVideoByName(item.name, item.mimeType)) return
        if (enrichedCovers.value.containsKey(item.id)) return
        if (!peekAttempted.add(item.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val row = runCatching { enrichmentCacheDao.get(item.id) }.getOrNull()
            val art = row?.artworkUrl
            if (art == com.powermediaplayer.data.db.entity.EnrichmentCacheEntity.NO_ART) {
                return@launch // confirmed artless — durable verdict
            }
            val artAlive = art != null && (!art.startsWith("file:") ||
                android.net.Uri.parse(art).path?.let { java.io.File(it).exists() } == true)
            if (artAlive) return@launch
            // (art == null, OR a dangling file:// row after LRU eviction — repair it
            // with a fresh peek rather than leaving the icon forever.)
            if (runCatching { offlineCopyDao.get(item.id)?.localPath }.getOrNull() != null) {
                return@launch // row renders the local copy's own embedded art already
            }
            // Free path: a prior play left the cover in the durable ArtworkCache.
            com.powermediaplayer.util.ArtworkCache.uriFor(context, item.downloadUrl)?.let {
                writeArtRow(item.id, row, it.toString())
                return@launch
            }
            peekSemaphore.withPermit {
                when (val peek = peekEmbeddedArt(item)) {
                    is PeekResult.Found -> {
                        val uri = com.powermediaplayer.util.ArtworkCache
                            .write(context, item.downloadUrl, peek.bytes)
                        if (uri != null) writeArtRow(item.id, row, uri.toString())
                    }
                    PeekResult.NoArt -> writeArtRow(
                        item.id, row,
                        com.powermediaplayer.data.db.entity.EnrichmentCacheEntity.NO_ART
                    )
                    // Retryable outcomes: write nothing AND release the attempt slot
                    // so a later row composition (Wi-Fi regained, network back) can
                    // try again. The semaphore still bounds concurrency.
                    PeekResult.Inconclusive, PeekResult.FetchFailed ->
                        peekAttempted.remove(item.id)
                }
            }
        }
    }

    private sealed interface PeekResult {
        data class Found(val bytes: ByteArray) : PeekResult

        /** HIGH-confidence absence — safe to persist the durable NO_ART verdict. */
        object NoArt : PeekResult

        /** LOW-confidence absence (parser limits, metered tail skipped, unknown
         *  size, retriever threw) — must NOT be persisted as artless. */
        object Inconclusive : PeekResult
        object FetchFailed : PeekResult
    }

    private suspend fun peekEmbeddedArt(item: CloudMediaItem): PeekResult {
        // The WHOLE peek (4 MB head + 8 MB tail) honours Settings, Cloud,
        // "Download cover art on mobile data": on a metered network with the
        // toggle off, fetch nothing and stay Inconclusive, so the item retries
        // on Wi-Fi or after the toggle flips. Android flags cellular metered
        // regardless of the plan, so unlimited-plan users need the toggle.
        if (isMeteredNetwork() &&
            !runCatching { settingsDataStore.coverArtOnMobileData.first() }.getOrDefault(false)
        ) return PeekResult.Inconclusive
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mp4Family = ext in setOf("m4b", "m4a", "mp4", "m4v", "mov")
        // Formats whose art sits at the file START and which MediaMetadataRetriever
        // reads reliably — for these a clean null from the head IS high confidence.
        // (ogg/opus pictures are NOT reliably read by MMR: never mark those NO_ART.)
        val headConfident = ext in setOf("mp3", "flac", "wav", "wma")
        val headBytes = 4L * 1024 * 1024
        val head = runCatching {
            driveOAuthProvider.downloadRangeToCache(item, 0L, headBytes - 1, "peekhead")
        }.getOrNull() ?: return PeekResult.FetchFailed
        var retrieverThrew = false
        try {
            val art = runCatching {
                android.media.MediaMetadataRetriever().use { mmr ->
                    mmr.setDataSource(head.path)
                    mmr.embeddedPicture
                }
            }.onFailure { retrieverThrew = true }.getOrNull()
            if (art != null && art.isNotEmpty()) return PeekResult.Found(art)
        } finally {
            runCatching { head.delete() }
        }
        if (item.size in 1..headBytes) {
            // The whole file was examined; a retriever failure still isn't proof.
            return if (retrieverThrew) PeekResult.Inconclusive else PeekResult.NoArt
        }
        if (!mp4Family) {
            return if (headConfident && !retrieverThrew) PeekResult.NoArt
            else PeekResult.Inconclusive
        }
        if (item.size <= 0L) return PeekResult.Inconclusive // no size → no tail Range
        val tailBytes = 8L * 1024 * 1024
        val start = (item.size - tailBytes).coerceAtLeast(0L)
        val tail = runCatching {
            driveOAuthProvider.downloadRangeToCache(item, start, item.size - 1, "peektail")
        }.getOrNull() ?: return PeekResult.FetchFailed
        return try {
            val art = runCatching {
                com.powermediaplayer.util.Mp4CoverTailParser
                    .extractCoverFromBuffer(tail.readBytes())
            }.getOrNull()
            when {
                art != null -> PeekResult.Found(art)
                // QuickTime .mov meta layouts vary; a clean miss there is weaker
                // evidence than for iTunes-style m4b/m4a/mp4.
                ext == "mov" -> PeekResult.Inconclusive
                else -> PeekResult.NoArt
            }
        } finally {
            runCatching { tail.delete() }
        }
    }

    private fun isMeteredNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(true)

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
                _uiState.update { it.copy(errorMessage = "Couldn't load $name, it may have been removed from Drive.") }
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
                _uiState.update { it.copy(
                    errorMessage = "Playing on Spotify: $name"
                ) }
                onPlaybackStarted()
            }.onFailure { ex ->
                // vc32: never leave a provisional mirror for a
                // track that failed to play.
                spotifyProvider.clearProvisionalMirror()
                _uiState.update { it.copy(
                    errorMessage = ex.message ?: "Spotify playback failed"
                ) }
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
        // Capture the artist (subtitle) + cover (thumbnailUri) at star time so the
        // favourite row shows them without a live Spotify session (metadata feature).
        val artist = item.subtitle
        val cover = item.thumbnailUri?.toString().orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            when {
                uri.startsWith("spotify:track") ->
                    settingsDataStore.toggleSpotifyFavouriteTrack(uri, item.name, artist = artist, imageUrl = cover)
                uri.startsWith("spotify:album") ->
                    settingsDataStore.toggleSpotifyFavouriteAlbum(uri, item.name, artist = artist, imageUrl = cover)
                uri.startsWith("spotify:show") || uri.startsWith("spotify:episode") ->
                    settingsDataStore.toggleSpotifyFavouritePodcast(uri, item.name, artist = artist, imageUrl = cover)
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

    // I2 sticky-sign-in fixes. See buildSpotifyAuthIntent.
    @Volatile private var lastSpotifyAuthLaunchMs: Long = 0L

    /**
     * Returns the Spotify AppAuth intent to launch, or NULL when a launch is
     * already in flight and the tap is a rapid repeat (I2 sticky-fix a): the
     * ColorOS Custom Tab takes ~1 s to appear, so a user who taps again — thinking
     * nothing happened — used to launch a SECOND AppAuth flow that CANCELS the
     * first, and the cancelled one returns "User cancelled flow" → the sign-in
     * silently aborts. The caller must null-guard: `...()?.let { launcher.launch(it) }`.
     */
    fun buildSpotifyAuthIntent(): Intent? {
        val now = android.os.SystemClock.elapsedRealtime()
        // (a) De-bounce rapid re-taps while a launch is genuinely in flight.
        if (com.powermediaplayer.service.PlaybackService.oauthInFlight() &&
            now - lastSpotifyAuthLaunchMs < 4000L
        ) {
            com.powermediaplayer.diag.DiagLog.event(
                "SPOTIFYAUTH",
                "re-tap IGNORED (flow already launching ${now - lastSpotifyAuthLaunchMs}ms ago) — prevents cancel-storm"
            )
            return null
        }
        lastSpotifyAuthLaunchMs = now
        // Bug fix (user-reported "Spotify sign-in resumes playback"): mark OAuth
        // in-flight so PlaybackService.handleAudioFocusChange ignores the loss-then-
        // gain pair caused by the browser Custom Tab stealing audio focus.
        // Audit F2 — arm a 5-min DEADLINE (a real login can exceed 60 s). Deadline-
        // based, so it self-expires even if this ViewModel is destroyed before a
        // dropped result callback (the old viewModelScope timer could strand the
        // flag true → permanently suppress auto-resume). Cleared early in
        // handleSpotifyResult on the actual result.
        com.powermediaplayer.service.PlaybackService.armOauthInFlight(300_000L)
        com.powermediaplayer.diag.DiagLog.event(
            "SPOTIFYAUTH", "UI tap → launch auth intent, oauthInFlight armed (5min deadline)"
        )
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

    /** Persist a folder added via the native Drive folder browser. */
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
                    errorMessage = "Added \"$folderName\", opening it"
                )
            }
            browseDrive(folderId, folderName)
        }
    }

    /** Native folder browser: sub-folders of [parentId] ("root" = My Drive top). */
    suspend fun listDriveSubFolders(parentId: String): Result<List<com.powermediaplayer.cloud.CloudMediaItem>> =
        driveOAuthProvider.listSubFolders(parentId)

    /** True when signed in AND drive.readonly already granted (skip the sign-in step). */
    fun driveReadyForBrowse(): Boolean =
        driveOAuthProvider.currentAccountEmail() != null && !driveOAuthProvider.needsReadConsent()

    // Audit B2: openPickerChooser/dismissPickerChooser/buildDeepLinkedDriveIntent
    // were the never-invoked plumbing of the orphaned SourceChooserDialog
    // (superseded by the Drive Picker WebView). Removed; the provider's
    // buildSignInIntent/queryDocumentRoots API is untouched.

    fun handleDriveResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = driveProvider.handleSignInResult(data)
            _uiState.update { it.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            ) }
            if (result.isSuccess) browseDrive(null, "Root")
        }
    }

    fun handleSpotifyResult(data: Intent?) {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Cloud.handleSpotifyResult data=${data != null}")
        com.powermediaplayer.diag.DiagLog.event(
            "SPOTIFYAUTH", "UI result callback fired data=${data != null} (launcher returned)"
        )
        // Clear the OAuth-in-flight deadline immediately on result so AudioFocus
        // handling resumes its normal pause/duck/ignore policy from the next focus
        // event (F2: deadline-based, so no timer to cancel).
        com.powermediaplayer.service.PlaybackService.clearOauthInFlight()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = spotifyProvider.handleAuthResponse(data)
            _uiState.update { it.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            ) }
            if (result.isSuccess) browseSpotify()
        }
    }

    fun browseDrive(folderId: String?, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Switching provider/folder must drop any active search, else the
            // search-results branch keeps rendering the old (e.g. Spotify) hits.
            searchJob?.cancel()
            _uiState.update { it.copy(
                isLoading = true,
                activeProvider = CloudProviderType.GOOGLE_DRIVE,
                searchQuery = "",
                searchResults = emptyList()
            ) }
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
            _uiState.update { it.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = if (folderId == null) listOf(null to "Root")
                else it.folderStack + (folderId to label)
            ) }
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
        searchJob?.cancel()
        _uiState.update { it.copy(
            isLoading = false,
            activeProvider = CloudProviderType.SPOTIFY,
            items = emptyList(),
            spotifySection = null,
            errorMessage = null,
            searchQuery = "",
            searchResults = emptyList(),
            folderStack = listOf(null to "Spotify Library")
        ) }
    }

    fun openSpotifySection(section: com.powermediaplayer.cloud.SpotifySection) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                isLoading = true,
                activeProvider = CloudProviderType.SPOTIFY,
                spotifySection = section
            ) }
            val result = spotifyProvider.listSection(section)
            _uiState.update { it.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = listOf(null to "Spotify Library", null to section.label)
            ) }
        }
    }

    fun spotifyBackToSections() {
        _uiState.update { it.copy(
            spotifySection = null,
            items = emptyList(),
            folderStack = listOf(null to "Spotify Library")
        ) }
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
        // Suppress audio focus pause/gain across the bounce so the current playback
        // doesn't auto-resume on return. F2: arm a deadline covering the ~16 s poll
        // loop below (self-expires if this ViewModel dies mid-bounce).
        com.powermediaplayer.service.PlaybackService.armOauthInFlight(20_000L)
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
            com.powermediaplayer.service.PlaybackService.clearOauthInFlight()
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

    /** Reset the Cloud tab to its top level (provider picker) — used when the
     *  user taps the already-active Cloud nav tab again. */
    fun navigateToRoot() {
        searchJob?.cancel()
        _uiState.update { it.copy(
            activeProvider = null,
            spotifySection = null,
            items = emptyList(),
            folderStack = listOf(null to "Root"),
            searchQuery = "",
            searchResults = emptyList(),
            searchInProgress = false,
            priorSearchQuery = "",
            priorSearchResults = emptyList()
        ) }
    }

    fun navigateUp() {
        val stack = _uiState.value.folderStack
        // Backing out of an album/series that was drilled into FROM a search →
        // restore those search results (the user expects "back" to return one
        // search level, not drop to the section listing).
        if (_uiState.value.priorSearchQuery.isNotBlank() &&
            _uiState.value.activeProvider == CloudProviderType.SPOTIFY && stack.size >= 2
        ) {
            _uiState.update { it.copy(
                folderStack = stack.dropLast(1),
                items = emptyList(),
                searchQuery = it.priorSearchQuery,
                searchResults = it.priorSearchResults,
                priorSearchQuery = "",
                priorSearchResults = emptyList()
            ) }
            return
        }
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
            _uiState.update { it.copy(
                activeProvider = null,
                items = emptyList(),
                folderStack = listOf(null to "Root")
            ) }
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
                _uiState.update { it.copy(
                    items = result.getOrDefault(emptyList()),
                    folderStack = stack.dropLast(1)
                ) }
            }
        }
    }

    /**
     * @param onPlaybackStarted invoked ONLY when the item actually starts
     *   playing — used by the UI to navigate to the Player tab. Failures
     *   (Spotify previews removed, Drive 401, etc.) do not navigate.
     */
    /**
     * Play a Spotify ALBUM (or playlist) from the first track on the Connect
     * device, then set repeat=context so it loops back to the start at the end.
     * Next/previous traverse the album. Used by the play button on album rows;
     * tapping the row itself still BROWSES the album's tracks.
     */
    fun playSpotifyAlbum(item: CloudMediaItem, onPlaybackStarted: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { playbackConnection.pause() }
            val contextUri = if (item.downloadUrl.startsWith("spotify:")) item.downloadUrl
                else "spotify:${item.mimeType.substringAfter("application/spotify-")}:${item.id}"
            spotifyProvider.armProvisionalMirror(
                com.powermediaplayer.cloud.SpotifyPlaybackState(
                    title = item.name,
                    artist = item.subtitle,
                    album = item.name,
                    artworkUrl = item.thumbnailUri?.toString(),
                    positionMs = 0L,
                    durationMs = 0L,
                    isPlaying = true,
                    trackUri = contextUri,
                    deviceName = null,
                    contextUri = contextUri
                )
            )
            val r = spotifyProvider.playTrackOnConnectDevice(spotifyUri = null, contextUri = contextUri)
            if (r.isSuccess) {
                // #2 — setRepeat + setShuffle are independent of each other and of
                // the poll start; fire concurrently to save ~1 round trip of
                // perceived launch latency (both already runCatching-wrapped).
                val shuffleWanted = settingsDataStore.shuffleEnabled.first()
                coroutineScope {
                    launch { runCatching { spotifyProvider.setRepeat("context") } }
                    launch { runCatching { spotifyProvider.setShuffle(shuffleWanted) } }
                }
                spotifyProvider.startPlaybackPolling(expectPlayback = true, expectedTrack = contextUri)
                recordCloudPlay(item)
                _uiState.update { it.copy(errorMessage = "Playing album: ${item.name}") }
                onPlaybackStarted()
            } else {
                spotifyProvider.clearProvisionalMirror()
                _uiState.update { it.copy(
                    errorMessage = r.exceptionOrNull()?.message ?: "Spotify playback failed"
                ) }
            }
        }
    }

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
                    // Drilling in from SEARCH must drop the search, else the
                    // search-results branch keeps rendering and the album's
                    // tracks (now in `items`) never show.
                    searchJob?.cancel()
                    // Remember the search we're leaving so back-navigation can
                    // restore it (only capture when we actually came from a search
                    // — don't overwrite an earlier snapshot on a deeper drill-in).
                    val curQuery = _uiState.value.searchQuery
                    val curResults = _uiState.value.searchResults
                    val keepPriorQuery = if (curQuery.isNotBlank()) curQuery else _uiState.value.priorSearchQuery
                    val keepPriorResults = if (curQuery.isNotBlank()) curResults else _uiState.value.priorSearchResults
                    viewModelScope.launch(Dispatchers.IO) {
                        _uiState.update { it.copy(
                            isLoading = true,
                            searchQuery = "",
                            searchResults = emptyList()
                        ) }
                        val r = spotifyProvider.listContainer(containerUri)
                        val list = r.getOrDefault(emptyList())
                        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Cloud.openItem container loaded n=${list.size} first=${list.firstOrNull()?.name}")
                        _uiState.update { it.copy(
                            isLoading = false,
                            items = list,
                            searchQuery = "",
                            searchResults = emptyList(),
                            priorSearchQuery = keepPriorQuery,
                            priorSearchResults = keepPriorResults,
                            folderStack = it.folderStack + (containerUri to item.name),
                            errorMessage = r.exceptionOrNull()?.message
                        ) }
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
                    _uiState.update { it.copy(
                        errorMessage = "Playing on Spotify: ${item.name}"
                    ) }
                    onPlaybackStarted()
                }.onFailure { ex ->
                    // vc32: never leave a provisional mirror for a
                    // track that failed to play.
                    spotifyProvider.clearProvisionalMirror()
                    _uiState.update { it.copy(
                        errorMessage = ex.message ?: "Spotify playback failed"
                    ) }
                }
                // Track played WITHIN an album/playlist context → loop the
                // context so it returns to the start at the end (user request).
                if (r.isSuccess && !item.contextUri.isNullOrBlank()) {
                    runCatching { spotifyProvider.setRepeat("context") }
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
                _uiState.update { it.copy(
                    errorMessage = "Couldn't play: ${t.javaClass.simpleName}: ${t.message}"
                ) }
            }
        }
    }

    /**
     * Returns true iff playback actually started. False means an error was
     * recorded into [_uiState.errorMessage] and the caller should NOT
     * navigate away from the cloud screen.
     */
    // The Drive download+extract+persist logic lives in the shared
    // DriveTagEnricher (single source of truth, used by Cloud + Last Played) —
    // its in-session cache is shared, so a Cloud tap and a Last Played tap of
    // the same file reuse one download.

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
                // §C28 — offline copy may be a plain cache file OR a content://
                // doc in the user's chosen Drive folder. Verify it still exists,
                // then play it directly; else fall back to the network fetch.
                val isSaf = offlinePath.startsWith("content://")
                val localUri = if (isSaf) android.net.Uri.parse(offlinePath)
                    else android.net.Uri.fromFile(java.io.File(offlinePath))
                val present = if (isSaf)
                    com.powermediaplayer.util.SafStorage.exists(context, localUri)
                    else java.io.File(offlinePath).exists()
                if (present) kotlin.Result.success(localUri)
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
                _uiState.update { it.copy(
                    errorMessage = ex.message ?: "Cannot play this item"
                ) }
                return false
            }
            val uri = streamResult.getOrNull() ?: run {
                _uiState.update { it.copy(
                    errorMessage = "No playable URL for this item"
                ) }
                return false
            }
            // STABLE identity for this item, INDEPENDENT of whether we resolved to
            // the offline file or the network stream. mediaId + the resume-position
            // tick + enrichment all key on THIS, so an offline↔stream switch (e.g.
            // after trashing the download) keeps the SAME Recents row + resume
            // point. For streaming it already equals the resolved uri (no change);
            // only the offline case is realigned onto this stable stream key —
            // which is also what recordCloudPlay + cold-start use.
            val stableKey = item.downloadUrl.ifEmpty { item.id }
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
                loadedPlayer.currentMediaItem?.mediaId == stableKey &&
                // Don't "hold" an errored/idle player — play() won't re-prepare
                // it; fall through to a real reload so a retry actually retries.
                loadedPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                loadedPlayer.playerError == null
            ) {
                if (!loadedPlayer.playWhenReady) loadedPlayer.play()
                heldThisOpen = true
                // HOLD the player, but STILL load tags + chapters. A cold-start
                // auto-resume loads the item with only the filename (no
                // enrichment), so without this a tap would hold AND never enrich
                // → title/chapters never appear. Apply cached tags if we have
                // them, else run the Drive download+extract (no queue rebuild).
                val cached = driveTagEnricher.cached(item.id)
                if (cached != null) {
                    playbackConnection.setLocalMetadata(cached)
                } else if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE &&
                    !item.isFolder
                ) {
                    driveTagEnricher.enrich(viewModelScope, item, stableKey, writeSearchCache = true)
                }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "openItem: '${item.name}' already loaded → HOLD (no rebuild; " +
                        "tags ${if (cached != null) "from cache" else "enriching"})"
                )
                maybePrefetchNextCloud(item)
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
                        _uiState.update { it.copy(
                            errorMessage = t.message ?: "Reverse mode failed, playing forward"
                        ) }
                    }
                    .getOrDefault(uri)
            } else uri
            val mediaItem = MediaItem.Builder()
                .setMediaId(stableKey)
                .setUri(playUri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(uri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(com.powermediaplayer.util.TextNormalizer.cleanFileTitle(item.name))
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
            val cachedEnriched = driveTagEnricher.cached(item.id)
            playbackConnection.setLocalMetadata(
                cachedEnriched ?: LocalMetadataOverride(
                    // Don't pin a RAW filename as the override title — a non-blank override
                    // title outranks every lower layer, so "…[ID].m4b" would show for the
                    // whole enrich window. Nulling it for a raw name lets the item's OWN
                    // CLEANED title show meanwhile (set on the MediaItem via cleanFileTitle);
                    // the enricher then lands the real moov title into the override + cache.
                    // (The live stream-parsed title is deliberately NOT surfaced here — the
                    // cleaned-filename layer outranks it, to avoid Media3 sticky-title bleed.)
                    // Drive's black audio thumb is nulled at the provider, so artworkUri is
                    // null for audio and no longer masks the real cover.
                    title = item.name.takeUnless {
                        com.powermediaplayer.util.MediaClassifier.looksLikeRawMediaFilename(it)
                    },
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
                driveTagEnricher.enrich(viewModelScope, item, stableKey, writeSearchCache = true)
            }
            maybePrefetchNextCloud(item)
        }
        // Reaching here means setMediaItems was called — playback has been
        // handed to the service and (network permitting) will start.
        return true
    }

    /**
     * §T339 — make the "prefetch next cloud track" setting real (it was a dead
     * toggle). Drive plays one track at a time, so there is no in-player queue to
     * look ahead in — instead we pre-warm the embedded tags of the NEXT audio
     * track in the browsed folder. When the user taps it next, openItem finds the
     * tags already in [DriveTagEnricher.cache] and shows title/art/chapters with
     * no download wait. Silent: never flashes the current track's loading spinner.
     * Gated by the prefetchNextCloud preference (default on).
     */
    private fun maybePrefetchNextCloud(current: CloudMediaItem) {
        if (current.sourceProvider != CloudProviderType.GOOGLE_DRIVE || current.isFolder) return
        viewModelScope.launch {
            if (!settingsDataStore.prefetchNextCloud.first()) return@launch
            val items = _uiState.value.items
            val idx = items.indexOfFirst { it.id == current.id }
            if (idx < 0) return@launch
            val next = items.asSequence().drop(idx + 1).firstOrNull {
                !it.isFolder && it.sourceProvider == CloudProviderType.GOOGLE_DRIVE &&
                    it.mimeType.startsWith("audio/") &&
                    driveTagEnricher.cached(it.id) == null
            } ?: return@launch
            val nextKey = next.downloadUrl.ifEmpty { next.id }
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "prefetch next cloud tags: ${next.name}")
            driveTagEnricher.enrich(
                viewModelScope, next, nextKey, silent = true, writeSearchCache = true
            )
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Search the active provider. Debounced 300 ms so a fast typist's
     * keystrokes don't trigger an API call per character.
     */
    /**
     * #16 — search the local enriched metadata (played/offline Drive items) so a
     * query that isn't in the filename (e.g. the author "Matt") still finds the
     * item. Maps history/offline rows back to playable Drive [CloudMediaItem]s.
     * The drive.file scope blocks a true whole-Drive metadata search; this is the
     * within-scope way to surface non-filename matches.
     */
    private suspend fun searchDriveMetadata(query: String): List<CloudMediaItem> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList() // avoid trivially-broad scans
        val pattern = "%" + needle
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val hist = playbackHistoryDao.searchDriveMetadata(pattern).map { row ->
                CloudMediaItem(
                    id = row.mediaUri, name = row.title, mimeType = "audio/*",
                    size = 0L, downloadUrl = row.mediaUri,
                    thumbnailUri = row.artworkUri?.let { android.net.Uri.parse(it) },
                    sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                    subtitle = row.subtitle
                )
            }
            val offline = offlineCopyDao.searchDisplayName(pattern).map { row ->
                CloudMediaItem(
                    id = row.driveFileId, name = row.displayName, mimeType = "audio/*",
                    size = row.byteSize,
                    downloadUrl = "https://www.googleapis.com/drive/v3/files/${row.driveFileId}?alt=media",
                    sourceProvider = CloudProviderType.GOOGLE_DRIVE, subtitle = "Offline"
                )
            }
            // #16 — favourite-enriched items (never played) live in enrichment_cache
            // keyed by the Drive file id; reconstruct a playable item from the id.
            val enriched = enrichmentCacheDao.searchEnriched(pattern).mapNotNull { row ->
                // FULL rows only: PEEK rows carry art but no reliable tags, so they
                // would surface as bare-id results.
                if (row.provider !=
                    com.powermediaplayer.data.db.entity
                        .EnrichmentCacheEntity.PROVIDER_DRIVE_FULL
                ) return@mapNotNull null
                val id = row.cacheKey
                val dl = if (id.startsWith("content://")) id
                    else "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                CloudMediaItem(
                    id = id, name = row.title ?: id, mimeType = "audio/*", size = 0L,
                    downloadUrl = dl,
                    thumbnailUri = row.artworkUrl
                        ?.takeIf {
                            it != com.powermediaplayer.data.db.entity
                                .EnrichmentCacheEntity.NO_ART
                        }
                        ?.let { android.net.Uri.parse(it) },
                    sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                    subtitle = listOfNotNull(row.artist, row.album)
                        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                )
            }
            val playedOrOffline = com.powermediaplayer.cloud.DriveMetadataSearch
                .mergeDriveResults(hist, offline)
            com.powermediaplayer.cloud.DriveMetadataSearch
                .mergeDriveResults(playedOrOffline, enriched)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(
            searchQuery = query,
            searchInProgress = query.isNotBlank(),
            // A fresh search supersedes any saved drill-in snapshot.
            priorSearchQuery = if (query.isNotBlank()) "" else it.priorSearchQuery,
            priorSearchResults = if (query.isNotBlank()) emptyList() else it.priorSearchResults
        ) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val provider = _uiState.value.activeProvider
            val results = when {
                query.isBlank() -> emptyList()
                provider == CloudProviderType.GOOGLE_DRIVE ->
                    coroutineScope {
                        // SAF walk, Drive REST search, and the local enriched-
                        // metadata search are independent — run together (audit
                        // 5.3). #16 — the metadata search finds items by enriched
                        // author/series even when the needle isn't in the filename.
                        val saf = async {
                            driveProvider.searchFiles(query).getOrDefault(emptyList())
                        }
                        val oauth = async {
                            driveOAuthProvider.searchFiles(query).getOrDefault(emptyList())
                        }
                        val meta = async { searchDriveMetadata(query) }
                        val filename = saf.await() + oauth.await()
                        com.powermediaplayer.cloud.DriveMetadataSearch
                            .mergeDriveResults(filename, meta.await())
                    }
                provider == CloudProviderType.SPOTIFY -> {
                    // Surface a search failure (expired token / 401 / network) so it
                    // doesn't read as "no results" — mirrors the folder-open path.
                    val r = spotifyProvider.search(query)
                    r.exceptionOrNull()?.let { ex ->
                        _uiState.update { it.copy(
                            errorMessage = ex.message ?: "Spotify search failed"
                        ) }
                    }
                    r.getOrDefault(emptyList())
                }
                else -> emptyList()
            }
            _uiState.update { it.copy(searchResults = results, searchInProgress = false) }
            // Record the query in recent searches once it returns results — for
            // BOTH Drive and Spotify, and whether or not the user taps a result
            // (the earlier record-on-tap missed Drive + most Spotify searches).
            if (query.isNotBlank() && results.isNotEmpty()) {
                settingsDataStore.addRecentSearchCloud(query)
            }
        }
    }

    /**
     * Called by the UI after rendering an error message (Toast) so a
     * repeated tap on the same failing item re-triggers a new event.
     */
    fun clearError() {
        if (_uiState.value.errorMessage != null) {
            _uiState.update { it.copy(errorMessage = null) }
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
