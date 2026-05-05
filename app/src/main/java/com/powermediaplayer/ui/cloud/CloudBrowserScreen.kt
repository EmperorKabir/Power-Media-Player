package com.powermediaplayer.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        } else {
            // File browser
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TealAccent)
                }
            } else if (uiState.activeProvider == CloudProviderType.SPOTIFY &&
                       uiState.spotifySection == null) {
                // Spotify section picker — landing screen when entering
                // Spotify. Each card opens a single Web API endpoint.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
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
                            }
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
                    val showFavourites = uiState.activeProvider == CloudProviderType.GOOGLE_DRIVE &&
                        uiState.folderStack.size <= 1 &&
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
                            }
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

@Composable
private fun CloudItemRow(
    item: CloudMediaItem,
    onClick: () -> Unit,
    isFavourite: Boolean = false,
    canFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
    canForget: Boolean = false,
    onForget: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
