package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §C7 — per-file playback overrides for starred/pinned tracks.
 *
 * Each row stores **only the axes the user has overridden**. Every
 * column except [mediaUri] is nullable: NULL = "fall through to the
 * global setting". On play start, [PlayerViewModel] reads the row
 * for the current `mediaUri` and applies any non-null axis to the
 * live player + UI state.
 *
 * Override values clear automatically when the file is unstarred /
 * unpinned (see §C7 in the locked plan): an unfavouriting action
 * issues `MediaOverrideDao.clear(uri)` from the same callback that
 * removes the favourite/pinned record. We do NOT keep stale
 * overrides on files the user has rejected.
 */
@Entity(tableName = "media_overrides")
data class MediaOverrideEntity(
    @PrimaryKey
    val mediaUri: String,

    // Audio
    val reverbPreset: Int? = null,
    val stereoFlip: Boolean? = null,
    val monoMix: Boolean? = null,
    val eqPresetId: Long? = null,
    val replayGainMode: String? = null,
    val volumeBoostMb: Int? = null,

    // Video
    val videoFlipH: Boolean? = null,
    val videoFlipV: Boolean? = null,
    val videoBw: Boolean? = null,
    val videoSepia: Boolean? = null,
    val videoInvert: Boolean? = null,
    val videoRotation: Int? = null,

    // Speed / pitch
    val playbackSpeed: Float? = null,
    val pitch: Float? = null,

    val updatedAt: Long = System.currentTimeMillis()
) {
    fun hasAnyAudio(): Boolean = reverbPreset != null || stereoFlip != null ||
        monoMix != null || eqPresetId != null || replayGainMode != null ||
        volumeBoostMb != null

    fun hasAnyVideo(): Boolean = videoFlipH != null || videoFlipV != null ||
        videoBw != null || videoSepia != null || videoInvert != null ||
        videoRotation != null

    fun hasAnySpeed(): Boolean = playbackSpeed != null || pitch != null

    fun isEmpty(): Boolean = !hasAnyAudio() && !hasAnyVideo() && !hasAnySpeed()
}
