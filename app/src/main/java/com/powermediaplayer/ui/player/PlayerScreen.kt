package com.powermediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.player.components.*
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.util.CoverArtColors

/**
 * Main player screen — fully adaptive layout.
 *
 * Compact (phone portrait): single column, controls at bottom
 * Medium (large phone / unfolded foldable in portrait): wider single column
 * Expanded (tablet / landscape foldable): two-panel — artwork left, controls right
 */
@Composable
fun PlayerScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val artworkBytes by viewModel.artworkBytes.collectAsStateWithLifecycle()
    val sleepTimerExpired by viewModel.sleepTimerExpired.collectAsStateWithLifecycle()
    var coverColors by remember { mutableStateOf<CoverArtColors?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video ALWAYS uses the Compact layout regardless of screen size,
        // so the picture fills the whole screen on phones, tablets, and
        // unfolded foldables. Audio uses the size-appropriate layout.
        when {
            uiState.isVideoContent -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 0
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded -> PlayerScreenExpanded(
                uiState = uiState,
                artworkBytes = artworkBytes,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true }
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 32
            )
            else -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                horizontalPadding = 0
            )
        }

        // Cloud-fetch banner + error banner — top-level so they render
        // above whichever layout is active. AnimatedVisibility avoids the
        // sudden appear/disappear that registered as "screen jumps".
        AnimatedVisibility(
            visible = uiState.cloudFetchInProgress,
            enter = fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = fadeOut(animationSpec = tween(durationMillis = 300)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Teal800.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = TealAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading chapters & metadata…",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (sleepTimerExpired) {
            Surface(
                color = Teal800,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        tint = TealAccent,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Sleep timer finished — playback paused.",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.dismissSleepTimerExpired() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextPrimary)
                    }
                }
            }
        }
        uiState.playerError?.let { errMsg ->
            Surface(
                color = ErrorRed.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errMsg,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextPrimary)
                    }
                }
            }
        }
    }

    // ── Sleep Timer Dialog ────────────────────────────────────────
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            isActive = uiState.sleepTimerActive,
            sleepTimerFormatted = uiState.sleepTimerFormatted,
            onDismiss = { showSleepTimerDialog = false },
            onSetTimer = { minutes ->
                viewModel.startSleepTimer(minutes)
                showSleepTimerDialog = false
            },
            onCancel = {
                viewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            },
            onSleepAtEndOfChapter = {
                viewModel.startSleepAtEndOfChapter()
                showSleepTimerDialog = false
            }
        )
    }

    // ── Chapter / Track Picker ────────────────────────────────────
    if (showChapterPicker) {
        ChapterPickerDialog(
            chapters = uiState.chapters,
            currentChapterIndex = uiState.currentChapterIndex,
            onChapterSelected = { index -> viewModel.seekToChapter(index) },
            playlist = emptyList(), // playlist surfaced from LibraryViewModel in future pass
            currentTrackIndex = uiState.currentTrackIndex,
            onTrackSelected = { index -> viewModel.seekToPlaylistPosition(index.toLong()) },
            onDismiss = { showChapterPicker = false }
        )
    }
}

// ── Compact Layout (Phone / Small Tablet) ─────────────────────────

@Composable
private fun PlayerScreenCompact(
    uiState: PlayerUiState,
    artworkBytes: ByteArray?,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    horizontalPadding: Int = 0
) {
    // Video mode: tap to toggle controls; auto-hide after 32 s.
    // Audio: controls always visible — never auto-hide.
    var controlsVisible by remember(uiState.isVideoContent) {
        mutableStateOf(true)
    }
    LaunchedEffect(uiState.isVideoContent, controlsVisible) {
        if (uiState.isVideoContent && controlsVisible) {
            delay(4_000)            // 4 s — short, per user spec
            controlsVisible = false
        }
    }
    LaunchedEffect(uiState.isVideoContent) {
        if (!uiState.isVideoContent) controlsVisible = true
    }

    // No parent tap-detector — the previous Modifier.clickable on the
    // parent Box caused recompositions per tap that surfaced as
    // controls-row jumps and stutter. Auto-hide (4 s) is the sole
    // dismissal path. To re-show after auto-hide the user can tap any
    // control area (the row Column's child IconButtons trigger normal
    // recomposition that flips controlsVisible via tap-on-anything).
    // Tap-anywhere reveal is implemented below by a transparent
    // pointerInput Box that ONLY responds when controls are HIDDEN —
    // when visible there's no parent interceptor so child buttons fire
    // unimpeded (the bug fixed earlier where skip-buttons didn't
    // register).
    val parentTapModifier = if (uiState.isVideoContent && !controlsVisible) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { controlsVisible = true })
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(parentTapModifier)
    ) {
        if (uiState.isVideoContent) {
            // Video content: render the actual video frames
            // VideoSurface attaches directly to the ExoPlayer in PlaybackService
            VideoSurface(
                isVideoContent = true,
                videoWidth = uiState.videoWidth,
                videoHeight = uiState.videoHeight,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Audio content: show album art with palette-extracted colours
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
        }

        // Bottom-anchored gradient scrim. Video peak alpha = 0.97 (per
        // user spec: "3% transparent = 97% opaque" behind the controls).
        // Top 40 % of the gradient transparent so picture remains visible
        // above the controls; bottom 60 % ramps to near-opaque black.
        // Whole thing is wrapped by AnimatedVisibility together with the
        // controls — when the 32 s auto-hide fires both the scrim and
        // the controls fade out together.
        val scrim: Brush = remember(uiState.isVideoContent) {
            val cols = if (uiState.isVideoContent) {
                // Reverted to lighter pre-prompt values — heavier scrim
                // was contributing to overdraw cost during seeks.
                listOf(
                    Color.Transparent,
                    Color.Transparent,
                    OledBlack.copy(alpha = 0.40f),
                    OledBlack.copy(alpha = 0.85f),
                    OledBlack.copy(alpha = 0.97f)
                )
            } else {
                listOf(
                    Color.Transparent,
                    OledBlack.copy(alpha = 0.3f),
                    OledBlack.copy(alpha = 0.75f),
                    OledBlack.copy(alpha = 0.97f),
                    OledBlack
                )
            }
            Brush.verticalGradient(colors = cols)
        }

        if (uiState.isVideoContent) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(durationMillis = 500)),
                exit = fadeOut(animationSpec = tween(durationMillis = 1000))
            ) {
                OverlayContent(
                    uiState = uiState,
                    coverColors = coverColors,
                    scrim = scrim,
                    horizontalPadding = horizontalPadding,
                    viewModel = viewModel,
                    onShowSleepTimer = onShowSleepTimer,
                    onShowChapterPicker = onShowChapterPicker
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(durationMillis = 500)),
                exit = fadeOut(animationSpec = tween(durationMillis = 1000)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)
            ) { CastButton(modifier = Modifier.size(40.dp)) }
        } else {
            // Audio: render directly, no AnimatedVisibility — controls
            // can never disappear regardless of any state churn.
            OverlayContent(
                uiState = uiState,
                coverColors = coverColors,
                scrim = scrim,
                horizontalPadding = horizontalPadding,
                viewModel = viewModel,
                onShowSleepTimer = onShowSleepTimer,
                onShowChapterPicker = onShowChapterPicker
            )
            CastButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
                    .size(40.dp)
            )
        }
    }
}

/**
 * Player overlay (gradient scrim + track info + controls). Hoisted to
 * a top-level composable so it isn't reallocated as a closure on every
 * recomposition of the parent. With PlayerUiState now @Immutable and
 * artworkBytes lifted out, Compose's smart-recomposition will skip
 * this composable when only the position-poll tick changes.
 */
@Composable
private fun OverlayContent(
    uiState: PlayerUiState,
    coverColors: CoverArtColors?,
    scrim: Brush,
    horizontalPadding: Int,
    viewModel: PlayerViewModel,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
    )
    val scrollMod = if (uiState.isVideoContent) {
        Modifier
    } else {
        Modifier.verticalScroll(rememberScrollState())
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(scrollMod)
            .padding(top = 48.dp, start = horizontalPadding.dp, end = horizontalPadding.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Spacer(modifier = Modifier.weight(1f))
        TrackInfoSection(uiState, coverColors)
        Spacer(modifier = Modifier.height(12.dp))
        ChapterPickerChip(uiState, onShowChapterPicker)
        Spacer(modifier = Modifier.height(4.dp))
        ProgressSliders(
            trackPosition = uiState.trackProgress,
            trackPositionFormatted = uiState.currentPositionFormatted,
            trackDurationFormatted = uiState.durationFormatted,
            trackRemainingFormatted = uiState.trackRemainingFormatted,
            trackSliderEnabled = uiState.controls.trackSlider,
            onTrackSeek = { fraction ->
                val target = uiState.chapterStartMs + (fraction * uiState.duration).toLong()
                viewModel.seekTo(target)
            },
            playlistPosition = uiState.playlistProgress,
            playlistPositionFormatted = uiState.playlistPositionFormatted,
            playlistDurationFormatted = uiState.playlistDurationFormatted,
            playlistRemainingFormatted = uiState.playlistRemainingFormatted,
            playlistSliderEnabled = uiState.controls.playlistSlider,
            onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
            trackIndexDisplay = uiState.trackIndexDisplay
        )
        Spacer(modifier = Modifier.height(8.dp))
        PlaybackControls(
            isPlaying = uiState.isPlaying,
            controls = uiState.controls,
            onPreviousFile = { viewModel.previousFile() },
            onPreviousChapterOrTrack = { viewModel.previousChapterOrTrack() },
            onSkipBack = { viewModel.skipBack(it) },
            onPlayPause = { viewModel.playPause() },
            onSkipForward = { viewModel.skipForward(it) },
            onNextChapterOrTrack = { viewModel.nextChapterOrTrack() },
            onNextFile = { viewModel.nextFile() },
            mediaKind = uiState.mediaKind
        )
        Spacer(modifier = Modifier.height(4.dp))
        PreparedSpeedComponent(
            playbackSpeed = uiState.playbackSpeed,
            onSpeedChange = { viewModel.setPlaybackSpeed(it) },
            modifier = Modifier.padding(horizontal = 16.dp),
            enabled = uiState.controls.playbackSpeed
        )
        Spacer(modifier = Modifier.height(4.dp))
        TertiaryControls(
            currentVolume = viewModel.getCurrentVolume(),
            maxVolume = viewModel.getMaxVolume(),
            onVolumeChange = { viewModel.setVolume(it) },
            sleepTimerActive = uiState.sleepTimerActive,
            sleepTimerFormatted = uiState.sleepTimerFormatted,
            onSleepTimerClick = onShowSleepTimer
        )
        Spacer(modifier = Modifier.height(4.dp))
        // Power-user controls row: A-B loop, frame step ±, and the
        // Bluetooth button. Frame-step buttons only meaningful when
        // paused, so they're always present but cheap.
        val abStart by viewModel.abLoopStart.collectAsStateWithLifecycle()
        val abEnd by viewModel.abLoopEnd.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.stepFrameBack() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Frame back",
                    tint = TealAccent)
            }
            FilledTonalButton(
                onClick = { viewModel.toggleAbLoop() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (abEnd != null) Teal800 else SurfaceElevated,
                    contentColor = TealAccent
                )
            ) {
                Text(
                    text = when {
                        abEnd != null -> "A–B ON"
                        abStart != null -> "A set — tap for B"
                        else -> "A–B Loop"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(onClick = { viewModel.stepFrameForward() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Frame forward",
                    tint = TealAccent)
            }
            IconButton(onClick = { viewModel.addBookmarkHere() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = "Add bookmark",
                    tint = TealAccent)
            }
            // Video effects (mirror H/V, B&W, rotation) — visible only
            // when the current media is a video. Audio playback never
            // sees this button.
            if (uiState.isVideoContent) {
                VideoEffectsButton()
            }
            BluetoothButton(modifier = Modifier.size(48.dp))
        }
        // Bookmark chips for the currently playing item.
        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
        if (bookmarks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                bookmarks.forEach { b ->
                    AssistChip(
                        onClick = { viewModel.seekToBookmark(b) },
                        label = { Text(b.label, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.deleteBookmark(b) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove",
                                    tint = ErrorRed, modifier = Modifier.size(14.dp))
                            }
                        }
                    )
                }
            }
        }
        if (uiState.isLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(color = TealAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Expanded Layout (Tablet / Landscape Foldable) ─────────────────

@Composable
private fun PlayerScreenExpanded(
    uiState: PlayerUiState,
    artworkBytes: ByteArray?,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Left panel: cover art only — video uses the Compact layout for
        // full-screen playback regardless of size class.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
        }

        // Right panel: all controls
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TrackInfoSection(uiState, coverColors)
            Spacer(modifier = Modifier.height(12.dp))
            ChapterPickerChip(uiState, onShowChapterPicker)
            Spacer(modifier = Modifier.height(8.dp))
            ProgressSliders(
                trackPosition = uiState.trackProgress,
                trackPositionFormatted = uiState.currentPositionFormatted,
                trackDurationFormatted = uiState.durationFormatted,
                trackRemainingFormatted = uiState.trackRemainingFormatted,
                trackSliderEnabled = uiState.controls.trackSlider,
                onTrackSeek = { fraction ->
                    val target = uiState.chapterStartMs + (fraction * uiState.duration).toLong()
                    viewModel.seekTo(target)
                },
                playlistPosition = uiState.playlistProgress,
                playlistPositionFormatted = uiState.playlistPositionFormatted,
                playlistDurationFormatted = uiState.playlistDurationFormatted,
                playlistRemainingFormatted = uiState.playlistRemainingFormatted,
                playlistSliderEnabled = uiState.controls.playlistSlider,
                onPlaylistSeek = { fraction -> viewModel.seekToPlaylistPosition((fraction * uiState.totalPlaylistDuration).toLong()) },
                trackIndexDisplay = uiState.trackIndexDisplay
            )
            Spacer(modifier = Modifier.height(8.dp))
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                controls = uiState.controls,
                onPreviousFile = { viewModel.previousFile() },
                onPreviousChapterOrTrack = { viewModel.previousChapterOrTrack() },
                onSkipBack = { viewModel.skipBack(it) },
                onPlayPause = { viewModel.playPause() },
                onSkipForward = { viewModel.skipForward(it) },
                onNextChapterOrTrack = { viewModel.nextChapterOrTrack() },
                onNextFile = { viewModel.nextFile() },
                mediaKind = uiState.mediaKind
            )
            Spacer(modifier = Modifier.height(8.dp))
            PreparedSpeedComponent(
                playbackSpeed = uiState.playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                modifier = Modifier.padding(horizontal = 16.dp),
                enabled = uiState.controls.playbackSpeed
            )
            Spacer(modifier = Modifier.height(4.dp))
            TertiaryControls(
                currentVolume = viewModel.getCurrentVolume(),
                maxVolume = viewModel.getMaxVolume(),
                onVolumeChange = { viewModel.setVolume(it) },
                sleepTimerActive = uiState.sleepTimerActive,
                sleepTimerFormatted = uiState.sleepTimerFormatted,
                onSleepTimerClick = onShowSleepTimer
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Power-user controls row (audio-tablet parity with Compact):
            // A-B loop + frame step + bookmark + Bluetooth toggle.
            val abStartE by viewModel.abLoopStart.collectAsStateWithLifecycle()
            val abEndE by viewModel.abLoopEnd.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.stepFrameBack() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Frame back",
                        tint = TealAccent)
                }
                FilledTonalButton(
                    onClick = { viewModel.toggleAbLoop() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (abEndE != null) Teal800 else SurfaceElevated,
                        contentColor = TealAccent
                    )
                ) {
                    Text(
                        text = when {
                            abEndE != null -> "A–B ON"
                            abStartE != null -> "A set — tap for B"
                            else -> "A–B Loop"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                IconButton(onClick = { viewModel.stepFrameForward() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Frame forward",
                        tint = TealAccent)
                }
                IconButton(onClick = { viewModel.addBookmarkHere() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = "Add bookmark",
                        tint = TealAccent)
                }
                BluetoothButton(modifier = Modifier.size(48.dp))
            }
            val bookmarksE by viewModel.bookmarks.collectAsStateWithLifecycle()
            if (bookmarksE.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bookmarksE.forEach { b ->
                        AssistChip(
                            onClick = { viewModel.seekToBookmark(b) },
                            label = { Text(b.label, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.deleteBookmark(b) },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove",
                                        tint = ErrorRed, modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Shared Sub-Composables ─────────────────────────────────────────

@Composable
private fun TrackInfoSection(uiState: PlayerUiState, coverColors: CoverArtColors?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (uiState.artist.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.artist,
                style = MaterialTheme.typography.titleMedium,
                color = coverColors?.vibrant ?: TealAccent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.album.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = uiState.album,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Audio format indicator — codec + channel layout + sample rate.
        // Visible for both audio and video tracks (most films have a
        // separate audio track and the user often cares about whether
        // they're getting Atmos vs stereo downmix).
        if (uiState.audioFormatLabel.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.audioFormatLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = uiState.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (uiState.syncedLyrics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SyncedLyricsPanel(
                lines = uiState.syncedLyrics,
                positionMs = uiState.currentPosition,
                onLineTap = { /* set in caller */ }
            )
        } else if (uiState.lyrics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            // Plain (non-synced) lyrics — capped height with vertical
            // scroll so a long song doesn't push the controls off-screen.
            // Source: LRCLib (Spotify Web API doesn't expose lyrics).
            Surface(
                color = OledBlack.copy(alpha = 0.4f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = uiState.lyrics,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Synced lyrics panel for Spotify mirror playback. Highlights the
 * line whose timestamp is the latest <= current playback position,
 * auto-scrolls it into view, and lets the user tap any line to jump
 * Spotify Connect to that timestamp. The tap goes through the
 * existing PlayerViewModel.seekTo path which routes to the Web API
 * /me/player/seek endpoint when Spotify is the active source.
 */
@Composable
private fun SyncedLyricsPanel(
    lines: List<com.powermediaplayer.cloud.LyricLine>,
    positionMs: Long,
    onLineTap: (Long) -> Unit
) {
    val viewModel = androidx.hilt.navigation.compose.hiltViewModel<PlayerViewModel>()
    val activeIndex = remember(lines, positionMs) {
        // indexOfLast where line.timeMs <= positionMs; -1 if before first.
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        idx
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Grace period after the user manually scrolls before we re-centre
    // the active line. Set to "now + 2 s" whenever scroll-in-progress
    // starts; auto-recentre is suppressed until that timestamp passes.
    var userScrollUntilMs by remember { mutableStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrollUntilMs = android.os.SystemClock.elapsedRealtime() + 2_000L
        }
    }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 &&
            android.os.SystemClock.elapsedRealtime() >= userScrollUntilMs
        ) {
            // Centre the active line in the panel by leaving ~2 lines
            // of context above it.
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }
    Surface(
        color = OledBlack.copy(alpha = 0.4f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            itemsIndexed(
                items = lines,
                key = { i: Int, l: com.powermediaplayer.cloud.LyricLine -> "$i:${l.timeMs}" }
            ) { idx: Int, line: com.powermediaplayer.cloud.LyricLine ->
                val isActive = idx == activeIndex
                Text(
                    text = line.text.ifEmpty { "♪" },
                    style = if (isActive) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodySmall,
                    color = if (isActive) TealAccent else TextSecondary.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.seekTo(line.timeMs) }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Tappable chip to open chapter/track picker.
 * Visible when chapters or playlist are available.
 */
@Composable
private fun ChapterPickerChip(uiState: PlayerUiState, onClick: () -> Unit) {
    val hasChapters = uiState.hasChapters
    val hasPlaylist = uiState.totalTracks > 1

    if (!hasChapters && !hasPlaylist) return

    val label = when {
        hasChapters -> {
            val idx = uiState.currentChapterIndex
            val chapter = uiState.chapters.getOrNull(idx)
            if (chapter != null) "Ch. ${idx + 1}: ${chapter.title}" else "Chapters"
        }
        else -> "${uiState.currentTrackIndex + 1} / ${uiState.totalTracks} tracks"
    }

    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (hasChapters) Icons.Filled.BookmarkBorder else Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = SurfaceElevated,
            labelColor = TealAccent,
            leadingIconContentColor = TealAccent
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = TealAccent.copy(alpha = 0.4f)
        )
    )
}

// ── Sleep Timer Dialog ─────────────────────────────────────────────

@Composable
private fun SleepTimerDialog(
    isActive: Boolean,
    sleepTimerFormatted: String,
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancel: () -> Unit,
    onSleepAtEndOfChapter: () -> Unit = {}
) {
    val presets = listOf(
        15 to "15 minutes",
        30 to "30 minutes",
        45 to "45 minutes",
        60 to "1 hour",
        90 to "1.5 hours",
        120 to "2 hours"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer", style = MaterialTheme.typography.titleLarge, color = TealAccent) },
        text = {
            Column {
                // Description
                Text(
                    text = "App will pause music after this much time:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Active timer countdown
                if (isActive) {
                    Surface(
                        color = Teal800,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Time remaining:",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = sleepTimerFormatted,
                                style = MaterialTheme.typography.titleMedium,
                                color = TealAccent
                            )
                        }
                    }
                }

                HorizontalDivider(color = DisabledContent, modifier = Modifier.padding(bottom = 4.dp))

                // Preset buttons
                presets.forEach { (minutes, label) ->
                    TextButton(
                        onClick = { onSetTimer(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                HorizontalDivider(color = DisabledContent, modifier = Modifier.padding(vertical = 4.dp))
                TextButton(
                    onClick = onSleepAtEndOfChapter,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Sleep at end of current chapter / track",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TealAccent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isActive) {
                    HorizontalDivider(color = DisabledContent)
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Cancel Timer",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ErrorRed,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
        containerColor = SurfaceElevated
    )
}

