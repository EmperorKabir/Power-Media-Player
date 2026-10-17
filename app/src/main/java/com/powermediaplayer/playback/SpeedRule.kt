package com.powermediaplayer.playback

import androidx.media3.common.Player
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

/** True when [target] is far enough from [current] to be worth a Sonic flush. */
fun speedDiffers(current: Float, target: Float): Boolean =
    kotlin.math.abs(current - target) >= 0.01f

/**
 * The playing item's kind from a live Player (service ExoPlayer or a UI
 * MediaController). Mirrors PlaybackService.currentBtKind (chapter markers
 * win over the http-podcast heuristic, video by name or decoded size) so the
 * phone slider stores under the same kind the service later reads.
 */
fun btKindOf(player: Player): BtMediaKind {
    val item = player.currentMediaItem ?: return BtMediaKind.MUSIC
    val uri = item.mediaId
    val name = uri.substringAfterLast('/')
    val isVideo = com.powermediaplayer.util.MediaClassifier.isVideoByName(name, "") ||
        player.videoSize.width > 0
    val hasChapters = (player.mediaMetadata.extras?.getInt("chapter_count", 0) ?: 0) > 0
    val isPodcast = uri.startsWith("http")
    return BtKindResolver.resolve(isVideo, hasChapters, isPodcast)
}
