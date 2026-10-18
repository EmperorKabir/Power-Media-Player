package com.powermediaplayer.playback

import com.powermediaplayer.data.preferences.BtMediaKind

/**
 * PH9 — remembered playback speed PER MEDIA KIND.
 *
 * Before: the player slider wrote a per-file override only, the service seeded
 * the cold-start speed from a global key that only the car's speed button ever
 * wrote, and ExoPlayer carried whatever speed was last set into the next item
 * regardless of kind. So a new podcast episode (no override) played at 1.0× (or
 * at the car's leaked 1.25× after a relaunch) and a music track inherited the
 * podcast speed.
 *
 * Now: a user-chosen speed is stored for the KIND of what is playing (music /
 * audiobook / podcast / video, the same classification as the Bluetooth
 * mapping); the service applies it on every item change and after a relaunch.
 * An explicit per-file / per-show speed from the overrides popup is applied
 * by the UI afterwards and wins.
 */

const val SPEED_MIN = 0.25f
const val SPEED_MAX = 4f

/** The speed to apply for [kind]: the remembered one, else 1×. */
fun effectiveKindSpeed(speedByKind: Map<BtMediaKind, Float>, kind: BtMediaKind): Float =
    speedByKind[kind]?.coerceIn(SPEED_MIN, SPEED_MAX) ?: 1.0f

/** Which layer decided the speed (for the diag line). */
enum class SpeedLayer { FILE, KIND, DEFAULT }

/**
 * Layered resolution: a speed the user set while THIS file was playing (the
 * documented per-file memory, §C7 slim: "re-open the original track → it
 * auto-resumes at 1.5×") → the speed remembered for the file's KIND → 1×.
 * The explicit per-file / per-show speed from the overrides popup is applied
 * by the UI afterwards and wins over all three.
 */
fun resolveSpeed(
    fileSpeed: Float?,
    speedByKind: Map<BtMediaKind, Float>,
    kind: BtMediaKind
): Pair<Float, SpeedLayer> = when {
    fileSpeed != null -> fileSpeed.coerceIn(SPEED_MIN, SPEED_MAX) to SpeedLayer.FILE
    speedByKind[kind] != null -> effectiveKindSpeed(speedByKind, kind) to SpeedLayer.KIND
    else -> 1.0f to SpeedLayer.DEFAULT
}

/** True when [target] is far enough from [current] to be worth a Sonic flush. */
fun speedDiffers(current: Float, target: Float): Boolean =
    kotlin.math.abs(current - target) >= 0.01f

// NOTE: there is deliberately NO controller-side kind classifier here. The service's
// `currentBtKind` is the only classification (published as PlaybackService.currentKindFlow);
// a MediaController's metadata extras do not always carry the chapter markers the service
// sees, and a second classifier filed an audiobook's speed under "podcast".
