package com.powermediaplayer.ui.player

import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.cloud.SpotifyPlaybackState
import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.service.PlayerState
import com.powermediaplayer.util.TextNormalizer
import com.powermediaplayer.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * ViewModel for the Player screen.
 * Transforms raw PlayerState from PlaybackConnection into display-ready PlayerUiState.
 * Manages sleep timer countdown. All state updates flow via StateFlow to prevent
 * frame drops from the 12+ simultaneous on-screen buttons.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(

    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: SpotifyProvider,
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore,
    private val bookmarkDao: com.powermediaplayer.data.db.dao.BookmarkDao,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    private val mediaOverrideRepo: com.powermediaplayer.data.repository.MediaOverrideRepository,
    private val subtitleAutoFetcher: com.powermediaplayer.subtitles.SubtitleAutoFetcher,
    private val replayGainDao: com.powermediaplayer.data.db.dao.ReplayGainDao,
    private val replayGainScanner: com.powermediaplayer.replaygain.ReplayGainScanner,
    private val enrichmentCacheDao: com.powermediaplayer.data.db.dao.EnrichmentCacheDao,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * §C7 — currently-active per-file override row, sourced from the
     * shared [MediaOverrideRepository] so `PlaybackService` (audio
     * chain) and this view-model (video / speed / chip) stay coherent.
     */
    val currentOverride =
        mediaOverrideRepo.activeOverride

    private val musicBrainzClient =
        com.powermediaplayer.enrichment.MusicBrainzClient()
    private val discogsClient =
        com.powermediaplayer.enrichment.DiscogsClient()

    /**
     * §B5 LOCKED — auto-revert reason exposed via a Flow so the player
     * UI can show a Snackbar. Push-based from the service holder (the
     * old 750ms poll is gone — audit 3.5/3.11).
     */
    val crossfadeAutoRevertReason: kotlinx.coroutines.flow.StateFlow<String?> =
        com.powermediaplayer.service.PlaybackService.crossfadeAutoRevertReasonFlow

    fun clearCrossfadeAutoRevertReason() {
        com.powermediaplayer.service.PlaybackService.crossfadeAutoRevertReason = null
    }

    init {
        // §C7 — speed / pitch / volume-boost are direct calls into the
        // live ExoPlayer; audio + video effect axes flow through
        // dedicated combined flows via [MediaOverrideRepository] so
        // they don't pollute global settings.
        viewModelScope.launch {
            currentOverride.collect { row ->
                applyDirectAxes(row)
                // §C7 / A10.2 — when the override carries eqPresetId,
                // hot-swap the EQ to that preset for the duration of
                // this track. Cleared on track change when the next
                // override row resolves.
                row?.eqPresetId?.let { eqPresetId ->
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            // Resolve via PlaybackService static helper
                            // to avoid wiring EqualizerPresetDao here.
                            // EqualizerEffectController already accepts
                            // a List<Int> band-levels write; defer the
                            // resolution to a small companion call.
                            com.powermediaplayer.audio.EqualizerOverrideRouter
                                .applyPresetForUri(context, eqPresetId)
                        }
                    }
                }
            }
        }

    }

    private fun applyDirectAxes(
        row: com.powermediaplayer.data.db.entity.MediaOverrideEntity?
    ) {
        val player = playbackConnection.getPlayer() ?: return
        // No row, or all-null row → leave the live player alone. The
        // user's manual speed / pitch / volume-boost slider edits flow
        // through the existing setters and we'd clobber them by
        // restoring "global" values here.
        if (row == null || row.isEmpty()) return
        val speed = row.playbackSpeed ?: player.playbackParameters.speed
        val pitch = row.pitch ?: player.playbackParameters.pitch
        if (row.playbackSpeed != null || row.pitch != null) {
            runCatching {
                player.playbackParameters =
                    androidx.media3.common.PlaybackParameters(speed, pitch)
            }
        }
        row.volumeBoostMb?.let { setVolumeBoost(it) }
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Override direct axes: speed=${row.playbackSpeed} " +
                "pitch=${row.pitch} volumeBoost=${row.volumeBoostMb}"
        )
    }

    /**
     * §C7 — effective video-effect flows. VideoSurface composables
     * read these instead of [SettingsViewModel] so per-file overrides
     * affect the live shader without polluting global settings.
     */
    val effectiveVideoFlipH =
        mediaOverrideRepo.withOverrideBool(settingsDataStore.videoFlipH) { it.videoFlipH }
    val effectiveVideoFlipV =
        mediaOverrideRepo.withOverrideBool(settingsDataStore.videoFlipV) { it.videoFlipV }
    val effectiveVideoBw =
        mediaOverrideRepo.withOverrideBool(settingsDataStore.videoBw) { it.videoBw }
    val effectiveVideoSepia =
        mediaOverrideRepo.withOverrideBool(settingsDataStore.videoSepia) { it.videoSepia }
    val effectiveVideoInvert =
        mediaOverrideRepo.withOverrideBool(settingsDataStore.videoInvert) { it.videoInvert }
    val effectiveVideoRotation =
        mediaOverrideRepo.withOverrideInt(settingsDataStore.videoRotation) { it.videoRotation }

    /**
     * Most recent play session id, observed from the repository. Used
     * by [addBookmarkHere] to mirror Player-tab bookmark adds into the
     * session's snapshot table so the matching Recents row in Last
     * Played shows them. Null until the first play of the app's
     * lifetime.
     */
    private val currentSessionId: Long?
        get() = lastPlayedRepo.currentSessionId.value

    /**
     * Resolve the bookmark key for whatever's currently playing. For
     * local + Drive (SAF) playback this is the ExoPlayer mediaId — a
     * file URI / content:// URI. For Spotify, the local ExoPlayer
     * mediaId is stale (Spotify Connect drives playback off-device);
     * use the Spotify track URI from the polled mirror state instead.
     */
    private fun currentBookmarkKey(): String? {
        spotifyProvider.spotifyState.value?.let { s ->
            if (s.trackUri.isNotBlank()) return s.trackUri
        }
        return playbackConnection.getPlayer()?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
    }

    /**
     * Current playback position regardless of source. Spotify mirror
     * wins when active (its position is fresher than the local
     * MediaController's, which idles when Spotify is the source).
     */
    private fun currentBookmarkPositionMs(): Long {
        spotifyProvider.spotifyState.value?.let { return it.positionMs.coerceAtLeast(0L) }
        return playbackConnection.getPlayer()?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Add a bookmark at current playback position. Label is the
     * formatted timestamp; user can edit later (out-of-scope here).
     * Works uniformly across local / Drive (SAF) / Spotify sources.
     *
     * Also writes a session-scoped snapshot to history_bookmarks so
     * the current Recents row in Last Played shows it. The two writes
     * are independent: deleting from bookmarks (Player tab) does NOT
     * touch history_bookmarks (Recents) — that asymmetry is intentional
     * per the user-visible contract that "Player edits don't propagate
     * deletions to Last Played".
     */
    fun addBookmarkHere() {
        val mediaUri = currentBookmarkKey() ?: return
        val pos = currentBookmarkPositionMs()
        val label = TimeFormatter.formatDuration(pos)
        val sessionId = currentSessionId
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.insert(
                com.powermediaplayer.data.db.entity.BookmarkEntity(
                    mediaUri = mediaUri,
                    positionMs = pos,
                    label = label
                )
            )
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Bookmark added @ ${pos}ms uri=$mediaUri sessionId=$sessionId")
            // Mirror into the session's snapshot so the Recents row's
            // dropdown reflects it. Skip if no session is active yet
            // (rare — would only happen if user adds a bookmark before
            // any recordPlay has been called).
            if (sessionId != null) {
                runCatching { lastPlayedRepo.addSessionBookmark(sessionId, pos, label) }
            }
        }
    }

    /**
     * Bookmarks for the currently playing item. Empty when none / no
     * item. Re-keys when the active track changes — for Spotify the
     * trigger is the polled trackUri, for local/Drive it's the
     * ExoPlayer media-item title (cheap proxy for "track changed").
     */
    val bookmarks: StateFlow<List<com.powermediaplayer.data.db.entity.BookmarkEntity>> =
        kotlinx.coroutines.flow.combine(
            playbackConnection.playerState.map { it.title },
            spotifyProvider.spotifyState.map { it?.trackUri }
        ) { _, _ -> currentBookmarkKey() }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                if (key.isNullOrBlank()) flowOf(emptyList())
                else bookmarkDao.observeForMedia(key)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    /**
     * Seek to a bookmark's saved position. Routes through Spotify
     * Connect when Spotify is the active source so the seek lands on
     * the user's remote device, not the silent local ExoPlayer.
     */
    fun seekToBookmark(b: com.powermediaplayer.data.db.entity.BookmarkEntity) {
        // Apply user-configured replay-context offset so the seek
        // lands a few seconds BEFORE the saved moment — gives
        // podcast / audiobook listeners context for the bookmark.
        viewModelScope.launch {
            val offsetSec = runCatching {
                settingsDataStore.bookmarkReplayContextSec.first()
            }.getOrNull() ?: 10
            val target = (b.positionMs - offsetSec * 1000L).coerceAtLeast(0L)
            if (isSpotifyActive) {
                spotifyProvider.seekTo(target)
            } else {
                playbackConnection.seekTo(target)
            }
        }
    }
    fun deleteBookmark(b: com.powermediaplayer.data.db.entity.BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.delete(b.id) }
    }

    /** Rename a bookmark's label. Used by the chip long-press dialog. */
    fun renameBookmark(b: com.powermediaplayer.data.db.entity.BookmarkEntity, label: String) {
        val safe = label.trim().take(80).ifBlank { return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bookmarkDao.updateLabel(b.id, safe) }
        }
    }

    // ── Auto-hide timers (Phase 2) — exposed for PlayerScreen ─────
    // Seconds. 0 = Never. Read by the controls auto-hide LaunchedEffect
    // and by the audio/video effects sub-popups.
    val videoControlsHideSec: StateFlow<Int> = settingsDataStore.videoControlsHideSec
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4)
    val audioControlsHideFoldedSec: StateFlow<Int> = settingsDataStore.audioControlsHideFoldedSec
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val audioControlsHideTabletSec: StateFlow<Int> = settingsDataStore.audioControlsHideTabletSec
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ── State backing fields referenced by init-block coroutines ──
    // These MUST be declared BEFORE the `init {}` block, otherwise
    // `setPitch` / `setVolumeBoost` race their backing MutableStateFlow
    // initialisation: the init block launches DataStore collectors, the
    // first emission can fire synchronously on Dispatchers.Main.immediate
    // before constructor field initialisers further down the file run,
    // and `_pitch.value =` / `_volumeBoostMb.value =` then NPE.
    // (Discovered via Android 16 emulator launch crash on a fresh
    // upgrade-from-beta install — see
    // docs/superpowers/investigation/2026-05-06-cast-failures-and-s25-launch/.)
    private val _pitch = MutableStateFlow(1.0f)
    private val _volumeBoostMb = MutableStateFlow(0)

    init {
        // Settings → playback hot-wire: pitch and volume boost values
        // change in Settings; reflect them in the running player.
        viewModelScope.launch {
            settingsDataStore.pitchIndependent.collect { p -> setPitch(p) }
        }
        viewModelScope.launch {
            settingsDataStore.volumeBoostMb.collect { mb -> setVolumeBoost(mb) }
        }
        viewModelScope.launch {
            settingsDataStore.audioDelayMs.collect { playbackConnection.setAudioDelayMs(it) }
        }
        viewModelScope.launch {
            settingsDataStore.subtitleDelayMs.collect { playbackConnection.setSubtitleDelayMs(it) }
        }
        // §C7 slim — apply saved per-file overrides on track-load.
        // Distinct-until-changed on (title, uri) so we apply once per
        // fresh track without spamming on every position tick. Speed
        // override → setPlaybackSpeed; A-B loop override → restore the
        // start/end markers + start the loop poll. Only applies when
        // not Spotify-active (Connect-side playback handles its own).
        viewModelScope.launch(Dispatchers.Main) {
            playbackConnection.playerState
                .map { it.title to (playbackConnection.getPlayer()?.currentMediaItem?.mediaId ?: "") }
                .distinctUntilChanged()
                .collect { (_, uri) ->
                    if (uri.isBlank()) return@collect
                    // Speed
                    val speedOverrides = runCatching {
                        settingsDataStore.speedOverrides.first()
                    }.getOrNull().orEmpty()
                    speedOverrides[uri]?.let { saved ->
                        val current = playbackConnection.getPlayer()?.playbackParameters?.speed ?: 1.0f
                        if (kotlin.math.abs(saved - current) > 0.01f) {
                            playbackConnection.setPlaybackSpeed(saved)
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Applied saved speed override $saved× for uri=$uri"
                            )
                        }
                    }
                    // A-B loop
                    if (!isSpotifyActive) {
                        val abOverrides = runCatching {
                            settingsDataStore.abLoopOverrides.first()
                        }.getOrNull().orEmpty()
                        val saved = abOverrides[uri]
                        if (saved != null) {
                            val (a, b) = saved
                            if (_abLoopStart.value != a || _abLoopEnd.value != b) {
                                _abLoopStart.value = a
                                _abLoopEnd.value = b
                                abLoopJob?.cancel()
                                abLoopJob = launch {
                                    while (isActive) {
                                        delay(250L)
                                        val sa = _abLoopStart.value ?: break
                                        val sb = _abLoopEnd.value ?: break
                                        if (currentPositionMsAnySource() >= sb) {
                                            seekToAnySource(sa)
                                        }
                                    }
                                }
                                com.powermediaplayer.util.Diag.i(
                                    "PMP_DIAG",
                                    "Restored saved A-B loop $a..${b}ms for uri=$uri"
                                )
                            }
                        } else if (_abLoopStart.value != null || _abLoopEnd.value != null) {
                            // The new track has NO saved A-B loop — clear any
                            // loop carried over in memory from the previous
                            // track so its A/B markers don't keep seeking this
                            // one (the "doing an A-B loop I never set" bug).
                            _abLoopStart.value = null
                            _abLoopEnd.value = null
                            abLoopJob?.cancel()
                            abLoopJob = null
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Cleared carried-over A-B loop on track change to uri=$uri"
                            )
                        }
                    }
                }
        }

    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Sleep timer state
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)

    // One-shot "Sleep timer finished" message — set true on expiry,
    // cleared by [dismissSleepTimerExpired] when user taps the
    // dismiss action in the player UI.
    private val _sleepTimerExpired = MutableStateFlow(false)
    val sleepTimerExpired: StateFlow<Boolean> = _sleepTimerExpired.asStateFlow()

    fun dismissSleepTimerExpired() { _sleepTimerExpired.value = false }

    /**
     * The single source of truth for the player UI.
     * Combines PlaybackConnection state with computed display values.
     * When [spotifyProvider.spotifyState] is non-null, Spotify is the
     * active source — its title/artist/position/duration overlay the
     * local player state and transport controls route to Web API.
     */
    val uiState: StateFlow<PlayerUiState> = combine(
        playbackConnection.playerState,
        _sleepTimerRemainingMs,
        spotifyProvider.spotifyState,
        spotifyProvider.spotifyMetadataFetching,
        playbackConnection.playerFlow
    ) { args ->
        val playerState = args[0] as PlayerState
        @Suppress("UNCHECKED_CAST")
        val sleepRemaining = args[1] as Long
        val spotify = args[2] as SpotifyPlaybackState?
        val spotifyFetching = args[3] as Boolean
        val activePlayer = args[4] as androidx.media3.common.Player?
        val isCasting = activePlayer is androidx.media3.cast.CastPlayer
        val base = mapToUiState(playerState, sleepRemaining)
        val withSpotify = if (spotify != null) overlaySpotifyState(base, spotify) else base
        val withCloudBanner = if (spotifyFetching && !withSpotify.cloudFetchInProgress) {
            withSpotify.copy(cloudFetchInProgress = true)
        } else withSpotify
        withCloudBanner.copy(
            isSpotifyActive = spotify != null,
            isCasting = isCasting
        )
    }
        // Audit 3.3 — position-only ticks no longer reach the UI tree:
        // emissions whose position-stripped copies are equal are
        // suppressed. Sliders + synced lyrics read [positionUi] instead.
        .distinctUntilChanged { old, new ->
            old.positionStripped() == new.positionStripped()
        }
        .stateIn(
        scope = viewModelScope,
        // Eagerly keeps the combiner running so navigation to the player
        // tab finds the latest state already mapped — eliminates the
        // brief WhileSubscribed initial-value flash that swapped the
        // layout between Expanded (audio default) and Compact (video).
        // Per-tick cost is neutralised by the normalisation cache
        // (audit 3.2) + the playing-gated poller (3.7).
        started = SharingStarted.Eagerly,
        initialValue = mapToUiState(playbackConnection.playerState.value, 0L)
    )

    /** The eight per-tick-varying fields, zeroed for the equality check. */
    private fun PlayerUiState.positionStripped() = copy(
        currentPosition = 0L,
        currentPositionFormatted = "",
        trackRemainingFormatted = "",
        trackProgress = 0f,
        totalPlaylistPosition = 0L,
        playlistPositionFormatted = "",
        playlistRemainingFormatted = "",
        playlistProgress = 0f
    )

    /** Position-derived state at full tick rate — collected ONLY by the
     *  slider section and the synced-lyrics panel (audit 3.3). */
    val positionUi: StateFlow<PositionUi> = combine(
        playbackConnection.playerState,
        spotifyProvider.spotifyState
    ) { ps, spotify -> computePositionUi(ps, spotify) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionUi())

    private fun computePositionUi(
        ps: PlayerState,
        s: SpotifyPlaybackState?
    ): PositionUi {
        if (s != null) {
            val pos = s.positionMs.coerceAtLeast(0L)
            val dur = s.durationMs.coerceAtLeast(0L)
            val rem = (dur - pos).coerceAtLeast(0L)
            val prog = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            return PositionUi(
                trackProgress = prog,
                positionFormatted = TimeFormatter.formatDuration(pos),
                durationFormatted = TimeFormatter.formatDuration(dur),
                remainingFormatted = "-" + TimeFormatter.formatDuration(rem),
                playlistProgress = prog,
                playlistPositionFormatted = TimeFormatter.formatDuration(pos),
                playlistDurationFormatted = TimeFormatter.formatDuration(dur),
                playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(rem),
                chapterStartMs = 0L,
                durationMs = dur,
                totalPlaylistDurationMs = dur,
                positionMs = pos
            )
        }
        // Chapter-relative maths — mirrors mapToUiState's slider scope.
        val currentChapter = ps.chapters.getOrNull(ps.currentChapterIndex)
        val inChapter = ps.hasChapters && currentChapter != null
        val chapterStart = currentChapter?.startTimeMs?.takeIf { inChapter } ?: 0L
        val chapterEnd = currentChapter?.endTimeMs?.takeIf { inChapter } ?: ps.duration
        val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0L)
        val chapterPos = (ps.currentPosition - chapterStart).coerceIn(0L, chapterDuration)
        val trackProgress = if (inChapter && chapterDuration > 0) {
            (chapterPos.toFloat() / chapterDuration.toFloat()).coerceIn(0f, 1f)
        } else if (ps.duration > 0) {
            (ps.currentPosition.toFloat() / ps.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val playlistProgress = if (ps.totalPlaylistDuration > 0) {
            (ps.totalPlaylistPosition.toFloat() / ps.totalPlaylistDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val displayedPos = if (inChapter) chapterPos else ps.currentPosition
        val displayedDur = if (inChapter) chapterDuration else ps.duration
        val trackRemaining = (displayedDur - displayedPos).coerceAtLeast(0L)
        val playlistRemaining =
            (ps.totalPlaylistDuration - ps.totalPlaylistPosition).coerceAtLeast(0L)
        return PositionUi(
            trackProgress = trackProgress,
            positionFormatted = TimeFormatter.formatDuration(displayedPos),
            durationFormatted = TimeFormatter.formatDuration(displayedDur),
            remainingFormatted = "-" + TimeFormatter.formatDuration(trackRemaining),
            playlistProgress = playlistProgress,
            playlistPositionFormatted = TimeFormatter.formatDuration(ps.totalPlaylistPosition),
            playlistDurationFormatted = TimeFormatter.formatDuration(ps.totalPlaylistDuration),
            playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(playlistRemaining),
            chapterStartMs = chapterStart,
            durationMs = displayedDur,
            totalPlaylistDurationMs = ps.totalPlaylistDuration,
            positionMs = displayedPos
        )
    }

    /** Hardware volume as state (audit 3.11) — the overlay used to make
     *  two AudioManager binder calls per recomposition. */
    val volumeUi: StateFlow<Pair<Int, Int>> = callbackFlow {
        fun snap() = trySend(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) to
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        )
        snap()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { snap() }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 15)

    /**
     * Whether Spotify is the active source — drives the Player tab to
     * route control taps to the Web API instead of the local ExoPlayer.
     */
    private val isSpotifyActive: Boolean
        get() = spotifyProvider.spotifyState.value != null

    /**
     * Reactive Player reference — updates when the MediaController finishes connecting.
     * MUST be collected as state in the UI (not captured with `remember`) so that
     * VideoSurface attaches correctly after the async connection completes.
     */
    val playerFlow = playbackConnection.playerFlow

    /**
     * Cover-art bytes carried in their own flow (not in PlayerUiState),
     * because ByteArray is unstable in Compose. Keeping it inside the
     * UI state would force the entire player tree to recompose on every
     * 500 ms position-poll tick. Distinct value emissions only — when
     * the underlying reference is unchanged StateFlow conflates.
     *
     * When Spotify is the active source, emit null so CoverArtBackground
     * falls through to the URI path (uiState.artworkUri carries the
     * Spotify cover URL). Without this, the previously-decoded local
     * file's bytes win the bytes-vs-URI precedence in CoverArtBackground
     * and the Spotify cover never renders.
     */
    val artworkBytes: StateFlow<ByteArray?> = combine(
        playbackConnection.playerState,
        spotifyProvider.spotifyState
    ) { playerState, spotify ->
        if (spotify != null) null else playerState.artworkBytes
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = if (spotifyProvider.spotifyState.value != null) null
            else playbackConnection.playerState.value.artworkBytes
    )

    /**
     * Current cover-art scaling mode pulled from Settings — drives
     * ContentScale on the now-playing surface so the user can flip
     * between Fit (show whole cover) and Fill (no margins, may crop).
     */
    val artworkScaleMode: StateFlow<String> = settingsDataStore.artworkScaleMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "fit"
        )


    // ── Transport Controls (delegated to PlaybackConnection) ─────

    fun clearError() = playbackConnection.clearError()

    fun playPause() {
        if (isSpotifyActive) {
            viewModelScope.launch { spotifyProvider.togglePlayPause() }
            return
        }
        val player = playbackConnection.getPlayer()
        val isPlayingNow = player?.isPlaying == true
        viewModelScope.launch(Dispatchers.Main) {
            if (isPlayingNow) {
                // Fade-out on pause (§B2): ramp crossfadeFactor 1→0
                // over 400 ms before issuing pause, when the toggle is
                // ON. ReplayGain attenuator stays composable.
                val fadeOnPause = runCatching {
                    settingsDataStore.crossfadeFadeOutOnPause.first()
                }.getOrNull() == true
                if (fadeOnPause) {
                    rampCrossfadeFactor(from = 1.0f, to = 0.0f, durMs = 400L)
                }
                playbackConnection.pause()
                // Reset for the next play so we don't start silent.
                com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(1.0f)
            } else {
                // Fade-in on resume (§B2): set factor to 0, play, then
                // ramp 0→1 over 400 ms. Background music users get a
                // gentle re-entry instead of instant full volume.
                val fadeOnResume = runCatching {
                    settingsDataStore.crossfadeFadeInOnResume.first()
                }.getOrNull() == true
                if (fadeOnResume) {
                    com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(0.0f)
                    playbackConnection.play()
                    rampCrossfadeFactor(from = 0.0f, to = 1.0f, durMs = 400L)
                } else {
                    playbackConnection.play()
                }
            }
        }
    }

    /** Linear volume-factor ramp over [durMs] in ~30 steps. */
    private suspend fun rampCrossfadeFactor(from: Float, to: Float, durMs: Long) {
        val steps = 30
        val stepMs = durMs / steps
        val delta = to - from
        for (i in 1..steps) {
            val factor = (from + delta * (i.toFloat() / steps)).coerceIn(0.0f, 1.0f)
            com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(factor)
            kotlinx.coroutines.delay(stepMs)
        }
    }
    fun seekTo(positionMs: Long) {
        if (isSpotifyActive) {
            viewModelScope.launch { spotifyProvider.seekTo(positionMs) }
        } else {
            playbackConnection.seekTo(positionMs)
        }
    }
    fun seekToNext() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipNext() }
        else playbackConnection.seekToNext()
    }
    fun seekToPrevious() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipPrevious() }
        else playbackConnection.seekToPrevious()
    }
    fun skipBack(seconds: Int) {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "VM.skipBack(${seconds}s)")
        if (isSpotifyActive) {
            val target = ((spotifyProvider.spotifyState.value?.positionMs ?: 0L) - seconds * 1000L)
                .coerceAtLeast(0L)
            viewModelScope.launch { spotifyProvider.seekTo(target) }
            return
        }
        playbackConnection.skipBack(seconds)
    }
    fun skipForward(seconds: Int) {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "VM.skipForward(${seconds}s)")
        if (isSpotifyActive) {
            val target = (spotifyProvider.spotifyState.value?.positionMs ?: 0L) + seconds * 1000L
            viewModelScope.launch { spotifyProvider.seekTo(target) }
            return
        }
        playbackConnection.skipForward(seconds)
    }
    fun nextChapter() = playbackConnection.nextChapter()
    fun previousChapter() = playbackConnection.previousChapter()
    fun nextChapterOrTrack() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipNext() }
        else playbackConnection.nextChapterOrTrack()
    }
    fun previousChapterOrTrack() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipPrevious() }
        else playbackConnection.previousChapterOrTrack()
    }
    fun nextFile() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipNext() }
        else playbackConnection.nextFile()
    }
    fun previousFile() {
        if (isSpotifyActive) viewModelScope.launch { spotifyProvider.skipPrevious() }
        else playbackConnection.previousFile()
    }
    fun seekToChapter(index: Int) = playbackConnection.seekToChapterIndex(index)

    fun setPlaybackSpeed(speed: Float) {
        // UI → audible latency for speed/pitch is dominated by two
        // factors we can't shrink without risking dropouts:
        //  (1) ExoPlayer's Sonic time-stretch processor must flush its
        //      input buffer before a new ratio applies. Buffer is ~250 ms.
        //  (2) When routed over A2DP the BT codec adds another ~100-300 ms
        //      of encode/transport/decode latency on the HU side.
        // The IPC + DataStore-persist legs of THIS path are already
        // direct (controller.setPlaybackParameters fires immediately).
        // Logged here so the request-time stamp lands in the diag log
        // — pair with the next PLAYER state line to measure end-to-end.
        com.powermediaplayer.diag.DiagLog.ui(
            "speed change → IPC=${"%.3f".format(speed)} (Sonic+A2DP buffers will add audible delay)"
        )
        playbackConnection.setPlaybackSpeed(speed)
        // §C7 slim — persist per-file speed override so the next time
        // the user opens this track it resumes at the chosen speed.
        // Speed 1.0 (default) clears any existing override.
        // Audit 5.6 — the persist is debounced: a slider drag emits per
        // movement and each DataStore edit rewrites + fsyncs the whole
        // preferences file and wakes every collector. The player speed
        // above still applies instantly; only the WRITE settles.
        val uri = playbackConnection.getPlayer()?.currentMediaItem?.mediaId
        if (!uri.isNullOrBlank()) {
            ensureSpeedPersister()
            speedOverridePersist.value = uri to speed
        }
    }

    private val speedOverridePersist =
        kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Float>?>(null)
    private var speedPersistJob: kotlinx.coroutines.Job? = null
    private fun ensureSpeedPersister() {
        if (speedPersistJob?.isActive == true) return
        speedPersistJob = viewModelScope.launch {
            speedOverridePersist.filterNotNull().collectLatest { (uri, speed) ->
                kotlinx.coroutines.delay(300)   // new drag emissions cancel the wait
                if (kotlin.math.abs(speed - 1.0f) < 0.01f) {
                    settingsDataStore.clearSpeedOverride(uri)
                } else {
                    settingsDataStore.setSpeedOverride(uri, speed)
                }
            }
        }
    }

    // ── Volume (mapped to AudioManager system volume) ────────────

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun getCurrentVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(volume: Int) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volume.coerceIn(0, getMaxVolume()),
            0 // No flags (no UI toast)
        )
    }

    // ── Sleep Timer ──────────────────────────────────────────────
    //
    // §C11 — four sleep-timer modes:
    //   TIME_BASED    — fixed minutes countdown (legacy [startSleepTimer]).
    //   END_OF_TRACK  — pause at the next mediaItemTransition.
    //   END_OF_CHAPTER — pause at the next chapter boundary (Media3
    //                    folder-chapter aggregator + per-file MediaMetadata).
    //   END_OF_QUEUE  — pause once the queue's last MediaItem ends.
    //
    // Each mode also honours the "Linear fade-out over last 5 minutes"
    // switch where time can be predicted; modes whose end-time isn't
    // known until just-before-fire (END_OF_TRACK / END_OF_CHAPTER /
    // END_OF_QUEUE) only fade if the remaining duration is known and
    // exceeds the fade window.

    enum class SleepTimerMode { TIME_BASED, END_OF_TRACK, END_OF_CHAPTER, END_OF_QUEUE }

    private val _sleepTimerMode = MutableStateFlow(SleepTimerMode.TIME_BASED)
    val sleepTimerMode: StateFlow<SleepTimerMode> = _sleepTimerMode.asStateFlow()

    fun startSleepTimerMode(mode: SleepTimerMode, minutes: Int = 0) {
        sleepTimerJob?.cancel()
        _sleepTimerMode.value = mode
        when (mode) {
            SleepTimerMode.TIME_BASED -> startSleepTimer(minutes)
            SleepTimerMode.END_OF_TRACK -> startEndOfTrackTimer()
            SleepTimerMode.END_OF_CHAPTER -> startEndOfChapterTimer()
            SleepTimerMode.END_OF_QUEUE -> startEndOfQueueTimer()
        }
    }

    private fun startEndOfTrackTimer() {
        val player = playbackConnection.getPlayer() ?: return
        val durMs = player.duration.coerceAtLeast(0L)
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val remaining = (durMs - posMs).coerceAtLeast(0L)
        _sleepTimerRemainingMs.value = remaining
        sleepTimerJob = viewModelScope.launch {
            val fadeOutEnabled = runCatching {
                settingsDataStore.sleepTimerFadeOut.first()
            }.getOrNull() == true
            val fadeWindowMs = 5 * 60_000L
            var rem = remaining
            while (rem > 0) {
                delay(500)
                val p = playbackConnection.getPlayer() ?: break
                val newDur = p.duration.coerceAtLeast(0L)
                val newPos = p.currentPosition.coerceAtLeast(0L)
                rem = (newDur - newPos).coerceAtLeast(0L)
                _sleepTimerRemainingMs.value = rem
                if (fadeOutEnabled && rem in 1..fadeWindowMs) {
                    val factor = (rem.toFloat() / fadeWindowMs).coerceIn(0f, 1f)
                    com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(factor)
                }
            }
            firePauseAndExpire("END_OF_TRACK")
        }
    }

    private fun startEndOfChapterTimer() {
        // Use existing folder-chapter aggregator + per-file chapters
        // (M4B). Poll position; pause when we cross the next chapter
        // boundary. If no chapters are known, fall back to END_OF_TRACK.
        sleepTimerJob = viewModelScope.launch {
            val player = playbackConnection.getPlayer() ?: return@launch
            val initialPos = player.currentPosition.coerceAtLeast(0L)
            // Try local file chapters first (M4B / MP4 chap atom).
            // The actual key written by M4bChapterParser is `chapter_count`
            // + per-index scalar `chapter_start_<i>` longs (millis). The
            // earlier `chapter_starts_ms` long-array key wasn't written
            // by anything → END_OF_CHAPTER silently fell through to
            // END_OF_TRACK on every M4B. Read the parser's actual key
            // shape now.
            val chapters: List<Long> = run {
                val item = player.currentMediaItem ?: return@run emptyList()
                val extras = item.mediaMetadata.extras ?: return@run emptyList()
                val count = extras.getInt("chapter_count", 0)
                if (count <= 0) return@run emptyList()
                (0 until count)
                    .map { i -> extras.getLong("chapter_start_$i", -1L) }
                    .filter { it >= 0L }
                    .sorted()
            }
            val nextBoundary: Long = chapters
                .firstOrNull { it > initialPos }
                ?: run {
                    // No chapters → fall through to end-of-track behaviour.
                    val dur = player.duration.coerceAtLeast(0L)
                    if (dur > initialPos) dur else 0L
                }
            if (nextBoundary <= 0) {
                firePauseAndExpire("END_OF_CHAPTER (no boundary)")
                return@launch
            }
            val fadeOutEnabled = runCatching {
                settingsDataStore.sleepTimerFadeOut.first()
            }.getOrNull() == true
            val fadeWindowMs = 5 * 60_000L
            while (true) {
                delay(500)
                val p = playbackConnection.getPlayer() ?: break
                val cur = p.currentPosition.coerceAtLeast(0L)
                val rem = (nextBoundary - cur).coerceAtLeast(0L)
                _sleepTimerRemainingMs.value = rem
                if (fadeOutEnabled && rem in 1..fadeWindowMs) {
                    val factor = (rem.toFloat() / fadeWindowMs).coerceIn(0f, 1f)
                    com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(factor)
                }
                if (cur >= nextBoundary) break
            }
            firePauseAndExpire("END_OF_CHAPTER")
        }
    }

    private fun startEndOfQueueTimer() {
        sleepTimerJob = viewModelScope.launch {
            val fadeOutEnabled = runCatching {
                settingsDataStore.sleepTimerFadeOut.first()
            }.getOrNull() == true
            val fadeWindowMs = 5 * 60_000L
            while (true) {
                delay(500)
                val p = playbackConnection.getPlayer() ?: break
                val isLastItem = p.currentMediaItemIndex >= p.mediaItemCount - 1
                if (!isLastItem) {
                    _sleepTimerRemainingMs.value = -1L
                    continue
                }
                val rem = (p.duration.coerceAtLeast(0L) - p.currentPosition.coerceAtLeast(0L))
                    .coerceAtLeast(0L)
                _sleepTimerRemainingMs.value = rem
                if (fadeOutEnabled && rem in 1..fadeWindowMs) {
                    val factor = (rem.toFloat() / fadeWindowMs).coerceIn(0f, 1f)
                    com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(factor)
                }
                if (rem == 0L) break
            }
            firePauseAndExpire("END_OF_QUEUE")
        }
    }

    private fun firePauseAndExpire(label: String) {
        playbackConnection.pause()
        com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(1.0f)
        _sleepTimerRemainingMs.value = 0
        _sleepTimerExpired.value = true
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "SleepTimer expired — mode=$label"
        )
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs

        sleepTimerJob = viewModelScope.launch {
            // §C11: optional fade-out over the last 30 s. Read setting
            // once at start; toggling mid-timer doesn't retroactively
            // re-apply (would require re-pumping volume).
            val fadeOutEnabled = runCatching {
                settingsDataStore.sleepTimerFadeOut.first()
            }.getOrNull() == true
            val fadeWindowMs = 30_000L
            var remaining = totalMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
                if (fadeOutEnabled && remaining in 1..fadeWindowMs) {
                    // Linear ramp from 1.0 at remaining=fadeWindowMs to
                    // 0.0 at remaining=0. Routed through the same
                    // crossfade-factor channel so it composes with the
                    // existing ReplayGain attenuator without fighting
                    // for volume control.
                    val factor = (remaining.toFloat() / fadeWindowMs)
                        .coerceIn(0.0f, 1.0f)
                    com.powermediaplayer.service.PlaybackService
                        .setCrossfadeFactor(factor)
                }
            }
            // Timer expired — pause playback (no alarm sound) and raise
            // a dismissible "Sleep timer finished" flag the UI shows.
            playbackConnection.pause()
            // Reset the crossfade factor so the next play resumes at
            // full volume. ReplayGain attenuator stays as-is.
            com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(1.0f)
            _sleepTimerRemainingMs.value = 0
            _sleepTimerExpired.value = true
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "SleepTimer expired — paused playback (fadeOut=$fadeOutEnabled)"
            )
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = 0
    }

    /**
     * §B2 Manual fade-now: ramps crossfadeFactor 1.0 → 0.0 over 1.5 s
     * then advances to the next track and resets the factor. If there
     * is no next track, falls back to a fade-to-pause. Triggered by
     * the "Fade now" button inside the Crossfade panel (visible only
     * when crossfadeManualFadeNowEnabled is ON in DataStore).
     */
    fun fadeNow() {
        viewModelScope.launch(Dispatchers.Main) {
            val rampMs = 1_500L
            val steps = 30
            val stepMs = rampMs / steps
            for (i in 0 until steps) {
                val factor = 1.0f - (i + 1) / steps.toFloat()
                com.powermediaplayer.service.PlaybackService
                    .setCrossfadeFactor(factor.coerceIn(0.0f, 1.0f))
                kotlinx.coroutines.delay(stepMs)
            }
            val player = playbackConnection.getPlayer()
            if (player?.hasNextMediaItem() == true) {
                player.seekToNextMediaItem()
            } else {
                playbackConnection.pause()
            }
            // Restore full volume so the next track resumes at audible
            // level (or, if pause path, the next manual play does).
            com.powermediaplayer.service.PlaybackService.setCrossfadeFactor(1.0f)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "fadeNow advanced to next track / paused")
        }
    }

    /**
     * Sleep timer that pauses at the end of the CURRENT chapter (or
     * track if the file has no chapters). Power-user feature: lets the
     * listener fall asleep without losing their place mid-chapter.
     */
    fun startSleepAtEndOfChapter() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            val state = uiState.value
            val pos = playbackConnection.playerState.value.currentPosition
            val target: Long = if (state.hasChapters && state.chapterDurationMs > 0) {
                state.chapterStartMs + state.chapterDurationMs
            } else {
                playbackConnection.playerState.value.duration
            }
            val deltaMs = (target - pos).coerceAtLeast(1_000L)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "SleepAtEndOfChapter delta=${deltaMs}ms target=${target}ms")
            _sleepTimerRemainingMs.value = deltaMs
            var remaining = deltaMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
            }
            playbackConnection.pause()
            _sleepTimerRemainingMs.value = 0
            _sleepTimerExpired.value = true
        }
    }

    // ── A-B loop ─────────────────────────────────────────────────
    private val _abLoopStart = MutableStateFlow<Long?>(null)
    private val _abLoopEnd = MutableStateFlow<Long?>(null)
    val abLoopStart: StateFlow<Long?> = _abLoopStart.asStateFlow()
    val abLoopEnd: StateFlow<Long?> = _abLoopEnd.asStateFlow()
    private var abLoopJob: Job? = null

    /**
     * Three-state A-B loop: first tap captures A; second tap captures
     * B and starts looping; third tap clears the loop. The loop is
     * enforced by a polling job that seeks back to A whenever
     * currentPosition crosses B.
     */
    /** Current playback position regardless of source (Spotify mirror or local). */
    private fun currentPositionMsAnySource(): Long =
        if (isSpotifyActive) spotifyProvider.spotifyState.value?.positionMs ?: 0L
        else playbackConnection.playerState.value.currentPosition

    /** Seek regardless of source. Spotify routes through Connect /me/player/seek. */
    private fun seekToAnySource(positionMs: Long) {
        if (isSpotifyActive) {
            viewModelScope.launch { spotifyProvider.seekTo(positionMs) }
        } else {
            playbackConnection.seekTo(positionMs)
        }
    }

    fun toggleAbLoop() {
        val mediaUri = playbackConnection.getPlayer()?.currentMediaItem?.mediaId
        when {
            _abLoopStart.value == null -> {
                _abLoopStart.value = currentPositionMsAnySource()
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop A=${_abLoopStart.value}ms src=${if (isSpotifyActive) "spotify" else "local"}")
            }
            _abLoopEnd.value == null -> {
                val end = currentPositionMsAnySource()
                val start = _abLoopStart.value ?: return
                if (end <= start + 1_000) {
                    _abLoopStart.value = null
                    _abLoopEnd.value = null
                    return
                }
                _abLoopEnd.value = end
                abLoopJob?.cancel()
                // Persist for next open of this track (§C7 slim). Only
                // for non-Spotify (local + Drive) — Spotify track URIs
                // are stable but the experience is "Connect"-side, so
                // restoring on Connect first-emit could surprise users.
                if (!mediaUri.isNullOrBlank() && !isSpotifyActive) {
                    viewModelScope.launch {
                        runCatching {
                            settingsDataStore.setAbLoopOverride(mediaUri, start, end)
                        }
                    }
                }
                // Spotify Connect /seek IPC is round-trip ~300-500ms;
                // poll less aggressively when remote and add a small
                // dwell guard so we don't spam-seek.
                val pollInterval = if (isSpotifyActive) 750L else 250L
                abLoopJob = viewModelScope.launch {
                    while (isActive) {
                        delay(pollInterval)
                        val a = _abLoopStart.value ?: break
                        val b = _abLoopEnd.value ?: break
                        if (currentPositionMsAnySource() >= b) {
                            seekToAnySource(a)
                        }
                    }
                }
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop B=${end}ms (loop active, src=${if (isSpotifyActive) "spotify" else "local"})")
            }
            else -> {
                _abLoopStart.value = null
                _abLoopEnd.value = null
                abLoopJob?.cancel()
                if (!mediaUri.isNullOrBlank()) {
                    viewModelScope.launch {
                        runCatching { settingsDataStore.clearAbLoopOverride(mediaUri) }
                    }
                }
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop cleared")
            }
        }
    }

    // ── Pitch shift (independent of speed) ────────────────────────
    // _pitch backing field is declared above the init block (see
    // comment near `init {}`); only the public StateFlow façade lives
    // here so caller-visible API stays in this section.
    val pitch: StateFlow<Float> = _pitch.asStateFlow()
    fun setPitch(value: Float) {
        val clamped = value.coerceIn(0.5f, 2.0f)
        // Pitch shares the Sonic processor + A2DP buffer with speed
        // (see setPlaybackSpeed comment). Same inherent latency floor;
        // direct IPC on this side already.
        com.powermediaplayer.diag.DiagLog.ui(
            "slider pitch → IPC=${"%.3f".format(clamped)} (Sonic+A2DP buffers will add audible delay)"
        )
        _pitch.value = clamped
        // Re-apply current speed with new pitch (Media3 takes both in
        // PlaybackParameters). Read speed directly from the Player —
        // NOT from `uiState.value.playbackSpeed`. uiState is declared
        // AFTER the init block that launches the pitchIndependent
        // collector, and that collector's first emit can fire
        // synchronously on Dispatchers.Main.immediate before the
        // uiState property initialiser runs. Touching uiState there
        // NPEs on a fresh launch (same field-init-order race class
        // that previously hit _pitch / _volumeBoostMb).
        // See docs/superpowers/plans/2026-05-07-info-icons-...md §A3.
        val speed = runCatching {
            playbackConnection.getPlayer()?.playbackParameters?.speed
        }.getOrNull() ?: 1.0f
        playbackConnection.setPlaybackParametersWithPitch(speed, clamped)
    }

    // ── Reverb (rendered in the service AudioProcessor chain) ───────
    init {
        // Live direction flip: toggling "Reverse audio" mid-playback
        // swaps the CURRENT item's direction from the mirrored position
        // (2:00 forward of a 10-min file = 8:00 reversed — the audio
        // turns around exactly where you are). Playing/paused state is
        // preserved. Video and the Spotify mirror are untouched; if the
        // reversed copy can't be built (e.g. Drive over the 50 MB
        // guard), playback continues unchanged.
        viewModelScope.launch {
            settingsDataStore.audioReverseLocal
                .distinctUntilChanged()
                .drop(1) // react to flips, not the startup value
                .collect { wantReversed ->
                    val player = playbackConnection.getPlayer() ?: return@collect
                    val item = player.currentMediaItem ?: return@collect
                    val originalUri = item.mediaId.takeIf { it.isNotBlank() }
                        ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                        ?: return@collect
                    val isReversedNow = (item.localConfiguration?.uri?.path ?: "")
                        .contains("/reverse-cache/")
                    if (wantReversed == isReversedNow) return@collect
                    if (playbackConnection.playerState.value.isVideoContent) return@collect
                    if (spotifyProvider.spotifyState.value != null) return@collect
                    val duration = player.duration.takeIf { it > 0 } ?: return@collect
                    val mirrored = (duration - player.currentPosition)
                        .coerceIn(0L, duration)
                    val wasPlaying = player.isPlaying
                    val title = item.mediaMetadata.title
                    val artist = item.mediaMetadata.artist
                    val token = com.powermediaplayer.playback.ResumeGate.begin()
                    playbackConnection.setCloudFetchInProgress(true)
                    try {
                        val newUri = if (wantReversed) {
                            withContext(Dispatchers.IO) {
                                com.powermediaplayer.audio.ReverseAudio
                                    .ensureReversedWav(context, originalUri)
                                    .map { android.net.Uri.fromFile(it) }
                                    .getOrNull()
                            } ?: run {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Can't reverse this file — playing on normally",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return@collect
                            }
                        } else originalUri
                        if (!com.powermediaplayer.playback.ResumeGate.isCurrent(token)) {
                            return@collect
                        }
                        val extras = if (wantReversed) android.os.Bundle()
                        else com.powermediaplayer.util.M4bChapterParser
                            .cachedOnly(context, originalUri) ?: android.os.Bundle()
                        val swapped = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(originalUri.toString())
                            .setUri(newUri)
                            .setRequestMetadata(
                                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(newUri).build()
                            )
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setExtras(extras)
                                    .build()
                            )
                            .build()
                        playbackConnection.setMediaItems(
                            listOf(swapped), 0, playWhenReady = wasPlaying,
                            startPositionMs = mirrored
                        )
                        com.powermediaplayer.diag.DiagLog.event(
                            "PLAYER",
                            "direction flip reversed=$wantReversed mirroredPos=${mirrored}ms playing=$wasPlaying"
                        )
                    } finally {
                        playbackConnection.setCloudFetchInProgress(false)
                        com.powermediaplayer.playback.ResumeGate.end(token)
                    }
                }
        }
        // Reverb is rendered in the service's own AudioProcessor chain
        // (ReverbAudioProcessor) — the platform effect proved unusable
        // on real hardware. Per-file override wins over the global
        // preset; wet mix applies live.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                mediaOverrideRepo.withOverrideInt(
                    settingsDataStore.reverbPreset
                ) { it.reverbPreset },
                settingsDataStore.reverbWetMix
            ) { preset, wet -> preset to wet }.collect { (preset, wet) ->
                com.powermediaplayer.service.PlaybackService.setReverb(preset, wet)
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG", "Reverb chain params preset=$preset wet=$wet"
                )
            }
        }
    }

    // ── LoudnessEnhancer volume boost ─────────────────────────────
    // _volumeBoostMb backing field is declared above the init block.
    val volumeBoostMb: StateFlow<Int> = _volumeBoostMb.asStateFlow()

    /** millibels of gain on top of normal volume. 0 = off; 2000 = +20 dB.
     *  Routed through the service's user-boost channel; ReplayGain has
     *  its own channel there, so neither can clobber the other
     *  (audit 3.6 / T264 split — single writer per channel). */
    fun setVolumeBoost(milliBels: Int) {
        val mb = milliBels.coerceIn(0, 2000)
        _volumeBoostMb.value = mb
        com.powermediaplayer.service.PlaybackService.setUserBoostMb(mb)
    }

    // ── Frame step + screenshot helpers ───────────────────────────
    fun stepFrameForward() {
        playbackConnection.pause()
        // ~one frame at 30 fps. Good enough for visual stepping.
        val pos = playbackConnection.playerState.value.currentPosition
        playbackConnection.seekTo(pos + 33)
    }
    fun stepFrameBack() {
        playbackConnection.pause()
        val pos = playbackConnection.playerState.value.currentPosition
        playbackConnection.seekTo((pos - 33).coerceAtLeast(0L))
    }

    // ── Playlist Seek (seek within entire playlist by absolute position) ──

    fun seekToPlaylistPosition(absolutePositionMs: Long) =
        playbackConnection.seekToAbsolutePlaylistPosition(absolutePositionMs)

    // ── Set media items for playback ─────────────────────────────

    fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int = 0) {
        playbackConnection.setMediaItems(items, startIndex)
    }

    // ── State Mapping ────────────────────────────────────────────

    /**
     * Audit 3.2 — text normalisation (NFC + mojibake repair + regex)
     * and the full chapter-list copy ran on EVERY 500ms tick even
     * though those strings change only on track change. Cached per
     * track; per-tick work is position maths only. The reference-stable
     * chapter list also lets the chapter picker's Lazy list diff by
     * identity instead of re-keying per tick.
     */
    private data class NormalisedTrackText(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val description: String,
        val genre: String,
        val chapters: List<com.powermediaplayer.service.ChapterInfo>,
        val chaptersSource: List<com.powermediaplayer.service.ChapterInfo>
    )

    private var normCache: NormalisedTrackText? = null

    private fun normalisedFor(ps: PlayerState): NormalisedTrackText {
        val key = "${ps.currentMediaUri}|${ps.title}"
        val cached = normCache
        // chaptersSource reference check catches late chapter injection
        // (async fill on remote books swaps the list instance).
        if (cached != null && cached.key == key && cached.chaptersSource === ps.chapters) {
            return cached
        }
        return NormalisedTrackText(
            key = key,
            title = TextNormalizer.normalize(ps.title),
            artist = TextNormalizer.normalize(ps.artist),
            album = TextNormalizer.normalize(ps.album),
            description = TextNormalizer.normalize(ps.description),
            genre = TextNormalizer.normalize(ps.genre),
            chapters = ps.chapters.map { it.copy(title = TextNormalizer.normalize(it.title)) },
            chaptersSource = ps.chapters
        ).also { normCache = it }
    }

    private fun mapToUiState(playerState: PlayerState, sleepRemainingMs: Long): PlayerUiState {
        val hasMedia = playerState.mediaItemCount > 0
        val norm = normalisedFor(playerState)

        // Chapter-relative track slider — when the file has chapters, the
        // track slider scrubs the CURRENT chapter (start..end) so the user
        // sees per-chapter progress and remaining; the full slider shows
        // overall file/playlist progress separately.
        val currentChapter = playerState.chapters.getOrNull(playerState.currentChapterIndex)
        val inChapter = playerState.hasChapters && currentChapter != null
        val chapterStart = currentChapter?.startTimeMs?.takeIf { inChapter } ?: 0L
        val chapterEnd = currentChapter?.endTimeMs?.takeIf { inChapter } ?: playerState.duration
        val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0L)
        val chapterPos = (playerState.currentPosition - chapterStart).coerceIn(0L, chapterDuration)

        val trackProgress = if (inChapter && chapterDuration > 0) {
            (chapterPos.toFloat() / chapterDuration.toFloat()).coerceIn(0f, 1f)
        } else if (playerState.duration > 0) {
            (playerState.currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val playlistProgress = if (playerState.totalPlaylistDuration > 0) {
            (playerState.totalPlaylistPosition.toFloat() / playerState.totalPlaylistDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        // Track-slider numerator/denominator follow the chapter scope when
        // available, so the displayed times match what the slider shows.
        val displayedTrackPos = if (inChapter) chapterPos else playerState.currentPosition
        val displayedTrackDur = if (inChapter) chapterDuration else playerState.duration
        val trackRemaining = (displayedTrackDur - displayedTrackPos).coerceAtLeast(0L)
        val playlistRemaining = (playerState.totalPlaylistDuration - playerState.totalPlaylistPosition).coerceAtLeast(0L)
        return PlayerUiState(
            isPlaying = playerState.isPlaying,
            isLoading = playerState.isLoading,
            hasMedia = hasMedia,
            // Normalize all human-visible strings to repair UTF-8/Latin-1
            // mojibake (e.g. "Philosopherâ€™s" → "Philosopher's") and
            // collapse curly quotes / invisible formatting. Cached per
            // track (audit 3.2).
            title = norm.title.ifEmpty { "No media loaded" },
            artist = norm.artist,
            album = norm.album,
            description = norm.description,
            year = playerState.year,
            genre = norm.genre,
            artworkUri = playerState.artworkUri,
            hasCoverArt = playerState.hasCoverArt,
            currentPosition = displayedTrackPos,
            duration = displayedTrackDur,
            currentPositionFormatted = TimeFormatter.formatDuration(displayedTrackPos),
            durationFormatted = TimeFormatter.formatDuration(displayedTrackDur),
            trackRemainingFormatted = "-" + TimeFormatter.formatDuration(trackRemaining),
            trackProgress = trackProgress,
            chapterStartMs = chapterStart,
            chapterDurationMs = chapterDuration,
            playerError = playerState.playerError,
            cloudFetchInProgress = playerState.cloudFetchInProgress,
            videoWidth = playerState.videoWidth,
            videoHeight = playerState.videoHeight,
            totalPlaylistPosition = playerState.totalPlaylistPosition,
            totalPlaylistDuration = playerState.totalPlaylistDuration,
            playlistPositionFormatted = TimeFormatter.formatDuration(playerState.totalPlaylistPosition),
            playlistDurationFormatted = TimeFormatter.formatDuration(playerState.totalPlaylistDuration),
            playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(playlistRemaining),
            playlistProgress = playlistProgress,
            playbackSpeed = playerState.playbackSpeed,
            sleepTimerRemainingMs = sleepRemainingMs,
            sleepTimerActive = sleepRemainingMs > 0,
            sleepTimerFormatted = if (sleepRemainingMs > 0) TimeFormatter.formatDuration(sleepRemainingMs) else "",
            currentTrackIndex = playerState.currentMediaItemIndex,
            totalTracks = playerState.mediaItemCount,
            trackIndexDisplay = if (playerState.mediaItemCount > 1) {
                "${playerState.currentMediaItemIndex + 1} / ${playerState.mediaItemCount}"
            } else "",
            chapters = norm.chapters,
            currentChapterIndex = playerState.currentChapterIndex,
            hasChapters = playerState.hasChapters,
            controls = ControlsEnabledState(
                previousTrack = playerState.hasPrevious,
                nextTrack = playerState.hasNext,
                previousChapter = playerState.hasChapters && playerState.currentChapterIndex > 0,
                nextChapter = playerState.hasChapters && playerState.currentChapterIndex < playerState.chapters.size - 1,
                previousFile = playerState.hasPrevious,
                nextFile = playerState.hasNext,
                previousChapterOrTrack = (playerState.hasChapters && playerState.currentChapterIndex > 0) || playerState.hasPrevious || hasMedia,
                nextChapterOrTrack = (playerState.hasChapters && playerState.currentChapterIndex < playerState.chapters.size - 1) || playerState.hasNext,
                skipBack5 = hasMedia && playerState.isSeekable,
                skipBack10 = hasMedia && playerState.isSeekable,
                skipBack15 = hasMedia && playerState.isSeekable,
                skipBack20 = hasMedia && playerState.isSeekable,
                skipBack30 = hasMedia && playerState.isSeekable,
                skipForward5 = hasMedia && playerState.isSeekable,
                skipForward10 = hasMedia && playerState.isSeekable,
                skipForward15 = hasMedia && playerState.isSeekable,
                skipForward20 = hasMedia && playerState.isSeekable,
                skipForward30 = hasMedia && playerState.isSeekable,
                playPause = hasMedia,
                playbackSpeed = hasMedia,
                brightness = true,
                volume = true,
                sleepTimer = hasMedia,
                trackSlider = hasMedia && playerState.duration > 0 && playerState.isSeekable,
                // Full slider enabled when EITHER (a) we're playing a multi-
                // track queue (cross-track scrubbing) OR (b) the current
                // file has chapters (chapter slider scrubs within current
                // chapter; full slider scrubs the entire file). The (b)
                // path makes Drive M4Bs and other single-file audiobooks
                // scrubbable end-to-end.
                playlistSlider = (
                    (playerState.isPartOfPlaylist && playerState.totalPlaylistDuration > 0) ||
                    (playerState.hasChapters && playerState.duration > 0)
                ) && playerState.isSeekable
            ),
            isVideoContent = playerState.isVideoContent,
            audioFormatLabel = playerState.audioFormatLabel,
            mediaKind = inferMediaKind(playerState),
            isCurrentMediaCastable = isCastableMedia(playerState)
        )
    }

    /**
     * Authoritative cast-supported check for the *current* media item.
     * Default Cast Media Receiver (CC1AD845) reliably plays MP4 +
     * WebM containers (with H.264 / HEVC / VP8 / VP9 + AAC / MP3 /
     * Opus / FLAC). It cannot play MKV / AVI / MOV / FLV / WMV / TS /
     * 3GP. Audio-only files always return true.
     *
     * Source: official Cast Web Receiver supported-media documentation
     * (developers.google.com/cast/docs/media). Verified by deferring
     * the question of receiver capability to extension, since the
     * MediaItem MIME type is rarely populated by our LibraryViewModel
     * before the file plays — the URI's extension is the most stable
     * signal at queue time.
     */
    private fun isCastableMedia(playerState: PlayerState): Boolean {
        if (!playerState.isVideoContent) return true
        val uri = playerState.currentMediaUri
        if (uri.isBlank()) return true   // unknown — don't be over-restrictive
        val ext = uri.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext in CASTABLE_VIDEO_EXTENSIONS
    }

    internal companion object {

        // Conservative whitelist matching the default Cast receiver's
        // documented support. .3gp / .3gpp omitted on purpose — receiver
        // support varies by device generation; keeping the list tight
        // means false-negatives ("works but greyed out") rather than
        // false-positives ("looks castable, then fails on the TV").
        private val CASTABLE_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm")

    }

    /**
     * Coarse content classification used to label Prev/Next
     * buttons. Spotify mirror always wins; otherwise we inspect
     * tracks, chapters, and queue size.
     */
    private fun inferMediaKind(s: PlayerState): MediaKind = when {
        s.isVideoContent -> MediaKind.VIDEO
        s.hasChapters -> MediaKind.AUDIOBOOK
        s.mediaItemCount > 1 -> MediaKind.ALBUM
        s.mediaItemCount == 1 -> MediaKind.MUSIC
        else -> MediaKind.UNKNOWN
    }

    /**
     * Provides direct access to the underlying Player for VideoSurface attachment.
     */
    fun getPlayer() = playbackConnection.getPlayer()

    /**
     * Overlay the polled Spotify state on top of [base]. Only the
     * fields the Player UI displays (title/artist/album/artwork URI,
     * position, duration, formatted strings, isPlaying, controls
     * gating) are replaced — everything else stays at its default so
     * Compose's smart-skip still saves the static rows from
     * recomposing each tick.
     */
    private fun overlaySpotifyState(base: PlayerUiState, s: SpotifyPlaybackState): PlayerUiState {
        val pos = s.positionMs.coerceAtLeast(0L)
        val dur = s.durationMs.coerceAtLeast(0L)
        val remaining = (dur - pos).coerceAtLeast(0L)
        val progress = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
        return base.copy(
            isPlaying = s.isPlaying,
            isLoading = false,
            title = s.title.ifEmpty { "Spotify track" },
            artist = s.artist,
            album = s.album,
            description = s.deviceName?.let { "Playing on $it" }.orEmpty(),
            artworkUri = s.artworkUrl?.let { android.net.Uri.parse(it) },
            hasCoverArt = s.artworkUrl != null,
            lyrics = s.lyrics.orEmpty(),
            syncedLyrics = s.syncedLyrics,
            currentPosition = pos,
            duration = dur,
            currentPositionFormatted = TimeFormatter.formatDuration(pos),
            durationFormatted = TimeFormatter.formatDuration(dur),
            trackRemainingFormatted = "-" + TimeFormatter.formatDuration(remaining),
            trackProgress = progress,
            chapterStartMs = 0L,
            chapterDurationMs = dur,
            // Hide the playlist slider and chapter info — Spotify Connect
            // playback doesn't expose a queue or chapter structure here.
            totalPlaylistPosition = pos,
            totalPlaylistDuration = dur,
            playlistPositionFormatted = TimeFormatter.formatDuration(pos),
            playlistDurationFormatted = TimeFormatter.formatDuration(dur),
            playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(remaining),
            playlistProgress = progress,
            chapters = emptyList(),
            currentChapterIndex = -1,
            hasChapters = false,
            currentTrackIndex = 0,
            totalTracks = 0,
            trackIndexDisplay = "",
            isVideoContent = false,
            mediaKind = MediaKind.SPOTIFY_TRACK,
            controls = ControlsEnabledState(
                previousTrack = true,
                nextTrack = true,
                previousChapter = false,
                nextChapter = false,
                previousFile = true,
                nextFile = true,
                previousChapterOrTrack = true,
                nextChapterOrTrack = true,
                skipBack5 = true, skipBack10 = true, skipBack15 = true,
                skipBack20 = true, skipBack30 = true,
                skipForward5 = true, skipForward10 = true, skipForward15 = true,
                skipForward20 = true, skipForward30 = true,
                playPause = true,
                playbackSpeed = false,
                brightness = true,
                volume = true,
                sleepTimer = true,
                trackSlider = dur > 0,
                playlistSlider = false
            )
        )
    }
}
