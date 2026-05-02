package com.powermediaplayer.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    val driveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.handleDriveResult(result.data) }

    val spotifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.handleSpotifyResult(result.data) }

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

        // Provider quick-switch tabs — tap to jump straight to Drive or
        // Spotify. Tapping a not-signed-in provider triggers the sign-in
        // flow rather than no-op'ing.
        ProviderTabRow(
            active = uiState.activeProvider,
            driveLoggedIn = uiState.driveLoggedIn,
            spotifyLoggedIn = uiState.spotifyLoggedIn,
            onSelectDrive = {
                if (uiState.driveLoggedIn) viewModel.browseDrive(null, "Root")
                else driveLauncher.launch(viewModel.buildDriveSignInIntent())
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
                onConnectDrive = { driveLauncher.launch(viewModel.buildDriveSignInIntent()) },
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
            } else if (uiState.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Favourites strip — only at Drive root, only when
                    // there's at least one favourite. Single horizontal
                    // section above the regular file list.
                    val showFavourites = uiState.activeProvider == CloudProviderType.GOOGLE_DRIVE &&
                        uiState.folderStack.size <= 1 &&
                        uiState.driveFavourites.isNotEmpty()
                    if (showFavourites) {
                        item(key = "fav_header") {
                            Text(
                                text = "Favourite folders",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(uiState.driveFavourites, key = { "fav_${it.id}" }) { fav ->
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
                        item(key = "fav_divider") {
                            HorizontalDivider(
                                color = SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(uiState.items, key = { it.id }) { item ->
                        val isFav = uiState.driveFavourites.any { it.id == item.id }
                        val canFavourite = item.isFolder && item.sourceProvider == CloudProviderType.GOOGLE_DRIVE
                        CloudItemRow(
                            item = item,
                            isFavourite = isFav,
                            canFavourite = canFavourite,
                            onToggleFavourite = { viewModel.toggleDriveFavourite(item) },
                            onClick = {
                                if (item.isFolder) {
                                    viewModel.openItem(item)
                                } else {
                                    // Navigate ONLY when playback actually
                                    // starts — failed Spotify previews stay
                                    // on the cloud screen so the user sees
                                    // the error banner.
                                    viewModel.openItem(item, onPlaybackStarted = onNavigateToPlayer)
                                }
                            }
                        )
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
            name = "Google Drive",
            description = "Stream audio and video files stored in your Drive.",
            loggedIn = driveLoggedIn,
            onConnect = onConnectDrive,
            onBrowse = onBrowseDrive,
            onSignOut = onSignOutDrive
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
    onSignOut: () -> Unit
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
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign out", color = TextSecondary)
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onConnect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Teal800,
                        contentColor = TealAccent
                    )
                ) { Text("Connect $name") }
            }
        }
    }
}

@Composable
private fun CloudItemRow(
    item: CloudMediaItem,
    onClick: () -> Unit,
    isFavourite: Boolean = false,
    canFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {}
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
