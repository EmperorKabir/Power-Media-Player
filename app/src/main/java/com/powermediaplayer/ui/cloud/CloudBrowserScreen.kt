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
                    items(uiState.items, key = { it.id }) { item ->
                        CloudItemRow(
                            item = item,
                            onClick = {
                                viewModel.openItem(item)
                                if (!item.isFolder) onNavigateToPlayer()
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
private fun CloudItemRow(item: CloudMediaItem, onClick: () -> Unit) {
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
    }
}
