package com.powermediaplayer.playback

/**
 * Maps a raw ExoPlayer error into a clear, ACTIONABLE user message. A
 * Drive-streamed item that fails with a bad HTTP status almost always means the
 * Drive sign-in was lost (e.g. after a reinstall/data wipe) — surface a "sign in
 * again" prompt rather than the raw "ERROR_CODE_IO_BAD_HTTP_STATUS: Source
 * error". Pure (no Android types) so it is unit-tested.
 */
object PlayerErrorMessage {
    const val DRIVE_REAUTH =
        "Couldn't load this Google Drive item — sign in to Google Drive again to resume it."
    const val NETWORK =
        "Network problem — check your connection and try again."

    fun resolve(isDriveBadHttp: Boolean, isNetwork: Boolean, rawFallback: String): String = when {
        isDriveBadHttp -> DRIVE_REAUTH
        isNetwork -> NETWORK
        else -> rawFallback
    }

    /** True when the URI looks like a Google Drive stream. */
    fun isDriveUri(uri: String): Boolean =
        uri.contains("googleapis.com/drive") || uri.contains("alt=media") || uri.contains("drive.google")
}
