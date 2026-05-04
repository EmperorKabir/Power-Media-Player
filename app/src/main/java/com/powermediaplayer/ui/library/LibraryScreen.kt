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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
            onNavigateToPlayer()
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

        // ── Search Bar ───────────────────────────────────────────
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search title, artist, or album", color = TextSecondary) },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = TealAccent)
            },
            trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealAccent,
                unfocusedBorderColor = DisabledContent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = TealAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
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
                // Auto-scroll to top whenever the sort mode (or active tab)
                // changes — otherwise the list keeps its previous scroll
                // offset which is disorienting when items have re-ordered.
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(uiState.sortMode, uiState.selectedTab) {
                    listState.scrollToItem(0)
                }
                // Favourites strip — shown only when the active tab has
                // at least one starred file. Mirrors the Drive strip in
                // the Cloud tab so the user gets quick access to pinned
                // files at the top of the list. Tapping a favourite
                // plays it via the same path as the main list (single
                // for video, queue for audio).
                val favouriteFiles = remember(files, uiState.favorites) {
                    files.filter { it.uri.toString() in uiState.favorites }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (favouriteFiles.isNotEmpty()) {
                        item(key = "fav_header") {
                            Text(
                                text = "Favourite files (${favouriteFiles.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(
                                    start = 16.dp, top = 4.dp, bottom = 4.dp
                                )
                            )
                        }
                        itemsIndexed(
                            favouriteFiles,
                            key = { _, f -> "fav_${f.id}" }
                        ) { _, file ->
                            val originalIndex = files.indexOfFirst { it.id == file.id }
                                .coerceAtLeast(0)
                            MediaFileItem(
                                file = file,
                                isFavorite = true,
                                onClick = {
                                    if (file.isVideo) {
                                        viewModel.playSingle(file)
                                    } else {
                                        viewModel.playFiles(files, originalIndex)
                                    }
                                    onNavigateToPlayer()
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(file.uri) }
                            )
                        }
                        item(key = "fav_divider") {
                            HorizontalDivider(
                                color = SurfaceElevated,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                        MediaFileItem(
                            file = file,
                            isFavorite = file.uri.toString() in uiState.favorites,
                            onClick = {
                                // Videos play single; audio queues the
                                // visible list as an album/audiobook so
                                // chapter / track navigation makes sense.
                                if (file.isVideo) {
                                    viewModel.playSingle(file)
                                } else {
                                    viewModel.playFiles(files, index)
                                }
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
    SortMode.DURATION_DESC -> "Duration (longest first)"
    SortMode.DURATION_ASC -> "Duration (shortest first)"
}
