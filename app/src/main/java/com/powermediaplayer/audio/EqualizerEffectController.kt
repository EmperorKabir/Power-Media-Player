package com.powermediaplayer.audio

import android.media.audiofx.Equalizer
import com.powermediaplayer.service.PlaybackConnection
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single platform [Equalizer] AudioEffect attached to
 * ExoPlayer's audio session. Re-attaches when the audio session ID
 * changes (e.g. on track switch) and applies the latest band levels
 * each time. Until this controller existed the EqualizerViewModel
 * was a UI-only knob — band slider movements never reached the audio
 * pipeline (root cause of "EQ doesn't sound like it's working").
 */
@Singleton
class EqualizerEffectController @Inject constructor(
    private val playbackConnection: PlaybackConnection
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Current desired band levels in millibels (10 bands). */
    val bandLevels = MutableStateFlow<List<Int>>(List(10) { 0 })

    private var eq: Equalizer? = null
    private var lastSessionId: Int = 0

    init {
        // React to player connect / disconnect AND audio-session-id
        // changes. NOTE: playerFlow exposes a MediaController (IPC
        // proxy) — `as? ExoPlayer` always yields null, so the
        // Equalizer never used to attach. The real ExoPlayer lives
        // in PlaybackService; we read its audioSessionId directly
        // and re-poll on every player connect event.
        scope.launch {
            playbackConnection.playerFlow.collectLatest { _ ->
                val sessionId = com.powermediaplayer.service.PlaybackService.getExoPlayer()?.audioSessionId ?: 0
                applySession(sessionId)
            }
        }
        // React to user band changes.
        scope.launch {
            bandLevels.collectLatest { levels ->
                applyLevels(levels)
            }
        }
    }

    private fun applySession(sessionId: Int) {
        if (sessionId == 0) {
            release()
            return
        }
        if (sessionId == lastSessionId && eq != null) return
        release()
        runCatching {
            eq = Equalizer(0, sessionId).apply {
                enabled = true
            }
            lastSessionId = sessionId
            android.util.Log.i("PMP_DIAG", "EQ attached session=$sessionId bands=${eq?.numberOfBands}")
            applyLevels(bandLevels.value)
        }.onFailure {
            android.util.Log.w("PMP_DIAG", "EQ attach failed for session=$sessionId", it)
        }
    }

    private fun applyLevels(levels: List<Int>) {
        val e = eq ?: return
        runCatching {
            val n = e.numberOfBands.toInt()
            val safe = if (levels.size >= n) levels else (levels + List(n - levels.size) { 0 })
            for (i in 0 until n) {
                e.setBandLevel(i.toShort(), safe[i].toShort())
            }
            android.util.Log.i("PMP_DIAG", "EQ levels applied first3=${safe.take(3)}")
        }.onFailure {
            android.util.Log.w("PMP_DIAG", "EQ setBandLevel failed", it)
        }
    }

    fun release() {
        eq?.runCatching { release() }
        eq = null
    }
}
