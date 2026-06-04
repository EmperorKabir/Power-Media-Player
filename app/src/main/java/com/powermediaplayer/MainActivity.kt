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
        // §C20 — first-launch deep-link extra (the widget tap path).
        intent.getStringExtra(
            com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB
        )?.let { pendingOpenTab.value = it }
        enableEdgeToEdge()
        MainActivityHolder.set(this)
        playbackConnection.connect()

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
                                .systemBarsPadding()
                        ) {
                            AppNavigation(
                                windowSizeClass = windowSizeClass,
                                initialOpenTab = pendingOpenTab.value
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
        intent.getStringExtra(
            com.powermediaplayer.widget.NowPlayingWidgetProvider.EXTRA_OPEN_TAB
        )?.let { pendingOpenTab.value = it }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.powermediaplayer.util.Diag.i("PMP_PIP", "onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode")
        isInPip.value = isInPictureInPictureMode
        // Force the video view to be RECREATED on PiP exit. A re-bind
        // alone is not enough: logs show the codec connected to the new
        // surface within ms of the maximise while the picture stayed
        // black — the TextureView's SurfaceTexture comes back frozen
        // from the PiP window transition. Recreating the view (what a
        // tab-switch incidentally does) is the reliable cure.
        if (!isInPictureInPictureMode) {
            com.powermediaplayer.ui.player.components.VideoSurfaceBinding
                .pipExitGeneration.value++
            com.powermediaplayer.util.Diag.i(
                "PMP_PIP", "pip-exit → video view recreation requested"
            )
        }
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
