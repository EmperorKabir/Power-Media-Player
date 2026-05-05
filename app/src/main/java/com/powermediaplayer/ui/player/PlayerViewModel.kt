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
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * ViewModel for the Player screen.
 * Transforms raw PlayerState from PlaybackConnection into display-ready PlayerUiState.
 * Manages sleep timer countdown. All state updates flow via StateFlow to prevent
 * frame drops from the 12+ simultaneous on-screen buttons.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: SpotifyProvider,
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore,
    private val bookmarkDao: com.powermediaplayer.data.db.dao.BookmarkDao,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

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
            android.util.Log.i("PMP_DIAG", "Bookmark added @ ${pos}ms uri=$mediaUri sessionId=$sessionId")
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
        if (isSpotifyActive) {
            viewModelScope.launch { spotifyProvider.seekTo(b.positionMs) }
        } else {
            playbackConnection.seekTo(b.positionMs)
        }
    }
    fun deleteBookmark(b: com.powermediaplayer.data.db.entity.BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.delete(b.id) }
    }

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
        // Persist current position every 5s while playing — used by the
        // Last Played tab + cold-start resume. One small UPDATE per tick.
        // MediaController access MUST happen on the application main
        // thread (Media3 verifyApplicationThread); the DB write hops
        // off main via viewModelScope.launch(Dispatchers.IO).
        viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5_000)
                val player = playbackConnection.getPlayer() ?: continue
                val mediaUri = player.currentMediaItem?.mediaId ?: continue
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playing = player.isPlaying
                if (playing) {
                    launch(Dispatchers.IO) {
                        runCatching { lastPlayedRepo.updatePositionByUri(mediaUri, pos) }
                    }
                }
            }
        }
        // Cold-start resume: load the most-recent LOCAL history item
        // into the player paused at its saved position. Cloud items
        // are skipped — Drive needs token refresh and Spotify needs
        // Connect device, neither of which are guaranteed at cold
        // start. Auto-play is off so audio doesn't surprise the user.
        viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(800) // wait for service connection
            // Don't clobber an active Spotify mirror — when the user
            // switches tabs and comes back, the VM may be recreated
            // and this block re-runs. Without the guard, the local
            // resume overwrites the live Spotify state and the user
            // sees a paused old local video where Spotify should be.
            if (spotifyProvider.spotifyState.value != null) return@launch
            val recent = withContext(Dispatchers.IO) {
                runCatching { lastPlayedRepo.mostRecent() }.getOrNull()
            } ?: return@launch
            if (recent.source != "LOCAL") return@launch
            val player = playbackConnection.getPlayer() ?: return@launch
            // Only restore when nothing is loaded — never clobber an
            // active session. (Now safely on Main.)
            if (player.currentMediaItem != null) return@launch
            runCatching {
                val uri = android.net.Uri.parse(recent.mediaUri)
                val item = androidx.media3.common.MediaItem.Builder()
                    .setMediaId(recent.mediaUri)
                    .setUri(uri)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(uri).build()
                    )
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(recent.title)
                            .setArtist(recent.subtitle)
                            .build()
                    )
                    .build()
                playbackConnection.setMediaItems(listOf(item), 0)
                playbackConnection.seekTo(recent.lastPositionMs)
                player.playWhenReady = false
                android.util.Log.i(
                    "PMP_DIAG",
                    "Cold-start restored '${recent.title}' @ ${recent.lastPositionMs}ms"
                )
            }
        }
        // Replay gain: when enabled, read REPLAYGAIN_TRACK_GAIN tag on
        // each new track and apply via LoudnessEnhancer (negative gains
        // attenuate, positive boost). Cap to ±15 dB.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.replayGainEnabled,
                playbackConnection.playerState.map { it.title }.distinctUntilChanged()
            ) { enabled, _ -> enabled }
                .collectLatest { enabled ->
                    if (!enabled) return@collectLatest
                    // Read mediaUri on Main (MediaController access),
                    // then hop to IO for the blocking MMR read.
                    val mediaUri = withContext(Dispatchers.Main) {
                        playbackConnection.getPlayer()?.currentMediaItem?.mediaId
                    }
                    if (mediaUri.isNullOrBlank()) return@collectLatest
                    val mb = withContext(Dispatchers.IO) {
                        runCatching {
                            val mmr = android.media.MediaMetadataRetriever()
                            mmr.setDataSource(context, android.net.Uri.parse(mediaUri))
                            val raw = mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_GENRE
                            )
                            mmr.release()
                            val gainDb = raw?.let {
                                Regex("(-?\\d+\\.?\\d*)\\s*dB").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
                            } ?: 0.0
                            (gainDb * 100).toInt().coerceIn(-1500, 1500) to raw
                        }.getOrDefault(0 to null)
                    }
                    if (mb.first != 0) {
                        android.util.Log.i("PMP_DIAG", "ReplayGain mb=${mb.first} (from ${mb.second})")
                        setVolumeBoost(mb.first.coerceAtLeast(0))
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
        spotifyProvider.spotifyState
    ) { playerState, sleepRemaining, spotify ->
        val base = mapToUiState(playerState, sleepRemaining)
        if (spotify != null) overlaySpotifyState(base, spotify) else base
    }.stateIn(
        scope = viewModelScope,
        // Eagerly keeps the combiner running so navigation to the player
        // tab finds the latest state already mapped — eliminates the
        // brief WhileSubscribed initial-value flash that swapped the
        // layout between Expanded (audio default) and Compact (video).
        started = SharingStarted.Eagerly,
        initialValue = mapToUiState(playbackConnection.playerState.value, 0L)
    )

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
     */
    val artworkBytes: StateFlow<ByteArray?> = playbackConnection.playerState
        .map { it.artworkBytes }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = playbackConnection.playerState.value.artworkBytes
        )


    // ── Transport Controls (delegated to PlaybackConnection) ─────

    fun clearError() = playbackConnection.clearError()

    fun playPause() {
        if (isSpotifyActive) {
            viewModelScope.launch { spotifyProvider.togglePlayPause() }
        } else {
            playbackConnection.playPause()
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
        android.util.Log.i("PMP_DIAG", "VM.skipBack(${seconds}s)")
        if (isSpotifyActive) {
            val target = ((spotifyProvider.spotifyState.value?.positionMs ?: 0L) - seconds * 1000L)
                .coerceAtLeast(0L)
            viewModelScope.launch { spotifyProvider.seekTo(target) }
            return
        }
        playbackConnection.skipBack(seconds)
    }
    fun skipForward(seconds: Int) {
        android.util.Log.i("PMP_DIAG", "VM.skipForward(${seconds}s)")
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

    fun setPlaybackSpeed(speed: Float) = playbackConnection.setPlaybackSpeed(speed)

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

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs

        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
            }
            // Timer expired — pause playback (no alarm sound) and raise
            // a dismissible "Sleep timer finished" flag the UI shows.
            playbackConnection.pause()
            _sleepTimerRemainingMs.value = 0
            _sleepTimerExpired.value = true
            android.util.Log.i("PMP_DIAG", "SleepTimer expired — paused playback")
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = 0
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
            android.util.Log.i("PMP_DIAG", "SleepAtEndOfChapter delta=${deltaMs}ms target=${target}ms")
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
        when {
            _abLoopStart.value == null -> {
                _abLoopStart.value = currentPositionMsAnySource()
                android.util.Log.i("PMP_DIAG", "AB-loop A=${_abLoopStart.value}ms src=${if (isSpotifyActive) "spotify" else "local"}")
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
                android.util.Log.i("PMP_DIAG", "AB-loop B=${end}ms (loop active, src=${if (isSpotifyActive) "spotify" else "local"})")
            }
            else -> {
                _abLoopStart.value = null
                _abLoopEnd.value = null
                abLoopJob?.cancel()
                android.util.Log.i("PMP_DIAG", "AB-loop cleared")
            }
        }
    }

    // ── Pitch shift (independent of speed) ────────────────────────
    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()
    fun setPitch(value: Float) {
        val clamped = value.coerceIn(0.5f, 2.0f)
        _pitch.value = clamped
        // Re-apply current speed with new pitch (Media3 takes both in
        // PlaybackParameters).
        val speed = uiState.value.playbackSpeed
        playbackConnection.setPlaybackParametersWithPitch(speed, clamped)
    }

    // ── EnvironmentalReverb (heavier than PresetReverb) ─────────────
    private var environmentalReverb: android.media.audiofx.EnvironmentalReverb? = null
    // Most recent preset the user wants applied. Held independently
    // of the actual effect attachment because EnvironmentalReverb's
    // global-aux session (id = 0) can fail to attach on cold start
    // with AudioFlinger error -3 ("Cannot initialize effect engine")
    // before the audio pipeline has spun up. Whenever the player
    // (re)connects we retry-apply this remembered preset.
    @Volatile private var pendingReverbPreset: Int = 0
    @Volatile private var reverbAttachInFlight: Boolean = false

    init {
        // Reactive: when the user changes the reverb preset in
        // Settings, attach / detach / reconfigure the effect on the
        // current ExoPlayer audio session.
        viewModelScope.launch {
            settingsDataStore.reverbPreset.collect { preset ->
                pendingReverbPreset = preset
                applyReverbPreset(preset)
            }
        }
        // Re-apply on player (re)connect. EqualizerEffectController
        // uses the same hook to attach EQ; reverb piggy-backs because
        // the underlying audio session becomes valid at the same
        // moment.
        viewModelScope.launch {
            playbackConnection.playerFlow.collect { p ->
                if (p != null && pendingReverbPreset != 0 && environmentalReverb == null) {
                    applyReverbPreset(pendingReverbPreset)
                }
            }
        }
    }

    /**
     * Map our 0–5 setting onto custom EnvironmentalReverb parameters.
     * Levels chosen so the wet bus is actually audible on phone
     * speakers / earbuds: roomLevel = 0 (no master attenuation),
     * reverbLevel = +2000 (platform max), reflectionsLevel near 0
     * so early reflections — the cue the ear uses to identify "space"
     * — aren't muted.
     *
     * Robustness: on cold start AudioFlinger may reject the global
     * aux session with error -3. We retry up to 5×200 ms inside the
     * VM scope; if all attempts fail we leave [pendingReverbPreset]
     * set so the playerFlow observer above can retry on the next
     * MediaController connect.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyReverbPreset(preset: Int) {
        val exoPlayer = com.powermediaplayer.service.PlaybackService.getExoPlayer() ?: return
        try {
            if (preset == 0) {
                runCatching {
                    exoPlayer.setAuxEffectInfo(
                        androidx.media3.common.AuxEffectInfo(
                            androidx.media3.common.AuxEffectInfo.NO_AUX_EFFECT_ID, 0f
                        )
                    )
                }
                environmentalReverb?.runCatching { release() }
                environmentalReverb = null
                android.util.Log.i("PMP_DIAG", "Reverb off")
                return
            }
            data class ReverbSpec(
                val decayMs: Int,
                val decayHfRatio: Short,
                val reverbLevel: Short,
                val roomLevel: Short,
                val reflectionsLevel: Short,
                val density: Short,
                val diffusion: Short
            )
            val spec = when (preset) {
                1 -> ReverbSpec(decayMs = 1200, decayHfRatio = 830,
                    reverbLevel = 2000, roomLevel = 0,
                    reflectionsLevel = -300, density = 700, diffusion = 900)
                2 -> ReverbSpec(decayMs = 2600, decayHfRatio = 700,
                    reverbLevel = 2000, roomLevel = 0,
                    reflectionsLevel = -200, density = 800, diffusion = 1000)
                3 -> ReverbSpec(decayMs = 5500, decayHfRatio = 600,
                    reverbLevel = 2000, roomLevel = 0,
                    reflectionsLevel = -100, density = 900, diffusion = 1000)
                4 -> ReverbSpec(decayMs = 1700, decayHfRatio = 1200,
                    reverbLevel = 2000, roomLevel = 0,
                    reflectionsLevel = 0, density = 600, diffusion = 1000)
                5 -> ReverbSpec(decayMs = 10000, decayHfRatio = 500,
                    reverbLevel = 2000, roomLevel = 0,
                    reflectionsLevel = -100, density = 1000, diffusion = 1000)
                else -> return
            }
            val er = environmentalReverb ?: tryConstructEnvironmentalReverb()
            if (er == null) {
                // Construction failed; schedule a retry loop unless
                // one is already pending. Keep pendingReverbPreset
                // set so next playerFlow emission also tries.
                if (!reverbAttachInFlight) {
                    reverbAttachInFlight = true
                    viewModelScope.launch {
                        try {
                            for (attempt in 1..5) {
                                kotlinx.coroutines.delay(200)
                                val want = pendingReverbPreset
                                if (want == 0) return@launch
                                val built = tryConstructEnvironmentalReverb()
                                if (built != null) {
                                    applyReverbPreset(want)
                                    return@launch
                                }
                                android.util.Log.i(
                                    "PMP_DIAG",
                                    "Reverb retry $attempt/5 still pending preset=$want"
                                )
                            }
                        } finally {
                            reverbAttachInFlight = false
                        }
                    }
                }
                return
            }
            er.decayTime = spec.decayMs
            er.decayHFRatio = spec.decayHfRatio
            er.reverbLevel = spec.reverbLevel
            er.roomLevel = spec.roomLevel
            er.reflectionsLevel = spec.reflectionsLevel
            er.density = spec.density
            er.diffusion = spec.diffusion
            exoPlayer.setAuxEffectInfo(
                androidx.media3.common.AuxEffectInfo(er.id, 1.0f)
            )
            android.util.Log.i(
                "PMP_DIAG",
                "Reverb applied: preset=$preset decay=${spec.decayMs}ms reverbLvl=${spec.reverbLevel} auxId=${er.id}"
            )
        } catch (t: Throwable) {
            android.util.Log.w("PMP_DIAG", "EnvironmentalReverb apply failed", t)
        }
    }

    private fun tryConstructEnvironmentalReverb(): android.media.audiofx.EnvironmentalReverb? {
        return runCatching {
            android.media.audiofx.EnvironmentalReverb(0, 0).also {
                it.enabled = true
                environmentalReverb = it
            }
        }.onFailure {
            android.util.Log.w("PMP_DIAG", "EnvironmentalReverb construct failed (will retry)", it)
        }.getOrNull()
    }

    // ── LoudnessEnhancer volume boost ─────────────────────────────
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private val _volumeBoostMb = MutableStateFlow(0)
    val volumeBoostMb: StateFlow<Int> = _volumeBoostMb.asStateFlow()

    /** millibels of gain on top of normal volume. 0 = off; 2000 = +20 dB. */
    fun setVolumeBoost(milliBels: Int) {
        val clamped = milliBels.coerceIn(0, 2000)
        _volumeBoostMb.value = clamped
        // playbackConnection.getPlayer() returns a MediaController IPC
        // proxy — `as? ExoPlayer` always yields null. Reach the real
        // ExoPlayer via the same static accessor used by VideoSurface.
        val sessionId = com.powermediaplayer.service.PlaybackService
            .getExoPlayer()?.audioSessionId ?: 0
        if (sessionId == 0) return
        try {
            val le = loudnessEnhancer ?: android.media.audiofx.LoudnessEnhancer(sessionId).also {
                loudnessEnhancer = it
                it.enabled = true
            }
            le.setTargetGain(clamped)
        } catch (t: Throwable) {
            android.util.Log.w("PMP_DIAG", "LoudnessEnhancer setGain failed", t)
        }
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

    private fun mapToUiState(playerState: PlayerState, sleepRemainingMs: Long): PlayerUiState {
        val hasMedia = playerState.mediaItemCount > 0

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
            // Normalize all human-visible strings to repair UTF-8/Latin-1
            // mojibake (e.g. "Philosopherâ€™s" → "Philosopher's") and
            // collapse curly quotes / invisible formatting.
            title = TextNormalizer.normalize(playerState.title).ifEmpty { "No media loaded" },
            artist = TextNormalizer.normalize(playerState.artist),
            album = TextNormalizer.normalize(playerState.album),
            description = TextNormalizer.normalize(playerState.description),
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
            chapters = playerState.chapters.map { it.copy(title = TextNormalizer.normalize(it.title)) },
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
            mediaKind = inferMediaKind(playerState)
        )
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
