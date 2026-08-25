package com.powermediaplayer.cloud

import android.content.Context
import android.os.SystemClock
import com.powermediaplayer.BuildConfig
import com.powermediaplayer.diag.DiagLog
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time, low-latency control of the LOCAL Spotify app via the Spotify App
 * Remote SDK — used ONLY for the I4d precise "stop at the exact track end without
 * auto-advancing" when Spotify plays on THIS phone (autoplay-off).
 *
 * Why this exists: the app's normal Spotify control is the Web API, which polls
 * `GET /v1/me/player` at ~1 Hz and is "eventually consistent" (~1 s stale), and its
 * `pause()` is a 300-500 ms cloud round-trip. That lag makes an exact-boundary pause
 * impossible (stops early or overshoots+advances). App Remote's `subscribeToPlayerState`
 * PUSHES fresh state over a LOCAL bound-service IPC, and `pause()` is a local IPC call
 * (tens of ms) — precise enough to land at ~0 remaining.
 *
 * SCOPE LIMIT (honest): App Remote only gives this precision for playback ON THE PHONE.
 * For a remote/cast Connect device it learns state via the SAME cloud sync as the Web
 * API, so there is no gain there — the caller must gate this to on-phone playback and
 * keep the Web-API best-effort path for cast.
 *
 * Additive + isolated: does NOT touch the existing AppAuth OAuth or the Web-API mirror.
 * Reuses the same client id + redirect uri; the `app-remote-control` scope is granted
 * once via a lightweight in-Spotify consent (`showAuthView`). Requires the Spotify app
 * installed (visibility already declared in the manifest `<queries>`).
 */
@Singleton
class SpotifyAppRemoteController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object { private const val SSO_REQUEST_CODE = 0x59A1 }

    /** A single real-time player-state snapshot from the local Spotify app. */
    data class RemoteState(
        val trackUri: String,
        val durationMs: Long,
        val positionMs: Long,
        val playbackSpeed: Float,
        val isPaused: Boolean,
        /** SystemClock.elapsedRealtime() when this event arrived — lets the caller
         *  extrapolate the true current position (position + elapsed × speed). */
        val atElapsedRealtimeMs: Long
    )

    private val _state = MutableStateFlow<RemoteState?>(null)
    val state: StateFlow<RemoteState?> = _state.asStateFlow()

    /** True once a connect failed with UserNotAuthorizedException — the app-remote-control
     *  scope isn't granted yet (user signed in before it was added). UI can prompt a
     *  one-time Spotify re-sign-in. Cleared on a successful connect. */
    private val _needsReauth = MutableStateFlow(false)
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    @Volatile private var appRemote: SpotifyAppRemote? = null
    @Volatile private var subscription: Subscription<PlayerState>? = null
    @Volatile private var connecting = false

    val isConnected: Boolean get() = appRemote?.isConnected == true

    /**
     * Idempotent connect to the local Spotify app. `showAuthView(true)` triggers a
     * one-time in-Spotify consent for the `app-remote-control` scope — but that dialog
     * can ONLY be launched from a FOREGROUND Activity (device-proven: connecting from
     * the background app context returned UserNotAuthorizedException with no dialog).
     * So [ctx] must be a foreground Activity for the first-time consent; once granted,
     * later reconnects succeed with any context (the app context default). No-op if
     * already connected or a connect is in flight.
     */
    fun connect(activity: android.app.Activity? = null) {
        if (isConnected || connecting) return
        connecting = true
        // Foreground Activity (if any) for the consent dialog; app context otherwise
        // (backgrounded reconnect after consent was already granted).
        val ctx: Context = activity ?: context
        // showAuthView(false): the app-remote-control grant comes from the app's AppAuth
        // OAuth scope list (SpotifyProvider.scopes), NOT the SDK's built-in consent view
        // (which is broken on modern Spotify apps — returns UserNotAuthorizedException with
        // no dialog, device-proven 2026-08-25). If the grant is missing (user signed in
        // before the scope was added), connect fails with UserNotAuthorizedException →
        // needsReauth flips true so the UI can prompt a one-time Spotify re-sign-in.
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(false)
            .build()
        DiagLog.event(
            "APPREMOTE",
            "connect requested (ctx=${ctx.javaClass.simpleName}, foreground=${activity != null})"
        )
        SpotifyAppRemote.connect(ctx, params, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                connecting = false
                appRemote = remote
                _needsReauth.value = false
                DiagLog.event("APPREMOTE", "connected")
                subscribe(remote)
            }

            override fun onFailure(error: Throwable) {
                connecting = false
                appRemote = null
                _state.value = null
                val name = error.javaClass.simpleName
                // UserNotAuthorizedException = app-remote-control not granted → prompt re-sign-in.
                if (name.contains("UserNotAuthorized", ignoreCase = true)) {
                    _needsReauth.value = true
                }
                DiagLog.event("APPREMOTE", "connect failed: $name: ${error.message}")
            }
        })
    }

    private fun subscribe(remote: SpotifyAppRemote) {
        runCatching { subscription?.cancel() }
        // subscribeToPlayerState() returns the Subscription (kept for cancel());
        // setEventCallback/setErrorCallback return the base PendingResult, so attach
        // them AFTER capturing the Subscription reference.
        val sub: Subscription<PlayerState> = remote.playerApi.subscribeToPlayerState()
        subscription = sub
        sub.setEventCallback { ps: PlayerState ->
            val track = ps.track
            if (track != null && track.uri != null) {
                _state.value = RemoteState(
                    trackUri = track.uri,
                    durationMs = track.duration,
                    positionMs = ps.playbackPosition,
                    playbackSpeed = ps.playbackSpeed,
                    isPaused = ps.isPaused,
                    atElapsedRealtimeMs = SystemClock.elapsedRealtime()
                )
            }
        }.setErrorCallback { err ->
            DiagLog.event("APPREMOTE", "playerState sub error: ${err.message}")
        }
    }

    /** Pause the local Spotify app over the low-latency IPC. Best-effort. */
    fun pause() {
        runCatching { appRemote?.playerApi?.pause() }
            .onFailure { DiagLog.event("APPREMOTE", "pause failed: ${it.message}") }
    }

    /**
     * Build the Spotify SSO intent that grants the `app-remote-control` scope via
     * Spotify's OWN native consent (auth-lib `AuthorizationClient`). This is the ONLY
     * grant the App Remote SDK honours — a generic AppAuth token carrying the scope is
     * NOT enough (device-proven 2026-08-25). The caller launches this with
     * `startActivityForResult(intent, REQUEST_CODE)` from an Activity and passes the
     * result to [handleGrantResult]. Uses TOKEN response type (the App Remote grant is
     * account-level; we don't need the token itself).
     */
    fun buildGrantIntent(activity: android.app.Activity): android.content.Intent {
        val request = com.spotify.sdk.android.auth.AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI
        ).setScopes(arrayOf("app-remote-control")).build()
        DiagLog.event("APPREMOTE", "SSO grant intent built (app-remote-control)")
        return com.spotify.sdk.android.auth.AuthorizationClient
            .createLoginActivityIntent(activity, request)
    }

    /** Handle the SSO result. Returns true if the app-remote-control grant succeeded (the
     *  caller should then re-attempt connect — the lifecycle's decision boolean is
     *  unchanged, so it won't re-fire connect on its own). Clears needsReauth on success. */
    fun handleGrantResult(resultCode: Int, data: android.content.Intent?): Boolean {
        val resp = com.spotify.sdk.android.auth.AuthorizationClient.getResponse(resultCode, data)
        return when (resp.type) {
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN,
            com.spotify.sdk.android.auth.AuthorizationResponse.Type.CODE -> {
                _needsReauth.value = false
                DiagLog.event("APPREMOTE", "SSO grant OK (${resp.type}) — app-remote-control granted")
                true
            }
            else -> {
                DiagLog.event("APPREMOTE", "SSO grant failed type=${resp.type} error=${resp.error}")
                false
            }
        }
    }

    /** REQUEST_CODE for the SSO grant Activity result. */
    val ssoRequestCode: Int get() = SSO_REQUEST_CODE

    /** Tear down the subscription + connection. Idempotent. */
    fun disconnect() {
        runCatching { subscription?.cancel() }
        subscription = null
        val r = appRemote
        appRemote = null
        _state.value = null
        connecting = false
        if (r != null) {
            runCatching { SpotifyAppRemote.disconnect(r) }
            DiagLog.event("APPREMOTE", "disconnected")
        }
    }
}
