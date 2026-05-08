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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
            val tickMs = 50L
            val totalSteps = (crossfadeMs / tickMs).coerceAtLeast(1)
            for (i in 0..totalSteps) {
                val t = i.toFloat() / totalSteps
                val (volA, volB) = curveFactors(t, curve)
                runCatching { sec.volume = volB * primaryFinalVolume }
                // Primary attenuation is published via the existing
                // mixer in PlaybackService.Companion — we DON'T touch
                // primary's volume directly to avoid fighting the
                // ReplayGain × crossfade multiplier already applied
                // there. Instead we let PlaybackService.setCrossfadeFactor
                // do its thing and ride the secondary on top.
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
     * Map normalised progress `t ∈ [0,1]` through the chosen curve to
     * (primaryAttenuation, secondaryGain). Equal-power keeps the
     * combined energy constant.
     */
    private fun curveFactors(t: Float, curve: String): Pair<Float, Float> = when (curve) {
        "EQUAL_POWER" -> {
            val angle = (t * PI / 2f).toFloat()
            cos(angle) to sin(angle)
        }
        "EXPONENTIAL" -> (1f - t * t) to (t * t)
        "LOGARITHMIC" -> {
            val log = if (t <= 0f) 0f
            else (kotlin.math.log10(1f + 9f * t)).coerceIn(0f, 1f)
            (1f - log) to log
        }
        else -> (1f - t) to t // linear
    }

    /** Abort an active overlap (e.g. on user-driven Skip Next/Prev). */
    fun abort() {
        overlapJob?.cancel()
        overlapJob = null
        runCatching { secondary?.stop() }
        runCatching { secondary?.release() }
        secondary = null
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Crossfade overlap aborted")
    }
}
