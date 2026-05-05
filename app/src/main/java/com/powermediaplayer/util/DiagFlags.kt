package com.powermediaplayer.util

/**
 * Diagnostic logging flag for the video-controls-jump investigation
 * (docs/superpowers/plans/2026-05-05-video-controls-jump-investigation.md).
 *
 * All call-sites must use [diagV] so the dead-code elimination removes
 * the strings when the flag is off. Removable in one commit:
 * `chore(diag): remove PMP_DIAG_VIDEO instrumentation, RCA filed`.
 */
const val PMP_DIAG_VIDEO: Boolean = true

inline fun diagV(msg: () -> String) {
    if (PMP_DIAG_VIDEO) android.util.Log.i("PMP_DIAG_VIDEO", msg())
}
