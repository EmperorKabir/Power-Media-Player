package com.powermediaplayer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the user's desired 10-band EQ levels (millibels) as the single
 * source of truth shared between the UI and the audio pipeline.
 *
 * History: this used to own the platform [android.media.audiofx.Equalizer]
 * AudioEffect. That EQ only exposes the DEVICE's hardware bands (almost
 * always 5) and we mapped our 10 sliders onto them 1:1 by index — so the
 * low sliders drove the whole spectrum (boost → clipping/"farty") and the
 * top sliders never applied at all ("no effect on the high end"). It is now
 * replaced by [EqualizerAudioProcessor], an in-chain 10-band biquad EQ that
 * reads [bandLevels] directly; this class is just the shared state holder so
 * the EQ screen, per-track overrides and the audio processor all agree.
 */
@Singleton
class EqualizerEffectController @Inject constructor() {

    /** Current desired band levels in millibels (10 bands, 100 mB = 1 dB). */
    val bandLevels = MutableStateFlow<List<Int>>(List(10) { 0 })
}
