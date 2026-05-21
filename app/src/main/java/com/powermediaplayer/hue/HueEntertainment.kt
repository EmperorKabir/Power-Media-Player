package com.powermediaplayer.hue

/**
 * Entertainment-API audio-reactive lighting — scaffold ONLY.
 *
 * Not shipped in v1. The Entertainment API runs over DTLS-PSK with a
 * 50 Hz update budget per light. Doing it right needs:
 *   - BouncyCastle for DTLS-PSK (not in OkHttp / standard JSSE)
 *   - A separate UDP socket + binary protocol (CIE xy + brightness
 *     packed per light per frame)
 *   - An audio Visualizer attached to the player's session id for
 *     real-time FFT — energy in selected bands drives colour / brightness
 *   - A 50 Hz background loop with backpressure
 *
 * Per the design discussion: shipping this in a SAME vc as the rest of
 * Hue v1 would either crowd out the other tasks or land flaky DTLS code.
 * The honest call is to land the scaffold here, ship Bridge + Scenes
 * now, and finish Entertainment in v2 with the same rigour as the rest
 * of the audio pipeline.
 *
 * Public surface kept minimal so the v2 implementation can fill in
 * behind the same call sites without breaking callers.
 */
object HueEntertainment {

    enum class ReactiveMode {
        OFF,
        BASS_FLASH,
        SPECTRUM,
        COLOUR_FOLLOW_TRACK
    }

    /**
     * v2: open the bridge's Entertainment streaming session via DTLS,
     * start the 50 Hz feed loop. v1: no-op + diag log line so the call
     * site doesn't crash and the absence is auditable.
     */
    fun start(mode: ReactiveMode) {
        com.powermediaplayer.diag.DiagLog.event(
            "HUE",
            "entertainment.start(mode=${mode.name}) — scaffold-only in v1, no DTLS yet"
        )
    }

    fun stop() {
        com.powermediaplayer.diag.DiagLog.event("HUE", "entertainment.stop() — no-op in v1")
    }
}
