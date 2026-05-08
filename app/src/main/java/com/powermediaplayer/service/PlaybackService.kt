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
    @Volatile
    private var stereoFlipFlag: Boolean = false
    @Volatile
    private var monoMixFlag: Boolean = false
    @Volatile
    private var audioDelayFlag: Int = 0
    @Volatile
    private var crossfadeMsFlag: Int = 0
    @Volatile private var crossfadeAlbumModeFlag: Boolean = true
    @Volatile private var crossfadeCurveFlag: String = "EQUAL_POWER"

    @javax.inject.Inject
    lateinit var settingsDataStore: SettingsDataStore

    @javax.inject.Inject
    lateinit var audioOutputDetector: com.powermediaplayer.audio.AudioOutputDetector

    @javax.inject.Inject
    lateinit var mediaOverrideRepo: com.powermediaplayer.data.repository.MediaOverrideRepository

    // §B3 — true 2-player crossfade engine (lazy: spins up the second
    // ExoPlayer only inside the pre-fade window, releases on completion).
    private val crossfadeController by lazy {
        com.powermediaplayer.service.CrossfadeController(this)
    }


    private var player: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
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

        // ── Volume mixer ────────────────────────────────────────────
        // ExoPlayer.volume is multiplexed between two independent
        // sources: ReplayGain attenuation (negative track-gain values)
        // and the crossfade ramp during track transitions. Each
        // source publishes a 0..1 factor; the actual volume applied
        // to the player is replayGainFactor × crossfadeFactor. This
        // avoids one source overwriting the other.
        @Volatile private var replayGainFactor: Float = 1.0f
        @Volatile private var crossfadeFactor: Float = 1.0f

        /** ReplayGain attenuation for negative track-gain tags
         *  (LoudnessEnhancer can only boost). 1.0 = no attenuation. */
        fun setReplayGainAttenuation(factor: Float) {
            replayGainFactor = factor.coerceIn(0.0f, 1.0f)
            applyMixedVolume()
        }

        /** Internal — set by the crossfade controller. */
        internal fun setCrossfadeFactor(factor: Float) {
            crossfadeFactor = factor.coerceIn(0.0f, 1.0f)
            applyMixedVolume()
        }

        /** Internal — read by the crossfade tick to skip redundant
         *  setVolume calls. */
        internal fun crossfadeFactorRead(): Float = crossfadeFactor

        private fun applyMixedVolume() {
            val p = getExoPlayer() ?: return
            val v = (replayGainFactor * crossfadeFactor).coerceIn(0.0f, 1.0f)
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

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

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
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink
                            .DefaultAudioProcessorChain(
                                stereoTransformProcessor,
                                audioDelayProcessor
                            )
                    )
                    .build()
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
        serviceScope.launch {
            settingsDataStore.audioDelayMs.collect { audioDelayFlag = it }
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
            kotlinx.coroutines.flow.combine(
                settingsDataStore.crossfadeMs,
                settingsDataStore.crossfadeEnabled
            ) { ms, enabled -> if (enabled) ms else 0 }
                .collect { crossfadeMsFlag = it }
        }
        serviceScope.launch {
            settingsDataStore.crossfadeCurve.collect { crossfadeCurveFlag = it }
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
            castPlayer = cp
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
    @Volatile private var trackStartTimestampMs: Long = 0L

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
    @Volatile private var pausedDueToFocus: Boolean = false
    @Volatile private var duckedDueToFocus: Boolean = false

    private fun installAudioFocusPolicy() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleAudioFocusChange(focusChange)
        }
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
        val policy = serviceScope.async {
            when (change) {
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                    settingsDataStore.audioFocusOnCall.first()
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    settingsDataStore.audioFocusOnNotification.first()
                android.media.AudioManager.AUDIOFOCUS_LOSS ->
                    settingsDataStore.audioFocusOnOtherMedia.first()
                else -> "gain"
            }
        }
        serviceScope.launch {
            val choice = policy.await()
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
                        // "continue" / "ignore" → do nothing.
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
                // Phase 8 — refresh home-screen widget on track change.
                com.powermediaplayer.widget.NowPlayingWidgetProvider
                    .refresh(applicationContext)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                com.powermediaplayer.widget.NowPlayingWidgetProvider
                    .refresh(applicationContext)
            }
        })

        crossfadeJob = serviceScope.launch {
            while (isActive) {
                applyCrossfadeTick()
                kotlinx.coroutines.delay(100)
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

        // Fade-out window approaching the end of the current track.
        // Skip on the last track of the queue so playback doesn't end
        // muted (no next track to crossfade into). Also skip when
        // album-mode applies (same-album consecutive tracks).
        val fadeOut = if (
            !isLast && playing && !sameAlbumAsNext &&
            duration > 0L && pos > 0L &&
            duration - pos in 0..ms.toLong()
        ) {
            ((duration - pos).toFloat() / ms).coerceIn(0.0f, 1.0f)
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
        crossfadeController.maybeStartCrossfade(
            primary = p,
            crossfadeMs = ms,
            curve = crossfadeCurveFlag,
            primaryFinalVolume = 1.0f
        )
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

        val items = (0 until current.mediaItemCount).map { current.getMediaItemAt(it) }
        val currentIndex = current.currentMediaItemIndex
        val currentPosition = current.currentPosition
        val playWhenReady = current.playWhenReady

        val transformed = if (target is CastPlayer) {
            val server = startCastRelayIfNeeded()
            if (server == null && items.isNotEmpty()) {
                // BUG FIX (user-reported "cast connects but nothing plays,
                // player wipes all tracks"): if the relay can't start,
                // we used to silently fall through to the original
                // content:// / file:// URIs which the receiver cannot
                // fetch — CastPlayer.setMediaItems then loaded
                // unreachable URLs, the receiver bailed, currentMediaItem
                // went null, and the UI rendered as an empty player.
                // Now we abort the takeover entirely: keep playing
                // locally + post a toast so the user knows.
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
            server?.let { items.map { rebuildForCast(it, server) } } ?: items
        } else {
            stopCastRelay()
            items
        }

        current.stop()
        ms.player = target
        if (transformed.isNotEmpty()) {
            target.setMediaItems(transformed, currentIndex, currentPosition)
            target.playWhenReady = playWhenReady
            target.prepare()
        }

        // Re-publish the active player reference for the video surface.
        if (target is ExoPlayer) {
            exoPlayerRef = java.lang.ref.WeakReference(target)
        } else {
            exoPlayerRef = null
        }
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

    private fun guessMimeFromUri(uri: android.net.Uri): String {
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Default behaviour: stop the service ONLY when nothing is
        // queued / not playing, so audio survives the user swiping
        // the app from Recents (which is what most music players do).
        // When the user has opted in to "Stop on swipe-away" via
        // Settings, we stop unconditionally.
        val stopUnconditionally = runCatching {
            kotlinx.coroutines.runBlocking {
                settingsDataStore.stopOnTaskRemoved.first()
            }
        }.getOrDefault(false)
        val player = mediaSession?.player
        if (stopUnconditionally) {
            player?.stop()
            stopSelf()
            return
        }
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
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
            if (!isExternal) return super.onPlayerCommandRequest(session, controller, playerCommand)

            val player = session.player
            val mapping = btMapping
            return when (playerCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    applyAction(player, mapping.prevAction, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    applyAction(player, mapping.nextAction, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                // AVRCP FAST_FORWARD / REWIND are semantically "seek by N
                // seconds" (distinct from NEXT/PREV which navigate items),
                // so they always go through SKIP_FORWARD / SKIP_BACK
                // regardless of the user's prevAction / nextAction
                // remapping. Some car head-units bind their forward / back
                // keys to these instead of NEXT / PREV.
                Player.COMMAND_SEEK_FORWARD -> {
                    applyAction(player, BluetoothMediaActions.SKIP_FORWARD, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_BACK -> {
                    applyAction(player, BluetoothMediaActions.SKIP_BACK, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> @Suppress("DEPRECATION") super.onPlayerCommandRequest(session, controller, playerCommand)
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
            val resolvedItems = mediaItems.map { item ->
                val resolvedUri = item.localConfiguration?.uri
                    ?: item.requestMetadata.mediaUri
                    ?: runCatching { android.net.Uri.parse(item.mediaId) }.getOrNull()
                if (resolvedUri != null) {
                    item.buildUpon().setUri(resolvedUri).build()
                } else {
                    item
                }
            }.toMutableList()
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
        when (action) {
            BluetoothMediaActions.PREV_TRACK -> player.seekToPreviousMediaItem()
            BluetoothMediaActions.NEXT_TRACK -> player.seekToNextMediaItem()
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
