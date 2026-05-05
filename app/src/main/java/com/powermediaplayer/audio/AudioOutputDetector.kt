package com.powermediaplayer.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether the *currently routed* media output is a true mono
 * speaker — i.e. a built-in speaker that only ever delivers a single
 * channel.
 *
 * Why "true mono" specifically:
 *  - Many phones have stereo speakers (channelCounts contains 2): the
 *    Stereo flip / Mono mix effects produce real, audible channel
 *    differences.
 *  - Some single-speaker phones up-mix the audio framework output to
 *    2 channels even though the physical speaker is mono. From the
 *    framework's point of view channelCounts = [2], so we can't
 *    detect those reliably and we DO NOT flag them — false-disabling
 *    on a phone that genuinely supports stereo separation would be
 *    worse than a silent no-op.
 *  - Only when the active output is BUILTIN_SPEAKER and the framework
 *    itself reports `channelCounts` containing exclusively 1 (no 2)
 *    do we declare the path a true mono path. On those devices the
 *    Stereo flip / Mono mix toggles can be visually disabled with
 *    a hint, since they cannot produce an audible effect.
 *  - When wired/BT headphones or a Cast/Receiver device is connected,
 *    flagging is suppressed regardless of speaker capability — the
 *    listener path is no longer the speaker.
 */
@Singleton
class AudioOutputDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isTrueMonoOutput = MutableStateFlow(false)
    val isTrueMonoOutput: StateFlow<Boolean> = _isTrueMonoOutput.asStateFlow()

    init {
        recompute()
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    recompute()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    recompute()
                }
            },
            mainHandler
        )
    }

    private fun recompute() {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        // Prefer non-builtin sinks if connected — once headphones /
        // BT / cast is plugged the speaker isn't the listening path.
        val nonBuiltin = outputs.firstOrNull { d ->
            d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                d.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
                d.type != AudioDeviceInfo.TYPE_TELEPHONY
        }
        if (nonBuiltin != null) {
            _isTrueMonoOutput.value = false
            return
        }

        val speaker = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: run {
            _isTrueMonoOutput.value = false
            return
        }

        val channels = speaker.channelCounts.toList()
        // True mono = framework only ever reports channelCount 1 on
        // this device. Phones with stereo speakers report [1, 2] (or
        // similar); single-physical-speaker phones that up-mix
        // report [2] — both excluded by this rule.
        val trueMono = channels.isNotEmpty() && channels.all { it == 1 }
        _isTrueMonoOutput.value = trueMono
    }
}
