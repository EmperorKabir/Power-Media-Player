package com.powermediaplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.TimeFormatter

/**
 * Media library screen with tabs for Audio and Video files.
 * Scans the device on first load and displays files in a scrollable list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onNavigateToPlayer: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission handling
    var hasPermission by remember {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.any { it }
        if (hasPermission) viewModel.refreshMedia()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            val permissions = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions)
        }
    }

    // SAF File Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Take persistable permission so we can access the file later
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.handlePickedFile(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // ── Header ───────────────────────────────────────────────
        TopAppBar(
            title = {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TealAccent
                )
            },
            actions = {
                // Sort menu
                var sortMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = "Sort menu",
                            tint = TealAccent
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        SortMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = sortModeLabel(mode),
                                        color = if (mode == uiState.sortMode) TealAccent else TextPrimary
                                    )
                                },
                                onClick = {
                                    viewModel.setSortMode(mode)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                // Open file picker (SAF)
                IconButton(onClick = {
                    filePickerLauncher.launch(
                        arrayOf("audio/*", "video/*", "application/ogg", "application/x-flac")
                    )
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileOpen,
                        contentDescription = "Open file",
                        tint = TealAccent
                    )
                }
                // Refresh MediaStore scan
                IconButton(onClick = { viewModel.refreshMedia() }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = TealAccent
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = OledBlack
            )
        )

        // ── Tabs: Audio | Video ──────────────────────────────────
        TabRow(
            selectedTabIndex = uiState.selectedTab,
            containerColor = OledBlack,
            contentColor = TealAccent
        ) {
            Tab(
                selected = uiState.selectedTab == 0,
                onClick = { viewModel.setSelectedTab(0) },
                text = {
                    Text(
                        "Audio (${uiState.audioFiles.size})",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selectedContentColor = TealAccent,
                unselectedContentColor = DisabledGrey
            )
            Tab(
                selected = uiState.selectedTab == 1,
                onClick = { viewModel.setSelectedTab(1) },
                text = {
                    Text(
                        "Video (${uiState.videoFiles.size})",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selectedContentColor = TealAccent,
                unselectedContentColor = DisabledGrey
            )
        }

        // ── Content ──────────────────────────────────────────────
        if (!hasPermission) {
            // Permission not granted
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.FolderOff,
                        contentDescription = null,
                        tint = DisabledGrey,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Storage permission required",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val permissions = if (Build.VERSION.SDK_INT >= 33) {
                                arrayOf(
                                    Manifest.permission.READ_MEDIA_AUDIO,
                                    Manifest.permission.READ_MEDIA_VIDEO
                                )
                            } else {
                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            permissionLauncher.launch(permissions)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Teal800,
                            contentColor = TealAccent
                        )
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TealAccent)
            }
        } else {
            val files = if (uiState.selectedTab == 0) uiState.audioFiles else uiState.videoFiles

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (uiState.selectedTab == 0) Icons.Filled.MusicOff else Icons.Filled.VideocamOff,
                            contentDescription = null,
                            tint = DisabledGrey,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No ${if (uiState.selectedTab == 0) "audio" else "video"} files found",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                        MediaFileItem(
                            file = file,
                            isFavorite = file.uri.toString() in uiState.favorites,
                            onClick = {
                                // Enqueue all visible files, start at tapped index
                                viewModel.playFiles(files, index)
                                onNavigateToPlayer()
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(file.uri) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single media file list item with favorite toggle.
 */
@Composable
private fun MediaFileItem(
    file: MediaFileInfo,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (file.isVideo) Icons.Filled.VideoFile else Icons.Filled.AudioFile,
                contentDescription = if (file.isVideo) "Video file" else "Audio file",
                tint = TealAccent,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                if (file.artist.isNotEmpty() && file.artist != "Unknown Artist") {
                    Text(
                        text = file.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }

        // Duration
        if (file.duration > 0) {
            Text(
                text = TimeFormatter.formatDuration(file.duration),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }

        // Favorite toggle
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) androidx.compose.ui.graphics.Color.Red else TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME_ASC -> "Name (A → Z)"
    SortMode.NAME_DESC -> "Name (Z → A)"
    SortMode.SIZE_ASC -> "Size (smallest first)"
    SortMode.SIZE_DESC -> "Size (largest first)"
    SortMode.TYPE -> "Type"
    SortMode.DATE_DESC -> "Recently modified"
    SortMode.FAVORITES_FIRST -> "Favorites first"
}
