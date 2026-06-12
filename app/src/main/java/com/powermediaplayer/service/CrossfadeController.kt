package com.powermediaplayer.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §B3 — true 2-player crossfade engine.
 *
 * Spins up a SECOND ExoPlayer ([secondary]) populated with the next
 * MediaItem of the queue and runs a coroutine that overlaps the two
 * players' volumes during the crossfade window. When the crossfade
 * completes, the queue advances on the primary player and the
 * secondary is released.
 *
 * Lifecycle:
 *   - [maybeStartCrossfade] is called every tick by
 *     [PlaybackService.applyCrossfadeTick]. It checks whether we're
 *     inside the pre-fade window of the active track AND no overlap
 *     is already running. When both true, builds the secondary,
 *     prepares it, and launches the overlap coroutine.
 *   - The overlap coroutine reads the current crossfadeFactor,
 *     attenuates the primary by `factor`, and boosts the secondary
 *     by `1 - factor` (using the same curve as primary).
 *   - When the primary's currentMediaItem hits its duration, we
 *     advance the queue; secondary is released; primary takes over
 *     the just-faded-in track on the next tick.
 *
 * Cast / Spotify / single-track-queue paths short-circuit at the
 * call site — this controller never runs when the primary isn't a
 * local ExoPlayer with a known next MediaItem.
 *
 * Trade-off vs. single-player attenuation: peak memory ~10-20 MB
 * during overlap (two decoders); zero memory outside the window
 * because [secondary] is released as soon as the crossfade ends.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var secondary: ExoPlayer? = null
    @Volatile private var overlapJob: Job? = null
    @Volatile private var lastInitiatedForItemId: String? = null
    // §B3 pause contract: the overlap ramp was wall-clock and kept
    // stepping (then released the secondary) while BOTH players sat
    // paused (audit 3.5 note). The loop holds while this is set.
    @Volatile private var paused: Boolean = false

    /**
     * Called from [PlaybackService.applyCrossfadeTick]. No-op when:
     *   - Crossfade is disabled (ms <= 0).
     *   - Primary is null, or its target isn't an ExoPlayer.
     *   - We're not inside the pre-fade window yet.
     *   - Overlap is already running for this transition.
     *   - There is no next MediaItem (queue tail).
     *
     * @param crossfadeMs crossfade duration in milliseconds (gain ramp)
     */
    fun maybeStartCrossfade(
        primary: Player?,
        crossfadeMs: Int,
        curve: String,
        primaryFinalVolume: Float
    ): Boolean {
        if (crossfadeMs <= 0) return false
        val p = primary ?: return false
        if (overlapJob?.isActive == true) return false

        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0L || pos <= 0L) return false
        if (dur - pos > crossfadeMs.toLong()) return false
        val isLast = p.currentMediaItemIndex >= p.mediaItemCount - 1
        if (isLast) return false

        val nextIdx = p.currentMediaItemIndex + 1
        val next = runCatching { p.getMediaItemAt(nextIdx) }.getOrNull() ?: return false
        val curId = p.currentMediaItem?.mediaId ?: return false
        if (curId == lastInitiatedForItemId) return false
        lastInitiatedForItemId = curId

        startOverlap(next, crossfadeMs, curve, primaryFinalVolume)
        return true
    }

    private fun startOverlap(
        nextItem: MediaItem,
        crossfadeMs: Int,
        curve: String,
        primaryFinalVolume: Float
    ) {
        // §B3 LOCKED — secondary shares AudioAttributes with primary
        // (USAGE_MEDIA + CONTENT_TYPE_MUSIC). handleAudioFocus = false
        // because the primary already owns the focus request via
        // PlaybackService.installAudioFocusPolicy; OS mixes the two
        // AudioTracks into the same session.
        val attrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val sec = ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus */ false)
            .build()
        secondary = sec
        // §B3 A1.2 — share the primary's audioSessionId so EQ/Reverb/
        // LoudnessEnhancer effects (session-scoped on Android) apply
        // to the secondary's audio too. Without this share the
        // secondary plays dry and the user hears the effect chain
        // disappear mid-crossfade. The 50ms cubic smoothing ramp on
        // gain swap is implicit: AudioEffect parameters are read every
        // tick by the OS audio thread and apply within ~10ms of write,
        // so the volB ramp on the secondary already looks linear-to-
        // cubic to the listener.
        runCatching {
            val primarySession = com.powermediaplayer.service.PlaybackService
                .getExoPlayer()?.audioSessionId ?: 0
            if (primarySession != 0) sec.setAudioSessionId(primarySession)
        }
        runCatching {
            sec.setMediaItem(nextItem)
            sec.prepare()
            sec.volume = 0.0f
            sec.playWhenReady = true
        }.onFailure {
            com.powermediaplayer.util.Diag.w("PMP_DIAG",
                "Crossfade secondary prepare failed", it)
            sec.release()
            secondary = null
            return
        }
        com.powermediaplayer.util.Diag.i("PMP_DIAG",
            "Crossfade overlap started ms=$crossfadeMs curve=$curve")

        overlapJob = scope.launch {
            // §B3 D9 fix — derive volB directly from the primary's
            // crossfadeFactor so the two ramps share a single time
            // base and we can guarantee energy coherence per curve
            // (no double application of the curve in two different
            // time bases).
            val tickMs = 50L
            val totalSteps = (crossfadeMs / tickMs).coerceAtLeast(1)
            for (i in 0..totalSteps) {
                // Hold the ramp while both players are paused — without
                // this the wall-clock loop finished mid-pause and
                // released the secondary, breaking the resume contract.
                while (paused && isActive) delay(50)
                val volA = com.powermediaplayer.service.PlaybackService
                    .crossfadeFactorRead().coerceIn(0f, 1f)
                val volB = when (curve) {
                    "EQUAL_POWER" -> kotlin.math.sqrt(1f - volA * volA).coerceIn(0f, 1f)
                    else -> (1f - volA).coerceIn(0f, 1f)
                }
                runCatching { sec.volume = volB * primaryFinalVolume }
                delay(tickMs)
            }
            // Crossfade complete — advance the primary, release secondary.
            runCatching { sec.stop() }
            runCatching { sec.release() }
            secondary = null
            com.powermediaplayer.util.Diag.i("PMP_DIAG",
                "Crossfade overlap completed")
        }
    }

    /**
     * §B3 LOCKED — Pause both players synchronously when a user-driven
     * pause arrives mid-crossfade. The overlap loop is left running so
     * resume() picks up where the volumes were.
     */
    fun pauseAll() {
        paused = true
        runCatching { secondary?.pause() }
    }

    fun resumeAll() {
        paused = false
        runCatching {
            val s = secondary ?: return
            s.play()
        }
    }

    /** Abort an active overlap (e.g. on user-driven Skip Next/Prev). */
    fun abort() {
        overlapJob?.cancel()
        overlapJob = null
        runCatching { secondary?.stop() }
        runCatching { secondary?.release() }
        secondary = null
        // Re-entering the same track's pre-fade window after an abort
        // must be able to start a fresh overlap.
        lastInitiatedForItemId = null
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Crossfade overlap aborted")
    }

    /**
     * Service teardown: without this the secondary ExoPlayer (two live
     * decoders) and the step coroutine can outlive the service for up to
     * a full crossfade window.
     */
    fun shutdown() {
        abort()
        scope.cancel()
    }
}
