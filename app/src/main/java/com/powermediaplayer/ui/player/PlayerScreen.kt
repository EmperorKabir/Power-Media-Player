package com.powermediaplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import com.powermediaplayer.ui.player.components.captureCoverFrost
import com.powermediaplayer.ui.player.components.FrostedTextLine
import androidx.compose.ui.semantics.clearAndSetSemantics
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
 * §T336 — "see the track list" for a Spotify Connect album/playlist (the
 * chapters-equivalent: a Connect album mirrors only the current track, so the
 * tracks are fetched from the album context). Renders nothing unless a Spotify
 * album/playlist is the active context. Tapping a track plays it WITHIN that
 * context so next/previous + shuffle keep traversing the album.
 */
@Composable
private fun SpotifyAlbumTracksButton(viewModel: PlayerViewModel) {
    val tracks by viewModel.spotifyContextTracks.collectAsStateWithLifecycle()
    if (tracks.isEmpty()) return
    val currentUri by viewModel.spotifyTrackUri.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = Modifier.size(48.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "Album track list",
            tint = TextTertiary
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            containerColor = OledBlack,
            title = { Text("Tracks", color = TextPrimary) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(tracks) { idx, t ->
                        val isCurrent = currentUri != null &&
                            (t.downloadUrl == currentUri || currentUri!!.endsWith(t.id))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.playSpotifyContextTrack(t)
                                    show = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${idx + 1}",
                                color = TextTertiary,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name,
                                    color = if (isCurrent) TealAccent else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (t.subtitle.isNotBlank()) {
                                    Text(
                                        t.subtitle,
                                        color = TextTertiary,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isCurrent) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = "Now playing",
                                    tint = TealAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("Close", color = TealAccent) }
            }
        )
    }
}

/**
 * Download the CURRENT item for offline use, or delete its local copy. Renders
 * nothing for Spotify / plain local-library files (offlineState NOT_APPLICABLE);
 * shows a live progress spinner while a Drive download is in flight. Added to
 * BOTH player layouts so it works folded and unfolded.
 */
@Composable
private fun PlayerOfflineButton(viewModel: PlayerViewModel) {
    val state by viewModel.offlineState.collectAsStateWithLifecycle()
    // applicationContext: the Toast collector outlives this composable's Activity
    // context across config changes; the app context is the safe long-lived one.
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        viewModel.offlineStatus.collect {
            android.widget.Toast.makeText(appCtx, it, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    if (state == com.powermediaplayer.offline.OfflineState.NOT_APPLICABLE) return
    val progressId by viewModel.currentProgressId.collectAsStateWithLifecycle()
    // Per-id progress (remembered per progressId) so the button recomposes only on
    // THIS item's download ticks, not on every other in-flight download's chunk.
    val progressFlow = remember(progressId) {
        progressId?.let { com.powermediaplayer.util.DownloadProgressBus.progressFor(it) }
            ?: kotlinx.coroutines.flow.flowOf<com.powermediaplayer.util.DownloadProgressBus.Prog?>(null)
    }
    val prog by progressFlow.collectAsStateWithLifecycle(initialValue = null)
    val downloading = prog != null
    IconButton(
        onClick = {
            // While downloading, tap = STOP (the shared cancel signal aborts the
            // copy + removes the partial file); otherwise download / delete.
            if (downloading) progressId?.let { com.powermediaplayer.util.DownloadProgressBus.requestCancel(it) }
            else viewModel.toggleOffline()
        },
        modifier = Modifier.size(48.dp)
    ) {
        when {
            downloading -> Box(contentAlignment = Alignment.Center) {
                val frac = prog?.fraction ?: 0f
                if (frac > 0f) CircularProgressIndicator(
                    progress = { frac }, color = TealAccent, strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                ) else CircularProgressIndicator(
                    color = TealAccent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)
                )
                // ✕ in the ring = tap to stop.
                Icon(Icons.Filled.Close, "Stop download", tint = ErrorRed, modifier = Modifier.size(12.dp))
            }
            state == com.powermediaplayer.offline.OfflineState.DOWNLOADED -> Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Delete from local storage",
                tint = TealAccent
            )
            else -> Icon(
                Icons.Filled.Download,
                contentDescription = "Download for offline use",
                tint = TealAccent
            )
        }
    }
}

/**
 * Audit 8.1/8.2 (F6/F7/F8) — adaptive layout facts (width/height size
 * class, foldable posture, hinge geometry) made ambient so the deep
 * player sub-composables (OverlayContent, PlayerScreenExpanded, video
 * branch) can read them without threading through every signature.
 * null on the rare path where PlayerScreen wasn't given one.
 */
val LocalAdaptiveInfo =
    compositionLocalOf<com.powermediaplayer.ui.adaptive.AdaptiveInfo?> { null }

/**
 * Main player screen — fully adaptive layout.
 *
 * Compact (phone portrait): single column, controls at bottom
 * Medium (large phone / unfolded foldable in portrait): wider single column
 * Expanded (tablet / landscape foldable): two-panel — artwork left, controls right
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    windowSizeClass: WindowSizeClass,
    adaptive: com.powermediaplayer.ui.adaptive.AdaptiveInfo? = null,
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

    // vc31 overlap fix — the empty-player guidance must REPLACE the player
    // chrome, not overlay it (it previously drew on top, so "No media
    // loaded" + Track labels bled through underneath).
    // The Spotify mirror counts as "something playing" even though the
    // LOCAL player is empty — without this gate the Player tab said
    // "Nothing's playing yet" while the mini-bar (which keys off the
    // overlaid title) correctly offered the Spotify track.
    // Casting a video to an audio-only device keeps the PICTURE playing on
    // this screen via the local player, even though the SESSION player is the
    // audio-only CastPlayer (which can briefly read as "no media"). Suppress
    // the empty state in that case so "nothing's playing yet" doesn't flash
    // over the playing picture.
    val castingLocalVideo by com.powermediaplayer.service.PlaybackService
        .castLocalVideoActiveFlow.collectAsStateWithLifecycle()
    // ANY cast (audio too): during the swap to the CastPlayer the session
    // player briefly reads as "no media" before the receiver echoes metadata —
    // without this an AUDIO cast (audiobook) flashes "nothing's playing yet"
    // and a blank title. Hold both across the whole cast.
    val castActive by com.powermediaplayer.service.PlaybackService
        .castActiveFlow.collectAsStateWithLifecycle()
    // While casting a video to an audio-only device the SESSION player is the
    // audio-only m4a (isVideoContent=false), but the LOCAL player is playing the
    // SILENT VIDEO bound to the surface. Present it AS video content so the app
    // shows the picture (the VideoSurface) instead of falling into the audio
    // layout. The cast transport still drives the audio; the on-screen aspect
    // comes from the local player's real size (VideoSurface's size fallback).
    // Remember the last real title: while casting, the session player briefly
    // has no item during audio extraction, so its title reads "No media
    // loaded" for a moment — show the last real title instead of flashing it.
    // A title that still ends in a media extension is the raw FILENAME, not a
    // real title (cast items carry the filename; an un-enriched item shows it).
    val mediaExtRegex = remember {
        // Require a non-space before the dot, and only the unambiguous media
        // extensions — dropped short tokens (ts/ape/aif/oga/mka/3gp) that could
        // end a real title.
        Regex("\\S\\.(m4b|m4a|m4p|mp3|flac|mkv|mp4|wav|ogg|opus|aac|wma|aiff|mov|avi|webm)$",
            RegexOption.IGNORE_CASE)
    }
    fun isFilenameTitle(t: String) = mediaExtRegex.containsMatchIn(t.trim())
    // Remember the last PROPER (non-blank, non-filename) metadata. During a cast
    // swap the session player briefly reads as "no media", and the cast item
    // often carries only the filename as its title — in both cases re-show this
    // last real metadata so it stays seamless ("display what was already there")
    // instead of flashing "No media loaded" or the filename.
    val lastReal = remember { androidx.compose.runtime.mutableStateOf<PlayerUiState?>(null) }
    if (uiState.hasMedia && uiState.title.isNotBlank() &&
        uiState.title != "No media loaded" && !isFilenameTitle(uiState.title)
    ) {
        lastReal.value = uiState
    }
    val titleNeedsHold = !uiState.hasMedia || uiState.title.isBlank() ||
        uiState.title == "No media loaded" || isFilenameTitle(uiState.title)
    val held = lastReal.value
    val displayState = when {
        castingLocalVideo -> uiState.copy(
            isVideoContent = true,
            title = if (titleNeedsHold && held != null) held.title else uiState.title
        )
        // ANY cast where the title is missing or just the filename → hold the
        // whole visible metadata block (title + artist + album + cover).
        castActive && titleNeedsHold && held != null -> uiState.copy(
            hasMedia = true,
            title = held.title,
            artist = held.artist,
            album = held.album,
            artworkUri = held.artworkUri,
            hasCoverArt = held.hasCoverArt
        )
        else -> uiState
    }
    val showEmptyState = !uiState.hasMedia && !uiState.isLoading &&
        !uiState.cloudFetchInProgress && !uiState.isVideoContent &&
        !uiState.isSpotifyActive && !castingLocalVideo && !castActive

    // Audit 8.1 (F6) — Expanded width always two-pane; Medium two-pane
    // only in landscape (a portrait unfolded-fold stays single column).
    // Video always fills via the Compact layout regardless of size.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp >= cfg.screenHeightDp
    val twoPanePlayer =
        windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded ||
            (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium && isLandscape)

    // T294 — the window is full-bleed (MainActivity no longer pads). NON-VIDEO
    // player content (audio, two-pane, empty state) self-insets here: top
    // status bar + the bottom-bar / side-rail clearance for the always-present
    // nav overlay. VIDEO stays full-bleed and self-insets inside OverlayContent
    // instead. This condition flips only on a video↔audio track change (never
    // on tab entry), so it never contributes to the entry relayout/flicker.
    val chromeCompactWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val nonVideoChromeInset = Modifier
        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
        .padding(
            bottom = if (chromeCompactWidth)
                com.powermediaplayer.ui.navigation.ImmersiveVideoTabBarHeight else 0.dp,
            start = if (chromeCompactWidth)
                0.dp else com.powermediaplayer.ui.navigation.ImmersiveVideoRailWidth
        )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (displayState.isVideoContent) Modifier else nonVideoChromeInset)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAdaptiveInfo provides adaptive) {
        // Video ALWAYS uses the Compact layout regardless of screen size,
        // so the picture fills the whole screen on phones, tablets, and
        // unfolded foldables. Audio uses the size-appropriate layout.
        val coverFrost = com.powermediaplayer.ui.player.components.rememberCoverFrost()
        androidx.compose.runtime.CompositionLocalProvider(
            com.powermediaplayer.ui.player.components.LocalCoverFrost provides coverFrost
        ) {
        when {
            showEmptyState -> EmptyPlayerState(onNavigateToLibrary)
            displayState.isVideoContent -> PlayerScreenCompact(
                uiState = displayState,
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
            twoPanePlayer -> PlayerScreenExpanded(
                uiState = displayState,
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
                uiState = displayState,
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
                uiState = displayState,
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
        }
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
        // Debounced: ExoPlayer toggles isLoading every ~100 ms during
        // ordinary chunked reads (logged spam) — the banner must only
        // surface for SUSTAINED loading, not flicker per chunk.
        var sustainedLoading by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(uiState.isLoading) {
            if (uiState.isLoading) {
                kotlinx.coroutines.delay(450)
                sustainedLoading = true
            } else {
                sustainedLoading = false
            }
        }
        AnimatedVisibility(
            visible = sustainedLoading && !uiState.cloudFetchInProgress,
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

/**
 * vc31 — empty-player guidance, rendered INSTEAD of the player chrome
 * (replacing the layout branch entirely so no "No media loaded" text or
 * disabled controls bleed through underneath). Shows only when there is
 * genuinely nothing to wait in the player: fresh install, cleared
 * history, or a Spotify-only recent that can't be pre-loaded — the
 * cold-start path otherwise restores the most-recent LOCAL/DRIVE item
 * paused (PlayerViewModel init).
 */
@Composable
private fun EmptyPlayerState(onNavigateToLibrary: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
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
    // Audit 8.2 (F8) — tabletop foldable: video occupies the top leaf,
    // controls the bottom leaf. Immersive auto-hide is suspended here
    // (bars + controls stay shown) since the picture isn't full-bleed.
    val adaptive = LocalAdaptiveInfo.current
    val tabletopVideo = uiState.isVideoContent &&
        adaptive?.isTabletop == true && adaptive.hingeBounds != null
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
            videoHideSec > 0 && openPopupCount.intValue == 0 && !tabletopVideo
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

    // Audit 6.4 / 8.3a — immersive: system bars follow the video
    // controls. Hidden controls = hidden bars + full-bleed root; any
    // other state (controls shown, audio, leaving the screen) restores
    // them. Swipe shows transient bars per platform convention.
    val immersiveActivity = androidx.compose.ui.platform.LocalContext.current
        as? android.app.Activity
    LaunchedEffect(uiState.isVideoContent, controlsVisible, tabletopVideo) {
        val window = immersiveActivity?.window ?: return@LaunchedEffect
        val ctrl = androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
        if (uiState.isVideoContent && !controlsVisible) {
            ctrl.systemBarsBehavior = androidx.core.view
                .WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        // Video is full-bleed the WHOLE time it's showing — toggling controls
        // never resizes the picture; the system bars + controls + tab bar all
        // overlay on top and inset themselves. The tab overlay shows only when
        // controls are up (and not in the tabletop split, which lays controls
        // out in its own bottom leaf).
        com.powermediaplayer.MainActivityHolder.fullBleedVideo.value =
            uiState.isVideoContent
        com.powermediaplayer.MainActivityHolder.videoControlsVisible.value =
            uiState.isVideoContent && controlsVisible && !tabletopVideo
    }
    DisposableEffect(Unit) {
        onDispose {
            immersiveActivity?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            com.powermediaplayer.MainActivityHolder.fullBleedVideo.value = false
            com.powermediaplayer.MainActivityHolder.videoControlsVisible.value = false
            // 8.3b — leaving the player never strands an orientation lock.
            com.powermediaplayer.MainActivityHolder.releaseVideoOrientation()
        }
    }

    // Tap-to-TOGGLE is implemented by a transparent pointerInput layer
    // sitting directly ABOVE the video surface but BELOW OverlayContent
    // (added in the layout). A tap on the bare picture flips
    // controlsVisible — showing when hidden, hiding immediately when shown
    // (overriding the auto-hide timer, which itself stays intact). Because
    // the layer is BELOW the controls, the control IconButtons sit on top
    // and consume their own taps unimpeded (no parent interceptor — the
    // earlier bug where skip-buttons didn't register).

    androidx.compose.runtime.CompositionLocalProvider(LocalOpenPopupCount provides openPopupCount) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (tabletopVideo && adaptive != null) {
            TabletopVideoLayout(
                adaptive = adaptive,
                uiState = uiState,
                coverColors = coverColors,
                viewModel = viewModel,
                horizontalPadding = horizontalPadding,
                onShowSleepTimer = onShowSleepTimer,
                onShowChapterPicker = onShowChapterPicker,
                onShowInfo = onShowInfo
            )
        } else {
        if (uiState.isVideoContent) {
            // Video content: render the actual video frames
            // VideoSurface attaches directly to the ExoPlayer in PlaybackService
            VideoSurface(
                isVideoContent = true,
                videoWidth = uiState.videoWidth,
                videoHeight = uiState.videoHeight,
                modifier = Modifier.fillMaxSize()
            )
            // Tap the picture to TOGGLE the controls (show when hidden,
            // hide immediately when shown — overriding, but not removing,
            // the auto-hide timer). Sits above the video, below
            // OverlayContent, so control buttons still get their own taps.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
            )
        } else {
            // Audio content: show album art with palette-extracted colours
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted,
                modifier = Modifier.captureCoverFrost(com.powermediaplayer.ui.player.components.LocalCoverFrost.current),
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
            // Controls are an ALPHA overlay (draw-phase only), NOT
            // AnimatedVisibility. The subtree stays composed + measured at a
            // constant size the whole time the video shows; toggling controls
            // changes only the GPU alpha, so nothing relayouts (the flicker
            // was the AnimatedVisibility compose/dispose + inset re-read). The
            // 500ms-in / 1000ms-out feel is preserved by the animation spec.
            val controlsAlpha by animateFloatAsState(
                targetValue = if (controlsVisible) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (controlsVisible) 500 else 1000
                ),
                label = "videoControlsAlpha"
            )
            val controlsHidden = controlsAlpha < 0.01f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controlsAlpha }
                    // Alpha 0 ≠ gone for accessibility — hide the still-composed
                    // controls from TalkBack when fully faded out.
                    .then(
                        if (controlsHidden) Modifier.clearAndSetSemantics { }
                        else Modifier
                    )
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
                // Alpha 0 keeps the subtree HIT-TESTABLE: an invisible
                // slider/button could still catch a tap/drag. When hidden, a
                // top-most transparent catcher swallows the gesture and brings
                // the controls back instead — the show-on-tap behaviour that
                // AnimatedVisibility gave for free by leaving the tree.
                if (controlsHidden) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { controlsVisible = true })
                            }
                    )
                }
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
    }
    } // closes CompositionLocalProvider
}

/**
 * Audit 8.2 (F8) — tabletop foldable video layout. The device is propped
 * half-open (book/laptop posture, horizontal hinge); we put the picture
 * in the TOP leaf (its bottom edge at the hinge) and the transport
 * controls in the BOTTOM leaf, so the screen reads like a tiny laptop.
 * The bottom-leaf overlay is given a Compact height class so its control
 * stack scrolls within the leaf rather than clipping.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletopVideoLayout(
    adaptive: com.powermediaplayer.ui.adaptive.AdaptiveInfo,
    uiState: PlayerUiState,
    coverColors: CoverArtColors?,
    viewModel: PlayerViewModel,
    horizontalPadding: Int,
    onShowSleepTimer: () -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowInfo: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hinge = adaptive.hingeBounds
    val topLeafDp = if (hinge != null) with(density) { hinge.top.toDp() } else 0.dp
    val hingeGapDp =
        if (hinge != null) with(density) { (hinge.bottom - hinge.top).toDp() } else 0.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Top leaf — video, frame bottom edge resting on the hinge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topLeafDp)
        ) {
            VideoSurface(
                isVideoContent = true,
                videoWidth = uiState.videoWidth,
                videoHeight = uiState.videoHeight,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Physical hinge gap.
        if (hingeGapDp > 0.dp) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hingeGapDp)
                    .background(OledBlack)
            )
        }
        // Bottom leaf — controls, always visible (no immersive auto-hide).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val scrim = remember {
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        OledBlack.copy(alpha = 0.40f),
                        OledBlack.copy(alpha = 0.85f),
                        OledBlack.copy(alpha = 0.97f)
                    )
                )
            }
            // Force a Compact height class for the leaf subtree so
            // OverlayContent enables its internal scroll (the leaf is
            // ~half the window — the full control stack would clip).
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAdaptiveInfo provides adaptive.copy(
                    heightClass = androidx.compose.material3.windowsizeclass.WindowHeightSizeClass.Compact
                )
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
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Audit 3.11 - hardware volume as state; the old per-recomposition
    // getStreamVolume calls were 4Hz binder traffic during playback.
    val volumeUi by viewModel.volumeUi.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
    )
    // Audit 6.4 — landscape cutouts (corner punch-holes) can overlap the
    // overlay's edge controls; horizontal cutout padding keeps them clear
    // without disturbing the vertical bottom-anchor.
    val cutoutPad = Modifier.windowInsetsPadding(
        androidx.compose.foundation.layout.WindowInsets.displayCutout
            .only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)
    )
    // Full-bleed video drops the root systemBarsPadding so the picture is
    // edge-to-edge at all times (no resize when controls toggle). The
    // CONTROLS therefore self-inset: top icons clear the status bar, the
    // bottom transport stack clears the nav bar AND the immersive app-tab
    // overlay that floats above it. Audio keeps the root padding, so it adds
    // nothing here (would double-inset otherwise).
    val isVideoOverlay = uiState.isVideoContent
    val immersiveCompactWidth =
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    // CONSTANT insets (…IgnoringVisibility): unlike statusBarsPadding /
    // navigationBarsPadding, these do NOT collapse to zero when the system
    // bars hide, so toggling controls (which hides/shows the bars) never
    // re-pads the stack → no relayout. The live variants shrinking on hide
    // were a per-toggle reflow source (the flicker).
    val topBarInset = if (isVideoOverlay)
        Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    else Modifier
    // Reserve the immersive app-tab overlay UNCONDITIONALLY (drop the old
    // 0↔height flip on controls visibility — that flip was a per-toggle
    // relayout). Keep the WIDTH branch: BOTTOM bar on compact/folded →
    // reserve bottom; SIDE rail on expanded/unfolded → reserve start. The
    // overlay's real footprint = its 80dp content + the system nav inset,
    // matched by navigationBarsIgnoringVisibility (constant) + the 80dp const.
    val bottomBarInset = if (isVideoOverlay) {
        if (immersiveCompactWidth) {
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = com.powermediaplayer.ui.navigation.ImmersiveVideoTabBarHeight)
        } else {
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(start = com.powermediaplayer.ui.navigation.ImmersiveVideoRailWidth)
        }
    } else {
        Modifier
    }
    // #14 — the Info-icon Box (top-right "i" + the video rotate button) is
    // declared LAST, AFTER the controls Column, so it is the top-most hit-test
    // sibling and its 48dp clickable wins the pointer chain over the folded
    // controls Column (fillMaxSize().verticalScroll captures pointer-down across
    // the whole screen). Declaration order — NOT zIndex — governs hit order; the
    // empty area of this fillMaxSize Box has no hit target so scroll/taps fall
    // through to the Column. This mirrors PlayerScreenExpanded (InfoIcon last).
    // Audit 6.8 - the video overlay's ~500dp control stack clipped at
    // compact window heights (split screen, landscape phones): controls
    // above the bottom anchor became unreachable. Scroll when short.
    // Audit 8.1 (F7) — now keyed off the window HEIGHT size class
    // (Compact = <480dp) instead of a raw 500dp LocalConfiguration read,
    // so it tracks the same size-class system the rest of the layout
    // uses. Falls back to the raw read when no AdaptiveInfo is provided.
    val compactHeight = LocalAdaptiveInfo.current?.let {
        it.heightClass == androidx.compose.material3.windowsizeclass.WindowHeightSizeClass.Compact
    } ?: (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500)
    val scrollMod = if (uiState.isVideoContent && !compactHeight) {
        Modifier
    } else {
        Modifier.verticalScroll(rememberScrollState())
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(cutoutPad)
            .then(bottomBarInset)
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
        PositionSection(
            viewModel = viewModel,
            controls = uiState.controls,
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
            currentVolume = volumeUi.first,
            maxVolume = volumeUi.second,
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
            // Shuffle the playback queue (greyed when off, accent when on).
            val shuffleOn by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
            IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                    tint = if (shuffleOn) TealAccent else TextTertiary
                )
            }
            // Spotify album/playlist track list (chapters-equivalent) — only
            // renders when a Spotify album/playlist context is active.
            SpotifyAlbumTracksButton(viewModel)
            // Download-offline / delete-local for the current Drive/podcast item.
            PlayerOfflineButton(viewModel)
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
                title = { Text("Rename bookmark", color = TealAccent, style = MaterialTheme.typography.titleLarge) },
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
    // #14 — Info icon top-right, declared LAST so it is the top-most hit
    // sibling (see the note where it used to sit). For video this OverlayContent
    // is inside the controls alpha layer, so the icon fades + goes
    // non-interactive with the controls; for audio it stays visible.
    Box(modifier = Modifier.fillMaxSize().then(cutoutPad).then(topBarInset)) {
        InfoIcon(
            onClick = onShowInfo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        )
        // 8.3b — rotate-to-fullscreen for video on COMPACT widths only:
        // 12L+ large-screen devices may ignore orientation requests by
        // policy, and wide windows already show video large. No
        // permission needed — activity-level requests override the
        // auto-rotate quick-setting on phones.
        val compactWidth = androidx.compose.ui.platform.LocalConfiguration
            .current.screenWidthDp < 600
        if (uiState.isVideoContent && compactWidth) {
            androidx.compose.material3.IconButton(
                onClick = { com.powermediaplayer.MainActivityHolder.toggleVideoOrientation() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.ScreenRotation,
                    contentDescription = "Rotate video to fullscreen",
                    tint = com.powermediaplayer.ui.theme.TealAccent
                )
            }
        }
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
    // Audit 3.11 — hardware volume as state (see OverlayContent's twin).
    val volumeUi by viewModel.volumeUi.collectAsStateWithLifecycle()
    // Audit 8.1/8.2 (F6) — when a vertical separating hinge crosses the
    // window (book-posture foldable / Surface Duo), pin the split AT the
    // hinge: art fills the left leaf, controls the right, gap left clear.
    // Slab tablets / continuous-display folds get a 45/55 art:controls
    // ratio instead of a flat 50/50 (controls need the room).
    val adaptive = LocalAdaptiveInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hinge = adaptive?.hingeBounds
    val useHinge = hinge != null && adaptive.hingeIsVertical && adaptive.hingeIsSeparating
    val hingeLeftDp = if (useHinge && hinge != null) with(density) { hinge.left.toDp() } else 0.dp
    val hingeWidthDp =
        if (useHinge && hinge != null) with(density) { (hinge.right - hinge.left).toDp() } else 0.dp
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        // Left panel: cover art only — video uses the Compact layout for
        // full-screen playback regardless of size class.
        Box(
            modifier = (if (useHinge) Modifier.width(hingeLeftDp) else Modifier.weight(0.45f))
                .fillMaxHeight()
        ) {
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted,
                modifier = Modifier.captureCoverFrost(com.powermediaplayer.ui.player.components.LocalCoverFrost.current),
                contentScale = artworkContentScale
            )
        }

        // Hinge occlusion gap — only when a physically separating hinge
        // sits between the leaves; zero-width (skipped) otherwise.
        if (useHinge && hingeWidthDp > 0.dp) {
            Spacer(modifier = Modifier.width(hingeWidthDp).fillMaxHeight())
        }

        // Right panel: all controls. Wrapped in Box so the InfoIcon
        // can anchor top-right of the panel without disturbing the
        // centred control column.
        Box(
            modifier = (if (useHinge) Modifier.weight(1f) else Modifier.weight(0.55f))
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
            PositionSection(
                viewModel = viewModel,
                controls = uiState.controls,
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
                currentVolume = volumeUi.first,
                maxVolume = volumeUi.second,
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
                // Shuffle the playback queue (teal on / grey off).
                val shuffleOnE by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
                IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (shuffleOnE) "Shuffle on" else "Shuffle off",
                        tint = if (shuffleOnE) TealAccent else TextTertiary
                    )
                }
                // Spotify album/playlist track list (chapters-equivalent).
                SpotifyAlbumTracksButton(viewModel)
                // Download-offline / delete-local for the current Drive/podcast item.
                PlayerOfflineButton(viewModel)
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
        // Cover-text legibility (2026-06-30): each line carries its OWN per-visual-line frosted
        // pill (FrostedTextLine) sized to that line — a long title that wraps shows two independent
        // pills (different widths), not one rectangle with dead space — with a light/dark colour
        // adapted to the REAL blurred backdrop behind it. Robust to Fit/Crop/zoom/fold/display and
        // to variable line lengths.
        val coverFrost = com.powermediaplayer.ui.player.components.LocalCoverFrost.current
        val frostEnabled = !uiState.isVideoContent && uiState.hasCoverArt && coverFrost != null
        // §C17 — year • genre line, populated by online enrichment when the embedded tags don't carry them.
        val yearGenreLine = listOfNotNull(
            uiState.year.takeIf { it > 0 }?.toString(),
            uiState.genre.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        FrostedTextLine(
            text = uiState.title,
            style = MaterialTheme.typography.headlineSmall,
            frost = coverFrost,
            enabled = frostEnabled,
            baseColor = TextPrimary,
            maxLines = 2
        )
        if (uiState.artist.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FrostedTextLine(
                text = uiState.artist,
                style = MaterialTheme.typography.titleMedium,
                frost = coverFrost,
                enabled = frostEnabled,
                baseColor = coverColors?.vibrant ?: TealAccent,
                maxLines = 1
            )
        }
        if (uiState.album.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            FrostedTextLine(
                text = uiState.album,
                style = MaterialTheme.typography.bodyMedium,
                frost = coverFrost,
                enabled = frostEnabled,
                baseColor = TextSecondary,
                maxLines = 1
            )
        }
        if (yearGenreLine.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            FrostedTextLine(
                text = yearGenreLine,
                style = MaterialTheme.typography.labelSmall,
                frost = coverFrost,
                enabled = frostEnabled,
                baseColor = TextTertiary,
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
            // Audit 3.3 - live highlight position from positionUi; the
            // surrounding uiState no longer carries per-tick positions.
            val livePos by viewModel.positionUi.collectAsStateWithLifecycle()
            SyncedLyricsPanel(
                lines = uiState.syncedLyrics,
                positionMs = livePos.positionMs,
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
        } else if (uiState.isSpotifyActive && !uiState.lyricsLoading) {
            // Loading finished with no lyrics for this track (the single
            // metadata banner covers the in-flight case; this is the settled
            // empty result, so the panel isn't left silently blank).
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No lyrics for this track",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
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

    if (!hasChapters && !hasPlaylist) {
        // A7: while cloud/Drive chapters are still downloading + parsing, show a
        // greyed "Loading chapters…" chip rather than hiding the affordance or
        // implying there are none. Resolves to the real chip once they arrive.
        if (uiState.cloudFetchInProgress) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = "Loading metadata…",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = TealAccent
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = SurfaceElevated
                )
            )
        }
        return
    }

    val label = when {
        hasChapters -> {
            val idx = uiState.currentChapterIndex
            val chapter = uiState.chapters.getOrNull(idx)
            if (chapter != null) chapter.title else "Chapters"
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



/**
 * Audit 3.3 - the ONLY composable that recomposes at tick rate. Sliders
 * read [PlayerViewModel.positionUi]; the rest of the player tree sees
 * position-stripped uiState emissions.
 */
@Composable
private fun PositionSection(
    viewModel: PlayerViewModel,
    controls: ControlsEnabledState,
    trackIndexDisplay: String
) {
    val pos by viewModel.positionUi.collectAsStateWithLifecycle()
    // Live A-B loop markers on the Track bar. A/B are absolute ms; map each
    // into the current chapter's [start, start+duration] window as a 0..1
    // fraction, dropping any marker that falls outside the visible track.
    val abStart by viewModel.abLoopStart.collectAsStateWithLifecycle()
    val abEnd by viewModel.abLoopEnd.collectAsStateWithLifecycle()
    fun abFraction(ms: Long?): Float? {
        if (ms == null || pos.durationMs <= 0L) return null
        val f = (ms - pos.chapterStartMs).toFloat() / pos.durationMs.toFloat()
        return if (f in 0f..1f) f else null
    }
    // #4 — pending numeric-seek dialog (which bar + which end), shown once.
    var seekDialog by remember { mutableStateOf<SeekDialogReq?>(null) }
    ProgressSliders(
        trackPosition = pos.trackProgress,
        trackPositionFormatted = pos.positionFormatted,
        trackDurationFormatted = pos.durationFormatted,
        trackRemainingFormatted = pos.remainingFormatted,
        trackSliderEnabled = controls.trackSlider,
        onTrackSeek = { fraction ->
            viewModel.seekTo(pos.chapterStartMs + (fraction * pos.durationMs).toLong())
        },
        // #4 — Track time taps. Math mirrors onTrackSeek (chapterStartMs + ms).
        onTrackSeekToElapsedMs = {
            seekDialog = SeekDialogReq("Jump to time", pos.positionFormatted, pos.durationMs) { ms ->
                viewModel.seekTo(pos.chapterStartMs + ms)
            }
        },
        onTrackSeekToRemainingMs = {
            seekDialog = SeekDialogReq("Jump to time remaining", pos.remainingFormatted, pos.durationMs) { ms ->
                viewModel.seekTo(pos.chapterStartMs + (pos.durationMs - ms))
            }
        },
        // #4 — Full/playlist time taps. Mirrors onPlaylistSeek (absolute ms).
        onPlaylistSeekToElapsedMs = {
            seekDialog = SeekDialogReq("Jump in album/book", pos.playlistPositionFormatted, pos.totalPlaylistDurationMs) { ms ->
                viewModel.seekToPlaylistPosition(ms)
            }
        },
        onPlaylistSeekToRemainingMs = {
            seekDialog = SeekDialogReq("Jump — album/book remaining", pos.playlistRemainingFormatted, pos.totalPlaylistDurationMs) { ms ->
                viewModel.seekToPlaylistPosition(pos.totalPlaylistDurationMs - ms)
            }
        },
        abStartFraction = abFraction(abStart),
        abEndFraction = abFraction(abEnd),
        playlistPosition = pos.playlistProgress,
        playlistPositionFormatted = pos.playlistPositionFormatted,
        playlistDurationFormatted = pos.playlistDurationFormatted,
        playlistRemainingFormatted = pos.playlistRemainingFormatted,
        playlistSliderEnabled = controls.playlistSlider,
        onPlaylistSeek = { fraction ->
            viewModel.seekToPlaylistPosition((fraction * pos.totalPlaylistDurationMs).toLong())
        },
        trackIndexDisplay = trackIndexDisplay
    )
    seekDialog?.let { req ->
        com.powermediaplayer.ui.player.components.SeekTimeDialog(
            title = req.title,
            currentLabel = req.currentLabel,
            maxMs = req.maxMs,
            onConfirmMs = req.onConfirmMs,
            onDismiss = { seekDialog = null }
        )
    }
}

/** #4 — a pending numeric-seek dialog request raised by tapping a time label. */
private data class SeekDialogReq(
    val title: String,
    val currentLabel: String,
    val maxMs: Long,
    val onConfirmMs: (Long) -> Unit
)
