package com.powermediaplayer

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.ui.navigation.AppNavigation
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.PowerMediaPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tuple-of-4 for distinctUntilChanged on PiP-relevant fields. */
private data class Quad(val a: Int, val b: Int, val c: Boolean, val d: Boolean)

/**
 * Single activity host for the entire Compose UI.
 * Computes WindowSizeClass once here and passes it to navigation
 * so all screens can adapt to phone/tablet/foldable widths.
 */
/**
 * Holder for the currently-resumed MainActivity, used by SpotifyProvider
 * to bounce our app back to the foreground after auto-launching Spotify.
 * Calling Activity.startActivity (vs Application.startActivity) carries
 * the user-interaction token that satisfies Android's BAL
 * (background-activity-launch) restriction so the bounce-back works
 * reliably even on second + third invocations within a session.
 */
object MainActivityHolder {
    private var ref: java.lang.ref.WeakReference<android.app.Activity>? = null
    fun set(a: android.app.Activity) { ref = java.lang.ref.WeakReference(a) }
    fun get(): android.app.Activity? = ref?.get()

    /**
     * True the WHOLE time video is on the Player screen (not only when the
     * controls are hidden). The activity root drops its systemBarsPadding so
     * the picture is full-bleed at all times — toggling the controls never
     * resizes it. The controls/bars/tab overlays sit ON TOP and inset
     * themselves. Written by the player's controls-visibility effect.
     */
    val fullBleedVideo = androidx.compose.runtime.mutableStateOf(false)

    /**
     * True while the video controls are SHOWN (full-bleed, non-tabletop).
     * Drives the immersive app-tab overlay (AppNavigation) + the transport
     * controls' bottom clearance so the tab bar and controls don't collide.
     */
    val videoControlsVisible = androidx.compose.runtime.mutableStateOf(false)

    /**
     * True while the activity is in Picture-in-Picture. Shared here so the
     * PlaybackService can read it during onTaskRemoved — a swipe-away while
     * PiP is showing must NOT tear the service down.
     */
    @Volatile
    var isInPip: Boolean = false

    /**
     * 8.3 — rotate-to-fullscreen toggle. An activity-level orientation
     * request overrides the user's auto-rotate quick-setting on phones
     * with NO permission (verified against current platform docs);
     * Android 12L+ large-screen devices may ignore it by policy, which
     * is why the button only shows on compact widths.
     */
    fun toggleVideoOrientation() {
        val a = get() ?: return
        a.requestedOrientation =
            if (a.requestedOrientation ==
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    /** Leaving the player must never strand an orientation lock. */
    fun releaseVideoOrientation() {
        get()?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
// FragmentActivity (rather than bare ComponentActivity) so AndroidX
// MediaRouteButton's chooser DialogFragment can attach. FragmentActivity
// extends ComponentActivity, so Compose's setContent + lifecycle hooks
// all still work unchanged.
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var playbackConnection: PlaybackConnection

    @Inject
    lateinit var sessionCoordinator: com.powermediaplayer.playback.PlaybackSessionCoordinator

    @Inject
    lateinit var settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore

    @Inject
    lateinit var spotifyProvider: com.powermediaplayer.cloud.SpotifyProvider

    /**
     * True while the system is rendering us in PiP. Drives
     * AppNavigation to render ONLY the VideoSurface in this mode so
     * the user doesn't see the bottom nav / sliders behind black bars.
     */
    private val isInPip = androidx.compose.runtime.mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        MainActivityHolder.set(this)
        com.powermediaplayer.diag.DiagLog.lifecycle("MainActivity.onResume")
    }

    override fun onStart() {
        super.onStart()
        com.powermediaplayer.diag.DiagLog.lifecycle("MainActivity.onStart")
    }

    override fun onStop() {
        super.onStop()
        com.powermediaplayer.diag.DiagLog.lifecycle("MainActivity.onStop isFinishing=$isFinishing")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.powermediaplayer.diag.DiagLog.lifecycle(
            "MainActivity.onCreate savedState=${savedInstanceState != null}"
        )
        // Audit 6.3 — recreation while in PiP (uiMode/density/locale all
        // recreate) must not render the full chrome inside the PiP frame.
        isInPip.value = isInPictureInPictureMode
        MainActivityHolder.isInPip = isInPictureInPictureMode
        // §C20 — first-launch deep-link extra (the widget tap path).
        // Strip it from the sticky intent too: recreation re-reads the
        // SAME intent and would re-arm the navigation (audit 6.2).
        readOpenTabExtra(intent)
        // Audit 6.5 — bare enableEdgeToEdge() keys bar-icon appearance
        // off the SYSTEM theme; the app is hard-forced dark, so a
        // system-light device got dark icons over pure black. Explicit
        // dark styles regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )
        MainActivityHolder.set(this)
        // (helpers for the deep-link lifecycle live below onCreate)
        playbackConnection.connect()
        // Playback-session side effects run exactly once per process
        // (audit 3.1/8.4) — idempotent ignition, not lifecycle-bound.
        sessionCoordinator.start()
        // Cold-start restore runs OUTSIDE start()'s once-per-process guard:
        // a swipe-away kills the service but Android keeps the process cached,
        // so reopening is a WARM start where start() no-ops — the restore must
        // still fire. Fresh create only (savedInstanceState==null); a config
        // recreate keeps the live session, so re-restoring there is wrong.
        if (savedInstanceState == null) {
            sessionCoordinator.attemptColdStartRestore()
        }

        // DIAGNOSTIC (F8 fold posture) — log the RAW FoldingFeature from
        // androidx.window (the same source material3-adaptive reads) on
        // every posture change, so a physical fold shows exactly what the
        // device reports: state HALF_OPENED/FLAT, orientation, separating.
        // If isTabletop never flips, this proves whether the OEM extension
        // even surfaces the half-open posture to the app.
        lifecycleScope.launch {
            androidx.window.layout.WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collect { info ->
                    val folds = info.displayFeatures
                        .filterIsInstance<androidx.window.layout.FoldingFeature>()
                    if (folds.isEmpty()) {
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "[POSTURE] flat/slab — no FoldingFeature (features=${info.displayFeatures.size})"
                        )
                    } else folds.forEach { f ->
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "[POSTURE] FoldingFeature state=${f.state} orientation=${f.orientation} " +
                                "isSeparating=${f.isSeparating} occlusion=${f.occlusionType} bounds=${f.bounds}"
                        )
                    }
                }
        }

        // "Auto-play on launch", status-bar case: closing the app with
        // back/swipe leaves the playback service alive with the item
        // PAUSED in the notification — the cold-start restore then sees
        // media already loaded and steps aside, so autoplay never fired.
        // The user's definition is the right one: OPENING the app means
        // resume, however it was closed. savedInstanceState==null keeps
        // rotations/recreations from re-triggering; the truly-cold path
        // is handled by the restore itself (playWhenReady=autoplay).
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val autoplay = runCatching {
                    settingsDataStore.autoplayOnLaunch.first()
                }.getOrDefault(false)
                if (!autoplay) return@launch
                val player = kotlinx.coroutines.withTimeoutOrNull(3000) {
                    playbackConnection.playerFlow.filterNotNull().first()
                } ?: return@launch
                // Let any restored state settle.
                kotlinx.coroutines.delay(400)
                val spotifyActive = spotifyProvider.spotifyState.value != null
                if (!spotifyActive && !player.isPlaying && player.mediaItemCount > 0) {
                    player.play()
                    com.powermediaplayer.diag.DiagLog.dec(
                        branch = "cold-start",
                        reason = "autoplayOnLaunch → resumed the paused status-bar item"
                    )
                }
            }
        }

        // Keep PiP params in sync with playback state so SDK 31+
        // setAutoEnterEnabled actually fires when the user presses Home.
        // setAutoEnterEnabled is only honoured if the params are
        // already on the activity at the time the user leaves; setting
        // it inside onUserLeaveHint is too late and the system rejects
        // the PiP entry (enterPictureInPictureMode returns false).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Filter out the 500 ms position-poll noise — only update
            // the PiP params when the *PiP-relevant* state changes
            // (video dimensions, video-mode flag, isPlaying). The
            // previous unfiltered collector fired setPictureInPictureParams
            // (a system IPC) twice per second forever even during audio.
            lifecycleScope.launch {
                playbackConnection.playerState
                    .map { Quad(it.videoWidth, it.videoHeight, it.isVideoContent, it.isPlaying) }
                    .distinctUntilChanged()
                    .collect { (w0, h0, isVideo, isPlaying) ->
                        runCatching {
                            val w = w0.coerceAtLeast(16)
                            val h = h0.coerceAtLeast(9)
                            val aspect = android.util.Rational(w, h)
                            val safe = if (aspect.toFloat() > 2.39f || aspect.toFloat() < 0.42f) {
                                android.util.Rational(16, 9)
                            } else aspect
                            val params = android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(safe)
                                .setSeamlessResizeEnabled(true)
                                .setAutoEnterEnabled(isVideo && isPlaying)
                                // Audit 6.12 — enter animates from the video
                                // frame; transport actions in the window
                                // (Media3 adds none automatically).
                                .setSourceRectHint(
                                    com.powermediaplayer.ui.player.components.PipBoundsHolder.rect
                                )
                                .setActions(pipActions(isPlaying))
                                .build()
                            setPictureInPictureParams(params)
                        }
                    }
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            PowerMediaPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OledBlack
                ) {
                    if (isInPip.value) {
                        // PiP mode: render ONLY the video surface so the
                        // window content matches the PiP frame. App
                        // chrome (tabs, controls) stays hidden.
                        com.powermediaplayer.ui.player.components.VideoSurface(
                            isVideoContent = true,
                            videoWidth = playbackConnection.playerState.value.videoWidth,
                            videoHeight = playbackConnection.playerState.value.videoHeight,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // vc31 edge-to-edge: keep the OledBlack Surface
                        // full-bleed behind the (transparent, Android-15
                        // default) system bars, but inset the app chrome
                        // so InfoIcon/controls don't collide with the
                        // notch or the back-gesture zone. Addresses the
                        // Play Console edge-to-edge warning.
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // Audit 6.4 — immersive video drops the bar
                                // padding so the frame is truly full-bleed;
                                // everything else keeps the inset chrome.
                                .then(
                                    if (MainActivityHolder.fullBleedVideo.value) Modifier
                                    else Modifier.systemBarsPadding()
                                )
                        ) {
                            AppNavigation(
                                windowSizeClass = windowSizeClass,
                                initialOpenTab = pendingOpenTab.value,
                                onOpenTabConsumed = { consumeOpenTab() }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * §C20 — widget tap deep-link target. Read at composition time and
     * cleared after consumption so a second taps-on-launcher doesn't
     * re-navigate. onNewIntent re-populates when the activity is
     * already in the foreground.
     */
    private val pendingOpenTab = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)   // the stripped intent becomes the sticky one
        readOpenTabExtra(intent)
    }

    private fun readOpenTabExtra(i: android.content.Intent) {
        i.getStringExtra(
            com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB
        )?.let {
            pendingOpenTab.value = it
            // Recreation re-reads this intent — never re-navigate.
            i.removeExtra(com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB)
        }
    }

    /** Called by AppNavigation once the deep-link navigation has fired. */
    private fun consumeOpenTab() {
        pendingOpenTab.value = null
    }

    /** Audit 6.12 — PiP transport actions (play/pause toggle + 15s
     *  forward). The collector refreshes params on isPlaying changes so
     *  the toggle icon flips while in PiP. */
    private fun pipActions(isPlaying: Boolean): List<android.app.RemoteAction> {
        fun pi(code: Int, action: String) = android.app.PendingIntent.getBroadcast(
            this, code,
            android.content.Intent(this, com.powermediaplayer.widget.PipActionReceiver::class.java)
                .setAction(action),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return listOf(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    if (isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                ),
                if (isPlaying) "Pause" else "Play",
                "Play or pause",
                pi(1, com.powermediaplayer.widget.PipActionReceiver.ACTION_PLAY_PAUSE)
            ),
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_media_ff
                ),
                "+15s",
                "Forward 15 seconds",
                pi(2, com.powermediaplayer.widget.PipActionReceiver.ACTION_FFWD15)
            )
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.powermediaplayer.util.Diag.i("PMP_PIP", "onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode")
        isInPip.value = isInPictureInPictureMode
        MainActivityHolder.isInPip = isInPictureInPictureMode
        // Surface ownership across the transition is handled by
        // VideoSurfaceBinding's healing stack: two surfaces can bind
        // within ~30 ms of each other on exit, and the loser's disposal
        // would otherwise clear the winner's output.
    }

    override fun onDestroy() {
        super.onDestroy()
        com.powermediaplayer.diag.DiagLog.lifecycle(
            "MainActivity.onDestroy isFinishing=$isFinishing"
        )
        if (isFinishing) {
            playbackConnection.disconnect()
        }
    }

    /**
     * Auto-enter Picture-in-Picture when the user presses Home while a
     * video is playing. Audio playback continues via the MediaSession
     * notification (no PiP needed). Spotify mirror playback is also
     * notification-driven, so we only enter PiP for local/Drive video.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = playbackConnection.playerState.value
        val isVideo = state.isVideoContent
        val isPlaying = state.isPlaying
        com.powermediaplayer.util.Diag.i(
            "PMP_PIP",
            "onUserLeaveHint isVideo=$isVideo isPlaying=$isPlaying w=${state.videoWidth} h=${state.videoHeight}"
        )
        if (isVideo && isPlaying &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        ) {
            runCatching {
                val w = state.videoWidth.coerceAtLeast(16)
                val h = state.videoHeight.coerceAtLeast(9)
                // PiP aspect ratio is clamped by the system (~2.39:1 to 1:2.39).
                val aspect = android.util.Rational(w, h)
                val safeAspect = if (aspect.toFloat() > 2.39f || aspect.toFloat() < 0.42f) {
                    android.util.Rational(16, 9)
                } else aspect
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(safeAspect)
                    .setSourceRectHint(
                        com.powermediaplayer.ui.player.components.PipBoundsHolder.rect
                    )
                    .setActions(pipActions(isPlaying))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    builder.setSeamlessResizeEnabled(true)
                    builder.setAutoEnterEnabled(true)
                }
                val ok = enterPictureInPictureMode(builder.build())
                com.powermediaplayer.util.Diag.i("PMP_PIP", "enterPictureInPictureMode returned=$ok aspect=$safeAspect")
            }.onFailure {
                com.powermediaplayer.util.Diag.w("PMP_PIP", "PiP enter failed", it)
            }
        }
    }
}
