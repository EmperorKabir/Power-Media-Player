package com.powermediaplayer.playback

import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.di.ApplicationScope
import com.powermediaplayer.service.PlaybackConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PH8 — the ONE A-B loop engine.
 *
 * Before: every PlayerViewModel instance (the Player screen, each route's
 * MiniPlayerBar, the floating video mini player, VideoSurface — Hilt scopes
 * one per navigation destination) owned its own A/B markers and its own
 * 250 ms poller, and each restored the saved loop for the current track from
 * DataStore. Clearing the loop in the Player screen only cleared THAT
 * instance; the others kept seeking back to A until the track changed — the
 * "A-B bug, had to switch track" report.
 *
 * Now: markers, poller, persistence and the per-track restore live here
 * (@Singleton, app scope); the view models delegate.
 */
@Singleton
class AbLoopEngine @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val spotifyProvider: SpotifyProvider,
    private val settings: SettingsDataStore,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _start = MutableStateFlow<Long?>(null)
    private val _end = MutableStateFlow<Long?>(null)
    val start: StateFlow<Long?> = _start.asStateFlow()
    val end: StateFlow<Long?> = _end.asStateFlow()

    private var pollJob: Job? = null
    /** The mediaId the current markers belong to (null = none). */
    private var loopUri: String? = null
    private var restoreJob: Job? = null

    private val isSpotifyActive: Boolean
        get() = spotifyProvider.spotifyState.value != null

    /** Idempotent: starts the per-track restore collector once. */
    fun start() {
        if (restoreJob?.isActive == true) return
        restoreJob = appScope.launch {
            playbackConnection.playerState
                .map { it.title to (playbackConnection.getPlayer()?.currentMediaItem?.mediaId ?: "") }
                .distinctUntilChanged()
                .collect { (_, uri) -> onTrack(uri) }
        }
    }

    private suspend fun onTrack(uri: String) {
        if (uri.isBlank()) return
        if (isSpotifyActive) return
        val saved = runCatching { settings.abLoopOverrides.first() }.getOrNull().orEmpty()[uri]
        if (saved != null) {
            val (a, b) = saved
            if (loopUri != uri || _start.value != a || _end.value != b) {
                activate(uri, a, b, persist = false)
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Restored saved A-B loop $a..${b}ms for uri=$uri")
            }
        } else if (_start.value != null || _end.value != null) {
            // The new track has NO saved loop — drop any loop carried over in memory
            // from the previous track so its markers don't keep seeking this one.
            deactivate(clearPersisted = false, why = "track changed")
        }
    }

    private fun currentPositionMs(): Long =
        if (isSpotifyActive) spotifyProvider.spotifyState.value?.positionMs ?: 0L
        else playbackConnection.playerState.value.currentPosition

    private fun seekTo(positionMs: Long) {
        if (isSpotifyActive) appScope.launch { runCatching { spotifyProvider.seekTo(positionMs) } }
        else playbackConnection.seekTo(positionMs)
    }

    private fun activate(uri: String?, a: Long, b: Long, persist: Boolean) {
        _start.value = a
        _end.value = b
        loopUri = uri
        pollJob?.cancel()
        // Spotify Connect /seek is a 300-500 ms round trip → poll less aggressively.
        val pollInterval = if (isSpotifyActive) 750L else 250L
        pollJob = appScope.launch {
            while (isActive) {
                delay(pollInterval)
                val sa = _start.value ?: break
                val sb = _end.value ?: break
                if (currentPositionMs() >= sb) seekTo(sa)
            }
        }
        if (persist && !uri.isNullOrBlank() && !isSpotifyActive) {
            appScope.launch(Dispatchers.IO) { runCatching { settings.setAbLoopOverride(uri, a, b) } }
        }
    }

    private fun deactivate(clearPersisted: Boolean, why: String) {
        val uri = loopUri
        _start.value = null
        _end.value = null
        loopUri = null
        pollJob?.cancel()
        pollJob = null
        if (clearPersisted && !uri.isNullOrBlank()) {
            appScope.launch(Dispatchers.IO) { runCatching { settings.clearAbLoopOverride(uri) } }
        }
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop cleared ($why)")
    }

    /**
     * Three-state toggle: first tap captures A; second tap captures B (at least 1 s
     * after A, else both reset) and starts looping; third tap clears the loop and
     * its saved markers for this track.
     */
    fun toggle() {
        val mediaUri = playbackConnection.getPlayer()?.currentMediaItem?.mediaId
        val src = if (isSpotifyActive) "spotify" else "local"
        when {
            _start.value == null -> {
                _start.value = currentPositionMs()
                loopUri = mediaUri
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop A=${_start.value}ms src=$src")
            }
            _end.value == null -> {
                val end = currentPositionMs()
                val start = _start.value ?: return
                if (end <= start + 1_000) {
                    _start.value = null
                    _end.value = null
                    loopUri = null
                    return
                }
                activate(mediaUri, start, end, persist = true)
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AB-loop B=${end}ms (loop active, src=$src)")
            }
            else -> deactivate(clearPersisted = true, why = "user")
        }
    }
}
