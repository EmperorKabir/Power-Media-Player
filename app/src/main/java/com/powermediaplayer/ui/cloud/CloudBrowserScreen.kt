package com.powermediaplayer.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic

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
    onNavigateToPlayer: () -> Unit = {},
    onOpenDownloads: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Audit 4.5 - per-row .any{} scans were O(rows x favourites) per
    // composition pass, and the offline map was read via .value inside
    // composition (snapshot-invisible -> stale Offline chip). Set
    // lookups + reactive collection instead.
    val driveFavFolderIds = remember(uiState.driveFavourites) {
        uiState.driveFavourites.mapTo(HashSet()) { it.id }
    }
    val driveFavTrackIds = remember(uiState.driveFavouriteTracks) {
        uiState.driveFavouriteTracks.mapTo(HashSet()) { it.id }
    }
    val spotifyFavIds = remember(
        uiState.spotifyFavTracks, uiState.spotifyFavAlbums, uiState.spotifyFavPodcasts
    ) {
        HashSet<String>().apply {
            uiState.spotifyFavTracks.mapTo(this) { it.id }
            uiState.spotifyFavAlbums.mapTo(this) { it.id }
            uiState.spotifyFavPodcasts.mapTo(this) { it.id }
        }
    }
    val offlineIds by viewModel.offlineDrivePairs.collectAsStateWithLifecycle()
    val enrichedCovers by viewModel.enrichedCovers.collectAsStateWithLifecycle()
    val savingOffline by viewModel.savingOffline.collectAsStateWithLifecycle()
    // Favourite-cover reconciliation: converge every favourite to "covered or
    // confirmed artless" on Cloud entry (self-heals failed/interrupted/evicted
    // enriches; once per process, silent).
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.sweepFavouriteCovers() }
    // #16 D6 — Drive items being background-enriched after a favourite. Purely
    // informational; never blocks taps/scroll/playback.
    val enrichingIds by viewModel.enrichingIds.collectAsStateWithLifecycle()
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
    // Tap the already-active Cloud tab again → jump back to the top level.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.powermediaplayer.ui.navigation.TabReselectBus.events.collect { route ->
            if (route == "cloud") viewModel.navigateToRoot()
        }
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
                // Audit 4.7 - a plain resume (notification shade, app
                // switch) re-listed Drive/Spotify over the network every
                // time. Stale-gated now; the OAuth-return launcher paths
                // keep their forced refresh.
                viewModel.refreshIfStale(5_000)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    contextItem?.let { item ->
        val isSpotify = item.sourceProvider == CloudProviderType.SPOTIFY
        val isDriveTrack = item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder
        val starredOffline by viewModel.starredOfflineIds.collectAsStateWithLifecycle()
        val isPinned = starredOffline.contains(item.id)
        // §C25 — Drive favourites can carry per-file overrides keyed
        // by the SAF content:// URI. Drive OAuth (REST) has session-
        // dependent stream URLs that don't key stably, so override-*
        // surfaces only for SAF-imported tracks.
        val isFavDriveSaf = isDriveTrack && item.id.startsWith("content://") &&
            uiState.driveFavouriteTracks.any { it.id == item.id }
        com.powermediaplayer.ui.player.components.TrackContextSheet(
            title = item.name,
            subtitle = item.subtitle.ifBlank { if (isSpotify) "Spotify" else "Drive" },
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
                // §C28/Part 3.2 — pin/unpin so the LRU evictor spares this copy.
                onPinOffline = if (isDriveTrack && viewModel.hasOfflineCopy(item.id) && !isPinned) {
                    { viewModel.toggleStarredOffline(item.id); contextItem = null }
                } else null,
                onUnpinOffline = if (isDriveTrack && viewModel.hasOfflineCopy(item.id) && isPinned) {
                    { viewModel.toggleStarredOffline(item.id); contextItem = null }
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

    // Native folder-browser visibility (replaces the WebView Picker; no
    // WebView → no second sign-in, no cold-start scaling).
    var showDriveBrowser by remember { mutableStateOf(false) }

    val pickerScope = rememberCoroutineScope()

    // Drive OAuth sign-in launcher. On success, open the NATIVE folder
    // browser. drive.readonly reads the whole Drive, so browsing needs no
    // WebView Picker grant. On failure / cancel, just refresh.
    val driveOAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pickerScope.launch {
            val ok = viewModel.handleDriveOAuthResult(result.data)
            // `ok` only means an account came back — NOT that drive.readonly
            // was granted. If the user declined the Drive scope (or bailed at
            // the "unverified app" screen after choosing the account), opening
            // the browser would dead-end on "Not authenticated". Gate on
            // driveReadyForBrowse (checks the readonly grant) and nudge a retry.
            if (ok && viewModel.driveReadyForBrowse()) {
                showDriveBrowser = true
            } else if (ok) {
                android.widget.Toast.makeText(
                    context,
                    "Drive read access is needed to browse your folders. " +
                        "Tap Add folder again and allow it.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
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

    // 2026-07-25: Drive folder-adding opens the NATIVE in-app folder browser
    // (DriveFolderBrowser) — no WebView, no second sign-in, no scaling.
    // [launchDriveSystemPicker] is a legacy SAF (system document-tree) path,
    // kept for adding a folder via the OS file picker.
    fun launchDriveSystemPicker() {
        driveLauncher.launch(viewModel.buildDriveSignInIntent())
    }

    // Open the native folder browser. If already signed in WITH read access,
    // go straight there; otherwise do the native Google sign-in first (its
    // result re-gates on read consent before opening the browser).
    fun proceedToDriveBrowser() {
        if (viewModel.driveReadyForBrowse()) showDriveBrowser = true
        else driveOAuthLauncher.launch(viewModel.buildDriveOAuthSignInIntent())
    }

    fun launchDriveOAuth() {
        if (!firstPickWarningSeen) { pendingFirstPickWarning = true; return }
        proceedToDriveBrowser()
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
                    "Open the folder that CONTAINS your books/music (tap folders to " +
                        "go into them), then tap “Add this folder”. You'll then " +
                        "browse the files here in the app.",
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                // Robust tap target on both folded + unfolded form factors: a short
                // label otherwise yields a thin hitbox. Enforce a min 56dp x 120dp
                // button with roomy content padding.
                TextButton(
                    onClick = {
                        viewModel.markDriveFirstPickWarningSeen()
                        pendingFirstPickWarning = false
                        // Proceed directly — the acknowledgement is already
                        // known here; re-invoking the guarded launchDriveOAuth()
                        // would re-show this dialog (the persisted flag hasn't
                        // recomposed yet), forcing a second tap.
                        proceedToDriveBrowser()
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 120.dp, minHeight = 56.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        "I understand",
                        color = TealAccent,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            containerColor = OledBlack
        )
    }

    // Native in-app Drive folder browser (replaces the WebView Picker).
    if (showDriveBrowser) {
        DriveFolderBrowser(
            listSubFolders = { pid -> viewModel.listDriveSubFolders(pid) },
            onAdd = { id, name ->
                showDriveBrowser = false
                viewModel.rememberPickedDriveFolder(id, name)
            },
            onDismiss = { showDriveBrowser = false }
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

        // Search bar — Drive AND Spotify. Spotify catalogue search is
        // re-enabled (Part 5): result rows now show "Title · Artist",
        // standalone episodes play the full show on Connect, and artist
        // results drill to top-tracks.
        if (uiState.activeProvider != null) {
            var searchFocused by remember { mutableStateOf(false) }
            val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
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
                    .onFocusChanged { searchFocused = it.isFocused }
            )
            // Recent searches: shown when the field is focused + empty.
            if (searchFocused && uiState.searchQuery.isBlank()) {
                com.powermediaplayer.ui.components.RecentSearchesDropdown(
                    recents = recentSearches,
                    onPick = { viewModel.setSearchQuery(it) },
                    onClear = { viewModel.clearRecentSearches() }
                )
            }
        }

        // Provider quick-switch tabs — tap to jump straight to Drive or
        // Spotify. Tapping a not-signed-in provider triggers the sign-in
        // flow rather than no-op'ing.
        ProviderTabRow(
            active = uiState.activeProvider,
            driveLoggedIn = uiState.driveLoggedIn,
            spotifyLoggedIn = uiState.spotifyLoggedIn,
            onSelectDrive = {
                // U8: signed in but the sensitive readonly scope not yet granted
                // (returning drive.file-only user) → ASK for it rather than browse
                // blind (a null token would fail silently). launchDriveOAuth re-consents.
                if (uiState.driveLoggedIn && viewModel.driveReadyForBrowse())
                    viewModel.browseDrive(null, "Root")
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
                        // U8: re-consent for readonly if it's missing, rather than
                        // browsing blind and failing silently on a null token.
                        onBrowseDrive = {
                            if (viewModel.driveReadyForBrowse()) viewModel.browseDrive(null, "Root")
                            else launchDriveOAuth()
                        },
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
                // Part 3.3 — unified Downloads manager (podcasts + Drive offline).
                item(key = "downloads_entry") {
                    androidx.compose.material3.HorizontalDivider(
                        color = DisabledGrey, modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDownloads() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = TealAccent)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Manage downloads", color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall)
                            Text("Podcasts + offline Drive files · storage usage",
                                color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }
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
                       uiState.spotifySection == null &&
                       uiState.searchQuery.isBlank() &&
                       uiState.folderStack.size <= 1) {
                // Spotify section picker — landing screen when entering
                // Spotify. Each card opens a single Web API endpoint.
                // A non-blank query falls through to the search-results
                // branch below (Part 5.2 ordering fix); a pushed folderStack
                // (drilled into an album from search) falls to the browse list.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 360.dp),   // audit 8.1 (F4)
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item(key = "sp_connect_picker", span = { GridItemSpan(maxLineSpan) }) {
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
                                        text = "Pick a speaker or another device, Spotify, Google Home, etc.",
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
                        item(key = "sp_fav_header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Favourite tracks/albums/podcasts ($spotifyFavCount)",
                                style = MaterialTheme.typography.labelMedium,
                                color = SpotifyGreen,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        gridItems(
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
                        gridItems(
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
                        gridItems(
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
                        item(key = "sp_fav_divider", span = { GridItemSpan(maxLineSpan) }) {
                            HorizontalDivider(
                                color = SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    gridItems(
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
                                            Icons.AutoMirrored.Filled.QueueMusic
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
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 360.dp),   // audit 8.1 (F4)
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    gridItems(uiState.searchResults, key = { "search_${it.id}_${it.sourceProvider}" }) { item ->
                        val isDriveTrack = item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder
                        val isSpotifyAlbum = item.sourceProvider == CloudProviderType.SPOTIFY &&
                            item.isFolder &&
                            (item.mimeType.endsWith("album") || item.mimeType.endsWith("playlist"))
                        // Browse-time cover peek — self-guarded (audio-only, once per
                        // item per process, ≤2 concurrent bounded Range fetches).
                        androidx.compose.runtime.LaunchedEffect(item.id) {
                            viewModel.peekCoverIfNeeded(item)
                        }
                        CloudItemRow(
                            item = item,
                            onClick = {
                                viewModel.openItem(item, onPlaybackStarted = onNavigateToPlayer)
                            },
                            onLongClick = { if (!item.isFolder) contextItem = item },
                            isOffline = isDriveTrack && offlineIds.containsKey(item.id),
                            localThumbnailUri = offlineIds[item.id]?.let { p ->
                                if (p.startsWith("content://")) android.net.Uri.parse(p)
                                else android.net.Uri.fromFile(java.io.File(p))
                            },
                            enrichedCoverUri = enrichedCovers[item.id]?.let { android.net.Uri.parse(it) },
                            canManageOffline = isDriveTrack,
                            isSavingOffline = item.id in savingOffline,
                            isEnriching = item.id in enrichingIds,
                            onSaveOffline = { viewModel.saveDriveOffline(item) },
                            onRemoveOffline = { viewModel.removeDriveOffline(item.id) },
                            onCancelOffline = { viewModel.cancelDownload(item.id) },
                            canPlayAlbum = isSpotifyAlbum,
                            onPlayAlbum = { viewModel.playSpotifyAlbum(item, onPlaybackStarted = onNavigateToPlayer) }
                        )
                    }
                    if (uiState.searchResults.isEmpty()) {
                        item(key = "search_empty", span = { GridItemSpan(maxLineSpan) }) {
                            if (uiState.searchInProgress) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = TealAccent, strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Searching your Drive folders…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            } else {
                                Text(
                                    text = "No results for \"${uiState.searchQuery}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary,
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 360.dp),   // audit 8.1 (F4)
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
                        item(key = "fav_header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Favourite folders/files",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        gridItems(favFolders, key = { "favfolder_${it.id}" }) { fav ->
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
                        gridItems(favTracks, key = { "favtrack_${it.id}" }) { fav ->
                            val favItem = CloudMediaItem(
                                id = fav.id,
                                name = fav.name,
                                mimeType = "",
                                size = 0L,
                                // The download providers fetch from downloadUrl (SAF
                                // = the content uri; OAuth = the files/{id}?alt=media
                                // endpoint) — an empty url would fail. Reconstruct it
                                // the same way the providers' toCloudItem does.
                                downloadUrl = if (fav.id.startsWith("content://")) fav.id
                                    else "https://www.googleapis.com/drive/v3/files/${fav.id}?alt=media",
                                sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                                isFolder = false
                            )
                            FavouriteTrackRow(
                                fav = fav,
                                isOffline = offlineIds.containsKey(fav.id),
                                localThumbnailUri = offlineIds[fav.id]?.let { p ->
                                    if (p.startsWith("content://")) android.net.Uri.parse(p)
                                    else android.net.Uri.fromFile(java.io.File(p))
                                },
                                enrichedCoverUri = enrichedCovers[fav.id]?.let { android.net.Uri.parse(it) },
                                isSaving = fav.id in savingOffline,
                                isEnriching = fav.id in enrichingIds,
                                onSaveOffline = { viewModel.saveDriveOffline(favItem) },
                                onRemoveOffline = { viewModel.removeDriveOffline(fav.id) },
                                onCancelOffline = { viewModel.cancelDownload(fav.id) },
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
                        item(key = "fav_divider", span = { GridItemSpan(maxLineSpan) }) {
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
                        item(key = "add_more_folders", span = { GridItemSpan(maxLineSpan) }) {
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
                    // "Play album / playlist / series" — visible when the user
                    // has drilled INTO a Spotify album/playlist/show (so its
                    // tracks/episodes are listed). Plays the whole container on
                    // the Connect device + loops it (repeat=context). The ▶ on
                    // the list ROW only appears one level up, in search/section.
                    val spotifyContainerUri = uiState.folderStack.lastOrNull()?.first
                    val containerType = spotifyContainerUri?.split(":")?.getOrNull(1)
                    val insideSpotifyContainer = uiState.activeProvider == CloudProviderType.SPOTIFY &&
                        uiState.folderStack.size > 1 &&
                        spotifyContainerUri?.startsWith("spotify:") == true &&
                        // The artist ALBUMS view is a list of albums — no single
                        // "play" target; the "Top tracks" row handles playback.
                        containerType != "artist"
                    if (insideSpotifyContainer && spotifyContainerUri != null) {
                        item(key = "play_spotify_container", span = { GridItemSpan(maxLineSpan) }) {
                            val type = containerType ?: "album"
                            val playLabel = when (type) {
                                "show" -> "Play series"
                                "playlist" -> "Play playlist"
                                "artisttracks" -> "Play top tracks"
                                else -> "Play album"
                            }
                            val containerName = uiState.folderStack.lastOrNull()?.second ?: "this"
                            // Top-tracks plays via the ARTIST context uri (Spotify
                            // has no "artisttracks" context).
                            val idPart = spotifyContainerUri.substringAfterLast(':')
                            val isArtistTracks = type == "artisttracks"
                            val playUri = if (isArtistTracks) "spotify:artist:$idPart" else spotifyContainerUri
                            val playMime = "application/spotify-" + (if (isArtistTracks) "artist" else type)
                            Surface(
                                color = SurfaceElevated,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.playSpotifyAlbum(
                                            com.powermediaplayer.cloud.CloudMediaItem(
                                                id = idPart,
                                                name = containerName,
                                                mimeType = playMime,
                                                size = 0L,
                                                downloadUrl = playUri,
                                                sourceProvider = CloudProviderType.SPOTIFY,
                                                isFolder = true
                                            ),
                                            onPlaybackStarted = onNavigateToPlayer
                                        )
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow, contentDescription = null,
                                        tint = TealAccent, modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        playLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TealAccent
                                    )
                                }
                            }
                        }
                    }
                    // "Pin this folder as album" — visible when the user is
                    // inside a Drive folder (not at root) and the folder
                    // contains at least one audio file. The Library tab
                    // has its own pin-album entry via long-press; this
                    // is the equivalent for Drive content where the
                    // folder == album concept is most natural.
                    val insideDriveFolder = uiState.activeProvider == CloudProviderType.GOOGLE_DRIVE &&
                        uiState.folderStack.size > 1
                    val audioCount = uiState.items.count {
                        !it.isFolder && it.mimeType.startsWith("audio/")
                    }
                    if (insideDriveFolder && audioCount > 0) {
                        item(key = "pin_folder_as_album", span = { GridItemSpan(maxLineSpan) }) {
                            val folderLabel = uiState.folderStack.lastOrNull()?.second ?: "Folder"
                            Surface(
                                color = SurfaceElevated,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        pickerScope.launch {
                                            val ok = viewModel.pinCurrentFolderAsAlbum().isSuccess
                                            android.widget.Toast.makeText(
                                                context,
                                                if (ok)
                                                    "Pinned '$folderLabel' ($audioCount tracks)"
                                                else
                                                    "Couldn't pin, favourites full or no audio in folder",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = TealAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Pin this folder as album",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = TealAccent
                                        )
                                        Text(
                                            "$audioCount tracks → Last Played → Pinned",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "download_folder", span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                color = SurfaceElevated,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                                    .clickable { viewModel.saveFolderOffline(uiState.items) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Download, contentDescription = null,
                                        tint = TealAccent, modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Download all in this folder",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = TealAccent
                                        )
                                        Text(
                                            "$audioCount audio file(s) → offline",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    gridItemsIndexed(uiState.items, key = { _, it -> "${it.sourceProvider}_${it.id}" }) { _, item ->
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
                            isDriveFolder -> item.id in driveFavFolderIds
                            isDriveTrack -> item.id in driveFavTrackIds
                            isSpotify -> {
                                val uri = if (item.downloadUrl.startsWith("spotify:")) item.downloadUrl
                                    else "spotify:track:${item.id}"
                                uri in spotifyFavIds
                            }
                            else -> false
                        }
                        val canFavourite = isDriveFolder || isDriveTrack || isSpotify
                        // Browse-time cover peek — self-guarded (audio-only, once per
                        // item per process, ≤2 concurrent bounded Range fetches).
                        androidx.compose.runtime.LaunchedEffect(item.id) {
                            viewModel.peekCoverIfNeeded(item)
                        }
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
                            isOffline = isDriveTrack && offlineIds.containsKey(item.id),
                            localThumbnailUri = offlineIds[item.id]?.let { p ->
                                if (p.startsWith("content://")) android.net.Uri.parse(p)
                                else android.net.Uri.fromFile(java.io.File(p))
                            },
                            enrichedCoverUri = enrichedCovers[item.id]?.let { android.net.Uri.parse(it) },
                            canManageOffline = isDriveTrack,
                            isSavingOffline = item.id in savingOffline,
                            isEnriching = item.id in enrichingIds,
                            onSaveOffline = { viewModel.saveDriveOffline(item) },
                            onRemoveOffline = { viewModel.removeDriveOffline(item.id) },
                            onCancelOffline = { viewModel.cancelDownload(item.id) },
                            canPlayAlbum = isSpotify && item.isFolder &&
                                (item.mimeType.endsWith("album") || item.mimeType.endsWith("playlist")),
                            onPlayAlbum = { viewModel.playSpotifyAlbum(item, onPlaybackStarted = onNavigateToPlayer) }
                        )
                    }
                    if (uiState.items.isEmpty() && uiState.driveFavourites.isEmpty()) {
                        item(key = "empty_folder", span = { GridItemSpan(maxLineSpan) }) {
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
                    text = "Not yet working, Spotify only lets other apps see " +
                        "Spotify-aware speakers and devices here, so things like " +
                        "Google Home, Fire Stick, Sonos and smart TVs don't show " +
                        "up yet. For now, use the Cast icon on the Player tab " +
                        "instead. A fuller built-in Spotify connection is planned " +
                        "for a future update.",
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
                            "show up in the official Spotify app via local-network discovery, " +
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
                            "audio to a Chromecast / Google Home / smart TV directly, that uses " +
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

// Audit B2: SourceChooserDialog removed (never invoked; superseded by the
// Drive Picker WebView path).

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
    isOffline: Boolean = false,
    // S6: local copy of a downloaded file, used to show its own embedded art or a
    // video frame as the thumbnail instead of a generic icon.
    localThumbnailUri: android.net.Uri? = null,
    // Cover extracted at favourite time (before a full download) — shown ahead of Drive's thumbnail.
    enrichedCoverUri: android.net.Uri? = null,
    canManageOffline: Boolean = false,
    isSavingOffline: Boolean = false,
    isEnriching: Boolean = false,
    onSaveOffline: () -> Unit = {},
    onRemoveOffline: () -> Unit = {},
    onCancelOffline: () -> Unit = {},
    canPlayAlbum: Boolean = false,
    onPlayAlbum: () -> Unit = {}
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
                // #13 — extension-authoritative: a .m4b audiobook arrives from
                // Drive with mime "video/mp4" (the MP4 container can hold video).
                // The file extension is the real signal, matching the play-path
                // override in CloudViewModel.openItemInternal.
                com.powermediaplayer.util.MediaClassifier
                    .isVideoByName(item.name, item.mimeType) -> Icons.Filled.VideoFile to "Video"
                else -> Icons.Filled.AudioFile to "Audio"
            }
            // Thumbnail priority: a downloaded file's OWN embedded art / video frame first, then the
            // cover extracted at favourite time, then the carried artwork URL (Spotify album covers
            // and — as a last resort — Drive's own thumbnail, which is unreliable/black for m4b),
            // then the type icon. The icon is drawn behind, so it shows whenever the image resolves
            // to nothing (a real Drive folder, or a file with no art and no decodable frame).
            // Spotify albums have no local/enriched cover, so they fall through to item.thumbnailUri.
            val thumbModel: Any? =
                localThumbnailUri?.let { com.powermediaplayer.util.MediaThumbnailRequest(it) }
                    ?: enrichedCoverUri
                    ?: item.thumbnailUri
            Icon(icon, contentDescription = label, tint = TealAccent, modifier = Modifier.size(22.dp))
            if (thumbModel != null) {
                coil3.compose.AsyncImage(
                    model = thumbModel,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Part 5.1 — secondary line: a Spotify type tag (Album/Single/
            // Podcast/…) + the artist/publisher line. The tag matches the
            // Last Played SourcePill style so the surfaces feel consistent.
            val typeTag = spotifyTypeLabel(item.spotifyType)
            if (typeTag != null || item.subtitle.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (typeTag != null) {
                        val tagColor = spotifyTypeColor(item.spotifyType)
                        Surface(
                            color = tagColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                typeTag,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                                color = tagColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (isEnriching) EnrichingHint()
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
        // Visible save/remove-offline affordance for Drive tracks (was only in
        // the long-press menu — undiscoverable). Mirrors the podcast row.
        if (canManageOffline) {
            when {
                isSavingOffline -> com.powermediaplayer.ui.components.DownloadProgressMini(
                    item.id, onCancel = onCancelOffline
                )
                isOffline -> IconButton(onClick = onRemoveOffline, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Remove offline copy",
                        tint = TealAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> IconButton(onClick = onSaveOffline, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Download / save offline",
                        tint = TealAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        if (canPlayAlbum) {
            IconButton(onClick = onPlayAlbum, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play album",
                    tint = TealAccent,
                    modifier = Modifier.size(24.dp)
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

/** Human-readable tag for a Spotify result kind (null = no tag, e.g. Drive
 *  items). "single"/"compilation" come from the album's album_type so a single
 *  release reads "Single" not "Album", matching Spotify's own labelling. */
private fun spotifyTypeLabel(kind: String?): String? = when (kind) {
    "track" -> "Song"
    "single" -> "Single"
    "album" -> "Album"
    "compilation" -> "Compilation"
    "artist" -> "Artist"
    "playlist" -> "Playlist"
    "show" -> "Podcast"
    "episode" -> "Episode"
    else -> null
}

/** Tag colour by kind: podcasts violet (matches Last Played), playlists blue,
 *  artists amber, everything else Spotify green. */
private fun spotifyTypeColor(kind: String?): androidx.compose.ui.graphics.Color = when (kind) {
    "show", "episode" -> androidx.compose.ui.graphics.Color(0xFFB388FF)
    "playlist" -> androidx.compose.ui.graphics.Color(0xFF4FC3F7)
    "artist" -> androidx.compose.ui.graphics.Color(0xFFFFB74D)
    else -> SpotifyGreen
}

/**
 * #16 D6 — tiny non-blocking "Updating…" indicator shown on a favourited Drive
 * row while its tags/cover are enriched in the background. Informational only:
 * no clickable, no intrinsic min-size that could intercept the row's gestures.
 */
@Composable
private fun EnrichingHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 6.dp)
    ) {
        CircularProgressIndicator(
            color = TealAccent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Updating…",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun FavouriteTrackRow(
    fav: com.powermediaplayer.data.preferences.DriveFavouriteFolder,
    onClick: () -> Unit,
    onUnstar: () -> Unit,
    isOffline: Boolean = false,
    localThumbnailUri: android.net.Uri? = null,
    enrichedCoverUri: android.net.Uri? = null,
    isSaving: Boolean = false,
    isEnriching: Boolean = false,
    onSaveOffline: () -> Unit = {},
    onRemoveOffline: () -> Unit = {},
    onCancelOffline: () -> Unit = {}
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
            // A downloaded favourite shows its own embedded art / video frame; a favourited-but-not-
            // downloaded item shows the cover extracted at favourite time (enrichedCoverUri). The
            // icon stays as the fallback if neither is available or decodes.
            val cover: Any? = localThumbnailUri?.let { com.powermediaplayer.util.MediaThumbnailRequest(it) }
                ?: enrichedCoverUri
            cover?.let { model ->
                coil3.compose.AsyncImage(
                    model = model,
                    contentDescription = "Favourite track",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
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
        if (isEnriching) EnrichingHint()
        // Visible download / remove-offline (favourite Drive tracks had none).
        when {
            isSaving -> com.powermediaplayer.ui.components.DownloadProgressMini(
                fav.id, onCancel = onCancelOffline
            )
            isOffline -> IconButton(onClick = onRemoveOffline, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove offline copy",
                    tint = TealAccent, modifier = Modifier.size(20.dp))
            }
            else -> IconButton(onClick = onSaveOffline, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Download, contentDescription = "Download / save offline",
                    tint = TealAccent, modifier = Modifier.size(20.dp))
            }
        }
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
            // vc32: was a star — redundant with the trailing unstar
            // and easy to misread as a track row. The leading icon now
            // says what the item IS.
            Icon(
                Icons.Filled.Folder,
                contentDescription = "Folder",
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

/**
 * Native in-app Drive folder browser — replaces the WebView Google Picker
 * (so there is NO WebView → no second in-WebView sign-in, no cold-start
 * scaling). Lists a folder's sub-folders via drive.readonly; tap a folder
 * to navigate in, the back arrow to go up, and "Add this folder" to add the
 * CURRENT folder to the picked list. "root" is Drive's My-Drive-top alias.
 */
@Composable
private fun DriveFolderBrowser(
    listSubFolders: suspend (String) -> Result<List<com.powermediaplayer.cloud.CloudMediaItem>>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Start at the location chooser: My Drive, Shared with me, and each Shared
    // Drive (U7). These three virtual roots can't themselves be added as a picked
    // folder (you can't add "all of My Drive" or an aggregate) — only real folders
    // inside them. Mirrors DriveOAuthProvider LOC_ROOT/MY_DRIVE_ID/SHARED_WITH_ME_ID.
    // Virtual-root ids single-sourced from the provider so the browser and
    // listSubFolders can't drift: a missed literal would let a synthetic root be
    // "added" as a bogus picked folder that 404s in listFiles.
    val locRoot = com.powermediaplayer.cloud.DriveOAuthProvider.LOC_ROOT
    val myDriveId = com.powermediaplayer.cloud.DriveOAuthProvider.MY_DRIVE_ID
    val sharedWithMeId = com.powermediaplayer.cloud.DriveOAuthProvider.SHARED_WITH_ME_ID
    val nonAddable = remember { setOf(locRoot, myDriveId, sharedWithMeId) }
    val stack = remember { mutableStateListOf(locRoot to "Google Drive") }
    val current = stack.last()
    var folders by remember { mutableStateOf<List<com.powermediaplayer.cloud.CloudMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current.first) {
        loading = true
        error = null
        listSubFolders(current.first)
            .onSuccess { folders = it }
            .onFailure { error = it.message ?: "Could not load folders" }
        loading = false
    }

    AlertDialog(
        // System-back / scrim-tap goes UP one folder when navigated in, dismissing the
        // browser only at the root — mirrors the in-title back arrow, so back no longer
        // discards the whole navigation depth. The Cancel button always dismisses.
        onDismissRequest = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss() },
        containerColor = OledBlack,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Up a folder",
                            tint = TealAccent
                        )
                    }
                }
                Text(
                    current.second,
                    color = TealAccent,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = TealAccent) }
                    error != null -> Text("Couldn't load folders: $error", color = TextPrimary)
                    folders.isEmpty() -> Text(
                        // A virtual root ("Add this folder" disabled) must not tell the
                        // user to tap it; a real folder should.
                        when (current.first) {
                            myDriveId -> "No folders in My Drive. Open a folder to add it."
                            sharedWithMeId -> "Nothing has been shared with you yet."
                            locRoot -> "No Drive locations found."
                            else -> "No sub-folders here. Tap “Add this folder” to add “${current.second}”."
                        },
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(folders, key = { it.id }) { f ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { stack.add(f.id to f.name) }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = TealAccent)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    f.name,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.first !in nonAddable,
                onClick = { onAdd(current.first, current.second) },
                modifier = Modifier.defaultMinSize(minWidth = 120.dp, minHeight = 56.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    "Add this folder",
                    color = if (current.first !in nonAddable) TealAccent else TextPrimary.copy(alpha = 0.4f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextPrimary) }
        }
    )
}
