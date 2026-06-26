package com.powermediaplayer.playback

/**
 * Pure decision for the granular "Resume & auto-play" feature. A trigger fires
 * auto-play only when its toggle is on AND the gating conditions pass (was-playing
 * and per-media-type). Triggers are independent (OR'd); conditions AND-gate them.
 *
 * LAUNCH is evaluated at cold-start; BLUETOOTH/WIRED/CAST are evaluated event-driven
 * when the device/session connects. Pure → unit-tested (AutoplayDecisionTest) and
 * shared by PlaybackSessionCoordinator (launch) + PlaybackService (connect events).
 */
enum class AutoplayTrigger { LAUNCH, BLUETOOTH, WIRED, CAST }

/** The restored/loaded item's kind for the per-type gate. */
enum class MediaPlayKind { SPOKEN, MUSIC, VIDEO }

data class AutoplayPrefs(
    val onLaunch: Boolean,
    val onBt: Boolean,
    val onWired: Boolean,
    val onCast: Boolean,
    val onlyIfWasPlaying: Boolean,
    val kindSpoken: Boolean,
    val kindMusic: Boolean,
    val kindVideo: Boolean
)

object AutoplayDecision {

    /** Whether [trigger] should start auto-play for an item of [kind], given the
     *  user [prefs] and whether the session [wasPlayingAtClose]. */
    fun shouldAutoPlay(
        trigger: AutoplayTrigger,
        prefs: AutoplayPrefs,
        wasPlayingAtClose: Boolean,
        kind: MediaPlayKind
    ): Boolean {
        val triggerEnabled = when (trigger) {
            AutoplayTrigger.LAUNCH -> prefs.onLaunch
            AutoplayTrigger.BLUETOOTH -> prefs.onBt
            AutoplayTrigger.WIRED -> prefs.onWired
            AutoplayTrigger.CAST -> prefs.onCast
        }
        if (!triggerEnabled) return false
        if (prefs.onlyIfWasPlaying && !wasPlayingAtClose) return false
        return when (kind) {
            MediaPlayKind.SPOKEN -> prefs.kindSpoken
            MediaPlayKind.MUSIC -> prefs.kindMusic
            MediaPlayKind.VIDEO -> prefs.kindVideo
        }
    }

    /** Classify an item into a play-kind for the per-type gate. Video wins; then
     *  spoken-word (podcast/audiobook); everything else is music. */
    fun classify(isVideo: Boolean, isSpokenWord: Boolean): MediaPlayKind = when {
        isVideo -> MediaPlayKind.VIDEO
        isSpokenWord -> MediaPlayKind.SPOKEN
        else -> MediaPlayKind.MUSIC
    }
}
