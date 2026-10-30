package com.powermediaplayer.service

/**
 * 2026-10-30 — who owns the shared [PlaybackConnection] right now.
 *
 * MainActivity is launchMode=standard, so a CLEAR_TOP launch (notification / widget tap while
 * the app is open) creates a NEW instance and finishes the old one, in that order:
 * new.onCreate → new.onStart → new.onResume → old.onStop → old.onDestroy(isFinishing=true).
 * The old instance's onDestroy used to call disconnect() unconditionally and tore down the
 * MediaController the new instance had already adopted — every Library tap after that was
 * parked as "setMediaItems deferred: controller not bound" until the app was backgrounded and
 * resumed (phone-proven on vc135-test after three deep links).
 *
 * Each connect() claims a fresh epoch; disconnect(epoch) is a no-op unless that epoch is still
 * the current one. Pure, so it is unit-testable without Android.
 */
class OwnerEpoch {
    private var current = 0

    /** Claim ownership; returns the token the caller must pass back to [isCurrent]. */
    fun claim(): Int = ++current

    /** True while no later claim has happened. */
    fun isCurrent(epoch: Int): Boolean = epoch == current

    /** The disconnect rule: `-1` (no epoch) always may; otherwise only the current owner may. */
    fun mayDisconnect(epoch: Int): Boolean = epoch == -1 || isCurrent(epoch)
}
