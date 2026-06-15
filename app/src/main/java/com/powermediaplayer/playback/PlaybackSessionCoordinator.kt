package com.powermediaplayer.playback

import android.content.Context
import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.service.PlaybackConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Owns playback-session side effects exactly once per process. These
 * blocks lived in PlayerViewModel.init — with up to three coexisting VM
 * instances (player screen, mini-bar, floating player) every collector,
 * Room write and network lookup ran in triplicate (audit 3.1/8.4). The
 * blocks below are moved VERBATIM from the ViewModel (vc32 lineage,
 * including ResumeGate token flow, the reverse-cache tick skip and the
 * Spotify-mirror branches); only the owning scope changed.
 *
 * ViewModels remain pure UI adapters; [start] is idempotent and ignited
 * from MainActivity right after PlaybackConnection.connect().
 */
@Singleton
class PlaybackSessionCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:com.powermediaplayer.di.ApplicationScope private val scope: CoroutineScope,
    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: SpotifyProvider,
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    private val mediaOverrideRepo: com.powermediaplayer.data.repository.MediaOverrideRepository,
    private val subtitleAutoFetcher: com.powermediaplayer.subtitles.SubtitleAutoFetcher,
    private val replayGainDao: com.powermediaplayer.data.db.dao.ReplayGainDao,
    private val replayGainScanner: com.powermediaplayer.replaygain.ReplayGainScanner,
    private val enrichmentCacheDao: com.powermediaplayer.data.db.dao.EnrichmentCacheDao
) {

    private val musicBrainzClient =
        com.powermediaplayer.enrichment.MusicBrainzClient()
    private val discogsClient =
        com.powermediaplayer.enrichment.DiscogsClient()

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        startEnrichment()
        startSubtitleAutoFetch()
        startReplayGainAutoScan()
        startReplayGainApply()
        startPositionPersistTick()
        startBackgroundPositionSave()
        startColdStartRestore()
    }

    private fun startBackgroundPositionSave() {
        // The 5s persist tick alone loses the last <5s before the app is
        // closed AND saves nothing for a play shorter than one tick — both
        // make a short listen resume from 0. Persist the current position
        // immediately when the app is backgrounded (which precedes most
        // swipe-aways / system kills) so the resume spot is current.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    // Spotify mirror keeps a stale local item loaded — the 5s
                    // tick persists the Spotify row by its own trackUri; don't
                    // overwrite it with the stale player position here.
                    if (spotifyProvider.spotifyState.value != null) return
                    val player = playbackConnection.getPlayer() ?: return
                    val item = player.currentMediaItem ?: return
                    val mediaUri = item.mediaId.takeIf { it.isNotBlank() } ?: return
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (pos <= 0L) return
                    val path = item.localConfiguration?.uri?.path ?: ""
                    if (path.contains("/reverse-cache/")) return
                    scope.launch(Dispatchers.IO) {
                        runCatching { lastPlayedRepo.updatePositionByUri(mediaUri, pos) }
                    }
                }
            }
        )
    }

    private fun startEnrichment() {
        // §C17 — online metadata enrichment. When enabled, on every
        // track change with at least one missing field, query the
        // configured provider and fill in the blanks. Throttled to a
        // single in-flight fetch via the flow's flatMapLatest.
        scope.launch {
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
    }

    private fun startSubtitleAutoFetch() {
        // §C9 — on video play with no sidecar SRT, kick off the
        // OpenSubtitles auto-fetch. Throttled internally — repeat
        // plays of the same mediaUri don't re-hit the API. Skips
        // silently when not signed in.
        scope.launch {
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
    }

    private fun startReplayGainAutoScan() {
        // §C18 auto-scan-on-import — when the toggle is on AND we have
        // no pre-scan row for the current track, kick a one-file scan
        // off Dispatchers.IO so the next play has a usable gain.
        scope.launch {
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
    }

    /**
     * MERGED ReplayGain pipeline (audit 3.6 + 1.6): sources from the old
     * pipeline A (embedded replayGainTrackDb on PlayerState, Room
     * pre-scan rows, album/track mode w/ per-file override) and sinks
     * from the old pipeline B (the T264 channel split: attenuation
     * factor through the service mixer for negative gain, RG-boost mB
     * through the chain for positive gain). The old A wrote
     * player.volume directly — silently clamped at 1.0 by ExoPlayer and
     * racing the crossfade factor product; the old B re-opened every
     * track with MediaMetadataRetriever to sniff a "dB" substring out of
     * GENRE. Both defects end here. RG-off resets ONLY the RG channels —
     * never the user's boost (T259 fix, preserved by construction).
     */
    private fun startReplayGainApply() {
        scope.launch {
            playbackConnection.playerState
                .map { it.replayGainTrackDb to (playbackConnection.getPlayer()?.currentMediaItem?.mediaId ?: "") }
                .distinctUntilChanged()
                .combine(settingsDataStore.replayGainEnabled) { (db, uri), enabled ->
                    Triple(enabled, db, uri)
                }
                .collectLatest { (enabled, embeddedDb, uri) ->
                    if (!enabled || uri.isBlank()) {
                        com.powermediaplayer.service.PlaybackService.setReplayGainBoostMb(0)
                        com.powermediaplayer.service.PlaybackService.setReplayGainAttenuation(1.0f)
                        return@collectLatest
                    }
                    val override = mediaOverrideRepo.activeOverride.value
                    // §C7 / A10.1 — per-file replayGainMode override wins
                    // over the global setting.
                    val mode = override?.replayGainMode
                        ?: settingsDataStore.replayGainMode.first()
                    val effectiveDb: Double = if (!embeddedDb.isNaN()) embeddedDb
                    else withContext(Dispatchers.IO) {
                        val row = replayGainDao.getForUri(uri)
                        when {
                            row == null -> Double.NaN
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
                    if (effectiveDb.isNaN()) {
                        com.powermediaplayer.service.PlaybackService.setReplayGainBoostMb(0)
                        com.powermediaplayer.service.PlaybackService.setReplayGainAttenuation(1.0f)
                        return@collectLatest
                    }
                    val mb = (effectiveDb * 100).toInt().coerceIn(-1500, 1500)
                    if (mb >= 0) {
                        com.powermediaplayer.service.PlaybackService.setReplayGainAttenuation(1.0f)
                        com.powermediaplayer.service.PlaybackService.setReplayGainBoostMb(mb)
                    } else {
                        com.powermediaplayer.service.PlaybackService.setReplayGainBoostMb(0)
                        val factor = Math.pow(10.0, mb / 2000.0)
                            .toFloat().coerceIn(0.05f, 1.0f)
                        com.powermediaplayer.service.PlaybackService.setReplayGainAttenuation(factor)
                    }
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "ReplayGain applied enabled=$enabled embedded=$embeddedDb " +
                            "effective=$effectiveDb mb=$mb"
                    )
                }
        }
    }

    private fun startPositionPersistTick() {
        // Persist current position every 5s while playing — used by the
        // Last Played tab + cold-start resume. One small UPDATE per tick.
        // MediaController access MUST happen on the application main
        // thread (Media3 verifyApplicationThread); the DB write hops
        // off main via scope.launch(Dispatchers.IO).
        //
        // Also acts as a safety-net for the bookmark→Last-Played mirror:
        // if no session was wired up by either the cold-start adoptSession
        // branch below OR a Library/Cloud/LastPlayed tap, synthesise one
        // here from the current MediaItem so subsequent bookmarks mirror.
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5_000)
                // vc32: during a Spotify mirror the LOCAL player is
                // paused on a stale item — Spotify rows never got their
                // position persisted at all.
                // Persist the MIRROR's position against the Spotify row's
                // mediaUri (spotify:track:… — matchable by
                // updatePositionByUri) instead.
                val spot = spotifyProvider.spotifyState.value
                if (spot != null) {
                    if (spot.isPlaying && spot.trackUri.isNotBlank()) {
                        launch(Dispatchers.IO) {
                            runCatching {
                                lastPlayedRepo.updatePositionByUri(
                                    spot.trackUri, spot.positionMs.coerceAtLeast(0L)
                                )
                            }
                        }
                    }
                    continue
                }
                val player = playbackConnection.getPlayer() ?: continue
                val item = player.currentMediaItem ?: continue
                val mediaUri = item.mediaId.takeIf { it.isNotBlank() } ?: continue
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playing = player.isPlaying
                if (!playing) continue
                // Reverse mode is ephemeral: its positions live on a
                // MIRRORED timeline (1:00 reversed = 9:00 forward of a
                // 10-minute file) but the row is keyed by the ORIGINAL
                // uri — persisting them would corrupt the forward resume
                // spot. The reversed temp wav identifies the mode.
                val playbackPath = item.localConfiguration?.uri?.path ?: ""
                if (playbackPath.contains("/reverse-cache/")) continue
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
    }

    private fun startColdStartRestore() {
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
        scope.launch(Dispatchers.Main) {
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
            if (!coldStartGuard.compareAndSet(false, true)) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start", reason = "guard-already-fired (other VM instance won)"
                )
                return@launch
            }
            // vc32: token taken BEFORE the grace delay so any user
            // play during it supersedes the cold-start restore instead of
            // racing it.
            val gateToken = com.powermediaplayer.playback.ResumeGate.begin()
            try {
            kotlinx.coroutines.delay(800) // wait for service connection
            if (spotifyProvider.spotifyState.value != null) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start", reason = "spotify-mirror-active → skip"
                )
                return@launch
            }
            if (lastPlayedRepo.currentSessionId.value != null) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start",
                    reason = "session-already-adopted by Library/Cloud/LastPlayed tap → skip"
                )
                return@launch
            }
            // User-controllable: the launch restore can be switched off in
            // Settings (Playback → Restore last played on launch). The
            // Bluetooth/headphone auto-resume toggles are unaffected —
            // they act on whatever is already loaded, which is nothing
            // when this is off, so they no-op safely.
            val restoreEnabled = runCatching {
                settingsDataStore.restoreLastOnLaunch.first()
            }.getOrDefault(true)
            if (!restoreEnabled) {
                // The playback service can outlive the UI process, so a
                // previously-loaded PAUSED item may still sit in the
                // player — with the toggle off the user expects a clean
                // launch, so clear it. Actively PLAYING audio is never
                // touched (reopening mid-listen must not stop music).
                val leftover = playbackConnection.getPlayer()
                if (leftover != null && !leftover.isPlaying &&
                    leftover.mediaItemCount > 0
                ) {
                    runCatching { leftover.clearMediaItems() }
                    com.powermediaplayer.diag.DiagLog.dec(
                        branch = "cold-start",
                        reason = "restoreLastOnLaunch=false → cleared paused leftover"
                    )
                } else {
                    com.powermediaplayer.diag.DiagLog.dec(
                        branch = "cold-start", reason = "restoreLastOnLaunch=false → skip"
                    )
                }
                return@launch
            }
            val recent = withContext(Dispatchers.IO) {
                runCatching { lastPlayedRepo.mostRecent() }.getOrNull()
            }
            if (recent == null) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start", reason = "no-recent-row → nothing to restore"
                )
                return@launch
            }
            val player = playbackConnection.getPlayer()
            if (player == null) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start", reason = "player-null after 800ms grace → skip"
                )
                return@launch
            }
            val currentMediaUri = player.currentMediaItem?.mediaId
            com.powermediaplayer.diag.DiagLog.dec(
                branch = "cold-start",
                reason = "evaluating: currentMediaUri=${com.powermediaplayer.diag.DiagLog.hash(currentMediaUri)} " +
                    "recent.source=${recent.source} recent.id=${recent.id} " +
                    "recent.uri=${com.powermediaplayer.diag.DiagLog.hash(recent.mediaUri)}"
            )
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
                    // vc32: banner over the cold-start parse +
                    // restore. runCatching swallows, so the post-clear
                    // below is finally-equivalent.
                    playbackConnection.setCloudFetchInProgress(true)
                    runCatching {
                        if (!com.powermediaplayer.playback.ResumeGate.isCurrent(gateToken)) {
                            com.powermediaplayer.diag.DiagLog.dec(
                                branch = "cold-start",
                                reason = "superseded by a user play intent — abort restore"
                            )
                            return@runCatching
                        }
                        val uri = android.net.Uri.parse(recent.mediaUri)
                        // vc32: the launch restore must never parse a
                        // remote file inline — on a slow network that
                        // holds the loading banner for minutes and its
                        // gate token blocks user taps. Remote →
                        // disk-cached chapters or none (they fill in when
                        // the user actively resumes the item); local →
                        // parse off-Main as before (ms-fast, cached).
                        val chapterExtras = withContext(Dispatchers.IO) {
                            if (com.powermediaplayer.util.M4bChapterParser.isRemote(uri)) {
                                com.powermediaplayer.util.M4bChapterParser
                                    .cachedOnly(context, uri) ?: android.os.Bundle()
                            } else {
                                runCatching {
                                    com.powermediaplayer.util.M4bChapterParser
                                        .extractChaptersAsBundle(context, uri)
                                }.getOrDefault(android.os.Bundle())
                            }
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
                        // Paused by default; "Auto-play on launch" starts
                        // playback immediately from the saved spot instead.
                        val autoplay = runCatching {
                            settingsDataStore.autoplayOnLaunch.first()
                        }.getOrDefault(false)
                        // Apply user-configured backoff so the user lands
                        // a bit BEFORE the saved position for context.
                        val backoffSec = runCatching {
                            settingsDataStore.coldStartResumeBackoffSec.first()
                        }.getOrNull() ?: 5
                        val backoffMs = backoffSec * 1000L
                        // The "land a bit earlier for context" backoff must not
                        // swallow the whole resume: for a saved position shorter
                        // than the backoff, resume AT the saved spot rather than
                        // clamping to 0 (which restarted short listens from the
                        // beginning — the reported local/video "starts at 0" bug).
                        val target = if (recent.lastPositionMs > backoffMs)
                            recent.lastPositionMs - backoffMs
                        else
                            recent.lastPositionMs.coerceAtLeast(0L)
                        playbackConnection.setMediaItems(
                            listOf(item), 0, playWhenReady = autoplay,
                            startPositionMs = target
                        )
                        lastPlayedRepo.adoptSession(recent.id)
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Cold-start restored '${recent.title}' [src=${recent.source}] @ ${target}ms (saved=${recent.lastPositionMs}ms, backoff=${backoffSec}s, session ${recent.id})"
                        )
                    }.onFailure { t ->
                        com.powermediaplayer.util.Diag.w(
                            "PMP_DIAG", "Cold-start restore FAILED mid-way", t
                        )
                    }
                    playbackConnection.setCloudFetchInProgress(false)
                }
                // Auto-resume Spotify (user opt-in): play the saved track on
                // the last/available Connect device and seek to the saved
                // spot. Connect needs a reachable device, so this fails
                // gracefully (the picker stays available) when none is found.
                currentMediaUri == null && recent.source == "SPOTIFY" -> {
                    playbackConnection.setCloudFetchInProgress(true)
                    runCatching {
                        if (!com.powermediaplayer.playback.ResumeGate.isCurrent(gateToken)) {
                            return@runCatching
                        }
                        val played = spotifyProvider
                            .playTrackOnConnectDevice(recent.mediaUri)
                        if (played.isSuccess) {
                            if (recent.lastPositionMs > 0L) {
                                spotifyProvider.seekTo(recent.lastPositionMs)
                            }
                            val autoplay = runCatching {
                                settingsDataStore.autoplayOnLaunch.first()
                            }.getOrDefault(false)
                            if (!autoplay) runCatching { spotifyProvider.pause() }
                            lastPlayedRepo.adoptSession(recent.id)
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Cold-start Spotify resumed '${recent.title}' @ " +
                                    "${recent.lastPositionMs}ms (session ${recent.id})"
                            )
                        } else {
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Cold-start Spotify resume failed (no Connect " +
                                    "device?) — left to picker"
                            )
                        }
                    }
                    playbackConnection.setCloudFetchInProgress(false)
                }
            }
            } finally {
                com.powermediaplayer.playback.ResumeGate.end(gateToken)
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

    companion object {
        /**
         * Process-global single-fire guard for the cold-start resume
         * coroutine (moved with the restore block; PlaybackConnection
         * resets it when the MediaController disconnects so a service
         * restart can re-attempt restore).
         */
        val coldStartGuard = java.util.concurrent.atomic.AtomicBoolean(false)

        fun resetColdStartGuard() {
            if (coldStartGuard.compareAndSet(true, false)) {
                com.powermediaplayer.diag.DiagLog.dec(
                    branch = "cold-start",
                    reason = "guard reset by PlaybackConnection (service disconnected)"
                )
            }
        }
    }
}
