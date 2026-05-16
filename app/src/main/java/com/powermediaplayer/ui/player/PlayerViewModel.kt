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
     * UI can show a Snackbar. Polls the companion-object holder set by
     * PlaybackService whenever it zeros the effective crossfade ms.
     */
    val crossfadeAutoRevertReason: kotlinx.coroutines.flow.StateFlow<String?> =
        kotlinx.coroutines.flow.flow {
            var last: String? = null
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val r = com.powermediaplayer.service.PlaybackService.crossfadeAutoRevertReason
                if (r != last) { last = r; emit(r) }
                kotlinx.coroutines.delay(750)
            }
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

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
        // §C17 — online metadata enrichment. When enabled, on every
        // track change with at least one missing field, query the
        // configured provider and fill in the blanks. Throttled to a
        // single in-flight fetch via the flow's flatMapLatest.
        viewModelScope.launch {
            settingsDataStore.metadataEnrichmentEnabled
                .combine(playbackConnection.playerState) { enabled, ps -> enabled to ps }
                .filter { (enabled, ps) ->
                    enabled && ps.title.isNotBlank() && (
                        ps.artist.isBlank() || ps.album.isBlank()
                    )
                }
                .map { (_, ps) -> ps.title to ps.artist }
                .distinctUntilChanged()
                .collect { (title, artistHint) ->
                    val fetchYear = settingsDataStore.enrichFetchYear.first()
                    val fetchGenre = settingsDataStore.enrichFetchGenre.first()
                    val applyScope = settingsDataStore.enrichApplyScope.first()
                    val curState = playbackConnection.playerState.value
                    val hasArtist = curState.artist.isNotBlank()
                    val hasAlbum = curState.album.isNotBlank()
                    if (applyScope == "missing_only" && hasArtist && hasAlbum) return@collect
                    val provider = settingsDataStore.metadataEnrichmentProvider.first()
                    // §C17 A11.4 — cache key keyed on artist|title.
                    val cacheKey = "${provider}|" +
                        "${artistHint.lowercase()}|${title.lowercase()}"
                    val cached = withContext(Dispatchers.IO) {
                        runCatching { enrichmentCacheDao.get(cacheKey) }.getOrNull()
                    }
                    val res = if (cached != null) {
                        com.powermediaplayer.enrichment.EnrichmentResult(
                            title = cached.title,
                            artist = cached.artist,
                            album = cached.album,
                            year = cached.year,
                            genre = cached.genre
                        )
                    } else withContext(Dispatchers.IO) {
                        // §C17 LOCKED — MusicBrainz / Discogs / Both.
                        // "both" prefers MusicBrainz, falls through to
                        // Discogs only when MB returns null.
                        val fresh = when (provider) {
                            "discogs" -> discogsClient.lookupRecording(
                                title, artistHint.takeIf { it.isNotBlank() }
                            )
                            "both" -> musicBrainzClient.lookupRecording(
                                title, artistHint.takeIf { it.isNotBlank() }
                            ) ?: discogsClient.lookupRecording(
                                title, artistHint.takeIf { it.isNotBlank() }
                            )
                            else -> musicBrainzClient.lookupRecording(
                                title, artistHint.takeIf { it.isNotBlank() }
                            )
                        }
                        if (fresh != null) {
                            runCatching {
                                enrichmentCacheDao.put(
                                    com.powermediaplayer.data.db.entity.EnrichmentCacheEntity(
                                        cacheKey = cacheKey,
                                        provider = provider,
                                        title = fresh.title,
                                        artist = fresh.artist,
                                        album = fresh.album,
                                        year = fresh.year,
                                        genre = fresh.genre,
                                        artworkUrl = null,
                                        fetchedAtMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        fresh
                    } ?: return@collect
                    // Honor sub-toggles by zeroing fields the user
                    // disabled before patching state.
                    val gated = res.copy(
                        year = if (fetchYear) res.year else null,
                        genre = if (fetchGenre) res.genre else null
                    )
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "MusicBrainz hit title=$title artist=${gated.artist} " +
                            "album=${gated.album} year=${gated.year} genre=${gated.genre}"
                    )
                    // Patch the live player's MediaItem metadata with
                    // any non-null returned field. The lookup runs
                    // off-main; metadata mutation must hop back.
                    withContext(Dispatchers.Main) {
                        applyEnrichment(gated)
                    }
                }
        }
        // §C9 — on video play with no sidecar SRT, kick off the
        // OpenSubtitles auto-fetch. Throttled internally — repeat
        // plays of the same mediaUri don't re-hit the API. Skips
        // silently when not signed in.
        viewModelScope.launch {
            playbackConnection.playerState
                .map { it.title to (playbackConnection.getPlayer()?.currentMediaItem?.mediaId ?: "") }
                .distinctUntilChanged()
                .collect { (title, mediaId) ->
                    if (mediaId.isBlank() || title.isBlank()) return@collect
                    val isVideo = playbackConnection.playerState.value.videoWidth > 0
                    if (!isVideo) return@collect
                    val srt = withContext(Dispatchers.IO) {
                        runCatching {
                            subtitleAutoFetcher.fetchIfNeeded(mediaId, title)
                        }.getOrNull()
                    }
                    // §C9 — once the SRT lands on disk, attach it as a
                    // subtitle configuration on the live player. Without
                    // this the file would persist forever as inert.
                    // setSubtitleConfigurations rebuilds only the track
                    // surfaces, NOT the queue, so playback continues
                    // uninterrupted.
                    if (srt != null && srt.exists()) {
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "C9 SRT auto-attaching ${srt.absolutePath}"
                        )
                        attachSubtitle(srt)
                    }
                }
        }
        // §C18 auto-scan-on-import — when the toggle is on AND we have
        // no pre-scan row for the current track, kick a one-file scan
        // off Dispatchers.IO so the next play has a usable gain.
        viewModelScope.launch {
            settingsDataStore.replayGainAutoScan
                .combine(playbackConnection.playerState.map {
                    playbackConnection.getPlayer()?.currentMediaItem?.mediaId.orEmpty()
                }.distinctUntilChanged()) { auto, uri -> auto to uri }
                .collect { (auto, uri) ->
                    if (!auto || uri.isBlank()) return@collect
                    val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull()
                        ?: return@collect
                    withContext(Dispatchers.IO) {
                        if (replayGainDao.getForUri(uri) != null) return@withContext
                        runCatching {
                            replayGainScanner.scanSingle(
                                com.powermediaplayer.ui.library.MediaFileInfo(
                                    id = 0L, uri = parsed,
                                    title = playbackConnection.playerState.value.title,
                                    artist = "", album = "",
                                    duration = 0L, mimeType = "", size = 0L,
                                    dateModified = 0L, isVideo = false, albumArtUri = null
                                )
                            )
                        }
                    }
                }
        }

        // §C18 — apply ReplayGain to the live ExoPlayer. Source order:
        //   1. Embedded tag (replayGainTrackDb on PlayerState; NaN if
        //      the file ships without RG metadata).
        //   2. Pre-scan row (`replay_gain` Room table — populated by
        //      ReplayGainScanner via the "Scan now" button or auto-
        //      scan on import).
        //   3. None → volume = 1.0.
        viewModelScope.launch {
            playbackConnection.playerState
                .map { it.replayGainTrackDb to (playbackConnection.getPlayer()?.currentMediaItem?.mediaId ?: "") }
                .distinctUntilChanged()
                .combine(settingsDataStore.replayGainEnabled) { (db, uri), enabled ->
                    Triple(enabled, db, uri)
                }
                .collect { (enabled, embeddedDb, uri) ->
                    val player = com.powermediaplayer.service.PlaybackService
                        .getExoPlayer() ?: return@collect
                    val override = mediaOverrideRepo.activeOverride.value
                    // §C7 / A10.1 — per-file replayGainMode override
                    // wins over the global setting.
                    val mode = override?.replayGainMode
                        ?: settingsDataStore.replayGainMode.first()
                    val effectiveDb: Double = if (!embeddedDb.isNaN()) embeddedDb
                    else withContext(Dispatchers.IO) {
                        val row = if (uri.isBlank()) null else replayGainDao.getForUri(uri)
                        when {
                            row == null -> Double.NaN
                            // §C18 mode toggle — "album" prefers album gain
                            // when present, falls back to track. "track"
                            // (default) does the reverse.
                            mode == "album" && row.albumGainDb !=
                                com.powermediaplayer.data.db.entity.ReplayGainEntity.ABSENT ->
                                row.albumGainDb
                            mode == "album" && row.trackGainDb !=
                                com.powermediaplayer.data.db.entity.ReplayGainEntity.ABSENT ->
                                row.trackGainDb
                            row.trackGainDb !=
                                com.powermediaplayer.data.db.entity.ReplayGainEntity.ABSENT ->
                                row.trackGainDb
                            row.albumGainDb !=
                                com.powermediaplayer.data.db.entity.ReplayGainEntity.ABSENT ->
                                row.albumGainDb
                            else -> Double.NaN
                        }
                    }
                    val target = if (!enabled || effectiveDb.isNaN()) 1.0f
                    else (Math.pow(10.0, effectiveDb / 20.0).coerceIn(0.05, 4.0)).toFloat()
                    runCatching { player.volume = target }
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "ReplayGain applied enabled=$enabled embedded=$embeddedDb " +
                            "effective=$effectiveDb volume=$target"
                    )
                }
        }
    }

    /**
     * §C9 — attach an SRT file to the currently-playing MediaItem so
     * the standard Track Selection menu surfaces it. Implementation
     * note: Media3's MediaItem is immutable, so we rebuild the current
     * item with the additional [SubtitleConfiguration] and call
     * [setMediaItems] preserving position + queue index.
     */
    private fun attachSubtitle(srt: java.io.File) {
        val player = com.powermediaplayer.service.PlaybackService.getExoPlayer() ?: return
        val current = player.currentMediaItem ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val idx = player.currentMediaItemIndex
        val sub = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
            android.net.Uri.fromFile(srt)
        )
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en")
            .setLabel("OpenSubtitles (auto)")
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()
        val rebuilt = current.buildUpon()
            .setSubtitleConfigurations(listOf(sub))
            .build()
        runCatching {
            player.setMediaItem(rebuilt, pos)
            // Re-prepare so the new track surface registers.
            player.prepare()
        }
    }

    private fun applyEnrichment(res: com.powermediaplayer.enrichment.EnrichmentResult) {
        playbackConnection.patchPlayerStateMetadata(
            artist = res.artist.orEmpty(),
            album = res.album.orEmpty(),
            year = res.year ?: 0,
            genre = res.genre.orEmpty()
        )
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
        // Persist current position every 5s while playing — used by the
        // Last Played tab + cold-start resume. One small UPDATE per tick.
        // MediaController access MUST happen on the application main
        // thread (Media3 verifyApplicationThread); the DB write hops
        // off main via viewModelScope.launch(Dispatchers.IO).
        //
        // Also acts as a safety-net for the bookmark→Last-Played mirror:
        // if no session was wired up by either the cold-start adoptSession
        // branch below OR a Library/Cloud/LastPlayed tap, synthesise one
        // here from the current MediaItem so subsequent bookmarks mirror.
        viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5_000)
                val player = playbackConnection.getPlayer() ?: continue
                val item = player.currentMediaItem ?: continue
                val mediaUri = item.mediaId.takeIf { it.isNotBlank() } ?: continue
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playing = player.isPlaying
                if (!playing) continue
                // Capture metadata on Main before hopping to IO.
                val title = item.mediaMetadata.title?.toString()
                val artist = item.mediaMetadata.artist?.toString()
                val artwork = item.mediaMetadata.artworkUri?.toString()
                val duration = player.duration.coerceAtLeast(0L)
                launch(Dispatchers.IO) {
                    runCatching { lastPlayedRepo.updatePositionByUri(mediaUri, pos) }
                    if (lastPlayedRepo.currentSessionId.value == null) {
                        runCatching {
                            lastPlayedRepo.recordPlay(
                                com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                                    mediaUri = mediaUri,
                                    title = title?.takeIf { it.isNotBlank() }
                                        ?: mediaUri.substringAfterLast('/'),
                                    subtitle = artist ?: "",
                                    artworkUri = artwork,
                                    source = "LOCAL",
                                    mediaKindOrdinal = 0,
                                    lastPositionMs = pos,
                                    durationMs = duration,
                                    lastPlayedAt = System.currentTimeMillis()
                                )
                            )
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "5s-tick synthesised session for uri=$mediaUri " +
                                    "(no cold-start adoptSession or tap-recordPlay fired)"
                            )
                        }
                    }
                }
            }
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
                        abOverrides[uri]?.let { (a, b) ->
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
                        }
                    }
                }
        }

        // Cold-start resume + notification-tap session adoption.
        //
        // Two distinct entry points share this block:
        //  (1) Cold start with no MediaItem loaded — restore the most
        //      recent LOCAL row paused at its saved position. Cloud
        //      items skipped (Drive needs token refresh; Spotify needs
        //      Connect device).
        //  (2) Notification-tap resume after process kill — the service
        //      has already loaded a MediaItem. We just need to adopt the
        //      matching recent row's session id so subsequent bookmarks
        //      mirror to its snapshot.
        //
        // Both paths call lastPlayedRepo.adoptSession(recent.id) so the
        // bookmark→Last-Played mirror works without creating a duplicate
        // Recents row.
        viewModelScope.launch(Dispatchers.Main) {
            // Single-fire guard — multiple PlayerViewModel instances
            // (PlayerScreen + MiniPlayerBar) each call this init block
            // and were both running the cold-start `when {}`. The
            // second instance saw `currentMediaUri == null` due to a
            // service-connect race AND ran the cold-start branch with
            // `playWhenReady = false`, pausing whatever the FIRST
            // instance's adopt-session branch had already accepted as
            // playing — bug: videos opened via auto-resume immediately
            // paused on launch. compareAndSet is atomic + thread-safe
            // and shared across every VM instance in the same process.
            if (!coldStartGuard.compareAndSet(false, true)) return@launch
            kotlinx.coroutines.delay(800) // wait for service connection
            // Don't clobber an active Spotify mirror.
            if (spotifyProvider.spotifyState.value != null) return@launch
            // Defensive: if a session is already adopted (e.g. user
            // tapped a Library row in the 800 ms grace window) skip.
            if (lastPlayedRepo.currentSessionId.value != null) return@launch
            val recent = withContext(Dispatchers.IO) {
                runCatching { lastPlayedRepo.mostRecent() }.getOrNull()
            } ?: return@launch
            val player = playbackConnection.getPlayer() ?: return@launch
            val currentMediaUri = player.currentMediaItem?.mediaId
            when {
                // Notification-tap resume: player already has the
                // matching item loaded. Adopt session, don't reload.
                currentMediaUri != null && currentMediaUri == recent.mediaUri -> {
                    if (lastPlayedRepo.currentSessionId.value == null) {
                        lastPlayedRepo.adoptSession(recent.id)
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Adopted session ${recent.id} for already-loaded MediaItem uri=$currentMediaUri"
                        )
                    }
                }
                // Cold start: nothing loaded. Restore the recent LOCAL
                // or DRIVE item paused, then adopt its session.
                // Drive (SAF content://) URIs survive process death via
                // the persistable-URI grants taken when the picker / SAF
                // tree was first authorised. Spotify is still skipped —
                // Connect needs an active device chosen explicitly.
                currentMediaUri == null && (recent.source == "LOCAL" || recent.source == "DRIVE") -> {
                    runCatching {
                        val uri = android.net.Uri.parse(recent.mediaUri)
                        // Parser off-Main — for multi-GB Drive
                        // audiobooks the synchronous MP4 box scan
                        // routinely blocked the cold-start coroutine
                        // > 5 s. Same fix shape as LastPlayedViewModel
                        // .playLocalAt and LibraryViewModel.playSingle.
                        val chapterExtras = withContext(Dispatchers.IO) {
                            runCatching {
                                com.powermediaplayer.util.M4bChapterParser
                                    .extractChaptersAsBundle(context, uri)
                            }.getOrDefault(android.os.Bundle())
                        }
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
                                    .setExtras(chapterExtras)
                                    .build()
                            )
                            .build()
                        playbackConnection.setMediaItems(listOf(item), 0)
                        // Apply user-configured backoff so the user lands
                        // a bit BEFORE the saved position for context.
                        val backoffSec = runCatching {
                            settingsDataStore.coldStartResumeBackoffSec.first()
                        }.getOrNull() ?: 5
                        val target = (recent.lastPositionMs - backoffSec * 1000L).coerceAtLeast(0L)
                        playbackConnection.seekTo(target)
                        player.playWhenReady = false
                        lastPlayedRepo.adoptSession(recent.id)
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Cold-start restored '${recent.title}' [src=${recent.source}] @ ${target}ms (saved=${recent.lastPositionMs}ms, backoff=${backoffSec}s, session ${recent.id})"
                        )
                    }
                }
                // Spotify recent / player has a different media — leave
                // session null. Spotify needs an active Connect device,
                // chosen by the user; the 5s tick will synthesise a
                // session if playback continues from another entry path.
            }
        }
        // ReplayGain: when enabled, read REPLAYGAIN_TRACK_GAIN on each
        // new track and apply across the full ±15 dB range. Positive
        // gains route via LoudnessEnhancer; negative gains route via
        // ExoPlayer.volume (LoudnessEnhancer cannot attenuate).
        // When the toggle is turned off OR a track has no tag we
        // RESET both paths so a previous attenuation doesn't leak.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.replayGainEnabled,
                playbackConnection.playerState.map { it.title }.distinctUntilChanged()
            ) { enabled, _ -> enabled }
                .collectLatest { enabled ->
                    if (!enabled) {
                        // Reset both paths so disabling the toggle
                        // restores unmodified output.
                        setVolumeBoost(0)
                        com.powermediaplayer.service.PlaybackService
                            .setReplayGainAttenuation(1.0f)
                        return@collectLatest
                    }
                    val mediaUri = withContext(Dispatchers.Main) {
                        playbackConnection.getPlayer()?.currentMediaItem?.mediaId
                    }
                    if (mediaUri.isNullOrBlank()) {
                        setVolumeBoost(0)
                        com.powermediaplayer.service.PlaybackService
                            .setReplayGainAttenuation(1.0f)
                        return@collectLatest
                    }
                    val mbAndRaw = withContext(Dispatchers.IO) {
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
                    val mb = mbAndRaw.first
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "ReplayGain mb=$mb (raw='${mbAndRaw.second}')"
                    )
                    if (mb >= 0) {
                        // Boost path. Reset attenuation first.
                        com.powermediaplayer.service.PlaybackService
                            .setReplayGainAttenuation(1.0f)
                        setVolumeBoost(mb)
                    } else {
                        // Attenuation path. linearGain = 10^(dB/20)
                        // = 10^(mb / 2000). Floor at 0.05 so the
                        // signal doesn't go fully silent on a
                        // pathological -∞ tag.
                        setVolumeBoost(0)
                        val factor = Math.pow(10.0, mb / 2000.0)
                            .toFloat().coerceIn(0.05f, 1.0f)
                        com.powermediaplayer.service.PlaybackService
                            .setReplayGainAttenuation(factor)
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
        playbackConnection.setPlaybackSpeed(speed)
        // §C7 slim — persist per-file speed override so the next time
        // the user opens this track it resumes at the chosen speed.
        // Speed 1.0 (default) clears any existing override.
        val uri = playbackConnection.getPlayer()?.currentMediaItem?.mediaId
        if (!uri.isNullOrBlank()) {
            viewModelScope.launch {
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
            // §C7 — combined flow: per-file override wins over global.
            mediaOverrideRepo.withOverrideInt(
                settingsDataStore.reverbPreset
            ) { it.reverbPreset }.collect { preset ->
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
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Reverb off")
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
                                com.powermediaplayer.util.Diag.i(
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
            // Wet/dry mix — user-controllable via Settings (or the
            // Audio Effects popup). 0.0 = dry only (effect inaudible),
            // 1.0 = full preset wetness. Read synchronously from the
            // already-cached DataStore to keep this binder-thread path
            // off the IO dispatcher.
            val wetMix = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(50) {
                    settingsDataStore.reverbWetMix.first()
                }
            } ?: 1.0f
            exoPlayer.setAuxEffectInfo(
                androidx.media3.common.AuxEffectInfo(er.id, wetMix.coerceIn(0f, 1f))
            )
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "Reverb applied: preset=$preset decay=${spec.decayMs}ms reverbLvl=${spec.reverbLevel} auxId=${er.id}"
            )
        } catch (t: Throwable) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "EnvironmentalReverb apply failed", t)
        }
    }

    private fun tryConstructEnvironmentalReverb(): android.media.audiofx.EnvironmentalReverb? {
        return runCatching {
            android.media.audiofx.EnvironmentalReverb(0, 0).also {
                it.enabled = true
                environmentalReverb = it
            }
        }.onFailure {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "EnvironmentalReverb construct failed (will retry)", it)
        }.getOrNull()
    }

    // ── LoudnessEnhancer volume boost ─────────────────────────────
    // _volumeBoostMb backing field is declared above the init block.
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = 0
    val volumeBoostMb: StateFlow<Int> = _volumeBoostMb.asStateFlow()

    /** millibels of gain on top of normal volume. 0 = off; 2000 = +20 dB. */
    fun setVolumeBoost(milliBels: Int) {
        val clamped = milliBels.coerceIn(0, 2000)
        _volumeBoostMb.value = clamped
        // Boost is off — never attach an effect. (Earlier versions
        // attached LoudnessEnhancer eagerly even at gain=0; on some
        // Samsung devices setTargetGain(0) returns INVALID_OPERATION
        // until the effect is fully primed, spamming the log on every
        // mediaItemTransition. Lazy-attach only when the user actually
        // wants boost.)
        if (clamped == 0) {
            disposeLoudnessEnhancer()
            return
        }
        // playbackConnection.getPlayer() returns a MediaController IPC
        // proxy — `as? ExoPlayer` always yields null. Reach the real
        // ExoPlayer via the same static accessor used by VideoSurface.
        val sessionId = com.powermediaplayer.service.PlaybackService
            .getExoPlayer()?.audioSessionId ?: 0
        if (sessionId == 0) {
            disposeLoudnessEnhancer()
            return
        }
        // Audio session id can change across track transitions (Media3
        // swaps the AudioTrack on some transitions). Rebuild on change.
        if (loudnessEnhancerSessionId != sessionId) {
            disposeLoudnessEnhancer()
        }
        try {
            val le = loudnessEnhancer
                ?: android.media.audiofx.LoudnessEnhancer(sessionId).also {
                    loudnessEnhancer = it
                    loudnessEnhancerSessionId = sessionId
                    it.enabled = true
                }
            le.setTargetGain(clamped)
        } catch (t: Throwable) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "LoudnessEnhancer setGain failed", t)
            disposeLoudnessEnhancer()
        }
    }

    private fun disposeLoudnessEnhancer() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        loudnessEnhancerSessionId = 0
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
            year = playerState.year,
            genre = TextNormalizer.normalize(playerState.genre),
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

    private companion object {
        // Conservative whitelist matching the default Cast receiver's
        // documented support. .3gp / .3gpp omitted on purpose — receiver
        // support varies by device generation; keeping the list tight
        // means false-negatives ("works but greyed out") rather than
        // false-positives ("looks castable, then fails on the TV").
        private val CASTABLE_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm")

        /**
         * Process-global single-fire guard for the cold-start resume
         * coroutine. Multiple PlayerViewModel instances (PlayerScreen
         * + MiniPlayerBar) each run the init block; without this,
         * the second instance can race the service-connect and run
         * the cold-start `when {}` 's "nothing-loaded" branch which
         * sets playWhenReady=false on a track the first instance
         * already adopted as playing — videos paused immediately
         * after auto-resume.
         */
        val coldStartGuard = java.util.concurrent.atomic.AtomicBoolean(false)
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
