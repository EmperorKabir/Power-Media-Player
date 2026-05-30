package com.powermediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.res.painterResource
import com.powermediaplayer.R
import com.powermediaplayer.ui.info.InfoIcon
import com.powermediaplayer.ui.info.InfoSheet
import com.powermediaplayer.ui.info.playerInfo
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
 * Bug fix (popup-timeout vs controls-timeout independence): every popup
 * launched from the player overlay (audio effects, video effects,
 * crossfade, BT, info, etc.) registers itself here on open and
 * deregisters on close. PlayerScreen's controls auto-hide LaunchedEffect
 * checks this counter so the controls won't fade out (and tear down
 * the popup composables) while the user is still in a popup whose own
 * timeout is set to a longer value or "Never".
 */
val LocalOpenPopupCount = compositionLocalOf<androidx.compose.runtime.MutableIntState> {
    error("LocalOpenPopupCount not provided")
}

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
    onNavigateToLibrary: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val artworkBytes by viewModel.artworkBytes.collectAsStateWithLifecycle()
    val sleepTimerExpired by viewModel.sleepTimerExpired.collectAsStateWithLifecycle()
    val artworkScaleMode by viewModel.artworkScaleMode.collectAsStateWithLifecycle()
    val artworkContentScale: androidx.compose.ui.layout.ContentScale =
        if (artworkScaleMode == "fill") {
            androidx.compose.ui.layout.ContentScale.Crop
        } else {
            androidx.compose.ui.layout.ContentScale.Fit
        }
    var coverColors by remember { mutableStateOf<CoverArtColors?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    // §B5 LOCKED — auto-revert snackbar. Triggered by PlaybackService
    // when the active source can't crossfade (Cast / video / M4B);
    // displayed as a Toast for parity with the rest of the player UI
    // (no SnackbarHost anchor in this screen).
    val crossfadeRevertReason by viewModel.crossfadeAutoRevertReason.collectAsStateWithLifecycle()
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(crossfadeRevertReason) {
        crossfadeRevertReason?.let { reason ->
            android.widget.Toast.makeText(
                toastCtx, reason, android.widget.Toast.LENGTH_SHORT
            ).show()
            viewModel.clearCrossfadeAutoRevertReason()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video ALWAYS uses the Compact layout regardless of screen size,
        // so the picture fills the whole screen on phones, tablets, and
        // unfolded foldables. Audio uses the size-appropriate layout.
        when {
            uiState.isVideoContent -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                artworkContentScale = artworkContentScale,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                onShowInfo = { showInfoSheet = true },
                horizontalPadding = 0
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded -> PlayerScreenExpanded(
                uiState = uiState,
                artworkBytes = artworkBytes,
                artworkContentScale = artworkContentScale,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                onShowInfo = { showInfoSheet = true }
            )
            windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                artworkContentScale = artworkContentScale,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                onShowInfo = { showInfoSheet = true },
                horizontalPadding = 32
            )
            else -> PlayerScreenCompact(
                uiState = uiState,
                artworkBytes = artworkBytes,
                artworkContentScale = artworkContentScale,
                viewModel = viewModel,
                coverColors = coverColors,
                onColorsExtracted = { coverColors = it },
                onShowSleepTimer = { showSleepTimerDialog = true },
                onShowChapterPicker = { showChapterPicker = true },
                onShowInfo = { showInfoSheet = true },
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
                        text = "Loading metadata… please wait…",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        // vc31 UX fix: render the player's own isLoading state. It was
        // set during a (slow, 2-3 min) Last-Played resume but never
        // rendered — the screen looked frozen. Mirrors the cloud-fetch
        // banner. Gated to not double-stack with cloudFetchInProgress.
        AnimatedVisibility(
            visible = uiState.isLoading && !uiState.cloudFetchInProgress,
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
                        text = "Loading media… please wait…",
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

        // vc31 — empty-player guidance. The cold-start path restores the
        // most-recent LOCAL/DRIVE item paused (PlayerViewModel init), so
        // this only shows when there is genuinely nothing to wait in the
        // player (fresh install, history cleared, or a Spotify-only
        // recent that can't be pre-loaded). Audio only — video has its
        // own surface. Gated off while loading so it never flashes over
        // an in-progress restore.
        if (!uiState.hasMedia && !uiState.isLoading &&
            !uiState.cloudFetchInProgress && !uiState.isVideoContent
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = TealAccent,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Nothing's playing yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pick a track from your Library or open a file, and it'll be waiting here next time you come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onNavigateToLibrary,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Teal800,
                        contentColor = TealAccent
                    )
                ) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open Library")
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
                viewModel.startSleepTimerMode(PlayerViewModel.SleepTimerMode.END_OF_CHAPTER)
                showSleepTimerDialog = false
            },
            onSleepAtEndOfTrack = {
                viewModel.startSleepTimerMode(PlayerViewModel.SleepTimerMode.END_OF_TRACK)
                showSleepTimerDialog = false
            },
            onSleepAtEndOfQueue = {
                viewModel.startSleepTimerMode(PlayerViewModel.SleepTimerMode.END_OF_QUEUE)
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

    // ── Info sheet ────────────────────────────────────────────────
    if (showInfoSheet) {
        InfoSheet(
            data = playerInfo,
            onDismiss = { showInfoSheet = false }
        )
    }
}

// ── Compact Layout (Phone / Small Tablet) ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerScreenCompact(
    uiState: PlayerUiState,
    artworkBytes: ByteArray?,
    artworkContentScale: androidx.compose.ui.layout.ContentScale,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowInfo: () -> Unit,
    horizontalPadding: Int = 0
) {
    // Video mode: tap to toggle controls; auto-hide per user setting.
    // Audio: controls always visible — hiding cover + transport in
    // audio mode would defeat the purpose of the now-playing surface.
    var controlsVisible by remember(uiState.isVideoContent) {
        mutableStateOf(true)
    }
    // Bug fix (user-reported "popup goes away when its timeout is set
    // to longer than the controls timeout"): every popup-launching
    // button used to keep its showSheet state inside its own composable.
    // When controls auto-hid, OverlayContent left composition → every
    // popup's remember was destroyed → sheets dismissed regardless of
    // their own timeout. Suppress the controls auto-hide while ANY
    // popup is open (counter > 0).
    val openPopupCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val videoHideSec by viewModel.videoControlsHideSec.collectAsStateWithLifecycle()
    LaunchedEffect(
        uiState.isVideoContent, controlsVisible, videoHideSec,
        openPopupCount.intValue
    ) {
        if (uiState.isVideoContent && controlsVisible &&
            videoHideSec > 0 && openPopupCount.intValue == 0
        ) {
            delay(videoHideSec * 1000L)
            // Re-check after delay — a popup may have opened in the
            // meantime which would have invalidated this hide.
            if (openPopupCount.intValue == 0) controlsVisible = false
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

    androidx.compose.runtime.CompositionLocalProvider(LocalOpenPopupCount provides openPopupCount) {
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
                onColorsExtracted = onColorsExtracted,
                contentScale = artworkContentScale
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
                    onShowChapterPicker = onShowChapterPicker,
                    onShowInfo = onShowInfo
                )
            }
            // CastButton previously lived top-right of the video frame and
            // top-right of the audio cover; both have moved into the
            // bottom transport-control row inside OverlayContent so the
            // Cast / Bluetooth / Audio-effects controls are grouped.
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
                onShowChapterPicker = onShowChapterPicker,
                onShowInfo = onShowInfo
            )
        }
    }
    } // closes CompositionLocalProvider
}

/**
 * Player overlay (gradient scrim + track info + controls). Hoisted to
 * a top-level composable so it isn't reallocated as a closure on every
 * recomposition of the parent. With PlayerUiState now @Immutable and
 * artworkBytes lifted out, Compose's smart-recomposition will skip
 * this composable when only the position-poll tick changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayContent(
    uiState: PlayerUiState,
    coverColors: CoverArtColors?,
    scrim: Brush,
    horizontalPadding: Int,
    viewModel: PlayerViewModel,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowInfo: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
    )
    // Info icon top-right. Inside this OverlayContent (which is wrapped
    // in AnimatedVisibility for video) the icon hides with controls per
    // Q1 LOCKED. For audio mode (no AnimatedVisibility wrapper) the
    // icon stays visible. Q2 LOCKED Option A: scrim hides with controls,
    // independent layer not required — current arch already correct.
    Box(modifier = Modifier.fillMaxSize()) {
        InfoIcon(
            onClick = onShowInfo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        )
    }
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
            // CastPlayer (Media3 1.6.0) doesn't expose playback speed
            // and Spotify Connect plays on a remote device we can't
            // touch — grey out in both cases so the user knows why
            // toggling the speed has no audible effect.
            enabled = uiState.controls.playbackSpeed && !uiState.isCasting && !uiState.isSpotifyActive
        )
        if (uiState.isSpotifyActive) {
            Text(
                text = "Speed / pitch don't apply to Spotify Connect — audio plays " +
                    "on the Spotify device. Switch to local or Drive to use them.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )
        }
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
            // Frame-step ± hidden during cast (receiver renders the
            // stream itself) AND on audio (no visible frame to step).
            // Bespoke ic_frame_step_back/forward drawables — the prior
            // SkipPrevious/SkipNext icons collided visually with
            // prev/next-chapter-or-track. ViewModel pauses internally
            // before the seek so a quick tap on a playing video
            // freezes + steps in one motion.
            if (uiState.isVideoContent && !uiState.isCasting) {
                IconButton(onClick = { viewModel.stepFrameBack() }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_frame_step_back), contentDescription = "Step one frame back",
                        tint = TealAccent)
                }
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
            if (uiState.isVideoContent && !uiState.isCasting) {
                IconButton(onClick = { viewModel.stepFrameForward() }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_frame_step_forward), contentDescription = "Step one frame forward",
                        tint = TealAccent)
                }
            }
            IconButton(onClick = { viewModel.addBookmarkHere() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = "Add bookmark",
                    tint = TealAccent)
            }
            // Video effects (mirror H/V, B&W, rotation) — visible only
            // when the current media is a video AND not casting (the
            // receiver renders the actual stream; local-TextureView
            // effects never reach it).
            if (uiState.isVideoContent && !uiState.isCasting) {
                VideoEffectsButton()
            }
            // Audio effects (reverb / stereo flip / mono mix /
            // passthrough) — applies to local audio chain only, so
            // greyed out when casting (audio is on the receiver).
            AudioEffectsButton(
                enabled = !uiState.isCasting && !uiState.isSpotifyActive,
                isSpotifyActive = uiState.isSpotifyActive
            )
            // Crossfade button (Phase 4) — right of Audio Effects per
            // §B1. Greyed when video / cast / Spotify Connect — only
            // audio queues benefit from a smooth transition.
            CrossfadeButton(
                enabled = !uiState.isVideoContent &&
                    !uiState.isCasting &&
                    !uiState.isSpotifyActive,
                onFadeNow = { viewModel.fadeNow() }
            )
            BluetoothButton(modifier = Modifier.size(48.dp))
            // Cast button — to the right of Bluetooth so the
            // wireless-output controls are grouped. Hidden when
            // Spotify is the active source because Cast has nothing
            // to do with Spotify Connect (Spotify uses its own
            // Connect-device picker in the cloud Spotify section).
            if (!uiState.isSpotifyActive) {
                // Single combined Cast button. Tap opens the sheet —
                // when idle: device chooser. When casting: chooser +
                // Stop Casting row. Replaced the dual-button pattern
                // (CastButton + CastSwitcherButton).
                CastSwitcherButton(modifier = Modifier.size(48.dp))
            }
        }
        // Bookmark chips for the currently playing item.
        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
        var renamingBookmark by remember { mutableStateOf<com.powermediaplayer.data.db.entity.BookmarkEntity?>(null) }
        if (bookmarks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                bookmarks.forEach { b ->
                    Box(
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.seekToBookmark(b) },
                            onLongClick = { renamingBookmark = b }
                        )
                    ) {
                        AssistChip(
                            onClick = { viewModel.seekToBookmark(b) },
                            label = { Text(b.label, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.deleteBookmark(b) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove",
                                        tint = ErrorRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
        renamingBookmark?.let { bookmark ->
            var label by remember(bookmark.id) { mutableStateOf(bookmark.label) }
            AlertDialog(
                onDismissRequest = { renamingBookmark = null },
                title = { Text("Rename bookmark", color = TealAccent) },
                text = {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        singleLine = true,
                        label = { Text("Label") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameBookmark(bookmark, label)
                        renamingBookmark = null
                    }) { Text("Save", color = TealAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { renamingBookmark = null }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = OledBlack
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Expanded Layout (Tablet / Landscape Foldable) ─────────────────

@Composable
private fun PlayerScreenExpanded(
    uiState: PlayerUiState,
    artworkBytes: ByteArray?,
    artworkContentScale: androidx.compose.ui.layout.ContentScale,
    viewModel: PlayerViewModel,
    coverColors: CoverArtColors?,
    onColorsExtracted: (CoverArtColors?) -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowInfo: () -> Unit
) {
    // #103 fix — AudioEffectsButton / CrossfadeButton / CastSwitcherButton
    // call PopupOpenGuard which reads LocalOpenPopupCount. PlayerScreenCompact
    // provides it; PlayerScreenExpanded didn't, so opening any popup on the
    // Z Fold 6 inner display crashed with "LocalOpenPopupCount not provided".
    val openPopupCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.CompositionLocalProvider(LocalOpenPopupCount provides openPopupCount) {
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
                onColorsExtracted = onColorsExtracted,
                contentScale = artworkContentScale
            )
        }

        // Right panel: all controls. Wrapped in Box so the InfoIcon
        // can anchor top-right of the panel without disturbing the
        // centred control column.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
        Column(
            modifier = Modifier
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
                enabled = uiState.controls.playbackSpeed && !uiState.isCasting
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
                if (uiState.isVideoContent && !uiState.isCasting) {
                    IconButton(onClick = { viewModel.stepFrameBack() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_frame_step_back), contentDescription = "Step one frame back",
                            tint = TealAccent)
                    }
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
                if (uiState.isVideoContent && !uiState.isCasting) {
                    IconButton(onClick = { viewModel.stepFrameForward() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_frame_step_forward), contentDescription = "Step one frame forward",
                            tint = TealAccent)
                    }
                }
                IconButton(onClick = { viewModel.addBookmarkHere() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = "Add bookmark",
                        tint = TealAccent)
                }
                // Audio effects (reverb / stereo flip / mono mix /
                // passthrough) — applies to any audio track so it's
                // present in both layouts. Greyed out while casting
                // because the local audio chain is silent then.
                AudioEffectsButton(
                enabled = !uiState.isCasting && !uiState.isSpotifyActive,
                isSpotifyActive = uiState.isSpotifyActive
            )
                CrossfadeButton(
                    enabled = !uiState.isVideoContent &&
                        !uiState.isCasting &&
                        !uiState.isSpotifyActive,
                    onFadeNow = { viewModel.fadeNow() }
                )
                BluetoothButton(modifier = Modifier.size(48.dp))
                if (!uiState.isSpotifyActive) {
                    // Single combined Cast button (see Compact layout
                    // comment above for the dual-button consolidation).
                    CastSwitcherButton(modifier = Modifier.size(48.dp))
                }
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
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove",
                                        tint = ErrorRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
            // Info icon — top-right of the right control panel
            // (anchored on the wrapping Box). Always visible in
            // Expanded mode (audio-only layout, no controls hide).
            InfoIcon(
                onClick = onShowInfo,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            )
        }
    }
    } // closes CompositionLocalProvider (#103 fix)
}

// ── Shared Sub-Composables ─────────────────────────────────────────

@Composable
private fun TrackInfoSection(
    uiState: PlayerUiState,
    coverColors: CoverArtColors?,
    viewModel: PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
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
        // §C17 — year • genre line, populated by online enrichment when
        // the embedded tags don't carry them.
        val yearGenreLine = listOfNotNull(
            uiState.year.takeIf { it > 0 }?.toString(),
            uiState.genre.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        if (yearGenreLine.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = yearGenreLine,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        // §C7 — indicator chip when this file has saved overrides.
        // Tells the user "what they're hearing differs from defaults."
        val overrideRow: com.powermediaplayer.data.db.entity.MediaOverrideEntity? by
            viewModel.currentOverride.collectAsStateWithLifecycle()
        val activeRow = overrideRow?.takeIf { !it.isEmpty() }
        if (activeRow != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val parts = buildList {
                if (activeRow.hasAnyAudio()) add("audio")
                if (activeRow.hasAnyVideo()) add("video")
                if (activeRow.hasAnySpeed()) add("speed")
            }
            Surface(
                color = TealAccent.copy(alpha = 0.18f),
                contentColor = TealAccent,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Custom ${parts.joinToString("/")} for this file",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
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
    onSleepAtEndOfChapter: () -> Unit = {},
    onSleepAtEndOfTrack: () -> Unit = {},
    onSleepAtEndOfQueue: () -> Unit = {},
    settingsVm: com.powermediaplayer.ui.settings.SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val sState by settingsVm.uiState.collectAsStateWithLifecycle()
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

                // Custom-duration slider — for users who want a value
                // outside the preset set (e.g. 7 min nap, 75 min car
                // commute). 1..240 min covers everything reasonable.
                var customMin by remember { mutableStateOf(20f) }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom: ${customMin.toInt()} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onSetTimer(customMin.toInt()) }) {
                        Text("Set", color = TealAccent)
                    }
                }
                Slider(
                    value = customMin,
                    onValueChange = { customMin = it },
                    valueRange = 1f..240f,
                    steps = 0,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(color = DisabledContent, modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Or sleep at:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                TextButton(
                    onClick = onSleepAtEndOfTrack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "End of current track",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TealAccent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    onClick = onSleepAtEndOfChapter,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "End of current chapter",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TealAccent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    onClick = onSleepAtEndOfQueue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "End of queue / album",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TealAccent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // §C11 fade-out toggle. Volume ramps over the last 30 s
                // before the timer pauses, so users wake up to silence
                // instead of an abrupt cut. Persisted across sessions.
                HorizontalDivider(color = DisabledContent, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Linear fade-out",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            "Ramp volume to silence over the last 30 seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                    Switch(
                        checked = sState.sleepTimerFadeOut,
                        onCheckedChange = { settingsVm.setSleepTimerFadeOut(it) }
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

