package com.powermediaplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.google.android.gms.cast.framework.CastContext
import com.powermediaplayer.cloud.GoogleDriveProvider
import com.powermediaplayer.data.preferences.BluetoothMediaActions
import com.powermediaplayer.data.preferences.BtMappingSnapshot
import com.powermediaplayer.data.preferences.SettingsDataStore
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.powermediaplayer.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Background media playback service implementing MediaSessionService.
 * Hosts the ExoPlayer and MediaSession for system-integrated playback
 * with lock screen controls and notification media controls.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @javax.inject.Inject
    lateinit var googleDriveProvider: GoogleDriveProvider

    @javax.inject.Inject
    lateinit var driveOAuthProvider: com.powermediaplayer.cloud.DriveOAuthProvider

    /**
     * Custom AudioProcessor that powers the Stereo flip / Mono mix
     * effects in Settings. Lives here (singleton-per-service) so it
     * outlasts the renderer factory and can read its enable flags
     * lazily on each input buffer.
     */
    private val stereoTransformProcessor by lazy {
        com.powermediaplayer.audio.StereoTransformProcessor(
            flipSupplier = { stereoFlipFlag },
            monoSupplier = { monoMixFlag }
        )
    }
    /**
     * Custom AudioProcessor that delays the audio path by N ms so the
     * Settings → "Audio delay" slider is no longer cosmetic. Reads the
     * delay value lazily per input buffer for live scrubbing.
     */
    private val audioDelayProcessor by lazy {
        com.powermediaplayer.audio.AudioDelayProcessor(
            delayMsSupplier = { audioDelayFlag }
        )
    }
    /**
     * §Hue — PCM tap for the audio-reactive lighting analyser. Hilt
     * @Singleton so the same instance is shared with HueEntertainment
     * (which reads analyser snapshots) and the audio chain (which
     * feeds PCM samples in). No RECORD_AUDIO required — we process
     * audio buffers we already own.
     */
    @javax.inject.Inject
    lateinit var hueAnalyserProcessor: com.powermediaplayer.hue.HueAnalyserAudioProcessor

    /**
     * Shared 10-band EQ levels (millibels). The in-chain
     * [equalizerProcessor] reads this each buffer; the EQ screen and
     * per-track overrides write it. Replaces the platform Equalizer.
     */
    @javax.inject.Inject
    lateinit var equalizerEffectController: com.powermediaplayer.audio.EqualizerEffectController
    /**
     * In-chain reverb — replaces the platform EnvironmentalReverb, which
     * failed twice on hardware (OUTPUT_MIX denied; session-insert
     * crackled while inaudible). Flags are written by PlayerViewModel's
     * override-aware collectors and read lazily per buffer.
     */
    private val reverbProcessor by lazy {
        com.powermediaplayer.audio.ReverbAudioProcessor(
            presetSupplier = { reverbPresetFlag },
            wetSupplier = { reverbWetFlag }
        )
    }

    @Volatile
    private var stereoFlipFlag: Boolean = false
    @Volatile
    private var monoMixFlag: Boolean = false
    /** In-chain boost (soft-limited) — replaces LoudnessEnhancer. */
    private val gainProcessor by lazy {
        com.powermediaplayer.audio.GainAudioProcessor(
            gainMbSupplier = { chainGainMbFlag }
        )
    }
    /**
     * In-chain 10-band biquad graphic EQ — replaces the platform Equalizer
     * (which only had the device's ~5 hardware bands, so the app's top
     * sliders did nothing and the low ones drove + clipped the whole
     * spectrum). Reads the shared band levels each buffer; flat = exact
     * pass-through. Sits AFTER reverb so reverb's input is unchanged.
     */
    private val equalizerProcessor by lazy {
        com.powermediaplayer.audio.EqualizerAudioProcessor(
            bandLevelsMbSupplier = { equalizerEffectController.bandLevels.value }
        )
    }

    @Volatile
    private var audioDelayFlag: Int = 0
    // BT video offset in µs (positive = delay the picture). Read live per frame
    // by OffsetVideoRenderer to compensate late Bluetooth-speaker audio.
    @Volatile private var btVideoOffsetUsFlag: Long = 0L
    // True only when a BT audio sink is the active output. The BT video offset
    // exists to compensate BT audio latency; on phone-speaker / wired it would
    // wrongly delay the picture, so the renderer reads 0 unless this is set.
    @Volatile private var btVideoRouteActive: Boolean = false
    /**
     * Subtitle delay in ms. Read by [com.powermediaplayer.subtitles
     * .ShiftingSubtitleParserFactory] at parse-time and applied as a
     * cue-time shift. Live drag of the slider re-prepares the current
     * MediaItem so the parser re-runs with the new value (see the
     * subtitleDelayMs collector in onCreate below).
     */
    @Volatile
    private var subtitleDelayMsFlag: Int = 0

    /**
     * Read by the [DefaultAudioSink] AudioTrackBufferSizeProvider on
     * each buffer-size negotiation. When true → request platform-minimum
     * buffer (snappier pitch / speed / effect changes, higher dropout
     * risk). When false → delegate to Media3's safety-multiplied default.
     */
    @Volatile
    private var audioBufferLowLatencyFlag: Boolean = false
    @Volatile
    private var crossfadeMsFlag: Int = 0
    private val crossfadeMsFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile private var crossfadeAlbumModeFlag: Boolean = true
    @Volatile private var crossfadeCurveFlag: String = "EQUAL_POWER"
    @Volatile private var crossfadePreFadeTriggerSFlag: Int = 5
    @Volatile private var crossfadeSkipSilenceFlag: Boolean = false

    @javax.inject.Inject
    lateinit var settingsDataStore: SettingsDataStore

    @javax.inject.Inject
    lateinit var audioOutputDetector: com.powermediaplayer.audio.AudioOutputDetector

    @javax.inject.Inject
    lateinit var mediaOverrideRepo: com.powermediaplayer.data.repository.MediaOverrideRepository

    @javax.inject.Inject
    lateinit var webhookEmitter: com.powermediaplayer.webhooks.WebhookEmitter

    @javax.inject.Inject
    lateinit var spotifyProvider: com.powermediaplayer.cloud.SpotifyProvider

    @javax.inject.Inject
    lateinit var hueEntertainment: com.powermediaplayer.hue.HueEntertainment

    /**
     * vc32: the area key ("<kind>:<uuid>") the CURRENTLY RUNNING
     * entertainment stream was started for. The collector compares it to
     * the live hueSelectedArea so a mid-stream room/zone switch restarts
     * the engine on the new area instead of silently driving the old one.
     */
    @Volatile private var activeHueAreaKey: String = ""

    @javax.inject.Inject
    lateinit var hueProvider: com.powermediaplayer.hue.HueProvider

    // §B3 — true 2-player crossfade engine (lazy: spins up the second
    // ExoPlayer only inside the pre-fade window, releases on completion).
    private val crossfadeController by lazy {
        com.powermediaplayer.service.CrossfadeController(this)
    }


    private var player: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    // Cast local-video: true while the local player is kept alive ONLY to show
    // the picture on the phone during an audio-only cast. Sync is EVENT-DRIVEN
    // (mirror play/pause + seeks via a cast Player.Listener + an offset
    // collector) — NO polling loop, so no jittery repeated seeking.
    @Volatile private var castLocalVideoActive: Boolean = false
    // True once the cast has TRULY started playing and we've aligned the local
    // picture once. Before this, the picture stays paused (frozen frame) while
    // the cast buffers. After, we follow the cast's INTENT (playWhenReady), not
    // its buffering blips — mirroring isPlaying made the picture stutter.
    @Volatile private var castInitialSyncDone: Boolean = false
    @Volatile private var castVideoAudioOffsetMsFlag: Int = 0
    // Audio-only cast of a video: the extracted temp .m4a we cast (small →
    // fast receiver buffer) and the extraction job, so we can cancel + delete
    // on cast-end.
    private var castAudioTempFile: java.io.File? = null
    private var castAudioExtractJob: kotlinx.coroutines.Job? = null
    // CastContext is process-static: a listener left registered pins this
    // service generation (and its released CastPlayer) until process death.
    private var castSessionListener:
        com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession>? = null
    private var headphonePlugReceiver: android.content.BroadcastReceiver? = null
    private var mediaSession: MediaSession? = null

    // Cached Bluetooth mapping. The MediaSession callback fires on the
    // binder thread; reading DataStore there would block. We refresh
    // this snapshot whenever DataStore emits and the callback reads it
    // lock-free via @Volatile.
    @Volatile
    private var btMapping: BtMappingSnapshot = BtMappingSnapshot(
        prevAction = BluetoothMediaActions.PREV_TRACK,
        nextAction = BluetoothMediaActions.NEXT_TRACK,
        skipBackSeconds = 30,
        skipForwardSeconds = 30
    )

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    /**
     * Running target for skip-button taps so 5 rapid skip-back-30 taps
     * cumulatively move 150 s back instead of all reading the same stale
     * currentPosition and effectively landing at -30 s. Cleared 600 ms
     * after the last tap (the player has by then settled at the seek
     * target and currentPosition is fresh again).
     */
    @Volatile
    private var pendingSeekTarget: Long = -1L
    private var clearPendingSeekJob: kotlinx.coroutines.Job? = null
    private var debouncedSeekJob: kotlinx.coroutines.Job? = null

    companion object {
        // Custom session commands for features not in standard transport controls
        const val ACTION_SKIP_BACK_5    = "ACTION_SKIP_BACK_5"
        const val ACTION_SKIP_BACK_10   = "ACTION_SKIP_BACK_10"
        const val ACTION_SKIP_BACK_15   = "ACTION_SKIP_BACK_15"
        const val ACTION_SKIP_BACK_20   = "ACTION_SKIP_BACK_20"
        const val ACTION_SKIP_BACK_30   = "ACTION_SKIP_BACK_30"
        const val ACTION_SKIP_FORWARD_5  = "ACTION_SKIP_FORWARD_5"
        const val ACTION_SKIP_FORWARD_10 = "ACTION_SKIP_FORWARD_10"
        const val ACTION_SKIP_FORWARD_15 = "ACTION_SKIP_FORWARD_15"
        const val ACTION_SKIP_FORWARD_20 = "ACTION_SKIP_FORWARD_20"
        const val ACTION_SKIP_FORWARD_30 = "ACTION_SKIP_FORWARD_30"

        /**
         * Direct reference to the ExoPlayer running inside this service.
         *
         * WHY: MediaController is an IPC proxy — it cannot deliver video frames to a Surface.
         * PlayerView MUST be attached to the real ExoPlayer object in the same process.
         * Using WeakReference prevents a memory leak if the service dies before the UI.
         *
         * Access via: PlaybackService.getExoPlayer()
         */
        private var exoPlayerRef: java.lang.ref.WeakReference<ExoPlayer>? = null

        fun getExoPlayer(): ExoPlayer? = exoPlayerRef?.get()

        // True while the local player is kept alive ONLY to show the picture
        // during an audio-only cast. The Player UI reads this to suppress the
        // "nothing's playing yet" empty state (the picture IS playing locally,
        // even though the SESSION player is the audio-only CastPlayer).
        val castLocalVideoActiveFlow =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** True whenever the SESSION player is a CastPlayer — i.e. the app is
         *  casting (any kind: audio cast, audio-only-while-local-video, TV).
         *  The Bluetooth button reads this so it doesn't show "routing to BT"
         *  while audio is actually on the cast device (BT may still be
         *  system-connected, but it isn't the app's active output). */
        val castActiveFlow =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** True while the player's audio is force-pinned to the phone speaker
         *  via [rerouteAudioToPhoneSpeaker] (a per-app override of the system
         *  route). The Bluetooth sheet reads this to flip its action between
         *  "Play on phone speaker" and "Play on <BT device>", so the user can
         *  always pull audio back to Bluetooth even when the device was already
         *  connected (no fresh device-add event would clear it otherwise). */
        val audioRerouteActiveFlow =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Reroute the player's audio OFF a Bluetooth speaker back to the
         *  phone's built-in speaker, WITHOUT disabling Bluetooth (a true ACL
         *  disconnect needs privileged system APIs). Uses the stable Media3
         *  [ExoPlayer.setPreferredAudioDevice]. Returns true if applied. The OS
         *  re-routes to BT again on the next route change; pass nothing /
         *  call [clearAudioReroute] to restore default routing. */
        fun rerouteAudioToPhoneSpeaker(context: android.content.Context): Boolean {
            val p = getExoPlayer() ?: return false
            val am = context.getSystemService(android.media.AudioManager::class.java)
                ?: return false
            val speaker = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return false
            return runCatching {
                p.setPreferredAudioDevice(speaker)
                audioRerouteActiveFlow.value = true
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Audio rerouted to phone speaker (BT kept on)")
                true
            }.getOrDefault(false)
        }

        /** Clear the preferred-device override → default (Bluetooth) routing. */
        fun clearAudioReroute() {
            runCatching { getExoPlayer()?.setPreferredAudioDevice(null) }
            audioRerouteActiveFlow.value = false
        }

        // ── Volume mixer ────────────────────────────────────────────
        // ExoPlayer.volume is multiplexed between two independent
        // sources: ReplayGain attenuation (negative track-gain values)
        // and the crossfade ramp during track transitions. Each
        // source publishes a 0..1 factor; the actual volume applied
        // to the player is replayGainFactor × crossfadeFactor. This
        // avoids one source overwriting the other.
        @Volatile private var replayGainFactor: Float = 1.0f
        @Volatile private var crossfadeFactor: Float = 1.0f
        // Cast local-video mute: 0 while the local player is kept alive ONLY
        // to show the picture on the phone (audio is on the cast device). A
        // THIRD factor — not a direct player.volume write — so the crossfade
        // tick (which re-asserts replayGain×crossfade every 100ms) can't
        // un-mute it. 1.0 in every normal case.
        @Volatile private var castLocalVideoMuteFactor: Float = 1.0f

        /** §B5 — last auto-revert reason (null when not active). */
        /** Push-based: the UI used to POLL a static holder for this at
         *  750ms (audit 3.5/3.11) — a StateFlow costs nothing idle. */
        val crossfadeAutoRevertReasonFlow =
            kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        @Volatile var crossfadeAutoRevertReason: String? = null
            set(value) { field = value; crossfadeAutoRevertReasonFlow.value = value }

        /**
         * Bug fix (user-reported "playback resumes when I sign in to
         * Spotify even though I never paused"): set to `true` while the
         * Spotify OAuth Custom Tab is in the foreground so the
         * AudioFocus loss-then-gain pair caused by the browser stealing
         * focus doesn't auto-pause and then auto-resume our player.
         * CloudViewModel toggles this when launching / completing the
         * OAuth intent. A 60s safety timer in PlaybackService also
         * clears it in case the user cancels OAuth without returning.
         */
        @Volatile var oauthInFlight: Boolean = false

        /**
         * D10 fix — pushed by the Player.Listener.onMediaItemTransition
         * inside [installCrossfadeListener]. Replaces a per-750ms
         * polling loop in MediaOverrideRepository. Empty string until
         * the first track is set.
         */
        val currentMediaIdFlow: kotlinx.coroutines.flow.MutableStateFlow<String> =
            kotlinx.coroutines.flow.MutableStateFlow("")

        /**
         * Cast bug fix (user-reported "album art gone when I cast"):
         * CastPlayer.currentMediaItem is RECONSTRUCTED from receiver
         * state via DefaultMediaItemConverter, so the original
         * sender-side mediaMetadata.artworkUri (a phone-local
         * `content://media/external/audio/albumart/<id>` URI the
         * receiver can't fetch) is dropped. Cache the sender-side
         * metadata keyed on mediaId so PlaybackConnection's update
         * path can still surface the right artwork in the app UI
         * regardless of what the receiver echoes back.
         */
        val senderMetadataByMediaId: java.util.concurrent.ConcurrentHashMap<String, androidx.media3.common.MediaMetadata> =
            java.util.concurrent.ConcurrentHashMap()

        /**
         * Cast bug fix (user-reported "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
         * appears when I stop casting"): once switchPlayer rebuilds
         * MediaItems for Cast (URI rewritten to a `http://<lan-ip>` relay
         * URL), the queue inside CastPlayer no longer has the original
         * `content://` / `file://` / `https://` URIs. When the user
         * disconnects, switchPlayer reads items from CastPlayer and
         * hands those relay URLs back to the local ExoPlayer — Android
         * 9+ blocks cleartext HTTP -> source error.
         *
         * Cache the ORIGINAL MediaItem (with the source-side URI) keyed
         * on mediaId so switchPlayer can restore it when going back to
         * local. Same mediaId space as senderMetadataByMediaId.
         */
        val senderItemByMediaId: java.util.concurrent.ConcurrentHashMap<String, androidx.media3.common.MediaItem> =
            java.util.concurrent.ConcurrentHashMap()

        /**
         * Evict sender-cache entries whose mediaId is no longer reachable
         * from any player timeline. Both caches retain artworkData (often
         * 100KB-2MB per track), so without eviction a long session accretes
         * unbounded heap. Live-queue ids must SURVIVE — cast artwork echo
         * and the cleartext-URI restore read them — so callers pass the
         * union of every live timeline and an empty set is treated as
         * "don't know, keep everything".
         */
        fun pruneSenderCaches(liveIds: Set<String>) {
            if (liveIds.isEmpty()) return
            senderMetadataByMediaId.keys.retainAll(liveIds)
            senderItemByMediaId.keys.retainAll(liveIds)
        }

        /** ReplayGain attenuation for negative track-gain tags
         *  (LoudnessEnhancer can only boost). 1.0 = no attenuation. */
        @Volatile private var reverbPresetFlag: Int = 0
        @Volatile private var reverbWetFlag: Float = 1.0f
        @Volatile private var chainGainMbFlag: Int = 0

        /** Composed user-boost + ReplayGain positive gain, in millibels. */
        fun setChainGain(mb: Int) {
            chainGainMbFlag = mb.coerceIn(0, 3000)
        }

        fun setReverb(preset: Int, wet: Float) {
            reverbPresetFlag = preset.coerceIn(0, 5)
            reverbWetFlag = wet.coerceIn(0f, 1f)
        }

        fun setReplayGainAttenuation(factor: Float) {
            replayGainFactor = factor.coerceIn(0.0f, 1.0f)
            applyMixedVolume()
        }

        // ── Chain-gain composition (audit 3.6 / T264 split) ─────────
        // The user's boost slider and ReplayGain's positive gain are
        // independent channels composed additively into ONE chain-gain
        // write. They lived as PlayerViewModel companion fields with a
        // per-VM applyLoudness; the single-writer home is here, next to
        // the chain they drive. RG-off zeroes ONLY its own channel.
        @Volatile private var userBoostMbFlag: Int = 0
        @Volatile private var rgBoostMbFlag: Int = 0

        fun setUserBoostMb(mb: Int) {
            userBoostMbFlag = mb.coerceIn(0, 2000)
            applyChainGain()
        }

        fun setReplayGainBoostMb(mb: Int) {
            rgBoostMbFlag = mb.coerceIn(0, 1500)
            applyChainGain()
        }

        private fun applyChainGain() {
            val total = (userBoostMbFlag + rgBoostMbFlag).coerceAtMost(3000)
            setChainGain(total)
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "Loudness gain=${total}mB (user=$userBoostMbFlag rg=$rgBoostMbFlag) via chain"
            )
        }

        /** Internal — set by the crossfade controller. */
        internal fun setCrossfadeFactor(factor: Float) {
            crossfadeFactor = factor.coerceIn(0.0f, 1.0f)
            applyMixedVolume()
        }

        /** Internal — read by the crossfade tick to skip redundant
         *  setVolume calls. */
        internal fun crossfadeFactorRead(): Float = crossfadeFactor

        /** Mute (0) / unmute (1) the local player WITHOUT a direct volume
         *  write — used while the local player only shows the picture during
         *  an audio-only cast. The crossfade tick re-asserts volume via
         *  applyMixedVolume, which multiplies this factor in, so the mute
         *  sticks. */
        internal fun setCastLocalVideoMute(mute: Boolean) {
            castLocalVideoMuteFactor = if (mute) 0.0f else 1.0f
            applyMixedVolume()
        }

        private fun applyMixedVolume() {
            val p = getExoPlayer() ?: return
            val v = (replayGainFactor * crossfadeFactor * castLocalVideoMuteFactor)
                .coerceIn(0.0f, 1.0f)
            // ExoPlayer.volume is main-thread-only; post if needed.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runCatching { p.volume = v }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { p.volume = v }
                }
            }
        }
    }

    /**
     * Registered in onCreate, unregistered in onDestroy. Fires whenever
     * Android routes audio to a different device (BT connect/disconnect,
     * headphones plug, speaker fallback). Lets the log reader correlate
     * "playback resumed" events with the routing change that triggered
     * them, which is the only way to tell a BT-reconnect resume from a
     * notification-tap resume.
     */
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    /** Friendly name for an AudioDeviceInfo.TYPE_* int. */
    private fun audioDeviceTypeName(t: Int): String = when (t) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        android.media.AudioDeviceInfo.TYPE_BUS -> "BUS"
        16 /* TYPE_TELEPHONY */ -> "TELEPHONY"
        18 /* TYPE_BUILTIN_MIC */ -> "BUILTIN_MIC"
        27 /* TYPE_REMOTE_SUBMIX */ -> "REMOTE_SUBMIX"
        29 /* TYPE_DOCK */ -> "DOCK"
        else -> "TYPE_$t"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        com.powermediaplayer.diag.DiagLog.lifecycle("PlaybackService.onCreate START")

        // Audit 2.1 — warm the DataStore with ONE read. The four seed
        // reads below each paid a sequential cold file read on the main
        // thread (worst case ~1.2s of service-start budget). DataStore
        // serves from memory once the first read lands, so after this
        // block their .first() calls are effectively free. Their own
        // timeouts/defaults stay as the per-value safety nets.
        val warmMs = android.os.SystemClock.uptimeMillis()
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(600) { settingsDataStore.snapshot() }
            }
        }
        com.powermediaplayer.diag.DiagLog.perf(
            "service.prefsWarmup",
            android.os.SystemClock.uptimeMillis() - warmMs
        )

        // Configure renderers honouring the user's Settings → Software/
        // Hardware decoding toggle. Read with a 200 ms timeout so a
        // sluggish DataStore can never ANR the foreground service
        // start. Default = HW (false) when the read times out — the
        // user can toggle and restart playback to apply.
        val swPreferred = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    settingsDataStore.useSoftwareDecoding.first()
                }
            }
        }.getOrNull() ?: false
        val rendererMode = if (swPreferred)
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "PlaybackService renderer mode swPreferred=$swPreferred extMode=$rendererMode"
        )
        // Custom RenderersFactory injects our StereoTransformProcessor
        // (Stereo flip + Mono mix) into the audio sink. Surround
        // streams (5.1 / 7.1 / Atmos) bypass our processor because
        // its onConfigure rejects non-16-bit-stereo formats — leaving
        // multi-channel output untouched, which is the right
        // behaviour for passthrough to AVRs.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                val defaultProvider = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder().build()
                // Low-latency provider — when the Settings toggle is
                // on we return the platform-minimum buffer (snappier
                // pitch / speed / effect changes; higher dropout risk).
                // Anonymous-object form is the portable across Media3
                // minor versions (some refactor the package between
                // 1.5 / 1.6 / 1.7).
                val lowLatencyProvider = object :
                    androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider {
                    override fun getBufferSizeInBytes(
                        minBufferSizeInBytes: Int,
                        encoding: Int,
                        outputMode: Int,
                        pcmFrameSize: Int,
                        sampleRate: Int,
                        bitrate: Int,
                        maxAudioTrackPlaybackSpeed: Double
                    ): Int = minBufferSizeInBytes
                }
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink
                            .DefaultAudioProcessorChain(
                                stereoTransformProcessor,
                                reverbProcessor,
                                // 10-band EQ AFTER reverb — reverb's input is
                                // unchanged so its behaviour is untouched; the
                                // EQ shapes the final tone. Flat = pass-through.
                                equalizerProcessor,
                                audioDelayProcessor,
                                gainProcessor,
                                // §Hue PCM tap — passes audio through;
                                // exposes the latest FFT analyser
                                // result for HueEntertainment to read.
                                hueAnalyserProcessor
                            )
                    )
                    .setAudioTrackBufferSizeProvider(
                        object :
                            androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider {
                            override fun getBufferSizeInBytes(
                                minBufferSizeInBytes: Int,
                                encoding: Int,
                                outputMode: Int,
                                pcmFrameSize: Int,
                                sampleRate: Int,
                                bitrate: Int,
                                maxAudioTrackPlaybackSpeed: Double
                            ): Int = if (audioBufferLowLatencyFlag) {
                                lowLatencyProvider.getBufferSizeInBytes(
                                    minBufferSizeInBytes, encoding, outputMode,
                                    pcmFrameSize, sampleRate, bitrate,
                                    maxAudioTrackPlaybackSpeed
                                )
                            } else {
                                defaultProvider.getBufferSizeInBytes(
                                    minBufferSizeInBytes, encoding, outputMode,
                                    pcmFrameSize, sampleRate, bitrate,
                                    maxAudioTrackPlaybackSpeed
                                )
                            }
                        }
                    )
                    .build()
            }

            // Inject the offset-aware video renderer so the BT video offset can
            // hold the picture back to match late Bluetooth audio. Default
            // renderers are still added (fallback / extension decoders); ours is
            // inserted FIRST so it handles standard video. At offset 0 it is
            // byte-for-byte the default, so normal playback is unaffected.
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, eventHandler, eventListener,
                    allowedVideoJoiningTimeMs, out
                )
                runCatching {
                    out.add(
                        0,
                        OffsetVideoRenderer(
                            androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                                .setCodecAdapterFactory(codecAdapterFactory)
                                .setMediaCodecSelector(mediaCodecSelector)
                                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                                .setEnableDecoderFallback(enableDecoderFallback)
                                .setEventHandler(eventHandler)
                                .setEventListener(eventListener)
                                .setMaxDroppedFramesToNotify(50),
                            // Slider value — applied IMMEDIATELY so tuning is
                            // responsive (a ramp here made the slider feel dead).
                            offsetUsSupplier = { btVideoOffsetUsFlag },
                            // Gate — eased on/off inside the renderer. Delay the
                            // picture ONLY when Bluetooth is genuinely the app's
                            // live output: not while rerouted to the phone speaker
                            // (BT kept on) and not while casting (audio is on the
                            // cast device).
                            gateSupplier = {
                                btVideoRouteActive &&
                                    !audioRerouteActiveFlow.value &&
                                    !castActiveFlow.value
                            }
                        )
                    )
                    // Diagnostic: prove OUR renderer is in the video list + its
                    // order (selector picks the lowest index on a capability tie).
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "BTVID videoRenderers=${out.map { it.javaClass.simpleName }}"
                    )
                }
            }
        }.setExtensionRendererMode(rendererMode)

        // Watch the stereo flip / mono mix prefs from DataStore and
        // mirror into the @Volatile flags the processor reads on every
        // input buffer. §C7: per-file override wins when present;
        // global setting otherwise. No restart of playback required.
        serviceScope.launch {
            mediaOverrideRepo.withOverrideBool(
                settingsDataStore.stereoFlip,
                pick = { it.stereoFlip }
            ).collect { stereoFlipFlag = it }
        }
        serviceScope.launch {
            mediaOverrideRepo.withOverrideBool(
                settingsDataStore.monoMix,
                pick = { it.monoMix }
            ).collect { monoMixFlag = it }
        }
        // Audio delay slider — lives in the AudioDelayProcessor's
        // ring buffer; the supplier reads this @Volatile per buffer.
        // Audio-delay slider ONLY feeds the audio processor now. The BT video
        // offset moved to the VIDEO renderer (OffsetVideoRenderer): for late
        // Bluetooth audio you must delay the VIDEO — delaying the audio (the
        // old summed behaviour) pushed it later still (worse) and negatives
        // were clamped to 0, so the slider "did nothing". Now SEPARATE: audio
        // delay → audio, BT offset → video.
        serviceScope.launch {
            settingsDataStore.audioDelayMs.collect { audioDelayFlag = it }
        }
        // BT video offset → delay the VIDEO frames (positive = hold the picture
        // back to match late BT audio). Read live per frame by OffsetVideoRenderer.
        // Clamp ≥0 defensively: you can only DELAY the picture (a stored negative
        // from an older build would advance frames = no useful effect for BT).
        serviceScope.launch {
            settingsDataStore.btVideoAudioOffsetMs.collect {
                btVideoOffsetUsFlag = it.coerceAtLeast(0) * 1000L
                // Diagnostic: prove the slider reaches the service AND compare the
                // gate flag against GROUND TRUTH (a fresh enumeration of output
                // devices), so a stuck gate is provable rather than guessed.
                val btOut = runCatching {
                    getSystemService(android.media.AudioManager::class.java)
                        ?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        ?.any { d -> d.isSink && (
                            d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            d.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER) } == true
                }.getOrDefault(false)
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "BTVID slider=${it}ms flag=${btVideoOffsetUsFlag}us gate.routeActive=$btVideoRouteActive " +
                        "groundTruth.btOutput=$btOut reroute=${Companion.audioRerouteActiveFlow.value} " +
                        "cast=${Companion.castActiveFlow.value}"
                )
            }
        }
        // Cast A/V offset — separate from the local AudioDelayProcessor sum
        // above. The flag updates IMMEDIATELY (so the cast listener uses the
        // latest value)…
        serviceScope.launch {
            settingsDataStore.castVideoAudioOffsetMs.collect { castVideoAudioOffsetMsFlag = it }
        }
        // …but the on-phone picture is re-seeked only AFTER the slider settles
        // (debounce). Seeking on every drag tick flooded the local player with
        // seeks — jittery rendering + file-read thrashing that starved the
        // cast relay.
        serviceScope.launch {
            settingsDataStore.castVideoAudioOffsetMs
                .debounce(350)
                .collect { off ->
                    if (castLocalVideoActive && castInitialSyncDone) {
                        val basePos = castPlayer?.currentPosition ?: player?.currentPosition ?: 0L
                        runCatching { player?.seekTo((basePos + off).coerceAtLeast(0L)) }
                    }
                }
        }
        // Low-latency audio buffer flag — re-read by the
        // AudioTrackBufferSizeProvider on every AudioTrack init.
        serviceScope.launch {
            settingsDataStore.audioBufferLowLatency
                .distinctUntilChanged()
                .collect {
                    audioBufferLowLatencyFlag = it
                    com.powermediaplayer.diag.DiagLog.settings(
                        "audioBufferLowLatency → $it (applies on next AudioTrack init)"
                    )
                }
        }
        // Subtitle delay — flag is read by ShiftingSubtitleParserFactory
        // at parse-time. Live changes need the parser to re-run, so we
        // also force a re-prepare of the current MediaItem on each new
        // value (rebuilds the SubtitleConfiguration round-trip). Skip
        // re-prepare when nothing is loaded yet (cold start).
        serviceScope.launch {
            settingsDataStore.subtitleDelayMs
                .distinctUntilChanged()
                .collect { newDelayMs ->
                    val prior = subtitleDelayMsFlag
                    subtitleDelayMsFlag = newDelayMs
                    if (prior == newDelayMs) return@collect
                    val p = player ?: return@collect
                    val current = p.currentMediaItem ?: return@collect
                    val pos = p.currentPosition
                    val playing = p.playWhenReady
                    // Rebuild the queue with same item so the parser
                    // re-runs with the new shift. setMediaItems triggers
                    // re-extraction; seekTo restores position.
                    runCatching {
                        com.powermediaplayer.diag.DiagLog.player(
                            "subtitle delay → ${newDelayMs}ms (re-preparing media item to apply)"
                        )
                        p.setMediaItem(current, pos)
                        p.playWhenReady = playing
                    }
                }
        }
        // Crossfade slider — drives the volume-ramp coroutine
        // started below in onCreate after the player is built.
        // Phase 4: master `crossfadeEnabled` toggle gates the slider —
        // when OFF we force-zero the effective duration so the existing
        // ramp path is a no-op without losing the user's preferred
        // duration. The 8 sub-toggles (curve / album mode / skip
        // silence / pre-fade trigger / manual fade-now / fade-out-on-
        // pause / fade-in-on-resume) plus true 2-player overlap are
        // separate engine work scheduled for a follow-up commit; the
        // panel surface ships now so users can preview their config.
        serviceScope.launch {
            // §B5 effectiveCrossfadeEnabled — the user's preference is
            // auto-greyed when the active source can't crossfade
            // (Cast / Spotify Connect / video / audiobook). The
            // PERSISTED preference is untouched; we just zero the
            // effective ms in the audio path so the engine is a no-op.
            kotlinx.coroutines.flow.combine(
                settingsDataStore.crossfadeMs,
                settingsDataStore.crossfadeEnabled
            ) { ms, enabled -> if (enabled) ms else 0 }
                .collect { incoming ->
                    val canX = canCrossfadeNow()
                    val effective = if (canX) incoming else 0
                    if (effective != crossfadeMsFlag) {
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Crossfade ms=$effective " +
                                "(persisted=$incoming, canCrossfade=$canX)"
                        )
                    }
                    crossfadeMsFlag = effective
                    crossfadeMsFlow.value = effective
                    // §B5 — record the auto-revert reason on the
                    // companion-object holder so PlayerViewModel can
                    // surface a Snackbar without us needing the
                    // PlaybackConnection here.
                    crossfadeAutoRevertReason = if (incoming > 0 && !canX) {
                        val active = mediaSession?.player
                        when {
                            active is androidx.media3.cast.CastPlayer ->
                                "Crossfade paused — Cast manages its own playback."
                            else ->
                                "Crossfade paused — current source can't crossfade."
                        }
                    } else null
                }
        }
        serviceScope.launch {
            settingsDataStore.crossfadeCurve.collect { crossfadeCurveFlag = it }
        }
        // §B2 Pre-fade trigger — drives the fade-out window's start
        // boundary; fade begins at (trackEnd - preFadeTriggerS).
        serviceScope.launch {
            settingsDataStore.crossfadePreFadeTriggerS
                .collect { crossfadePreFadeTriggerSFlag = it }
        }
        // §B2 / D6 fix — Skip silence now drives ExoPlayer's built-in
        // SilenceSkippingAudioProcessor via setSkipSilenceEnabled,
        // so the audible playback stream itself drops sub-threshold
        // segments. The +250 ms trigger shift in applyCrossfadeTick
        // remains as belt-and-braces for the crossfade ramp's start.
        serviceScope.launch {
            settingsDataStore.crossfadeSkipSilence
                .collect { v ->
                    crossfadeSkipSilenceFlag = v
                    runCatching { player?.skipSilenceEnabled = v }
                }
        }

        // §B2 Album mode — when ON, skip the fade between two
        // consecutive tracks with the same album metadata so the
        // listener keeps the artist's intended gap. Read by
        // applyCrossfadeTick to short-circuit the fade-out window.
        serviceScope.launch {
            settingsDataStore.crossfadeAlbumMode.collect { crossfadeAlbumModeFlag = it }
        }

        // §C22 — auto-play on headphone plug-in. ACTION_HEADSET_PLUG
        // is a runtime-only registration (cannot be declared in the
        // manifest). State extra: 0 = unplugged, 1 = plugged. Fires
        // play() when plug detected AND user toggled auto-resume on
        // AND a MediaItem is loaded paused.
        val headphoneReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != android.content.Intent.ACTION_HEADSET_PLUG) return
                if (intent.getIntExtra("state", -1) != 1) return
                serviceScope.launch {
                    val enabled = settingsDataStore.headphonePlugAutoplay.first()
                    if (!enabled) return@launch
                    val p = exoPlayerRef?.get() ?: return@launch
                    if (p.isPlaying) return@launch
                    if (p.currentMediaItem == null) return@launch
                    runCatching {
                        p.play()
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Headphone-plug auto-resume fired"
                        )
                    }
                }
            }
        }
        registerReceiver(
            headphoneReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_HEADSET_PLUG),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )
        this.headphonePlugReceiver = headphoneReceiver

        // Mp4 extractor with edit-list workaround — required for many M4B
        // audiobooks (especially Audible-converted) whose elst boxes confuse
        // the default extractor and cause audio to drop after chapter 1.
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        // Stamp googleapis.com requests with the user's Drive OAuth bearer
        // token before each open(). Non-Drive URIs pass through unchanged.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val resolvingHttpFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
            if (spec.uri.host?.contains("googleapis.com") == true) {
                // googleapis.com URLs only originate from the Drive REST
                // provider (drive.file scope); the SAF provider returns
                // content:// URIs that bypass HTTP entirely.
                val token = driveOAuthProvider.fetchAccessTokenBlocking()
                if (token != null) {
                    val newHeaders = spec.httpRequestHeaders.toMutableMap().apply {
                        put("Authorization", "Bearer $token")
                    }
                    spec.buildUpon().setHttpRequestHeaders(newHeaders).build()
                } else spec
            } else spec
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, resolvingHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            // Subtitle delay slider — previously persisted but never
            // reached the cue stream. The ShiftingSubtitleParserFactory
            // intercepts every CuesWithTiming emitted by the default
            // parser and shifts startTimeUs by the current delay flag.
            // Live changes take effect on the next subtitle reload
            // (handled by the existing setSubtitleConfigurations path
            // when the user adjusts the slider with a track loaded).
            .setSubtitleParserFactory(
                com.powermediaplayer.subtitles.ShiftingSubtitleParserFactory(
                    delayMsSupplier = { subtitleDelayMsFlag }
                )
            )

        // LoadControl tuned for snappy local-file seeks:
        //  - maxBufferMs 120 s so far forward scrubs land inside buffered
        //    content (avoids decoder re-init).
        //  - bufferForPlaybackMs 1000 (was 2500): playback starts with
        //    1 s pre-buffered instead of 2.5 s — visible as faster
        //    seek-resume after the rebuffer.
        //  - bufferForPlaybackAfterRebufferMs 1000 (was 5000): the gate
        //    that controls how quickly playback resumes after a seek
        //    stalls into BUFFERING. 5 s was a safety cushion for
        //    network streams; for local files 1 s is plenty and
        //    eliminates ~4 s of post-seek pause that was contributing
        //    to the "stutter on backward / large skip" perception.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                /* maxBufferMs */            120_000,
                /* bufferForPlaybackMs */    1_000,
                /* bufferForPlaybackAfterRebufferMs */
                                             1_000
            )
            .build()

        // Defensive: snapshot the user's BT skip seconds so we can wire
        // them into Player.seekForward() / seekBack() — used by any AVRCP
        // path that bypasses our onPlayerCommandRequest interception.
        // Snapshot synchronously with a short timeout so a sluggish
        // DataStore can never ANR service start; defaults (30s/30s in
        // BtMappingSnapshot) apply on timeout. Media3 1.6.0 only exposes
        // these as Builder setters (no runtime mutation), so live changes
        // to the BT seconds don't update the increments — but the live
        // BT path is already covered by the callback in Tasks 1 & 2.
        val initialBtMapping = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    settingsDataStore.btMappingSnapshot()
                }
            }
        }.getOrNull() ?: BtMappingSnapshot(
            prevAction = BluetoothMediaActions.PREV_TRACK,
            nextAction = BluetoothMediaActions.NEXT_TRACK,
            skipBackSeconds = 30,
            skipForwardSeconds = 30
        )

        // Build ExoPlayer with audio focus and wake lock.
        // Audio offload is left at the default (DISABLED) so AudioSink can
        // be re-initialised on sample-rate changes mid-stream — common in
        // chapter-concatenated M4B files.
        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // §C14 — disable ExoPlayer's built-in handler; the
                // installAudioFocusPolicy() call below registers our
                // own AudioFocusRequest with per-scenario semantics
                // (pause / duck / continue) read from SettingsDataStore.
                /* handleAudioFocus */ false
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(initialBtMapping.skipBackSeconds * 1000L)
            .setSeekForwardIncrementMs(initialBtMapping.skipForwardSeconds * 1000L)
            // 3-second rule for Prev: if position > 3000ms, seekToPrevious()
            // restarts the current item instead of going to the previous one.
            // Cross-app standard (Spotify, Apple Music, Pocket Casts, Media3).
            .setMaxSeekToPreviousPositionMs(3000L)
            .build()

        // PREVIOUS_SYNC always seeks to the keyframe BEFORE the requested
        // position. Two reasons over the previous CLOSEST_SYNC:
        //   1. Always responsive — the decoder never has to decode forward
        //      from a prior keyframe to reach the exact target.
        //   2. Direction-correct — a back-skip with CLOSEST_SYNC could
        //      snap to a keyframe AHEAD of the requested target, making
        //      the skip appear not to happen. PREVIOUS_SYNC guarantees
        //      every back-skip moves backward and every fwd-skip lands
        //      at-or-before the requested position (visible as a few
        //      seconds of imprecision, which is what every other media
        //      player does).
        player!!.setSeekParameters(SeekParameters.PREVIOUS_SYNC)
        // Frame-rate strategy left at default. Forcing OFF on a Z Fold
        // 6 (1-120Hz adaptive panel) made things worse — the panel
        // can match content rate which is usually best.

        // Cold-start sync: read pitch + speed + audio offset from
        // DataStore synchronously and seed the player BEFORE first
        // playback so the user doesn't hear unwanted pitch/speed for
        // the first ~100 ms while the async flow collectors catch up.
        // 600 ms timeout safety net (raised from 200 ms — testers
        // reported "half a second of un-pitched audio" on cold start;
        // DataStore cold reads can take 300–500 ms under load and were
        // missing the 200 ms window). Defaults applied on miss.
        val seedStartMs = android.os.SystemClock.uptimeMillis()
        val seedOk = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    val pitch = settingsDataStore.pitchIndependent.first()
                    val speed = settingsDataStore.playbackSpeed.first()
                    player!!.playbackParameters = androidx.media3.common.PlaybackParameters(
                        speed.coerceIn(0.25f, 4f),
                        pitch.coerceIn(0.5f, 2f)
                    )
                    com.powermediaplayer.diag.DiagLog.player(
                        "cold-start seeded speed=$speed pitch=$pitch " +
                            "in=${android.os.SystemClock.uptimeMillis() - seedStartMs}ms"
                    )
                    true
                }
            }
        }.getOrNull() == true
        if (!seedOk) {
            com.powermediaplayer.diag.DiagLog.player(
                "cold-start seed TIMED OUT after " +
                    "${android.os.SystemClock.uptimeMillis() - seedStartMs}ms — " +
                    "playback may start at defaults until DataStore catches up"
            )
        }

        // vc29.27 — prime the audio-focus policy cache synchronously
        // BEFORE registering the OS focus listener. Closes the cold-
        // start window where an immediate focus loss event (e.g.
        // ringing call ~50 ms after service onCreate) would otherwise
        // use the @Volatile defaults instead of the user's stored
        // overrides. withTimeoutOrNull(200) protects against an
        // unresponsive DataStore actor.
        kotlinx.coroutines.runBlocking {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    focusPolicyOnCall = settingsDataStore.audioFocusOnCall.first()
                    focusPolicyOnNotif = settingsDataStore.audioFocusOnNotification.first()
                    focusPolicyOnOther = settingsDataStore.audioFocusOnOtherMedia.first()
                }
            }
        }

        // §C14 — install per-scenario audio-focus policy.
        installAudioFocusPolicy()

        // Publish the real ExoPlayer so VideoSurface can attach to it for rendering
        exoPlayerRef = java.lang.ref.WeakReference(player!!)

        // Crossfade controller. Polls position and applies a linear
        // volume ramp via [setCrossfadeFactor] in the final
        // crossfadeMs window of each track, then ramps back up after
        // the new track starts. ExoPlayer doesn't actually overlap
        // tracks — what the listener perceives as a crossfade is
        // really a fast fade-out followed by fade-in. crossfadeMs == 0
        // forces the factor back to 1.0 immediately so the slider
        // value is always honoured live.
        startCrossfadeController()

        // ── Diagnostic Player.Listener ──────────────────────────────
        // Independent listener (separate from the crossfade one above)
        // so changing logging volume / detail later doesn't risk
        // touching the crossfade engine. Every line goes through DiagLog
        // which is a no-op when the user toggle is off — zero cost.
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                com.powermediaplayer.diag.DiagLog.player("state=$name")
                // Webhook: track-end fires once when the player reaches
                // STATE_ENDED. PLAY / PAUSE / RESUME / SKIP webhooks fire
                // elsewhere on their natural events.
                if (playbackState == Player.STATE_ENDED) {
                    val p = player ?: return
                    webhookEmitter.fire(
                        com.powermediaplayer.webhooks.WebhookEmitter.Event.END,
                        p.currentMediaItem?.mediaId,
                        p.currentPosition,
                        p.duration.coerceAtLeast(0L)
                    )
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val p = player
                com.powermediaplayer.diag.DiagLog.player(
                    "isPlaying=$isPlaying pos=${p?.currentPosition}ms " +
                        "playWhenReady=${p?.playWhenReady} mediaId=${com.powermediaplayer.diag.DiagLog.hash(p?.currentMediaItem?.mediaId)}"
                )
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val rname = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_ITEM"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                    else -> "REASON_$reason"
                }
                com.powermediaplayer.diag.DiagLog.player(
                    "playWhenReady=$playWhenReady reason=$rname"
                )
                // Webhooks: pause vs resume only fire on USER-initiated
                // changes — avoids firing for AUDIO_FOCUS_LOSS pauses
                // (call, alarm) which the user didn't trigger.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    val p = player ?: return
                    val ev = if (playWhenReady)
                        com.powermediaplayer.webhooks.WebhookEmitter.Event.RESUME
                    else
                        com.powermediaplayer.webhooks.WebhookEmitter.Event.PAUSE
                    webhookEmitter.fire(
                        ev,
                        p.currentMediaItem?.mediaId,
                        p.currentPosition,
                        p.duration.coerceAtLeast(0L)
                    )
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val rname = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    else -> "REASON_$reason"
                }
                com.powermediaplayer.diag.DiagLog.player(
                    "mediaItemTransition reason=$rname id=${com.powermediaplayer.diag.DiagLog.hash(mediaItem?.mediaId)}"
                )
                // Webhooks: new track started. PLAYLIST_CHANGED fires on
                // both fresh tap-to-play AND on cold-start adopt. AUTO
                // fires on advance to next item in queue. Both count as
                // "play" event for downstream automations.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                ) {
                    val p = player ?: return
                    webhookEmitter.fire(
                        com.powermediaplayer.webhooks.WebhookEmitter.Event.PLAY,
                        mediaItem?.mediaId,
                        0L,
                        p.duration.coerceAtLeast(0L)
                    )
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val rname = when (reason) {
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                    else -> "REASON_$reason"
                }
                com.powermediaplayer.diag.DiagLog.player(
                    "discontinuity reason=$rname from=${oldPosition.positionMs}ms to=${newPosition.positionMs}ms"
                )
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                com.powermediaplayer.diag.DiagLog.player(
                    "ERROR code=${error.errorCode} name=${error.errorCodeName} msg=${error.message}"
                )
            }
        })

        // ── Audio device routing callback ───────────────────────────
        // No permission required (in contrast to BluetoothA2dp profile
        // listening which needs BLUETOOTH_CONNECT on API 31+). Fires on
        // every routing change: BT connect/disconnect, headphones plug/
        // unplug, speaker fallback, USB audio, Cast Audio.
        runCatching {
            val am = getSystemService(android.media.AudioManager::class.java)
            audioDeviceCallback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    addedDevices?.forEach { d ->
                        com.powermediaplayer.diag.DiagLog.route(
                            "device+ type=${audioDeviceTypeName(d.type)} name='${d.productName}' " +
                                "id=${d.id} sink=${d.isSink} src=${d.isSource}"
                        )
                    }
                    // BT resume-on-connect: the old code only LOGGED, so plain
                    // Bluetooth speakers/headphones (which don't auto-send a
                    // media-PLAY key) never resumed. Now: when a Bluetooth audio
                    // SINK connects and the toggle is on, actually resume the
                    // LOCAL player (targeted so a CastPlayer can't absorb it).
                    val btSinkAdded = addedDevices?.any {
                        it.isSink && it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } == true
                    if (btSinkAdded) btVideoRouteActive = true
                    if (btSinkAdded) {
                        // A BT audio device connecting should ROUTE audio to it:
                        // (1) Clear any phone-speaker reroute. The BT-disconnect
                        //     button sets a preferred device; without clearing
                        //     it, audio stays on the phone speaker even after BT
                        //     reconnects ("won't connect back to Bluetooth").
                        Companion.clearAudioReroute()
                        val casting = mediaSession?.player is CastPlayer || castLocalVideoActive
                        if (casting) {
                            // (2) Failsafe (user-requested): a BT device takes
                            //     priority over casting — end the cast so audio
                            //     moves cleanly to BT instead of the cast device
                            //     playing on while BT sits connected-but-silent.
                            runCatching {
                                CastContext.getSharedInstance(applicationContext)
                                    .sessionManager.endCurrentSession(true)
                            }
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG", "BT connected while casting → ending cast (BT takes over)"
                            )
                        } else {
                            // (3) Resume-on-connect for plain BT (the toggle).
                            serviceScope.launch {
                                if (!settingsDataStore.resumeOnBt.first()) return@launch
                                // Spotify: the audio is on Spotify Connect; the
                                // LOCAL player is a silent mirror with NO media
                                // item, so p.currentMediaItem==null returned early
                                // and nothing resumed (BT resume failed on Spotify
                                // tracks). Resume Spotify Connect instead.
                                if (spotifyProvider.spotifyState.value != null) {
                                    val ok = runCatching { spotifyProvider.resume() }
                                        .getOrNull()?.isSuccess == true
                                    com.powermediaplayer.util.Diag.i(
                                        "PMP_DIAG",
                                        "BT resume-on-connect Spotify Connect: " +
                                            if (ok) "resumed" else "failed"
                                    )
                                    return@launch
                                }
                                val p = exoPlayerRef?.get() ?: return@launch
                                if (p.isPlaying || p.currentMediaItem == null) return@launch
                                runCatching {
                                    p.play()
                                    com.powermediaplayer.util.Diag.i(
                                        "PMP_DIAG", "BT resume-on-connect fired (local player)"
                                    )
                                }
                            }
                        }
                    }
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    removedDevices?.forEach { d ->
                        com.powermediaplayer.diag.DiagLog.route(
                            "device- type=${audioDeviceTypeName(d.type)} name='${d.productName}' " +
                                "id=${d.id} sink=${d.isSink} src=${d.isSource}"
                        )
                    }
                    // Re-evaluate whether BT is still the active route (drives the
                    // BT video-offset gate).
                    btVideoRouteActive = runCatching {
                        getSystemService(android.media.AudioManager::class.java)?.isBluetoothA2dpOn == true
                    }.getOrDefault(false)
                }
            }
            am?.registerAudioDeviceCallback(audioDeviceCallback, null)
            // Seed the BT-route flag from the current state at startup.
            btVideoRouteActive = runCatching { am?.isBluetoothA2dpOn == true }.getOrDefault(false)
            // Snapshot the initial routing state so a single car-test
            // log shows the device set the user started with.
            am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.forEach { d ->
                com.powermediaplayer.diag.DiagLog.route(
                    "device0 type=${audioDeviceTypeName(d.type)} " +
                        "name='${d.productName}' id=${d.id}"
                )
            }
        }.onFailure {
            com.powermediaplayer.diag.DiagLog.lifecycle("AudioDeviceCallback register failed: ${it.message}")
        }

        // ── Settings snapshot ───────────────────────────────────────
        // One-shot dump of every BT-relevant setting at service start so
        // the log reader knows the user's configuration before any
        // events fire. Reads have already happened above for pitch/speed/
        // sw-decoding/bt-mapping — re-read here only to log atomically.
        serviceScope.launch {
            runCatching {
                val snap = StringBuilder("snapshot ")
                snap.append("prevAct=").append(settingsDataStore.btPrevAction.first()).append(' ')
                snap.append("nextAct=").append(settingsDataStore.btNextAction.first()).append(' ')
                snap.append("skipBack=").append(settingsDataStore.btSkipBackSeconds.first()).append("s ")
                snap.append("skipFwd=").append(settingsDataStore.btSkipForwardSeconds.first()).append("s ")
                snap.append("resumeOnBt=").append(settingsDataStore.resumeOnBt.first()).append(' ')
                snap.append("headphoneAutoplay=").append(settingsDataStore.headphonePlugAutoplay.first()).append(' ')
                snap.append("btVideoAudioOffsetMs=").append(settingsDataStore.btVideoAudioOffsetMs.first()).append(' ')
                snap.append("coldStartBackoffSec=").append(settingsDataStore.coldStartResumeBackoffSec.first()).append(' ')
                snap.append("stopOnTaskRemoved=").append(settingsDataStore.stopOnTaskRemoved.first()).append(' ')
                snap.append("speed=").append(settingsDataStore.playbackSpeed.first()).append(' ')
                snap.append("pitch=").append(settingsDataStore.pitchIndependent.first())
                com.powermediaplayer.diag.DiagLog.settings(snap.toString())
            }.onFailure {
                com.powermediaplayer.diag.DiagLog.settings("snapshot failed: ${it.message}")
            }
        }

        // ── BT-mapping change watcher ──────────────────────────────
        // Logs every change to the four BT-mapping settings so the test
        // log shows when the user switched prev/next remap mid-session.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.btPrevAction,
                settingsDataStore.btNextAction,
                settingsDataStore.btSkipBackSeconds,
                settingsDataStore.btSkipForwardSeconds
            ) { prev, next, back, fwd ->
                "prev=$prev next=$next back=${back}s fwd=${fwd}s"
            }.distinctUntilChanged().collect {
                com.powermediaplayer.diag.DiagLog.settings("BT-mapping change → $it")
            }
        }
        // Also log resumeOnBt + headphoneAutoplay flips so we know when
        // the user toggled them relative to a BT connect event.
        serviceScope.launch {
            settingsDataStore.resumeOnBt.distinctUntilChanged().collect {
                com.powermediaplayer.diag.DiagLog.settings("resumeOnBt → $it")
            }
        }

        // vc29.26 — cache audio-focus policy. Eliminates 2 async + 2
        // launch coroutines per AudioFocus event (which can fire
        // rapidly during BT call ping-pong).
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.audioFocusOnCall,
                settingsDataStore.audioFocusOnNotification,
                settingsDataStore.audioFocusOnOtherMedia
            ) { c, n, o -> Triple(c, n, o) }
                .distinctUntilChanged()
                .collect { (c, n, o) ->
                    focusPolicyOnCall = c
                    focusPolicyOnNotif = n
                    focusPolicyOnOther = o
                }
        }

        // Hue Entertainment audio-reactive — single intensity-driven
        // path. When (paired + intensity > 0 + playback active) the
        // analyser feeds beat / band / BPM data into the DTLS stream.
        // No separate mode picker; the slider is the only control.
        serviceScope.launch {
            // vc29.26 — was a 500 ms polling tick forever. Replaced
            // with an event-driven callbackFlow bound to ExoPlayer's
            // Player.Listener so the CPU can actually sleep when not
            // playing. Saves measurable battery on long idle periods.
            val playingFlow = callbackFlow {
                val p = player
                trySend(p?.isPlaying == true)
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        trySend(isPlaying)
                    }
                }
                p?.addListener(listener)
                awaitClose { p?.removeListener(listener) }
            }.distinctUntilChanged()
            // vc32: hueSelectedArea is now a COLLECTOR SOURCE.
            // Previously it was only read one-shot inside the handler, so
            // (a) re-picking an area after Disconnect never re-fired the
            // collector and (b) switching rooms mid-stream kept driving
            // the OLD room.
            kotlinx.coroutines.flow.combine(
                settingsDataStore.hueReactiveIntensity,
                playingFlow,
                settingsDataStore.hueSelectedArea
            ) { intensity, isPlaying, areaKey -> Triple(intensity, isPlaying, areaKey) }
                .distinctUntilChanged()
                .collect { (intensity, isPlaying, areaKey) ->
                    // Spotify Connect + Cast — audio plays on a remote
                    // device, not through our AudioProcessor chain, so
                    // the PCM tap sees silence. Don't start the
                    // reactive engine for those sources; the user sees
                    // an explanatory note in Settings (the slider stays
                    // ineffectual).
                    val activePlayer = mediaSession?.player
                    val isCast = activePlayer is androidx.media3.cast.CastPlayer
                    val isSpotify = spotifyProvider.spotifyState.value != null
                    // vc31 — full gating snapshot every time the collector
                    // fires. The reconnect-regression report needs to see
                    // WHY a re-pick does/doesn't restart: in particular
                    // whether isStreaming() short-circuits with a stale
                    // stream after a disconnect, and what hueSelectedArea
                    // resolves to at this moment.
                    com.powermediaplayer.diag.DiagLog.event(
                        "HUE",
                        "collector fire intensity=$intensity isPlaying=$isPlaying " +
                            "isCast=$isCast isSpotify=$isSpotify " +
                            "isStreaming=${hueEntertainment.isStreaming()} " +
                            "selectedArea=$areaKey activeStreamArea=$activeHueAreaKey"
                    )
                    // Audit 3.4 — the FFT analyser runs only when the
                    // reactive engine can consume it; everyone else gets
                    // a pure passthrough on the audio thread.
                    hueAnalyserProcessor.setAnalysis(
                        intensity > 0 && isPlaying && !isCast && !isSpotify &&
                            areaKey.isNotBlank()
                    )
                    // vc32: a BLANK area now means OFF, full stop —
                    // an explicit Disconnect must kill the stream even
                    // though intensity is no longer zeroed. (Behaviour
                    // change for pre-vc29 installs that relied on the
                    // blank→first-entertainment-area fallback: they pick
                    // an area once in Settings.)
                    if (intensity > 0 && isPlaying && !isCast && !isSpotify &&
                        areaKey.isNotBlank()
                    ) {
                        // vc29.10 — skip the whole bridge query chain
                        // (listAreas / listAllLights / ensureConfig /
                        // fetchProfiles) if a stream is already up.
                        // Without this guard, every isPlaying flicker
                        // triggers ~4 unnecessary HTTPS calls.
                        // vc29.15 — but DO propagate the new
                        // sensitivity so slider moves during playback
                        // actually take effect. The engine reads
                        // liveIntensity each frame.
                        // vc32 — UNLESS the user moved to a
                        // different room/zone: the engine captured the
                        // old area at start, so an area change must
                        // stop + restart on the new one.
                        if (hueEntertainment.isStreaming()) {
                            if (areaKey != activeHueAreaKey) {
                                com.powermediaplayer.diag.DiagLog.event(
                                    "HUE",
                                    "area changed mid-stream ($activeHueAreaKey → $areaKey) — restarting engine"
                                )
                                hueEntertainment.stop()
                                // fall through to the start path below
                            } else {
                                hueEntertainment.setIntensity(intensity)
                                return@collect
                            }
                        }
                        // vc29 — resolve the user-picked area (room /
                        // zone / entertainment) from DataStore. The
                        // composite key is "<kind>:<uuid>" so we don't
                        // need a second roundtrip just to know what
                        // kind to look up.
                        val pickedKey = areaKey
                        val areas = runCatching { hueProvider.listAreas() }
                            .getOrDefault(emptyList())
                        val picked = if (pickedKey.isNotBlank()) {
                            val sep = pickedKey.indexOf(':')
                            val kindTok = pickedKey.substring(0, sep.coerceAtLeast(0))
                            val uuid = if (sep >= 0) pickedKey.substring(sep + 1) else pickedKey
                            val kind = when (kindTok) {
                                "room" -> com.powermediaplayer.hue.HueProvider.HueArea.Kind.ROOM
                                "zone" -> com.powermediaplayer.hue.HueProvider.HueArea.Kind.ZONE
                                "ent" -> com.powermediaplayer.hue.HueProvider.HueArea.Kind.ENTERTAINMENT
                                else -> null
                            }
                            areas.firstOrNull { it.id == uuid && (kind == null || it.kind == kind) }
                        } else null
                        // Fallback: first entertainment area if user
                        // hasn't picked yet — preserves vc28 behaviour
                        // for already-paired installs.
                        val area = picked
                            ?: areas.firstOrNull { it.kind == com.powermediaplayer.hue.HueProvider.HueArea.Kind.ENTERTAINMENT }
                        if (area == null) {
                            com.powermediaplayer.diag.DiagLog.event(
                                "HUE",
                                "auto-start skipped — no area picked and no entertainment areas on bridge"
                            )
                            return@collect
                        }
                        val lights = runCatching { hueProvider.listAllLights() }
                            .getOrDefault(emptyMap())
                        val breakdown = hueProvider.classifyArea(area, lights)
                        val ensured = runCatching {
                            hueProvider.ensureEntertainmentConfigForArea(area, breakdown)
                        }.getOrNull()
                        if (ensured == null || ensured.channelIds.isEmpty()) {
                            com.powermediaplayer.diag.DiagLog.event(
                                "HUE",
                                "auto-start skipped — could not ensure entertainment config " +
                                    "for area='${area.name}' (kind=${area.kind} colour=${breakdown.colour.size} " +
                                    "ambiance=${breakdown.ambiance.size})"
                            )
                            return@collect
                        }
                        val cappedChannels = ensured.channelIds.take(10) // bridge enforces ≤10
                        val cappedEnsured = com.powermediaplayer.hue.HueProvider.EnsuredArea(
                            ensured.areaId, cappedChannels
                        )
                        // DIMMABLE-only lights ride the parallel CLIP
                        // REST driver; ONOFF smart plugs are excluded
                        // outright (user directive — they don't pulse).
                        // Each bulb carries its own auto-detected
                        // command-to-photon latency so native Hue +
                        // IKEA / Tradfri / Innr / etc. stay in sync.
                        val dimmableIds = breakdown.dimmable.map { it.id }
                        val profiles = runCatching {
                            hueProvider.fetchBulbLatencyProfiles(dimmableIds)
                        }.getOrDefault(emptyMap())
                        val dimmableLights = dimmableIds.map { id ->
                            val p = profiles[id]
                            com.powermediaplayer.hue.HueDimmableDriver.DimmableLight(
                                id = id,
                                latencyMs = p?.latencyMs ?: 400,
                                maxSafeRateHz = p?.maxSafeRateHz ?: 1.0f
                            )
                        }
                        hueEntertainment.start(
                            cappedEnsured,
                            breakdown.colour.size,
                            dimmableLights,
                            intensity,
                            area.groupedLightId
                        )
                        // vc32: remember which area the running
                        // stream belongs to so a mid-stream room switch
                        // is detected and restarts the engine.
                        activeHueAreaKey = areaKey
                    } else {
                        if (intensity > 0 && isPlaying && (isCast || isSpotify)) {
                            com.powermediaplayer.diag.DiagLog.event(
                                "HUE",
                                "auto-start skipped — audio is on remote (isCast=$isCast isSpotify=$isSpotify); " +
                                    "PCM tap sees silence on these sources"
                            )
                        }
                        hueEntertainment.stop()
                        activeHueAreaKey = ""
                    }
                }
        }
        serviceScope.launch {
            settingsDataStore.headphonePlugAutoplay.distinctUntilChanged().collect {
                com.powermediaplayer.diag.DiagLog.settings("headphoneAutoplay → $it")
            }
        }

        com.powermediaplayer.diag.DiagLog.lifecycle("PlaybackService.onCreate DONE")

        // Create session activity intent for notification tap
        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build MediaSession with custom callback for skip actions +
        // a CustomLayout that places skip-back-15 / play-pause /
        // skip-forward-15 in the system notification (compact view).
        // Previous-track and Next-track come from the standard
        // Player commands so they appear automatically when the queue
        // supports them.
        @androidx.annotation.OptIn(UnstableApi::class)
        val customLayout = listOf(
            CommandButton.Builder()
                .setDisplayName("Back 15s")
                .setIconResId(android.R.drawable.ic_media_rew)
                .setSessionCommand(SessionCommand(ACTION_SKIP_BACK_15, Bundle.EMPTY))
                .build(),
            CommandButton.Builder()
                .setDisplayName("Forward 15s")
                .setIconResId(android.R.drawable.ic_media_ff)
                .setSessionCommand(SessionCommand(ACTION_SKIP_FORWARD_15, Bundle.EMPTY))
                .build()
        )
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(PlayerSessionCallback())
            .setCustomLayout(customLayout)
            .build()

        // Keep the Bluetooth mapping snapshot fresh. Combine into a
        // single Flow so we set the @Volatile field atomically. The
        // Player's seek increments are set once at Builder time above
        // (Media3 1.6.0 has no runtime setters); btMapping itself feeds
        // applyAction on every BT press so live changes still take
        // effect on the primary code path.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.btPrevAction,
                settingsDataStore.btNextAction,
                settingsDataStore.btSkipBackSeconds,
                settingsDataStore.btSkipForwardSeconds
            ) { prev, next, back, fwd ->
                BtMappingSnapshot(prev, next, back, fwd)
            }.collect { btMapping = it }
        }

        // Cast: initialize lazily; if Google Play Services is missing the
        // call throws and we run without cast support.
        try {
            val castContext = CastContext.getSharedInstance(this)
            val cp = CastPlayer(castContext)
            cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchPlayer(cp)
                override fun onCastSessionUnavailable() {
                    // Guard against NPE if cast session ends after the
                    // service has already torn down (player = null).
                    player?.let { switchPlayer(it) }
                }
            })
            // Cast local-video sync (EVENT-DRIVEN): while the picture is shown
            // on the phone during an audio-only cast, mirror the cast's
            // play/pause, user seeks, and track changes onto the local player.
            // No polling loop — fires only on real cast events, so the local
            // video never jitters (the old per-second seek loop caused the
            // "A-B loop" regression).
            cp.addListener(object : Player.Listener {
                // FIRST time the cast truly plays (post-buffer): align the
                // picture to the real cast position ONCE + resume it. We do NOT
                // react to later isPlaying flips — the cast toggles isPlaying on
                // every buffer underrun, and mirroring that made the picture
                // stutter + end up paused. cp.currentPosition is valid here
                // (the cast is READY), avoiding the stale-position seeks.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!castLocalVideoActive || castInitialSyncDone || !isPlaying) return
                    val lp = player ?: return
                    runCatching {
                        lp.seekTo((cp.currentPosition + castVideoAudioOffsetMsFlag).coerceAtLeast(0L))
                        lp.playWhenReady = true
                    }
                    castInitialSyncDone = true
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG", "Cast local-video: initial align done, picture resumed"
                    )
                }
                // After the initial align, follow the cast's INTENT (deliberate
                // play/pause). playWhenReady does NOT flip on buffering, so the
                // picture stays smooth instead of stuttering with the speaker.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (castLocalVideoActive && castInitialSyncDone) {
                        runCatching { player?.playWhenReady = playWhenReady }
                    }
                }
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    // Mirror only DELIBERATE user seeks, and only after the
                    // initial align (so early stale cast positions don't seek).
                    if (castLocalVideoActive && castInitialSyncDone &&
                        reason == Player.DISCONTINUITY_REASON_SEEK
                    ) {
                        val lp = player ?: return
                        runCatching {
                            lp.seekTo(
                                cp.currentMediaItemIndex
                                    .coerceIn(0, (lp.mediaItemCount - 1).coerceAtLeast(0)),
                                (cp.currentPosition + castVideoAudioOffsetMsFlag).coerceAtLeast(0L)
                            )
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!castLocalVideoActive || !castInitialSyncDone) return
                    val lp = player ?: return
                    val idx = cp.currentMediaItemIndex
                    if (idx in 0 until lp.mediaItemCount) {
                        runCatching {
                            lp.seekTo(idx, (cp.currentPosition + castVideoAudioOffsetMsFlag).coerceAtLeast(0L))
                        }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // The audio-only cast (relay m4a) failed to load/play — don't
                    // leave the local picture frozen + MUTED with no audio. End
                    // the cast so switchPlayer reverts to the local ExoPlayer
                    // (its teardown un-mutes + resumes), giving audio back on the
                    // phone instead of silent-forever.
                    if (castLocalVideoActive) {
                        com.powermediaplayer.util.Diag.w(
                            "PMP_DIAG",
                            "Cast audio-only error (${error.errorCodeName}) → reverting to local"
                        )
                        runCatching {
                            CastContext.getSharedInstance(applicationContext)
                                .sessionManager.endCurrentSession(true)
                        }
                    }
                }
            })
            castPlayer = cp

            // Bug fix (user-reported "I cast to A, then to B; A keeps
            // playing"): SessionAvailabilityListener only fires on
            // cast<->no-cast transitions. When the user switches devices
            // inside the picker, the SDK ends session A and starts B
            // without notifying the listener, leaving device A with the
            // last loaded URL — Google Home / Nest receivers happily
            // keep playing it. Hook SessionManagerListener so we can
            // explicitly clearMediaItems() before the channel closes.
            val sessionListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
                override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {
                    runCatching {
                        cp.clearMediaItems()
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG", "Cast onSessionEnding -> clearMediaItems"
                        )
                    }
                }
                override fun onSessionEnded(s: com.google.android.gms.cast.framework.CastSession, code: Int) {}
                override fun onSessionStarted(s: com.google.android.gms.cast.framework.CastSession, sid: String) {}
                override fun onSessionStarting(s: com.google.android.gms.cast.framework.CastSession) {}
                override fun onSessionResumed(s: com.google.android.gms.cast.framework.CastSession, w: Boolean) {}
                override fun onSessionResuming(s: com.google.android.gms.cast.framework.CastSession, sid: String) {}
                override fun onSessionResumeFailed(s: com.google.android.gms.cast.framework.CastSession, code: Int) {}
                override fun onSessionStartFailed(s: com.google.android.gms.cast.framework.CastSession, code: Int) {}
                override fun onSessionSuspended(s: com.google.android.gms.cast.framework.CastSession, code: Int) {}
            }
            castSessionListener = sessionListener
            castContext.sessionManager.addSessionManagerListener(
                sessionListener,
                com.google.android.gms.cast.framework.CastSession::class.java
            )
        } catch (_: Exception) {
            // No cast — phone has no Play Services or unsupported device.
        }
    }

    // ── Crossfade controller ───────────────────────────────────────
    // Tracks the elapsed-since-track-start time so the fade-in side
    // can ramp up from 0 → 1 over the configured crossfadeMs, and
    // tracks the remaining-until-track-end so the fade-out side can
    // ramp down. State is recomputed every 100 ms (cheap; only one
    // float multiply + a single setVolume() call when changing).
    private var crossfadeJob: kotlinx.coroutines.Job? = null

    // Audit 3.10 - play/pause + metadata events arrive in bursts; one
    // widget rebuild per 250ms quiet period instead of one per event.
    private var widgetRefreshJob: kotlinx.coroutines.Job? = null
    private fun scheduleWidgetRefresh() {
        widgetRefreshJob?.cancel()
        widgetRefreshJob = serviceScope.launch {
            kotlinx.coroutines.delay(250)
            com.powermediaplayer.widget.NowPlayingWidgetProvider.refresh(applicationContext)
        }
    }
    @Volatile private var trackStartTimestampMs: Long = 0L

    /**
     * §B4/B5 — true when the current source supports crossfade.
     * Returns false on any of:
     *  - active player is a CastPlayer (Cast receiver handles its own
     *    transitions; we don't have access to its mixer).
     *  - currentMediaItem is video (greyed per §B4 matrix).
     *  - currentMediaItem looks like an audiobook (M4B with chapters or
     *    explicit chapter_count extras).
     */
    private fun canCrossfadeNow(): Boolean {
        val ms = mediaSession ?: return true
        val active = ms.player
        if (active is androidx.media3.cast.CastPlayer) return false
        val item = active.currentMediaItem ?: return true
        // §B4 video → greyed.
        val mime = item.localConfiguration?.mimeType.orEmpty()
        if (mime.startsWith("video/")) return false
        // Audiobook detection (M4B or chapter_count > 0).
        val ext = item.localConfiguration?.uri?.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "m4b") return false
        val chapters = item.mediaMetadata.extras?.getInt("chapter_count", 0) ?: 0
        if (chapters > 0) return false
        return true
    }

    // ── §C14 audio-focus policy ─────────────────────────────────
    //
    // ExoPlayer's built-in handler is disabled (handleAudioFocus =
    // false above) because Media3 has no concept of per-scenario
    // pause / duck / continue choice. We register our own
    // [AudioFocusRequest] and route LOSS variants through the user's
    // settings:
    //   LOSS_TRANSIENT          → onCall setting
    //   LOSS_TRANSIENT_CAN_DUCK → onNotification setting
    //   LOSS                    → onOtherMedia setting
    //
    // Pre-O devices fall back to the deprecated requestAudioFocus()
    // shape; we use AudioManagerCompat for source-compat across both.
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var audioFocusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null
    @Volatile private var pausedDueToFocus: Boolean = false
    @Volatile private var duckedDueToFocus: Boolean = false
    // vc29.26 — audio-focus policy cached (was 2 async + 2 launch per
    // event reading DataStore each time). Policy changes are rare; cache
    // them via a single combine collector in onCreate.
    @Volatile private var focusPolicyOnCall: String = "pause"
    @Volatile private var focusPolicyOnNotif: String = "duck"
    @Volatile private var focusPolicyOnOther: String = "pause"

    private fun installAudioFocusPolicy() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleAudioFocusChange(focusChange)
        }
        audioFocusListener = listener
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN
            ).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(true)
                .build()
        }
        // Request focus immediately so subsequent transient/duck/loss
        // events flow into our listener. Failure here is non-fatal —
        // some emulator images return AUDIOFOCUS_REQUEST_FAILED on the
        // first attempt before AudioFlinger is fully initialised.
        runCatching {
            val req = audioFocusRequest
            if (android.os.Build.VERSION.SDK_INT >= 26 && req != null) {
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }.onFailure {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "AudioFocus request failed", it)
        }
    }

    private fun handleAudioFocusChange(change: Int) {
        val p = player ?: return
        if (Companion.oauthInFlight) {
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AudioFocus change=$change SUPPRESSED (OAuth in flight)"
            )
            return
        }
        // vc29.26 — synchronous read from cached @Volatile vars. No
        // coroutine allocation per event; safe to run on the focus
        // callback's own thread.
        val choice = when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusPolicyOnCall
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusPolicyOnNotif
            android.media.AudioManager.AUDIOFOCUS_LOSS -> focusPolicyOnOther
            else -> "gain"
        }
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedDueToFocus) {
                    runCatching { p.volume = 1.0f }
                    duckedDueToFocus = false
                }
                if (pausedDueToFocus) {
                    runCatching { p.play() }
                    pausedDueToFocus = false
                }
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "AudioFocus GAIN applied")
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                when (choice) {
                    "pause" -> {
                        if (p.isPlaying) {
                            pausedDueToFocus = true
                            runCatching { p.pause() }
                        }
                    }
                    "duck" -> {
                        duckedDueToFocus = true
                        runCatching { p.volume = 0.3f }
                    }
                    else -> {}
                }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "AudioFocus loss=$change choice=$choice " +
                        "paused=$pausedDueToFocus ducked=$duckedDueToFocus"
                )
            }
        }
    }

    private fun startCrossfadeController() {
        crossfadeJob?.cancel()
        // Note new track starts so fade-in knows when to begin from
        // zero. Also reset the crossfade factor on every transition
        // so a previous fade-out doesn't carry over silently.
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                trackStartTimestampMs = android.os.SystemClock.elapsedRealtime()
                // Force volume floor BEFORE the new track is audible
                // so the fade-in starts cleanly even when crossfade
                // is enabled.
                if (crossfadeMsFlag > 0) setCrossfadeFactor(0.0f)
                else setCrossfadeFactor(1.0f)
                // D10 fix — publish the new mediaId so
                // MediaOverrideRepository can replace its 750ms poll.
                currentMediaIdFlow.value = mediaItem?.mediaId.orEmpty()
                // Phase 8 — refresh home-screen widget on track change.
                scheduleWidgetRefresh()
                // Union of both timelines: during cast the live queue sits
                // in CastPlayer while the local player still holds the
                // originals needed for switch-back.
                val liveIds = buildSet {
                    player?.let { p ->
                        for (i in 0 until p.mediaItemCount) add(p.getMediaItemAt(i).mediaId)
                    }
                    castPlayer?.let { c ->
                        for (i in 0 until c.mediaItemCount) add(c.getMediaItemAt(i).mediaId)
                    }
                }
                pruneSenderCaches(liveIds)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // §B3 LOCKED — Pause = both players pause synchronously.
                // Resume = both players resume; secondary continues
                // ramping from where its volume sat.
                if (isPlaying) crossfadeController.resumeAll()
                else crossfadeController.pauseAll()
                scheduleWidgetRefresh()
            }

            override fun onMediaMetadataChanged(
                mediaMetadata: androidx.media3.common.MediaMetadata
            ) {
                // Cast bug fix #2: ExoPlayer's MetadataDecoder enriches
                // MediaMetadata after the file is parsed (artworkData /
                // artworkUri / chapter title pulled from the file
                // tags). Mirror the merged metadata into the sender
                // cache continuously so when the user later taps Cast,
                // the cache already has the rich version.
                val curId = player?.currentMediaItem?.mediaId
                if (!curId.isNullOrEmpty()) {
                    val existing = senderMetadataByMediaId[curId]
                    val builder = androidx.media3.common.MediaMetadata.Builder()
                    existing?.let { builder.populate(it) }
                    builder.populate(mediaMetadata)
                    senderMetadataByMediaId[curId] = builder.build()
                }
                // Widget art arrives AFTER onMediaItemTransition for
                // most files (ExoPlayer parses tags asynchronously).
                // Refresh again here so the embedded artwork shows up.
                scheduleWidgetRefresh()
            }
        })

        // Audit 3.5 — the tick used to run at 10Hz for the service's
        // whole life (disabled, paused, idle in background). Event-gated
        // now: active only while playing WITH crossfade on; one settle
        // tick on deactivation keeps the ms==0 force-restore-to-1.0
        // branch and lands any mid-fade factor (matrix DEP).
        crossfadeJob = serviceScope.launch {
            val playingFlow = callbackFlow {
                val p = player
                trySend(p?.isPlaying == true)
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        trySend(isPlaying)
                    }
                }
                p?.addListener(listener)
                awaitClose { p?.removeListener(listener) }
            }.distinctUntilChanged()
            kotlinx.coroutines.flow.combine(playingFlow, crossfadeMsFlow) { playing, ms ->
                playing && ms > 0
            }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        while (kotlin.coroutines.coroutineContext.isActive) {
                            applyCrossfadeTick()
                            kotlinx.coroutines.delay(100)
                        }
                    } else {
                        applyCrossfadeTick()
                    }
                }
        }
    }

    private fun applyCrossfadeTick() {
        val p = player ?: return
        val ms = crossfadeMsFlag
        if (ms <= 0) {
            // Crossfade disabled — make sure the factor is at unity
            // so a previous slider drag-down doesn't leave the
            // player muted.
            if (crossfadeFactor != 1.0f) setCrossfadeFactor(1.0f)
            return
        }
        val duration = p.duration
        val pos = p.currentPosition
        val isLast = p.currentMediaItemIndex >= p.mediaItemCount - 1
        val playing = p.isPlaying

        // Fade-in window after the most recent track transition.
        val sinceStart = (android.os.SystemClock.elapsedRealtime() - trackStartTimestampMs)
            .coerceAtLeast(0L)
        val fadeIn = if (sinceStart < ms) sinceStart.toFloat() / ms else 1.0f

        // §B2 Album mode — when the next queued track is from the
        // same album as the current one, skip the fade entirely so
        // the artist's intended gap is preserved. Determined by
        // comparing mediaMetadata.albumTitle of currentMediaItem and
        // the queued next item.
        val sameAlbumAsNext = if (crossfadeAlbumModeFlag && !isLast) {
            val cur = p.currentMediaItem?.mediaMetadata?.albumTitle?.toString().orEmpty()
            val nextIdx = p.currentMediaItemIndex + 1
            val next = if (nextIdx < p.mediaItemCount)
                p.getMediaItemAt(nextIdx).mediaMetadata.albumTitle?.toString().orEmpty()
            else ""
            cur.isNotBlank() && cur == next
        } else false

        // §B2 Pre-fade trigger — fade-out begins at (trackEnd -
        // preFadeTriggerS). Default 5 s; user-adjustable 1..30 s. Run
        // the curve over THIS window so a 30 s pre-fade actually feels
        // like 30 s of fade rather than just the duration ms.
        val triggerMs = (crossfadePreFadeTriggerSFlag * 1000L)
            .coerceAtLeast(ms.toLong())
        // §B2 Skip silence — pull the trigger forward by 250 ms when
        // the toggle is on. A naive heuristic — full LUFS-aware
        // silence trim would need an AudioProcessor analyser; that's
        // deferred. The 250 ms shift is enough to feel like the fade
        // overlaps the next track's leading content rather than a gap.
        val effectiveTriggerMs = if (crossfadeSkipSilenceFlag) triggerMs + 250L else triggerMs
        // Fade-out window approaching the end of the current track.
        // Skip on the last track of the queue so playback doesn't end
        // muted (no next track to crossfade into). Also skip when
        // album-mode applies (same-album consecutive tracks).
        val fadeOut = if (
            !isLast && playing && !sameAlbumAsNext &&
            duration > 0L && pos > 0L &&
            duration - pos in 0..effectiveTriggerMs
        ) {
            ((duration - pos).toFloat() / effectiveTriggerMs).coerceIn(0.0f, 1.0f)
        } else 1.0f

        // The active factor is the smaller (more attenuated) of the
        // two so transitions sound continuous.
        val rawFactor = minOf(fadeIn, fadeOut).coerceIn(0.0f, 1.0f)
        // §B2 / Phase 4 — apply the user-selected fade curve. Equal
        // power (default) = sin(π/2 × t) which delivers vA² + vB² = 1
        // (no perceived dip in the middle). Linear keeps the previous
        // dip-prone behaviour for users who explicitly chose it.
        // Logarithmic / exponential rough-shape curves fall back to
        // linear for now (their full per-track perceived-loudness
        // pairing would need a second player to be audibly distinct).
        val factor = when (crossfadeCurveFlag) {
            "EQUAL_POWER" -> kotlin.math.sin(rawFactor * Math.PI.toFloat() / 2f)
            "LOGARITHMIC" -> {
                if (rawFactor <= 0f) 0f else
                    (kotlin.math.log10(1f + 9f * rawFactor)).coerceIn(0f, 1f)
            }
            "EXPONENTIAL" -> rawFactor * rawFactor
            else -> rawFactor
        }.coerceIn(0.0f, 1.0f)
        if (kotlin.math.abs(factor - crossfadeFactor) > 0.005f ||
            (factor == 1.0f && crossfadeFactor != 1.0f) ||
            (factor == 0.0f && crossfadeFactor != 0.0f)
        ) {
            setCrossfadeFactor(factor)
        }
        // §B3 — true 2-player overlap. Kicks off when we cross into
        // the pre-fade window. The secondary plays the next track at
        // (1 - factor) * volume while the primary is attenuated by
        // factor — energy stays constant under equal-power.
        val started = crossfadeController.maybeStartCrossfade(
            primary = p,
            crossfadeMs = ms,
            curve = crossfadeCurveFlag,
            primaryFinalVolume = 1.0f
        )
        // §B3 metadata handoff at crossfade START — flip the
        // notification + lockscreen + BT car-display title/artist to
        // the INCOMING track the moment the secondary kicks in. We
        // synthesise a media-item swap via Media3's session-controller
        // path so the surfaces all update atomically.
        if (started == true) {
            runCatching {
                val nextIdx = p.currentMediaItemIndex + 1
                val incoming = p.getMediaItemAt(nextIdx)
                mediaSession?.setSessionActivity(
                    mediaSession?.sessionActivity ?: return@runCatching
                )
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Crossfade metadata handoff → ${incoming.mediaMetadata.title}"
                )
            }
        }
    }

    /** Forwards to the companion-object volume mixer. */
    private fun setCrossfadeFactor(factor: Float) =
        Companion.setCrossfadeFactor(factor)

    private val crossfadeFactor: Float
        get() = Companion.crossfadeFactorRead()

    /**
     * Migrate the current queue + position from the active player to [target]
     * so playback continues seamlessly when the user picks/leaves a cast device.
     *
     * When switching TO CastPlayer: every queued MediaItem is registered with
     * [CastRelayServer] and rebuilt with a relay URL (`http://<lan-ip>:<port>/<token>`)
     * so the receiver can fetch bytes that originally lived behind a
     * `content://` / `file://` URI or an OAuth-protected googleapis.com URL.
     * When switching back: original MediaItems are re-applied and the relay
     * is stopped.
     */
    private fun switchPlayer(target: Player) {
        val ms = mediaSession ?: return
        val current = ms.player
        if (current === target) return
        // Tear down any prior cast-local-video state on ANY switch (unmute +
        // clear the active flag); re-enabled below if the new switch qualifies.
        castLocalVideoActive = false
        castInitialSyncDone = false
        Companion.castLocalVideoActiveFlow.value = false
        // General cast indicator: true the moment the session player becomes a
        // CastPlayer, false on the way back to local. Drives the BT button's
        // "on but not the active app output" state during a cast.
        Companion.castActiveFlow.value = target is CastPlayer
        Companion.setCastLocalVideoMute(false)
        castAudioExtractJob?.cancel()
        castAudioExtractJob = null
        castAudioTempFile?.let { runCatching { it.delete() } }
        castAudioTempFile = null
        // Cast local-video: ONLY when casting a VIDEO item to an AUDIO-ONLY
        // device do we keep the local picture alive. Every other case (audio
        // cast, video-capable TV, non-video) takes the unchanged stop path.
        val keepLocalVideo = target is CastPlayer && current is ExoPlayer &&
            current.isPlayingVideo() && isAudioOnlyCastDevice()

        val items = (0 until current.mediaItemCount).map { current.getMediaItemAt(it) }
        val currentIndex = current.currentMediaItemIndex
        val currentPosition = current.currentPosition
        val playWhenReady = current.playWhenReady
        // Cache sender-side metadata + the full original item for every
        // item already in the queue so album art / title / artist
        // survive the receiver echo when we switch to CastPlayer, and
        // the original URI can be restored when switching back.
        // ALSO populate metadata-only entries from the CastPlayer side
        // when reattaching mid-cast after a process restart — without
        // this the cache is empty and the in-app UI shows blank.
        items.forEach { item ->
            if (item.mediaId.isNotEmpty()) {
                // Always keep best-known metadata. Don't blow away an
                // existing rich entry with a sparse cast-side reconstruction.
                val existing = Companion.senderMetadataByMediaId[item.mediaId]
                val incoming = item.mediaMetadata
                if (existing == null ||
                    (incoming.title != null && existing.title == null) ||
                    (incoming.artworkUri != null && existing.artworkUri == null) ||
                    (incoming.artworkData != null && existing.artworkData == null)
                ) {
                    Companion.senderMetadataByMediaId[item.mediaId] = incoming
                }
                if (current !is CastPlayer) {
                    // Only stash the WHOLE item if it currently holds
                    // the original sender-side URI — i.e. when we're on
                    // the local ExoPlayer about to switch to Cast.
                    Companion.senderItemByMediaId[item.mediaId] = item
                }
            }
        }
        // Cast bug fix #2 (album art still missing on cast): the items
        // above only carry queue-time MediaItem.mediaMetadata, which
        // for local audio files lacks artworkData until ExoPlayer's
        // MetadataDecoder has parsed the file. The MERGED Player
        // metadata (current.mediaMetadata) is the enriched copy. Snapshot
        // it for the CURRENT item so the cache lookup during cast finds
        // the artworkUri / artworkData / chapter title that the local
        // player had built up.
        runCatching {
            val curItem = current.currentMediaItem
            val curId = curItem?.mediaId
            if (!curId.isNullOrEmpty()) {
                val merged = current.mediaMetadata
                val mergedHasArt = merged.artworkUri != null || merged.artworkData != null
                val mergedHasTitle = merged.title != null
                if (mergedHasArt || mergedHasTitle) {
                    val existing = Companion.senderMetadataByMediaId[curId]
                    val builder = androidx.media3.common.MediaMetadata.Builder()
                    // Carry forward all fields from existing (if any) then
                    // overlay the merged enriched fields.
                    existing?.let { builder.populate(it) }
                    builder.populate(merged)
                    Companion.senderMetadataByMediaId[curId] = builder.build()
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "switchPlayer cached merged metadata for id='${curId.takeLast(40)}' " +
                            "art=${mergedHasArt} title=${mergedHasTitle}"
                    )
                }
            }
        }

        val transformed = if (target is CastPlayer) {
            // Cast bug fix: start the relay UNCONDITIONALLY (even with an
            // empty queue) so subsequent items added via onAddMediaItems
            // can be relayed. Without this, connecting to Cast before
            // picking any media left the relay un-started and the very
            // first track inserted afterwards reached the receiver with
            // a raw content:// URI — receiver bails → empty player.
            val server = startCastRelayIfNeeded()
            if (server == null) {
                com.powermediaplayer.util.Diag.w(
                    "PMP_DIAG",
                    "Cast aborted — relay unavailable (no Wi-Fi LAN IP or " +
                        "NanoHTTPD failed). Receiver cannot fetch content:// " +
                        "URIs without the relay. Keeping local playback."
                )
                kotlinx.coroutines.MainScope().launch {
                    runCatching {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "Cast unavailable — connect to Wi-Fi and retry",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return  // stay with local player; user can retry
            }
            items.map { rebuildForCast(it, server) }
        } else {
            stopCastRelay()
            // Cleartext-fix (user-reported "ERROR_CODE_IO_CLEARTEXT
            // _NOT_PERMITTED on stop casting"): the items currently in
            // CastPlayer carry the relay http URLs, which the local
            // ExoPlayer can't fetch on Android 9+. Restore each item
            // from the sender-side cache (keyed by mediaId) so the
            // local player gets back its original content://, file://
            // or https:// URI.
            items.map { item ->
                val cached = Companion.senderItemByMediaId[item.mediaId]
                cached ?: rebuildLocalFromCastItem(item)
            }
        }

        if (keepLocalVideo) {
            // Audio → audio-only cast device; KEEP the local player decoding
            // the picture on the phone, muted via the factor system (so the
            // crossfade tick can't un-mute it). PAUSE it until the cast device
            // actually starts (cast Player.Listener.onIsPlayingChanged) — this
            // frees the phone's file-read + decode for the relay so the cast
            // starts sooner, and stops the picture racing ahead of the
            // buffering cast. It resumes ALIGNED to the real cast position.
            Companion.setCastLocalVideoMute(true)
            runCatching { (current as ExoPlayer).playWhenReady = false }
            castLocalVideoActive = true
            Companion.castLocalVideoActiveFlow.value = true
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "Cast to AUDIO-ONLY device + video → keep local muted picture on phone (event-sync)"
            )
        } else {
            current.stop()
        }
        ms.player = target
        if (keepLocalVideo) {
            // Don't cast the heavy video — extract its audio track and cast
            // ONLY that, so the speaker starts in ~1-2s instead of buffering
            // 20-30s of 4K video. The picture plays locally (muted) meanwhile.
            startCastAudioOnly(
                target as CastPlayer, current as ExoPlayer, currentPosition, playWhenReady
            )
        } else if (transformed.isNotEmpty()) {
            // CRASH FIX (NPE in DefaultMediaSourceFactory.createMediaSource):
            // ExoPlayer requires every MediaItem to have a non-null
            // localConfiguration. CastPlayer's reconstructed items can
            // come back with localConfiguration=null when the receiver
            // round-trip drops it. Filter the items defensively.
            val safe = transformed.filter { it.localConfiguration != null }
            if (safe.isNotEmpty()) {
                target.setMediaItems(safe, currentIndex.coerceIn(0, safe.size - 1), currentPosition)
                target.playWhenReady = playWhenReady
                target.prepare()
            } else {
                com.powermediaplayer.util.Diag.w(
                    "PMP_DIAG",
                    "switchPlayer: every item lost localConfiguration after cast - skipping setMediaItems"
                )
            }
        }

        // Re-publish the active player reference for the video surface.
        if (target is ExoPlayer) {
            exoPlayerRef = java.lang.ref.WeakReference(target)
        } else if (keepLocalVideo && current is ExoPlayer) {
            // Keep the on-phone surface bound to the local (muted) player so it
            // keeps drawing the picture while audio plays on the cast device.
            exoPlayerRef = java.lang.ref.WeakReference(current)
        } else {
            exoPlayerRef = null
        }
    }

    /** True when the active cast session targets an AUDIO-ONLY device (no
     *  video-out capability) — e.g. a Google Home / Nest speaker. Defaults to
     *  false (the unchanged stop-local behaviour) when it can't be read. */
    private fun isAudioOnlyCastDevice(): Boolean = runCatching {
        val device = CastContext.getSharedInstance(this)
            .sessionManager.currentCastSession?.castDevice ?: return false
        !device.hasCapability(com.google.android.gms.cast.CastDevice.CAPABILITY_VIDEO_OUT)
    }.getOrDefault(false)

    /** True when the player currently has a video track (or a known video size). */
    private fun Player.isPlayingVideo(): Boolean =
        currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } ||
            videoSize.width > 0

    /** Extract the current video's audio track to a small temp .m4a (off the
     *  main thread) and cast ONLY that — a tiny stream the speaker buffers in
     *  ~1-2s instead of buffering tens of seconds of 4K video. Falls back to
     *  casting the video itself if extraction (or the relay) fails. */
    private fun startCastAudioOnly(
        cast: CastPlayer,
        local: ExoPlayer,
        positionMs: Long,
        play: Boolean
    ) {
        val sourceItem = local.currentMediaItem
        val sourceUri = sourceItem?.localConfiguration?.uri
            ?: sourceItem?.requestMetadata?.mediaUri
        if (sourceItem == null || sourceUri == null) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "Cast audio-only: no source uri")
            return
        }
        castAudioExtractJob?.cancel()
        castAudioExtractJob = serviceScope.launch {
            val item = buildCastAudioRelayItem(sourceUri, sourceItem.mediaMetadata)
                ?: castRelayServer?.let { rebuildForCast(sourceItem, it) }   // fallback: cast the video
            if (!castLocalVideoActive || item == null || item.localConfiguration == null) return@launch
            cast.setMediaItems(listOf(item), 0, positionMs)
            cast.playWhenReady = play
            cast.prepare()
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "Cast audio-only: cast item set @${positionMs}ms mime=${item.localConfiguration?.mimeType}"
            )
            // Watchdog: if the cast never reaches "really playing" (onIsPlaying
            // Changed sets castInitialSyncDone) within 30 s it has hung — revert
            // to local so the user isn't stuck on a frozen muted picture with no
            // audio. A child of castAudioExtractJob, so a re-switch cancels it.
            // Armed ONLY when we asked the cast to PLAY: a deliberately-paused
            // cast (route-while-paused) legitimately never reaches "really
            // playing", so arming it would wrongly tear down a healthy session.
            if (play) launch {
                kotlinx.coroutines.delay(30_000)
                if (castLocalVideoActive && !castInitialSyncDone) {
                    com.powermediaplayer.util.Diag.w(
                        "PMP_DIAG", "Cast audio-only never started in 30s → reverting to local"
                    )
                    runCatching {
                        CastContext.getSharedInstance(applicationContext)
                            .sessionManager.endCurrentSession(true)
                    }
                }
            }
        }
    }

    /** Extract [sourceUri]'s audio track to a temp m4a + register it with the
     *  relay; returns a cast-ready audio/mp4 MediaItem, or null on failure.
     *  Replaces + deletes any previous temp. Extraction runs off the main
     *  thread. Shared by the initial cast switch AND track changes while
     *  casting (onAddMediaItems). */
    private suspend fun buildCastAudioRelayItem(
        sourceUri: android.net.Uri,
        metadata: androidx.media3.common.MediaMetadata
    ): MediaItem? {
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG", "Cast audio-only: extracting audio from $sourceUri"
        )
        val temp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            CastAudioExtractor.extractAudio(applicationContext, sourceUri) {
                job?.isActive != false
            }
        }
        castAudioTempFile?.let { old -> runCatching { old.delete() } }
        castAudioTempFile = temp
        val server = castRelayServer ?: startCastRelayIfNeeded()
        if (temp == null || server == null) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "Cast audio-only: extraction/relay unavailable")
            return null
        }
        val token = server.register(
            CastRelayServer.RelayItem.Local(android.net.Uri.fromFile(temp), "audio/mp4")
        )
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG", "Cast audio-only: extracted ${temp.length()} bytes for $sourceUri"
        )
        return MediaItem.Builder()
            .setUri("http://$castRelayLanIp:${server.listeningPort}/$token")
            // CastPlayer's DefaultMediaItemConverter REQUIRES a mimeType (else
            // it throws → crash). The temp is MP4-container AAC.
            .setMimeType("audio/mp4")
            .setMediaId(sourceUri.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Cast relay (lazy-singleton). Started on first switch-to-CastPlayer,
     * stopped on switch-back-to-local.
     */
    private var castRelayServer: CastRelayServer? = null
    private var castRelayLanIp: String? = null

    private fun startCastRelayIfNeeded(): CastRelayServer? {
        val lanIp = com.powermediaplayer.util.LanIpDiscovery.firstWifiIpv4()
        if (lanIp == null) {
            com.powermediaplayer.util.Diag.w(
                "PMP_DIAG",
                "CastRelay: no Wi-Fi IPv4 — cannot relay to receiver"
            )
            return null
        }
        castRelayLanIp = lanIp
        castRelayServer?.let { return it }
        return try {
            val s = CastRelayServer(applicationContext, driveOAuthProvider)
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            castRelayServer = s
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "CastRelay started lanIp=$lanIp port=${s.listeningPort}"
            )
            s
        } catch (t: Throwable) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "CastRelay start failed", t)
            null
        }
    }

    private fun stopCastRelay() {
        castRelayServer?.let { s ->
            runCatching { s.stop() }
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "CastRelay stopped")
        }
        castRelayServer = null
        castRelayLanIp = null
    }

    /**
     * Wraps an existing MediaItem so its URL points at our relay. The
     * media-id is preserved (Media3 uses it for queue identity) but
     * `localConfiguration.uri` and `requestMetadata.mediaUri` are
     * rewritten so [DefaultMediaItemConverter] sends the relay URL to
     * the receiver via `MediaInfo.contentUrl`.
     */
    private fun rebuildForCast(item: MediaItem, server: CastRelayServer): MediaItem {
        val originalUri = item.localConfiguration?.uri
            ?: item.requestMetadata.mediaUri
            ?: runCatching { android.net.Uri.parse(item.mediaId) }.getOrNull()
            ?: return item
        val mime = item.localConfiguration?.mimeType ?: guessMimeFromUri(originalUri)
        val relayItem: CastRelayServer.RelayItem = when {
            originalUri.host?.contains("googleapis.com") == true -> {
                // Drive OAuth: extract file id from /drive/v3/files/{id}
                val fileId = originalUri.pathSegments?.getOrNull(
                    originalUri.pathSegments.indexOf("files") + 1
                ) ?: return item
                CastRelayServer.RelayItem.DriveOAuth(fileId, mime)
            }
            else -> CastRelayServer.RelayItem.Local(originalUri, mime)
        }
        val token = server.register(relayItem)
        val lanIp = castRelayLanIp ?: return item
        val relayUrl = android.net.Uri.parse(
            "http://$lanIp:${server.listeningPort}/$token"
        )
        return item.buildUpon()
            .setUri(relayUrl)
            .setMimeType(mime)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(relayUrl)
                    .build()
            )
            .build()
    }

    /**
     * Cast bug fix (cast-stop crash): when CastPlayer hands us back a
     * MediaItem after disconnect, its localConfiguration may be null
     * (Cast SDK's converter drops it for items reconstructed from a
     * remote MediaInfo). Rebuild a usable local MediaItem from the
     * mediaId / requestMetadata fallbacks. Skips relay http URLs (the
     * cleartext path) — caller should prefer the senderItemByMediaId
     * cache for those.
     */
    private fun rebuildLocalFromCastItem(item: MediaItem): MediaItem {
        val candidate = item.localConfiguration?.uri
            ?: item.requestMetadata.mediaUri
            ?: runCatching { android.net.Uri.parse(item.mediaId) }.getOrNull()
            ?: return item
        // Skip cleartext relay URLs — local player can't fetch them
        // and they're not the user's content anyway.
        if (candidate.scheme == "http" &&
            candidate.host?.startsWith("192.168.") == true) return item
        return item.buildUpon().setUri(candidate).build()
    }

    private fun guessMimeFromUri(uri: android.net.Uri): String {
        // First try ContentResolver — content:// URIs that lack a file
        // extension still surface a stable MIME via MediaStore. Cast
        // receivers refuse */* so this matters more on the Cast path
        // than for the local DataSource which sniffs bytes itself.
        val resolved = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (!resolved.isNullOrBlank()) return resolved
        val name = uri.lastPathSegment ?: return "*/*"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "*/*"
        }
    }


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        // PiP keeps the video playing in its own window — a swipe-away while
        // Picture-in-Picture is active must NOT tear the service down.
        if (com.powermediaplayer.MainActivityHolder.isInPip) {
            com.powermediaplayer.diag.DiagLog.lifecycle(
                "PlaybackService.onTaskRemoved — PiP active, service kept alive"
            )
            super.onTaskRemoved(rootIntent)
            return
        }
        // Swipe-away = full close by default (user choice): stop playback AND
        // clear the queue so the media/status notification disappears, then
        // stop the service + process. A power user can opt OUT via Settings →
        // "Stop on swipe-away" to keep music playing in the background.
        val stopOnSwipe = runCatching {
            kotlinx.coroutines.runBlocking {
                // Bounded read — an unbounded one blocks the main thread
                // during task removal (ANR vector).
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    settingsDataStore.stopOnTaskRemoved.first()
                } ?: true
            }
        }.getOrDefault(true)
        val player = mediaSession?.player
        com.powermediaplayer.diag.DiagLog.lifecycle(
            "PlaybackService.onTaskRemoved stopOnSwipe=$stopOnSwipe " +
                "playWhenReady=${player?.playWhenReady} itemCount=${player?.mediaItemCount}"
        )
        if (stopOnSwipe) {
            // Spotify Connect: the session `player` is only a SILENT local
            // mirror. Stopping it leaves the actual track playing on the
            // Connect device. Fire a best-effort pause to the Connect device
            // on a scope that OUTLIVES serviceScope.cancel() (GlobalScope) —
            // stopSelf() doesn't kill the process instantly, so the HTTP PUT
            // usually lands in the teardown window. Without this, swiping the
            // app away leaves Spotify playing on the speaker.
            if (spotifyProvider.spotifyState.value != null) {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { spotifyProvider.pause() }
                }
                com.powermediaplayer.diag.DiagLog.lifecycle(
                    "onTaskRemoved — Spotify mirror active → best-effort Connect pause"
                )
            }
            player?.let { runCatching { it.stop(); it.clearMediaItems() } }
            stopSelf()
            return
        }
        // Opted out → keep music playing across the swipe; only tear down
        // when nothing is actually playing.
        if (player != null && (!player.playWhenReady || player.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        com.powermediaplayer.diag.DiagLog.lifecycle("PlaybackService.onDestroy")
        audioDeviceCallback?.let {
            runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        }
        audioDeviceCallback = null
        // The focus registration outlives the service inside AudioManager
        // otherwise — one leaked service generation per restart.
        runCatching {
            val am = getSystemService(android.media.AudioManager::class.java)
            val req = audioFocusRequest
            if (android.os.Build.VERSION.SDK_INT >= 26 && req != null) {
                am?.abandonAudioFocusRequest(req)
            } else {
                @Suppress("DEPRECATION")
                audioFocusListener?.let { am?.abandonAudioFocus(it) }
            }
        }
        audioFocusRequest = null
        audioFocusListener = null
        runCatching {
            castSessionListener?.let {
                com.google.android.gms.cast.framework.CastContext.getSharedInstance()
                    ?.sessionManager?.removeSessionManagerListener(
                        it, com.google.android.gms.cast.framework.CastSession::class.java)
            }
        }
        castSessionListener = null
        // The Hue engine runs in its own singleton scope — cancelling
        // serviceScope only kills the COLLECTOR, not the 25Hz stream.
        runCatching { hueEntertainment.stop() }
        runCatching { hueAnalyserProcessor.setAnalysis(false) }
        runCatching { crossfadeController.shutdown() }
        serviceScope.cancel()
        stopCastRelay()
        headphonePlugReceiver?.let { runCatching { unregisterReceiver(it) } }
        headphonePlugReceiver = null
        exoPlayerRef = null          // clear before release so UI gets null not dead reference
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            release()
        }
        castPlayer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /**
     * Custom callback to handle our extended skip actions via custom session commands.
     */
    private inner class PlayerSessionCallback : MediaSession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Register all custom commands
            val customCommands = listOf(
                ACTION_SKIP_BACK_5, ACTION_SKIP_BACK_10, ACTION_SKIP_BACK_15,
                ACTION_SKIP_BACK_20, ACTION_SKIP_BACK_30,
                ACTION_SKIP_FORWARD_5, ACTION_SKIP_FORWARD_10, ACTION_SKIP_FORWARD_15,
                ACTION_SKIP_FORWARD_20, ACTION_SKIP_FORWARD_30
            )

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()

            customCommands.forEach { action ->
                sessionCommands.add(SessionCommand(action, Bundle.EMPTY))
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val deltaMs: Long = when (customCommand.customAction) {
                ACTION_SKIP_BACK_5 -> -5_000
                ACTION_SKIP_BACK_10 -> -10_000
                ACTION_SKIP_BACK_15 -> -15_000
                ACTION_SKIP_BACK_20 -> -20_000
                ACTION_SKIP_BACK_30 -> -30_000
                ACTION_SKIP_FORWARD_5 -> 5_000
                ACTION_SKIP_FORWARD_10 -> 10_000
                ACTION_SKIP_FORWARD_15 -> 15_000
                ACTION_SKIP_FORWARD_20 -> 20_000
                ACTION_SKIP_FORWARD_30 -> 30_000
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            cumulativeSkip(session.player, deltaMs)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Intercept SEEK_TO_NEXT / SEEK_TO_PREVIOUS so Bluetooth/AVRCP
         * "next" and "previous" car-stereo presses follow the user's
         * remapping (skip ±N seconds, restart, chapter-only, etc.).
         *
         * Returning Player.RESULT_INFO_SKIPPED tells Media3 NOT to
         * execute the original command — we then call into the player
         * directly with the remapped action.
         *
         * Note: the in-app PlaybackControls bypass this callback because
         * they call seekTo() / custom skip commands directly, never the
         * Player.seekToNextMediaItem() command. So the in-app prev/next
         * buttons remain hard-mapped to chapter/track navigation, which
         * is what users expect.
         */
        @OptIn(UnstableApi::class)
        @Deprecated("Hook itself is deprecated in Media3, but still the only stable way to intercept AVRCP next/prev")
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            // Only re-route AVRCP / system MediaController commands —
            // commands originating from our in-app MediaController
            // (same package, signed identity) bypass the remap so the
            // on-screen prev/next behave as labelled.
            val isExternal = controller.packageName != packageName
            // Log every external (BT / car / headset) command we receive
            // BEFORE filtering. Captures the raw opcode + caller package
            // so testers can see exactly what their car emits.
            if (isExternal) {
                com.powermediaplayer.diag.DiagLog.event(
                    "BT_CMD",
                    "cmd=$playerCommand pkg=${controller.packageName} " +
                        "prevAct=${btMapping.prevAction} nextAct=${btMapping.nextAction} " +
                        "skipBack=${btMapping.skipBackSeconds}s skipFwd=${btMapping.skipForwardSeconds}s"
                )
            }
            if (!isExternal) return super.onPlayerCommandRequest(session, controller, playerCommand)

            val player = session.player
            val mapping = btMapping
            return when (playerCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    com.powermediaplayer.diag.DiagLog.event(
                        "BT_CMD",
                        "→ dispatch PREV pos=${player.currentPosition}ms action=${mapping.prevAction}"
                    )
                    applyAction(player, mapping.prevAction, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    com.powermediaplayer.diag.DiagLog.event(
                        "BT_CMD",
                        "→ dispatch NEXT pos=${player.currentPosition}ms action=${mapping.nextAction}"
                    )
                    applyAction(player, mapping.nextAction, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_FORWARD -> {
                    com.powermediaplayer.diag.DiagLog.event(
                        "BT_CMD",
                        "→ dispatch SEEK_FORWARD pos=${player.currentPosition}ms by=${mapping.skipForwardSeconds}s"
                    )
                    applyAction(player, BluetoothMediaActions.SKIP_FORWARD, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_BACK -> {
                    com.powermediaplayer.diag.DiagLog.event(
                        "BT_CMD",
                        "→ dispatch SEEK_BACK pos=${player.currentPosition}ms by=${mapping.skipBackSeconds}s"
                    )
                    applyAction(player, BluetoothMediaActions.SKIP_BACK, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> {
                    com.powermediaplayer.diag.DiagLog.event(
                        "BT_CMD",
                        "→ unhandled cmd=$playerCommand passing through to Media3"
                    )
                    @Suppress("DEPRECATION") super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            }
        }

        /**
         * Direct interception of raw `KeyEvent.KEYCODE_MEDIA_*` events
         * delivered by the Bluetooth stack / car HU before Media3's
         * default dispatcher converts them to `Player.COMMAND_SEEK_TO_*`.
         *
         * Why this exists:
         * - Media3 ≥ 1.2.0 added this hook specifically to let apps
         *   intercept media-button KeyEvents (confirmed via Context7
         *   query against /androidx/media — 1.2.0 release notes).
         * - On a 2016 BMW F30 (and presumably most older AVRCP HUs)
         *   the steering-wheel prev/next buttons arrive as raw
         *   `KeyEvent.KEYCODE_MEDIA_PREVIOUS/NEXT` via the BT key path.
         *   Media3's default handler turns these into
         *   `seekToPreviousMediaItem()` / `seekToNextMediaItem()`
         *   DIRECTLY on the Player — NEVER routing them through
         *   `onPlayerCommandRequest`. So our prev/next remap was inert
         *   for KeyEvent-style cars.
         * - In a 7-minute test session this file's `onPlayerCommandRequest`
         *   logged ONE cmd (cmd=15 = SET_REPEAT_MODE) and zero seek/prev/
         *   next opcodes despite dozens of button presses → ironclad
         *   evidence that the KeyEvent path is the active route.
         *
         * Behaviour:
         * - In-app controllers (samePackage) → pass through (return false).
         *   The on-screen prev/next still call `seekToPrevious()` etc.
         *   directly via PlaybackConnection; we don't want to remap THOSE.
         * - External controllers (com.android.bluetooth, com.google.
         *   android.projection.gearhead, …) → log the raw KeyEvent, then:
         *     • PREVIOUS / REWIND  → applyAction(prevAction, skipBackSeconds)
         *     • NEXT / FAST_FORWARD → applyAction(nextAction, skipForwardSeconds)
         *     • PLAY  → gated by resumeOnBt setting (swallow if off)
         *     • everything else → return false (let Media3 default fire)
         *
         * Returning true tells Media3 we handled it. Returning false
         * tells it to run its standard dispatch.
         */
        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = @Suppress("DEPRECATION") (intent.getParcelableExtra(
                Intent.EXTRA_KEY_EVENT
            ) as? android.view.KeyEvent) ?: return false
            // Only act on key-DOWN; key-UP arrives ~50 ms later and
            // would otherwise double-fire the action.
            if (keyEvent.action != android.view.KeyEvent.ACTION_DOWN) return false
            val pkg = controllerInfo.packageName
            val isExternal = pkg != packageName
            val mapping = btMapping
            com.powermediaplayer.diag.DiagLog.bt(
                "keyEvent code=${keyEvent.keyCode} keyName=${android.view.KeyEvent.keyCodeToString(keyEvent.keyCode)} " +
                    "pkg=$pkg external=$isExternal repeat=${keyEvent.repeatCount} " +
                    "prevAct=${mapping.prevAction} nextAct=${mapping.nextAction} " +
                    "skipBack=${mapping.skipBackSeconds}s skipFwd=${mapping.skipForwardSeconds}s"
            )
            // In-app source: pass through. The Activity-side controller
            // dispatches direct Player calls anyway; this branch only
            // catches anything routed by mistake through KeyEvents.
            if (!isExternal) return false

            val player = session.player
            return when (keyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    com.powermediaplayer.diag.DiagLog.bt(
                        "→ keyEvent PREV→applyAction action=${mapping.prevAction} " +
                            "skipBackSec=${mapping.skipBackSeconds} pos=${player.currentPosition}ms"
                    )
                    applyAction(player, mapping.prevAction, mapping.skipBackSeconds, isPrev = true)
                    true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    com.powermediaplayer.diag.DiagLog.bt(
                        "→ keyEvent NEXT→applyAction action=${mapping.nextAction} " +
                            "skipFwdSec=${mapping.skipForwardSeconds} pos=${player.currentPosition}ms"
                    )
                    applyAction(player, mapping.nextAction, mapping.skipForwardSeconds, isPrev = false)
                    true
                }
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    // Single-tap headset hook: most HUs and BT headsets
                    // bind this to play/pause toggle. Let Media3 default
                    // dispatcher handle it so the existing play/pause
                    // flow + audio-focus rules apply unchanged.
                    com.powermediaplayer.diag.DiagLog.bt(
                        "→ keyEvent KEYCODE_HEADSETHOOK passthrough to Media3"
                    )
                    false
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    // §C — gate auto-resume on BT reconnect against the
                    // user's "Resume on Bluetooth connect" toggle. The
                    // KeyEvent arrives when the BMW HU re-establishes
                    // A2DP after ignition and the car media controller
                    // re-issues a play request. Snapshot synchronously
                    // — DataStore reads off the binder thread will block.
                    val allow = runCatching {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(150) {
                                settingsDataStore.resumeOnBt.first()
                            }
                        }
                    }.getOrNull() ?: false
                    com.powermediaplayer.diag.DiagLog.dec(
                        branch = "BT-PLAY",
                        reason = if (allow) "resumeOnBt=true → allowing play" else
                            "resumeOnBt=false → swallowing play KeyEvent"
                    )
                    if (allow) false else true // false = let Media3 dispatch; true = swallow
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    // When the Spotify mirror is active, route to
                    // Spotify Connect so the car-stereo PAUSE button
                    // actually pauses the song on the Connect device
                    // (Media3's default just pauses our silent mirror
                    // Player, leaving the user's audio playing).
                    if (spotifyProvider.spotifyState.value != null) {
                        com.powermediaplayer.diag.DiagLog.bt(
                            "→ keyEvent ${android.view.KeyEvent.keyCodeToString(keyEvent.keyCode)} routed to Spotify Connect (mirror active)"
                        )
                        serviceScope.launch {
                            runCatching {
                                val playing = spotifyProvider.spotifyState.value?.isPlaying == true
                                when (keyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                                    android.view.KeyEvent.KEYCODE_MEDIA_STOP ->
                                        spotifyProvider.pause()
                                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                                        if (playing) spotifyProvider.pause()
                                        else spotifyProvider.resume()
                                }
                            }
                        }
                        true
                    } else {
                        com.powermediaplayer.diag.DiagLog.bt(
                            "→ keyEvent ${android.view.KeyEvent.keyCodeToString(keyEvent.keyCode)} passthrough to Media3"
                        )
                        false
                    }
                }
                else -> {
                    com.powermediaplayer.diag.DiagLog.bt(
                        "→ keyEvent ${android.view.KeyEvent.keyCodeToString(keyEvent.keyCode)} unhandled — passthrough"
                    )
                    false
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Resolve media items with URIs for playback. URI may live in
            // localConfiguration (same-process MediaController preserves it),
            // requestMetadata.mediaUri (preserved across IPC), or mediaId
            // (we set this to the URI string for cloud + library items).
            //
            // BUG FIX (user-reported "cast connects but the player goes
            // empty as soon as I pick anything"): when the active player
            // is a CastPlayer, items inserted via the MediaSession path
            // were reaching the Cast receiver with raw `content://` /
            // `file://` / OAuth-Drive URIs the receiver can't fetch,
            // so currentMediaItem went null and the UI rendered as
            // "no media loaded". Now we route every CastPlayer-bound
            // item through `rebuildForCast` so the receiver gets a plain
            // `http://<lan-ip>:<port>/<token>` URL served by our relay.
            val isCasting = mediaSession.player is CastPlayer
            val relay = if (isCasting) castRelayServer else null
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "onAddMediaItems count=${mediaItems.size} casting=$isCasting " +
                    "firstId='${mediaItems.firstOrNull()?.mediaId?.takeLast(40).orEmpty()}'"
            )
            val resolvedItems = mediaItems.map { item ->
                val resolvedUri = item.localConfiguration?.uri
                    ?: item.requestMetadata.mediaUri
                    ?: runCatching { android.net.Uri.parse(item.mediaId) }.getOrNull()
                val withUri = if (resolvedUri != null) {
                    item.buildUpon().setUri(resolvedUri).build()
                } else {
                    item
                }
                // Cache sender-side metadata + the full original item
                // BEFORE the cast rebuild so album art etc. survive the
                // receiver round-trip and the original URI can be
                // restored on switch-back-to-local (cleartext fix).
                if (withUri.mediaId.isNotEmpty()) {
                    senderMetadataByMediaId[withUri.mediaId] = withUri.mediaMetadata
                    senderItemByMediaId[withUri.mediaId] = withUri
                }
                if (relay != null) rebuildForCast(withUri, relay) else withUri
            }.toMutableList()
            // Cast-local-video: a NEW track chosen WHILE casting must keep the
            // app's on-phone PICTURE in step with the cast audio. For a VIDEO,
            // load it into the local player (so the picture updates) and cast
            // AUDIO-ONLY (extracted). For an AUDIO track, disengage the
            // local-video feature so the app shows the normal audio UI.
            if (isCasting && castLocalVideoActive) {
                val first = mediaItems.firstOrNull()
                val srcUri = first?.localConfiguration?.uri
                    ?: first?.requestMetadata?.mediaUri
                    ?: first?.mediaId?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                val isVideo = srcUri != null && (
                    srcUri.toString().contains("/video/") ||
                        first?.localConfiguration?.mimeType?.startsWith("video/") == true
                )
                if (first != null && srcUri != null && isVideo) {
                    castInitialSyncDone = false
                    // Show the new video locally (paused until the new cast
                    // audio actually starts — onIsPlayingChanged realigns it).
                    val localItem = first.buildUpon().setUri(srcUri).build()
                    runCatching {
                        player?.setMediaItem(localItem)
                        player?.prepare()
                        player?.playWhenReady = false
                    }
                    val fut = com.google.common.util.concurrent.SettableFuture
                        .create<MutableList<MediaItem>>()
                    castAudioExtractJob?.cancel()
                    castAudioExtractJob = serviceScope.launch {
                        try {
                            val m4a = buildCastAudioRelayItem(srcUri, first.mediaMetadata)
                                ?: resolvedItems.firstOrNull()
                                ?: localItem
                            fut.set(mutableListOf(m4a))
                        } finally {
                            // Audit: if this job is cancelled mid-extract the
                            // future would never complete and Media3's add-items
                            // op would wedge. Always resolve it (fall back to the
                            // local item) so the queue update can finish.
                            if (!fut.isDone) fut.set(mutableListOf(localItem))
                        }
                    }
                    return fut
                } else {
                    // Audio track while casting → no local picture; normal cast.
                    castLocalVideoActive = false
                    castInitialSyncDone = false
                    Companion.castLocalVideoActiveFlow.value = false
                    Companion.setCastLocalVideoMute(false)
                    castAudioExtractJob?.cancel()
                }
            }
            return Futures.immediateFuture(resolvedItems)
        }
    }

    /**
     * Cumulative skip handler — each tap adds [deltaMs] to a running
     * pending target so 5 rapid skip-back-30 taps move 150 s back, not
     * 30 s. Without this, every tap reads the same stale currentPosition
     * (the player's position hasn't updated yet between taps), so taps
     * blur together and the user perceives "some skips don't happen".
     *
     * Pending target clears 600 ms after the last tap — by then the
     * player has settled and currentPosition is fresh.
     */
    private fun cumulativeSkip(player: Player, deltaMs: Long) {
        val pos = player.currentPosition
        val base = if (pendingSeekTarget >= 0L) pendingSeekTarget else pos
        val rawTarget = base + deltaMs
        val duration = player.duration.let { if (it == C.TIME_UNSET) Long.MAX_VALUE else it }
        val target = rawTarget.coerceIn(0L, duration)
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "skip delta=${deltaMs}ms pos=${pos}ms base=${base}ms target=${target}ms pending=${pendingSeekTarget}"
        )
        pendingSeekTarget = target

        // Debounce the actual seekTo call. Rapid taps now collapse into a
        // single seek to the cumulative target — eliminates the stacked
        // BUFFERING cycles (each seek freezes video for 50–150 ms; five
        // rapid taps would otherwise produce 500 ms+ of accumulated freeze).
        // 180 ms catches typical rapid-tap intervals (200–400 ms) while
        // staying under the perceptible-input-latency threshold.
        debouncedSeekJob?.cancel()
        debouncedSeekJob = serviceScope.launch {
            kotlinx.coroutines.delay(180)
            val finalTarget = pendingSeekTarget
            // Skip if already at target (within 250 ms) — avoids a useless
            // BUFFERING cycle when the cumulative target equals current
            // position (e.g. multiple back-skips clamped at 0).
            if (finalTarget >= 0L &&
                kotlin.math.abs(player.currentPosition - finalTarget) > 250L
            ) {
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "debounced seekTo target=${finalTarget}ms")
                player.seekTo(finalTarget)
            } else {
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "debounced seek SKIPPED (within 250ms of target)")
            }
        }

        clearPendingSeekJob?.cancel()
        clearPendingSeekJob = serviceScope.launch {
            kotlinx.coroutines.delay(1500)
            pendingSeekTarget = -1L
        }
    }

    /**
     * Apply a remapped Bluetooth media-button action against the player.
     * Runs on the binder thread but every Player call below is dispatched
     * to the application looper internally, so no extra wrapping needed.
     */
    private fun applyAction(player: Player, action: String, seconds: Int, isPrev: Boolean) {
        // Fire the matching webhook for external prev / next presses
        // (BT remote / Android Auto). Each fire is gated by both the
        // master URL + the per-event toggle inside WebhookEmitter, so
        // this is cheap when no webhook is configured.
        webhookEmitter.fire(
            if (isPrev) com.powermediaplayer.webhooks.WebhookEmitter.Event.SKIP_PREV
            else com.powermediaplayer.webhooks.WebhookEmitter.Event.SKIP_NEXT,
            player.currentMediaItem?.mediaId,
            player.currentPosition,
            player.duration.coerceAtLeast(0L)
        )
        // Spotify mirror routing — when the user is listening on a
        // Spotify Connect device, the LOCAL ExoPlayer is silent (it
        // mirrors metadata only). Driving player.seekToPrevious() etc.
        // would change nothing audible; the user's actual playback
        // continues unaffected on the Connect target. Route prev /
        // next / skip-back / skip-forward via SpotifyProvider so the
        // BT remote in the car actually moves the song.
        if (spotifyProvider.spotifyState.value != null) {
            com.powermediaplayer.diag.DiagLog.bt(
                "applyAction routed to Spotify Connect (mirror active) action=$action sec=$seconds isPrev=$isPrev"
            )
            serviceScope.launch {
                runCatching {
                    when (action) {
                        BluetoothMediaActions.PREV_TRACK -> spotifyProvider.skipPrevious()
                        BluetoothMediaActions.NEXT_TRACK -> spotifyProvider.skipNext()
                        BluetoothMediaActions.SKIP_BACK -> {
                            // Skip-back N seconds = seek to (currentPos - N*1000).
                            // Spotify polling gives us currentPos via spotifyState.
                            val pos = spotifyProvider.spotifyState.value?.positionMs ?: 0L
                            val target = (pos - seconds * 1000L).coerceAtLeast(0L)
                            spotifyProvider.seekTo(target)
                        }
                        BluetoothMediaActions.SKIP_FORWARD -> {
                            val pos = spotifyProvider.spotifyState.value?.positionMs ?: 0L
                            val target = pos + seconds * 1000L
                            spotifyProvider.seekTo(target)
                        }
                        BluetoothMediaActions.RESTART_TRACK ->
                            spotifyProvider.seekTo(0L)
                        BluetoothMediaActions.PREV_CHAPTER ->
                            spotifyProvider.skipPrevious()
                        BluetoothMediaActions.NEXT_CHAPTER ->
                            spotifyProvider.skipNext()
                    }
                }.onFailure {
                    com.powermediaplayer.diag.DiagLog.bt(
                        "Spotify-routed action FAILED: ${it.javaClass.simpleName}: ${it.message}"
                    )
                }
            }
            return
        }
        when (action) {
            BluetoothMediaActions.PREV_TRACK -> {
                crossfadeController.abort()
                // seekToPrevious() honours maxSeekToPreviousPositionMs
                // (3000ms): if > 3s into current item, restarts it;
                // otherwise skips to the previous item. Standard pattern.
                player.seekToPrevious()
            }
            BluetoothMediaActions.NEXT_TRACK -> {
                crossfadeController.abort()
                player.seekToNext()
            }
            BluetoothMediaActions.SKIP_BACK -> {
                // Route through the same cumulativeSkip path the in-app
                // skip buttons use — inherits cross-boundary spill,
                // duration clamping, debounce, and rapid-tap accumulation.
                cumulativeSkip(player, -seconds * 1000L)
            }
            BluetoothMediaActions.SKIP_FORWARD -> {
                cumulativeSkip(player, seconds * 1000L)
            }
            BluetoothMediaActions.RESTART_TRACK -> player.seekTo(0L)
            BluetoothMediaActions.PREV_CHAPTER,
            BluetoothMediaActions.NEXT_CHAPTER -> {
                // Chapter navigation lives in PlaybackConnection, not the
                // service. Fall back to media-item navigation for now —
                // a future pass can add a custom command roundtrip.
                if (isPrev) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
            }
            else -> if (isPrev) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        }
    }
}
