package com.powermediaplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import kotlinx.coroutines.launch
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
    val multiSelectMode by viewModel.multiSelectMode.collectAsStateWithLifecycle()
    val selectedUris by viewModel.selectedUris.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showInfoSheet by remember { mutableStateOf(false) }
    var contextItem by remember { mutableStateOf<MediaFileInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<MediaFileInfo?>(null) }
    var overrideTarget by remember { mutableStateOf<MediaFileInfo?>(null) }
    var editTagsTarget by remember { mutableStateOf<MediaFileInfo?>(null) }
    editTagsTarget?.let { item ->
        com.powermediaplayer.ui.library.EditTagsDialog(
            initialTitle = item.title,
            initialArtist = item.artist,
            initialAlbum = item.album,
            onCancel = { editTagsTarget = null },
            onSave = { t, a, al ->
                viewModel.editTags(item.uri.toString(), t, a, al)
                editTagsTarget = null
            }
        )
    }
    overrideTarget?.let { item ->
        com.powermediaplayer.ui.overrides.MediaOverridesPopup(
            mediaUri = item.uri.toString(),
            title = item.title,
            dao = viewModel.mediaOverrideDao,
            onDismiss = { overrideTarget = null }
        )
    }
    // Exit multi-select on system back if currently in it.
    androidx.activity.compose.BackHandler(enabled = multiSelectMode) {
        viewModel.exitMultiSelect()
    }
    if (showInfoSheet) {
        com.powermediaplayer.ui.info.InfoSheet(
            data = com.powermediaplayer.ui.info.libraryInfo,
            onDismiss = { showInfoSheet = false }
        )
    }
    contextItem?.let { item ->
        val isFav = item.uri.toString() in uiState.favorites
        com.powermediaplayer.ui.player.components.TrackContextSheet(
            title = item.title,
            subtitle = item.artist.takeIf { it.isNotBlank() && it != "Unknown Artist" } ?: "",
            actions = com.powermediaplayer.ui.player.components.TrackContextActions(
                onFavourite = if (!isFav) {
                    { viewModel.toggleFavorite(item.uri); contextItem = null }
                } else null,
                onUnfavourite = if (isFav) {
                    { viewModel.toggleFavorite(item.uri); contextItem = null }
                } else null,
                onHide = {
                    viewModel.hideUri(item.uri.toString())
                    contextItem = null
                },
                onAddToQueueNext = {
                    viewModel.addToQueueNext(item)
                    contextItem = null
                },
                onEditTags = {
                    editTagsTarget = item
                    contextItem = null
                },
                // §C7 / §C25 — override-* items only when the row is
                // favourited (the Library equivalent of "starred"). For
                // pinned files, the same gate fires from LastPlayedScreen.
                onOverrideSpeed = if (isFav) {
                    { overrideTarget = item; contextItem = null }
                } else null,
                onOverrideAudio = if (isFav) {
                    { overrideTarget = item; contextItem = null }
                } else null,
                onOverrideVideo = if (isFav && item.isVideo) {
                    { overrideTarget = item; contextItem = null }
                } else null,
                onPinAlbum = if (item.album.isNotBlank() && !item.isVideo) {
                    {
                        // Snapshot every audio track that shares this
                        // album + artist key from the current library
                        // state. Album disambiguated by artist so
                        // same-titled compilations stay distinct.
                        val members = uiState.audioFiles.filter {
                            it.album == item.album && it.artist == item.artist
                        }.sortedWith(compareBy({ it.album }, { it.title }))
                        val albumKey = "${item.artist.lowercase()}|||${item.album.lowercase()}"
                        scope.launch {
                            val ok = viewModel.pinAlbum(
                                albumKey = albumKey,
                                title = item.album,
                                artist = item.artist,
                                artworkUri = item.albumArtUri?.toString(),
                                tracks = members
                            ).isSuccess
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Pinned '${item.album}' (${members.size} tracks)"
                                else "Favourites full — unpin one first",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        contextItem = null
                    }
                } else null,
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = if (item.isVideo) "video/*" else "audio/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, item.uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share"))
                    contextItem = null
                },
                onOpenInOtherApp = {
                    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, if (item.isVideo) "video/*" else "audio/*")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(view, "Open with"))
                    }
                    contextItem = null
                },
                onDelete = {
                    // Defer to a confirmation dialog — irreversible.
                    pendingDelete = item
                    contextItem = null
                }
            ),
            onDismiss = { contextItem = null }
        )
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this file?", color = ErrorRed) },
            text = {
                Text(
                    "Permanently remove '${file.title}' from your phone. This can't be undone.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = file
                    pendingDelete = null
                    viewModel.deleteFile(toDelete.uri) { /* refreshed inside */ }
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = TealAccent)
                }
            },
            containerColor = OledBlack
        )
    }

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

    // §C16: refresh on tab-open if stale (>30 s since last scan).
    // Cost is one MediaStore round-trip; previous list stays visible
    // while the new one loads in. Zero battery vs continuous watcher.
    LaunchedEffect(Unit) {
        viewModel.refreshIfStale()
    }

    // §F — first-run deep-scan opt-in dialog. Shows once on the first
    // Library-tab open after fresh install (after media permissions
    // granted, since refreshIfStale's MediaStore query already
    // requested perms by this point).
    val firstRunSeen by viewModel.firstRunSeen.collectAsStateWithLifecycle()
    if (!firstRunSeen && hasPermission) {
        AlertDialog(
            onDismissRequest = { viewModel.skipFirstRunDeepScan() },
            title = {
                Text("Find more accurate album art and metadata?",
                    color = TealAccent,
                    style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "We can re-read every track's full header for richer titles, " +
                        "artists, and artwork. Takes a few seconds the first time. " +
                        "You can change this anytime in Settings → Deep Scan.",
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.acceptFirstRunDeepScan() }) {
                    Text("Yes, deep-scan", color = TealAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.skipFirstRunDeepScan() }) {
                    Text("Skip", color = TextSecondary)
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
                // 3-dot menu — currently exposes only "Select multiple"
                // (§C26). Future additions: "Sort by …", "Group by …".
                var moreMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = TealAccent
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Select multiple", color = TextPrimary) },
                            onClick = {
                                viewModel.enterMultiSelect()
                                moreMenuExpanded = false
                            }
                        )
                    }
                }
                // Per-tab info icon (Q1 LOCKED — rounded-square blue box).
                com.powermediaplayer.ui.info.InfoIcon(
                    onClick = { showInfoSheet = true }
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = OledBlack
            )
        )

        // Multi-select action bar overlay (§C26). Replaces visual focus
        // of the regular top bar. Renders BELOW the standard TopAppBar
        // so the user retains access to refresh / info while selecting.
        if (multiSelectMode) {
            Surface(color = TealAccent.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.exitMultiSelect() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = TealAccent)
                    }
                    Text(
                        text = "${selectedUris.size} selected",
                        color = TealAccent,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = { viewModel.selectAllVisible() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all visible",
                            tint = TealAccent)
                    }
                    IconButton(
                        onClick = { viewModel.favouriteSelectedAndExit() },
                        enabled = selectedUris.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = "Favourite all selected",
                            tint = if (selectedUris.isNotEmpty()) TealAccent else DisabledGrey)
                    }
                    IconButton(
                        onClick = { viewModel.hideSelected() },
                        enabled = selectedUris.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide all selected",
                            tint = if (selectedUris.isNotEmpty()) TealAccent else DisabledGrey)
                    }
                }
            }
        }

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

        // §C6 — saved smart-playlist rail. Tap a chip to play the
        // resolved track list. Empty state hidden so this never shows
        // unless the user has actually created one.
        com.powermediaplayer.ui.smartplaylists.SmartPlaylistRail(
            onPlayResolved = { resolved ->
                if (resolved.isNotEmpty()) {
                    viewModel.playFiles(resolved, 0)
                    onNavigateToPlayer()
                }
            }
        )
        // §C10 fix — smart-playlist editor (create/list/delete) now
        // sits next to the rail in the Library tab per the locked
        // spec, replacing the prior Settings-tab placement.
        com.powermediaplayer.ui.smartplaylists.SmartPlaylistsSection()

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
                                isSelected = file.uri.toString() in selectedUris,
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) {
                                        viewModel.toggleSelection(file.uri.toString())
                                    } else {
                                        if (file.isVideo) {
                                            viewModel.playSingle(file)
                                        } else {
                                            viewModel.playFiles(files, originalIndex)
                                        }
                                        onNavigateToPlayer()
                                    }
                                },
                                onLongClick = {
                                    if (!multiSelectMode) contextItem = file
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
                            isSelected = file.uri.toString() in selectedUris,
                            multiSelectMode = multiSelectMode,
                            onClick = {
                                if (multiSelectMode) {
                                    viewModel.toggleSelection(file.uri.toString())
                                } else {
                                    // Videos play single; audio queues the
                                    // visible list as an album/audiobook so
                                    // chapter / track navigation makes sense.
                                    if (file.isVideo) {
                                        viewModel.playSingle(file)
                                    } else {
                                        viewModel.playFiles(files, index)
                                    }
                                    onNavigateToPlayer()
                                }
                            },
                            onLongClick = {
                                if (!multiSelectMode) contextItem = file
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaFileItem(
    file: MediaFileInfo,
    isFavorite: Boolean,
    isSelected: Boolean = false,
    multiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) TealAccent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = TealAccent)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
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

        // Favourite toggle — star icon for visual consistency with the
        // Drive favourites strip and Pinned section in Last Played.
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) TealAccent else TextTertiary,
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
