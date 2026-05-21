package com.powermediaplayer.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.cloud.CloudMediaItem
import com.powermediaplayer.cloud.CloudProviderType
import com.powermediaplayer.ui.theme.*

private val SpotifyGreen = androidx.compose.ui.graphics.Color(0xFF1DB954)

/**
 * Cloud browser — shows provider sign-in cards when signed out and a
 * file/folder list once authenticated. Selecting a track hands it to
 * PlaybackConnection; the PlaybackService DataSource pipeline injects
 * the Drive bearer token transparently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBrowserScreen(
    viewModel: CloudViewModel = hiltViewModel(),
    onNavigateToPlayer: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showInfoSheet by remember { mutableStateOf(false) }
    var contextItem by remember { mutableStateOf<CloudMediaItem?>(null) }
    if (showInfoSheet) {
        com.powermediaplayer.ui.info.InfoSheet(
            data = com.powermediaplayer.ui.info.cloudInfo,
            onDismiss = { showInfoSheet = false }
        )
    }
    var overrideTarget by remember { mutableStateOf<com.powermediaplayer.cloud.CloudMediaItem?>(null) }
    overrideTarget?.let { tgt ->
        com.powermediaplayer.ui.overrides.MediaOverridesPopup(
            mediaUri = tgt.id,
            title = tgt.name,
            dao = viewModel.mediaOverrideDao,
            onDismiss = { overrideTarget = null }
        )
    }
    // §C16 — refresh-on-tab-open. Mirrors the Library tab pattern.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshIfStale()
    }

    // Refresh on every screen-resume (Compose ON_RESUME). Fixes the
    // user-reported bug: "when adding a file or folder from Google
    // Drive, it just stays on the sign-in screen but doesn't refresh
    // to show the added stuff." Adds + remove flow:
    //   - User taps "Sign in to Drive" → driveOAuthLauncher → external
    //     OAuth picker / Account chooser activity launches
    //   - User completes / cancels / returns
    //   - Our Activity → onResume fires
    //   - Composable observes the resume + forces refresh
    // Same hook also catches:
    //   - User adding files via Drive web in another tab then returning
    //   - User toggling Spotify on another device
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.forceRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    contextItem?.let { item ->
        val isSpotify = item.sourceProvider == CloudProviderType.SPOTIFY
        val isDriveTrack = item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder
        // §C25 — Drive favourites can carry per-file overrides keyed
        // by the SAF content:// URI. Drive OAuth (REST) has session-
        // dependent stream URLs that don't key stably, so override-*
        // surfaces only for SAF-imported tracks.
        val isFavDriveSaf = isDriveTrack && item.id.startsWith("content://") &&
            uiState.driveFavouriteTracks.any { it.id == item.id }
        com.powermediaplayer.ui.player.components.TrackContextSheet(
            title = item.name,
            subtitle = if (isSpotify) "Spotify" else "Drive",
            actions = com.powermediaplayer.ui.player.components.TrackContextActions(
                // Drive and Spotify tracks both support favouriting via
                // their respective viewmodel methods; folders skipped.
                onFavourite = if (!item.isFolder) {
                    {
                        if (isSpotify) viewModel.toggleSpotifyFav(item)
                        else if (isDriveTrack) viewModel.toggleDriveFavouriteTrack(item)
                        contextItem = null
                    }
                } else null,
                // Share opens system chooser with the canonical URL when
                // available (Spotify open.spotify.com or Drive view URL).
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT,
                            if (isSpotify) "https://open.spotify.com/track/${item.id.substringAfterLast(':')}"
                            else item.id)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share"))
                    contextItem = null
                },
                // §C28 — Drive offline copy. Spotify rows skip this
                // (DRM TOS); Drive rows surface Save / Remove based on
                // whether an offline pair exists.
                onSaveOffline = if (isDriveTrack && !viewModel.hasOfflineCopy(item.id)) {
                    { viewModel.saveDriveOffline(item); contextItem = null }
                } else null,
                onRemoveOffline = if (isDriveTrack && viewModel.hasOfflineCopy(item.id)) {
                    { viewModel.removeDriveOffline(item.id); contextItem = null }
                } else null,
                onOverrideSpeed = if (isFavDriveSaf) {
                    { overrideTarget = item; contextItem = null }
                } else null,
                onOverrideAudio = if (isFavDriveSaf) {
                    { overrideTarget = item; contextItem = null }
                } else null,
                onOverrideVideo = if (isFavDriveSaf &&
                    (item.mimeType.startsWith("video/") ||
                        item.name.substringAfterLast('.', "").lowercase() in
                        setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "3gp", "wmv", "flv"))) {
                    { overrideTarget = item; contextItem = null }
                } else null
                // Hide / Delete / Override-* deferred — not applicable to
                // streamed cloud items in Phase 3 scope.
            ),
            onDismiss = { contextItem = null }
        )
    }

    // System back: walk back up the cloud directory stack one level at a
    // time (delegate to viewModel.navigateUp which already encodes the
    // provider-specific semantics — Spotify section drill-down, Drive
    // folder pop, root → provider-selection). Only intercept when the
    // user is inside a provider; at top-level provider selection, fall
    // through so NavHost pops the Cloud tab back to Player.
    BackHandler(enabled = uiState.activeProvider != null) {
        viewModel.navigateUp()
    }

    // Toast fires on every NEW non-null error message — guarantees the
    // user sees the failure even if they don't notice the inline banner.
    // After showing, clear the message so a repeat tap on the same
    // failing item re-triggers a fresh Toast.
    androidx.compose.runtime.LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        viewModel.clearError()
    }

    val driveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.handleDriveResult(result.data) }

    val spotifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.handleSpotifyResult(result.data) }

    // Drive Picker (WebView) launcher — fires after OAuth sign-in
    // succeeds and we have an access token. Returns picked folder
    // ID/name which we persist to the user's Drive picked-folders list.
    val drivePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(
                com.powermediaplayer.cloud.DrivePickerActivity.RESULT_FOLDER_ID
            )
            val name = result.data?.getStringExtra(
                com.powermediaplayer.cloud.DrivePickerActivity.RESULT_FOLDER_NAME
            )
            if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                viewModel.rememberPickedDriveFolder(id, name)
            }
        }
        // Always refresh whatever the result — covers (a) user picked
        // a FILE not a folder (returns to us with no extras), (b) user
        // cancelled out, (c) the WebView Picker JS errored. Without
        // this force, the screen kept stale state and the user reported
        // "stays on the sign-in screen" even though sign-in actually
        // succeeded.
        viewModel.forceRefresh()
    }

    val pickerScope = rememberCoroutineScope()

    // Drive OAuth sign-in launcher. On success, fetch a token off-main
    // and launch the Picker WebView. On failure or user cancel, dismiss.
    val driveOAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pickerScope.launch {
            val ok = viewModel.handleDriveOAuthResult(result.data)
            if (ok) {
                val token = viewModel.fetchDriveAccessToken()
                if (!token.isNullOrBlank()) {
                    drivePickerLauncher.launch(
                        com.powermediaplayer.cloud.DrivePickerActivity.intent(context, token)
                    )
                }
            }
            // Force refresh regardless — covers the user who signs in
            // but is then routed away (e.g. Picker fails to launch, or
            // user backs out before the Picker WebView renders). The
            // sign-in itself still succeeded; the cards should reflect
            // the new logged-in state.
            viewModel.forceRefresh()
        }
    }

    // One-time "pick a FOLDER, not a file" warning. Shown the very
    // first time the user taps any of the Drive-launch buttons; the
    // acknowledgement is persisted so subsequent taps skip straight
    // to the Picker. Mitigates the new-user confusion that surfaced
    // when a tester tapped INTO a Drive folder and saw "No documents".
    val firstPickWarningSeen by viewModel.driveFirstPickWarningSeen
        .collectAsStateWithLifecycle(initialValue = true)
    var pendingFirstPickWarning by remember { mutableStateOf(false) }

    fun launchDriveOAuth() {
        if (firstPickWarningSeen) {
            driveOAuthLauncher.launch(viewModel.buildDriveOAuthSignInIntent())
        } else {
            pendingFirstPickWarning = true
        }
    }

    if (pendingFirstPickWarning) {
        AlertDialog(
            onDismissRequest = { pendingFirstPickWarning = false },
            title = {
                Text(
                    "Pick a FOLDER, not a file",
                    color = TealAccent,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    "Please select the FOLDER CONTAINING the files you want, " +
                        "and NOT the files themselves — files won't appear on the " +
                        "selection screen. You'll then be able to browse the FILES " +
                        "here in the app.",
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markDriveFirstPickWarningSeen()
                    pendingFirstPickWarning = false
                    launchDriveOAuth()
                }) {
                    Text("I understand", color = TealAccent)
                }
            },
            containerColor = OledBlack
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        TopAppBar(
            title = { Text("Cloud", style = MaterialTheme.typography.headlineMedium, color = TealAccent) },
            navigationIcon = {
                if (uiState.activeProvider != null) {
                    IconButton(onClick = { viewModel.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (uiState.folderStack.size > 1) "Up a folder" else "Back to providers",
                            tint = TealAccent
                        )
                    }
                }
            },
            actions = {
                com.powermediaplayer.ui.info.InfoIcon(
                    onClick = { showInfoSheet = true }
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
        )

        // Search bar — Drive only. Spotify search is hidden per UX
        // decision (the previous text-search implementation didn't
        // surface useful results to end-users).
        if (uiState.activeProvider != null && uiState.activeProvider != CloudProviderType.SPOTIFY) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search ${uiState.activeProvider?.name?.replace('_',' ')?.lowercase()}…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TealAccent) },
                trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Provider quick-switch tabs — tap to jump straight to Drive or
        // Spotify. Tapping a not-signed-in provider triggers the sign-in
        // flow rather than no-op'ing.
        ProviderTabRow(
            active = uiState.activeProvider,
            driveLoggedIn = uiState.driveLoggedIn,
            spotifyLoggedIn = uiState.spotifyLoggedIn,
            onSelectDrive = {
                if (uiState.driveLoggedIn) viewModel.browseDrive(null, "Root")
                else launchDriveOAuth()
            },
            onSelectSpotify = {
                if (uiState.spotifyLoggedIn) viewModel.browseSpotify()
                else spotifyLauncher.launch(viewModel.buildSpotifyAuthIntent())
            }
        )

        if (uiState.activeProvider == null) {
            // Provider selection / sign-in state
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "providers") {
                    ProviderCards(
                        driveLoggedIn = uiState.driveLoggedIn,
                        spotifyLoggedIn = uiState.spotifyLoggedIn,
                        onConnectDrive = { launchDriveOAuth() },
                        onConnectSpotify = { spotifyLauncher.launch(viewModel.buildSpotifyAuthIntent()) },
                        onBrowseDrive = { viewModel.browseDrive(null, "Root") },
                        onBrowseSpotify = { viewModel.browseSpotify() },
                        onSignOutDrive = { viewModel.signOutDrive() },
                        onSignOutSpotify = { viewModel.signOutSpotify() }
                    )
                }
                // §C10 LOCKED — "New section in Cloud tab below Spotify
                // favourites." Surfaced here as a peer of the provider
                // cards. Tapping into a podcast row opens the inline
                // episode browser; the same composable is also available
                // as a Settings entry for parity.
                item(key = "podcasts_cloud") {
                    androidx.compose.material3.HorizontalDivider(
                        color = DisabledGrey, modifier = Modifier.padding(vertical = 8.dp)
                    )
                    com.powermediaplayer.ui.podcast.PodcastsSection()
                }
            }
        } else {
            // File browser
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TealAccent)
                }
            } else if (uiState.activeProvider == CloudProviderType.SPOTIFY &&
                       uiState.spotifySection == null &&
                       !uiState.spotifyLoggedIn) {
                // Bug fix (silent-sign-out): when the Spotify refresh
                // token fails, _isLoggedIn flips to false and the user
                // would otherwise see the cached category list which
                // 401s as soon as they tap one. Surface a clear "sign
                // in again" prompt instead.
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = SpotifyGreen,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Spotify session expired",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Re-sign in from the tab bar above to keep browsing your library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            } else if (uiState.activeProvider == CloudProviderType.SPOTIFY &&
                       uiState.spotifySection == null) {
                // Spotify section picker — landing screen when entering
                // Spotify. Each card opens a single Web API endpoint.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item(key = "sp_connect_picker") {
                        // Spotify Connect device picker. Spotify Connect is
                        // its own protocol (not Cast). When the user has
                        // linked Google account in the Google Home app,
                        // their Google Home / Nest speakers appear here too.
                        Surface(
                            color = SpotifyGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { viewModel.openSpotifyConnectPicker() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Speaker,
                                    contentDescription = null,
                                    tint = SpotifyGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Spotify Connect device",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SpotifyGreen
                                    )
                                    Text(
                                        text = "Pick a speaker or another device — Spotify, Google Home, etc.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextTertiary
                                    )
                                }
                            }
                        }
                    }
                    val spotifyFavCount = uiState.spotifyFavTracks.size +
                        uiState.spotifyFavAlbums.size +
                        uiState.spotifyFavPodcasts.size
                    if (spotifyFavCount > 0) {
                        item(key = "sp_fav_header") {
                            Text(
                                text = "Favourite tracks/albums/podcasts ($spotifyFavCount)",
                                style = MaterialTheme.typography.labelMedium,
                                color = SpotifyGreen,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            uiState.spotifyFavTracks,
                            key = { "spfavt_${it.id}" }
                        ) { fav ->
                            SpotifyFavRow(
                                fav = fav,
                                kindIcon = Icons.Filled.MusicNote,
                                onClick = { viewModel.playSpotifyFavourite(fav.id, fav.name, "track", onNavigateToPlayer) },
                                onUnstar = { viewModel.unstarSpotifyFavourite(fav.id) }
                            )
                        }
                        items(
                            uiState.spotifyFavAlbums,
                            key = { "spfava_${it.id}" }
                        ) { fav ->
                            SpotifyFavRow(
                                fav = fav,
                                kindIcon = Icons.Filled.Album,
                                onClick = { viewModel.openSpotifyContainer(fav.id, fav.name) },
                                onUnstar = { viewModel.unstarSpotifyFavourite(fav.id) }
                            )
                        }
                        items(
                            uiState.spotifyFavPodcasts,
                            key = { "spfavp_${it.id}" }
                        ) { fav ->
                            SpotifyFavRow(
                                fav = fav,
                                kindIcon = Icons.Filled.Podcasts,
                                onClick = { viewModel.openSpotifyContainer(fav.id, fav.name) },
                                onUnstar = { viewModel.unstarSpotifyFavourite(fav.id) }
                            )
                        }
                        item(key = "sp_fav_divider") {
                            HorizontalDivider(
                                color = SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(
                        items = com.powermediaplayer.cloud.SpotifySection.values().toList(),
                        key = { it.name }
                    ) { section ->
                        Surface(
                            color = SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { viewModel.openSpotifySection(section) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (section) {
                                        com.powermediaplayer.cloud.SpotifySection.LIKED_SONGS ->
                                            Icons.Filled.Favorite
                                        com.powermediaplayer.cloud.SpotifySection.RECENT ->
                                            Icons.Filled.History
                                        com.powermediaplayer.cloud.SpotifySection.SAVED_ALBUMS ->
                                            Icons.Filled.Album
                                        com.powermediaplayer.cloud.SpotifySection.SAVED_PLAYLISTS,
                                        com.powermediaplayer.cloud.SpotifySection.FEATURED_PLAYLISTS ->
                                            Icons.Filled.QueueMusic
                                        com.powermediaplayer.cloud.SpotifySection.SAVED_EPISODES,
                                        com.powermediaplayer.cloud.SpotifySection.SAVED_SHOWS ->
                                            Icons.Filled.Podcasts
                                        com.powermediaplayer.cloud.SpotifySection.TOP_TRACKS ->
                                            Icons.Filled.TrendingUp
                                        com.powermediaplayer.cloud.SpotifySection.TOP_ARTISTS ->
                                            Icons.Filled.Person
                                        com.powermediaplayer.cloud.SpotifySection.NEW_RELEASES ->
                                            Icons.Filled.NewReleases
                                    },
                                    contentDescription = null,
                                    tint = TealAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = section.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = TextSecondary
                                )
                            }
                        }
                    }
                }
            } else if (uiState.searchQuery.isNotBlank()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.searchResults, key = { "search_${it.id}_${it.sourceProvider}" }) { item ->
                        CloudItemRow(
                            item = item,
                            onClick = {
                                viewModel.openItem(item, onPlaybackStarted = onNavigateToPlayer)
                            },
                            onLongClick = { if (!item.isFolder) contextItem = item }
                        )
                    }
                    if (uiState.searchResults.isEmpty()) {
                        item(key = "search_empty") {
                            Text(
                                text = "No results for \"${uiState.searchQuery}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Favourites strip — only at Drive root, shown when
                    // either folders or files are starred. Header now
                    // covers both since users can star tracks too via
                    // the per-row star icon.
                    val favFolders = uiState.driveFavourites
                    val favTracks = uiState.driveFavouriteTracks
                    // Favourites strip now shows at any folder depth so a
                    // long-press-to-favourite reflects immediately without
                    // navigating back to root.
                    val showFavourites = uiState.activeProvider == CloudProviderType.GOOGLE_DRIVE &&
                        (favFolders.isNotEmpty() || favTracks.isNotEmpty())
                    if (showFavourites) {
                        item(key = "fav_header") {
                            Text(
                                text = "Favourite folders/files",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(favFolders, key = { "favfolder_${it.id}" }) { fav ->
                            FavouriteFolderRow(
                                fav = fav,
                                onClick = { viewModel.openDriveFavourite(fav) },
                                onUnstar = {
                                    viewModel.toggleDriveFavourite(
                                        CloudMediaItem(
                                            id = fav.id,
                                            name = fav.name,
                                            mimeType = "application/vnd.google-apps.folder",
                                            size = 0L,
                                            downloadUrl = "",
                                            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                                            isFolder = true
                                        )
                                    )
                                }
                            )
                        }
                        items(favTracks, key = { "favtrack_${it.id}" }) { fav ->
                            FavouriteTrackRow(
                                fav = fav,
                                onClick = {
                                    viewModel.playDriveFavouriteTrack(
                                        fav.id,
                                        fav.name,
                                        onPlaybackStarted = onNavigateToPlayer
                                    )
                                },
                                onUnstar = {
                                    viewModel.toggleDriveFavouriteTrack(
                                        CloudMediaItem(
                                            id = fav.id,
                                            name = fav.name,
                                            mimeType = "",
                                            size = 0L,
                                            downloadUrl = "",
                                            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                                            isFolder = false
                                        )
                                    )
                                }
                            )
                        }
                        item(key = "fav_divider") {
                            HorizontalDivider(
                                color = SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    // At Drive root, expose an "+ Add folder" entry so the
                    // user can grant another folder without backing out
                    // through the provider-cards screen. Only shown when
                    // browsing Drive at depth 0 with at least one picked
                    // folder already (otherwise the bare ProviderCards
                    // screen shows "Pick a folder").
                    val atDriveRoot = uiState.activeProvider == CloudProviderType.GOOGLE_DRIVE &&
                        uiState.folderStack.size <= 1
                    if (atDriveRoot) {
                        item(key = "add_more_folders") {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable { launchDriveOAuth() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = TealAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Add another folder",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TealAccent
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(uiState.items, key = { _, it -> "${it.sourceProvider}_${it.id}" }) { _, item ->
                        val isDriveFolder = item.isFolder &&
                            item.sourceProvider == CloudProviderType.GOOGLE_DRIVE
                        val isDriveTrack = !item.isFolder &&
                            item.sourceProvider == CloudProviderType.GOOGLE_DRIVE
                        val isSpotify = item.sourceProvider == CloudProviderType.SPOTIFY
                        // Show a "Forget this folder" trailing icon on
                        // ROOT-level picked folders only (depth 0 and
                        // marked as folder). Drilling into a folder
                        // hides it so we don't accidentally forget a
                        // sub-folder.
                        val canForgetRoot = atDriveRoot && isDriveFolder
                        val isFav = when {
                            isDriveFolder -> uiState.driveFavourites.any { it.id == item.id }
                            isDriveTrack -> uiState.driveFavouriteTracks.any { it.id == item.id }
                            isSpotify -> {
                                val uri = if (item.downloadUrl.startsWith("spotify:")) item.downloadUrl
                                    else "spotify:track:${item.id}"
                                uiState.spotifyFavTracks.any { it.id == uri } ||
                                uiState.spotifyFavAlbums.any { it.id == uri } ||
                                uiState.spotifyFavPodcasts.any { it.id == uri }
                            }
                            else -> false
                        }
                        val canFavourite = isDriveFolder || isDriveTrack || isSpotify
                        CloudItemRow(
                            item = item,
                            isFavourite = isFav,
                            canFavourite = canFavourite,
                            onToggleFavourite = {
                                when {
                                    isDriveFolder -> viewModel.toggleDriveFavourite(item)
                                    isDriveTrack -> viewModel.toggleDriveFavouriteTrack(item)
                                    isSpotify -> viewModel.toggleSpotifyFav(item)
                                }
                            },
                            canForget = canForgetRoot,
                            onForget = { viewModel.forgetPickedFolder(item) },
                            onClick = {
                                if (item.isFolder) {
                                    viewModel.openItem(item)
                                } else {
                                    viewModel.openItem(item, onPlaybackStarted = onNavigateToPlayer)
                                }
                            },
                            onLongClick = { if (!item.isFolder) contextItem = item },
                            isOffline = isDriveTrack && viewModel.hasOfflineCopy(item.id)
                        )
                    }
                    if (uiState.items.isEmpty() && uiState.driveFavourites.isEmpty()) {
                        item(key = "empty_folder") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Empty folder", color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }

        uiState.errorMessage?.let { msg ->
            Surface(color = ErrorRed.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = msg,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }

    // Spotify Connect device picker bottom-sheet
    if (uiState.spotifyConnectPickerVisible) {
        SpotifyConnectPickerSheet(
            devices = uiState.spotifyConnectDevices,
            activeDeviceName = uiState.spotifyActiveDeviceName,
            isPlaying = uiState.spotifyIsPlaying,
            onRefresh = { viewModel.openSpotifyConnectPicker() },
            onWakeSpotify = { viewModel.wakeSpotifyForDeviceDiscovery() },
            onPick = { id, name -> viewModel.selectSpotifyConnectDevice(id, name) },
            onPauseSpotify = { viewModel.pauseSpotify() },
            onDismiss = { viewModel.dismissSpotifyConnectPicker() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyConnectPickerSheet(
    devices: List<Pair<String, String>>,
    activeDeviceName: String?,
    isPlaying: Boolean,
    onRefresh: () -> Unit,
    onWakeSpotify: () -> Unit,
    onPick: (String, String) -> Unit,
    onPauseSpotify: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.ui.graphics.Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pick a Spotify Connect device",
                    style = MaterialTheme.typography.titleMedium,
                    color = SpotifyGreen,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh device list",
                        tint = SpotifyGreen
                    )
                }
            }
            // "Now playing on X" banner with inline Pause / Disconnect.
            // Only shown when /me/player polling reports a non-blank
            // active device. Pause silences playback on that device
            // (Spotify Web API has no true disconnect — pause is the
            // closest user-visible equivalent).
            if (!activeDeviceName.isNullOrBlank()) {
                Surface(
                    color = SpotifyGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.Speaker,
                            contentDescription = null,
                            tint = SpotifyGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPlaying) "Now playing on" else "Last active on",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                            Text(
                                text = activeDeviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        if (isPlaying) {
                            TextButton(onClick = onPauseSpotify) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = SpotifyGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(text = "Stop", color = SpotifyGreen)
                            }
                        }
                    }
                }
            }
            Surface(
                color = androidx.compose.ui.graphics.Color(0xFF332200),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = "Not yet working — Spotify's public Web API only " +
                        "exposes Spotify SDK-registered devices. Google Home / " +
                        "Fire Stick / Sonos / smart TVs don't appear here yet. " +
                        "Use the Cast icon on the Player tab as a workaround. " +
                        "Native Spotify SDK integration is on the roadmap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFFFFB74D),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            // Bounce-out button: opens the Spotify app for ~1.5 s and
            // bounces back. Spotify's public Web API only lists devices
            // registered via the Spotify SDK, so Google Home / Fire
            // Stick / Sonos don't appear until Spotify itself has
            // recently been active. Tapping this wakes Spotify so those
            // devices publish themselves, then re-fetches devices.
            TextButton(
                onClick = onWakeSpotify,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = SpotifyGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Wake Spotify to find Google Home / Fire Stick / Sonos",
                    color = SpotifyGreen
                )
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty() || devices.size == 1) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(
                        text = if (devices.isEmpty()) "No Spotify Connect devices visible right now."
                        else "Only this phone is visible.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Spotify's public API only returns devices that are currently " +
                            "active on Spotify Connect. Google Home / Nest / Fire Stick / Sonos " +
                            "show up in the official Spotify app via local-network discovery — " +
                            "a channel third-party apps can't use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "What works: open the Spotify app on a phone or web browser, tap " +
                            "Connect, and pick the device once. After that it'll be visible here " +
                            "for a while. The button above tries to do this automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Alternative: tap the Cast icon on the Player tab to send LOCAL " +
                            "audio to a Chromecast / Google Home / smart TV directly — that uses " +
                            "Cast (not Spotify Connect) and discovers devices over your Wi-Fi.",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpotifyGreen
                    )
                }
            }
            if (devices.isNotEmpty()) {
                devices.forEach { (id, name) ->
                    val isActiveRow = !activeDeviceName.isNullOrBlank() &&
                        name.equals(activeDeviceName, ignoreCase = true)
                    Surface(
                        color = if (isActiveRow) SpotifyGreen.copy(alpha = 0.18f)
                        else SurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onPick(id, name) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speaker,
                                contentDescription = null,
                                tint = SpotifyGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActiveRow) {
                                Text(
                                    text = if (isPlaying) "Playing" else "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SpotifyGreen
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderTabRow(
    active: CloudProviderType?,
    driveLoggedIn: Boolean,
    spotifyLoggedIn: Boolean,
    onSelectDrive: () -> Unit,
    onSelectSpotify: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderTab(
            name = "Google Drive",
            isActive = active == CloudProviderType.GOOGLE_DRIVE,
            enabled = driveLoggedIn,
            onClick = onSelectDrive,
            modifier = Modifier.weight(1f)
        )
        ProviderTab(
            name = "Spotify",
            isActive = active == CloudProviderType.SPOTIFY,
            enabled = spotifyLoggedIn,
            onClick = onSelectSpotify,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProviderTab(
    name: String,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = when {
        isActive -> Teal800
        enabled -> SurfaceElevated
        else -> SurfaceElevated.copy(alpha = 0.4f)
    }
    val content = when {
        isActive -> TealAccent
        enabled -> TextPrimary
        else -> TextTertiary
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name + if (!enabled) " (sign in)" else "",
                color = content,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ProviderCards(
    driveLoggedIn: Boolean,
    spotifyLoggedIn: Boolean,
    onConnectDrive: () -> Unit,
    onConnectSpotify: () -> Unit,
    onBrowseDrive: () -> Unit,
    onBrowseSpotify: () -> Unit,
    onSignOutDrive: () -> Unit,
    onSignOutSpotify: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProviderCard(
            name = "Cloud / external folders",
            description = "Pick a folder from Google Drive, OneDrive, Dropbox, " +
                "or your phone's storage. Browse and play media inside it. " +
                "Add as many folders as you like.",
            loggedIn = driveLoggedIn,
            onConnect = onConnectDrive,
            onBrowse = onBrowseDrive,
            onSignOut = onSignOutDrive,
            connectLabel = "Pick a folder",
            signOutLabel = "Forget all folders",
            addAnotherLabel = "Add folder"
        )
        ProviderCard(
            name = "Spotify",
            description = "Browse your saved tracks, albums, and playlists. Full-track playback requires Spotify Premium (preview clips only on free tier).",
            loggedIn = spotifyLoggedIn,
            onConnect = onConnectSpotify,
            onBrowse = onBrowseSpotify,
            onSignOut = onSignOutSpotify
        )
    }
}

@Composable
private fun ProviderCard(
    name: String,
    description: String,
    loggedIn: Boolean,
    onConnect: () -> Unit,
    onBrowse: () -> Unit,
    onSignOut: () -> Unit,
    connectLabel: String = "Connect $name",
    signOutLabel: String = "Sign out",
    /**
     * If non-null, shows a third button beside Browse / Sign out that
     * fires [onConnect]. Lets the cloud card add another folder
     * without taking the user back through the not-logged-in flow.
     * Spotify and other single-account providers leave this null.
     */
    addAnotherLabel: String? = null
) {
    Surface(
        color = SurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = TealAccent)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            if (loggedIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onBrowse,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Teal800,
                            contentColor = TealAccent
                        )
                    ) { Text("Browse") }
                    if (addAnotherLabel != null) {
                        FilledTonalButton(
                            onClick = onConnect,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Teal800,
                                contentColor = TealAccent
                            )
                        ) { Text(addAnotherLabel) }
                    }
                    OutlinedButton(onClick = onSignOut) {
                        Text(signOutLabel, color = TextSecondary)
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onConnect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Teal800,
                        contentColor = TealAccent
                    )
                ) { Text(connectLabel) }
            }
        }
    }
}

/**
 * Source chooser shown when the user taps "Pick a folder" on devices
 * whose SAF picker hides the source drawer. Lists every available
 * DocumentsProvider root (Drive · email, OneDrive · email, Internal
 * storage, USB-OTG, …) and deep-links the picker into the chosen
 * source via EXTRA_INITIAL_URI when tapped. Falls back to the
 * un-deep-linked picker via "Choose another source" so users on
 * working devices can still see the drawer.
 */
@Composable
private fun SourceChooserDialog(
    roots: List<com.powermediaplayer.cloud.GoogleDriveProvider.CloudRoot>,
    onPick: (com.powermediaplayer.cloud.GoogleDriveProvider.CloudRoot) -> Unit,
    onPickAnything: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a source", color = TealAccent) },
        text = {
            Column {
                Text(
                    "Choose where your media is stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                if (roots.isEmpty()) {
                    Text(
                        "No sources detected. Make sure Google Drive / OneDrive " +
                            "are signed in, then tap \"Choose another source\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(roots, key = { "${it.authority}_${it.rootId}" }) { root ->
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onPick(root) }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        root.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!root.summary.isNullOrBlank()) {
                                        Text(
                                            root.summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPickAnything) {
                Text("Choose another source", color = TealAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = OledBlack
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CloudItemRow(
    item: CloudMediaItem,
    onClick: () -> Unit,
    isFavourite: Boolean = false,
    canFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
    canForget: Boolean = false,
    onForget: () -> Unit = {},
    onLongClick: () -> Unit = {},
    isOffline: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            val (icon, label) = when {
                item.isFolder -> Icons.Filled.Folder to "Folder"
                item.sourceProvider == CloudProviderType.SPOTIFY -> Icons.Filled.MusicNote to "Spotify track"
                item.mimeType.startsWith("video/") -> Icons.Filled.VideoFile to "Video"
                else -> Icons.Filled.AudioFile to "Audio"
            }
            Icon(icon, contentDescription = label, tint = TealAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isOffline) {
            Surface(
                color = TealAccent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    "Offline",
                    color = TealAccent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (canFavourite) {
            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavourite) TealAccent else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (canForget) {
            IconButton(onClick = onForget, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Forget this folder",
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SpotifyFavRow(
    fav: com.powermediaplayer.data.preferences.SpotifyFavourite,
    kindIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onUnstar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SpotifyGreen.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                kindIcon,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = fav.name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onUnstar, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Remove from favourites",
                tint = SpotifyGreen,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FavouriteTrackRow(
    fav: com.powermediaplayer.data.preferences.DriveFavouriteFolder,
    onClick: () -> Unit,
    onUnstar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Teal800),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.AudioFile,
                contentDescription = "Favourite track",
                tint = TealAccent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = fav.name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onUnstar, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Remove from favourites",
                tint = TealAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FavouriteFolderRow(
    fav: com.powermediaplayer.data.preferences.DriveFavouriteFolder,
    onClick: () -> Unit,
    onUnstar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Teal800),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourite folder",
                tint = TealAccent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = fav.name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onUnstar, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Remove from favourites",
                tint = TealAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
