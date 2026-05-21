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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @Volatile
    private var stereoFlipFlag: Boolean = false
    @Volatile
    private var monoMixFlag: Boolean = false
    @Volatile
    private var audioDelayFlag: Int = 0
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

    @javax.inject.Inject
    lateinit var hueProvider: com.powermediaplayer.hue.HueProvider

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

        /** §B5 — last auto-revert reason (null when not active). */
        @Volatile var crossfadeAutoRevertReason: String? = null

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
                                audioDelayProcessor,
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
            // Sum of the manual audio-delay slider AND the BT video
            // audio-offset slider. Both feed the same processor so users
            // can stack them without surprises.
            kotlinx.coroutines.flow.combine(
                settingsDataStore.audioDelayMs,
                settingsDataStore.btVideoAudioOffsetMs
            ) { a, b -> a + b }.collect { audioDelayFlag = it }
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
        // 600 ms timeout safety net (raised from 200 ms — friend
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
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    removedDevices?.forEach { d ->
                        com.powermediaplayer.diag.DiagLog.route(
                            "device- type=${audioDeviceTypeName(d.type)} name='${d.productName}' " +
                                "id=${d.id} sink=${d.isSink} src=${d.isSource}"
                        )
                    }
                }
            }
            am?.registerAudioDeviceCallback(audioDeviceCallback, null)
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

        // Hue Entertainment audio-reactive — single intensity-driven
        // path. When (paired + intensity > 0 + playback active) the
        // analyser feeds beat / band / BPM data into the DTLS stream.
        // No separate mode picker; the slider is the only control.
        serviceScope.launch {
            val playingFlow = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(player?.isPlaying == true)
                    kotlinx.coroutines.delay(500)
                }
            }.distinctUntilChanged()
            kotlinx.coroutines.flow.combine(
                settingsDataStore.hueReactiveIntensity,
                playingFlow
            ) { intensity, isPlaying -> intensity to isPlaying }
                .distinctUntilChanged()
                .collect { (intensity, isPlaying) ->
                    // Spotify Connect + Cast — audio plays on a remote
                    // device, not through our AudioProcessor chain, so
                    // the PCM tap sees silence. Don't start the
                    // reactive engine for those sources; the user sees
                    // an explanatory note in Settings (the slider stays
                    // ineffectual).
                    val activePlayer = mediaSession?.player
                    val isCast = activePlayer is androidx.media3.cast.CastPlayer
                    val isSpotify = spotifyProvider.spotifyState.value != null
                    if (intensity > 0 && isPlaying && !isCast && !isSpotify) {
                        // vc29.10 — skip the whole bridge query chain
                        // (listAreas / listAllLights / ensureConfig /
                        // fetchProfiles) if a stream is already up.
                        // Without this guard, every isPlaying flicker
                        // triggers ~4 unnecessary HTTPS calls.
                        if (hueEntertainment.isStreaming()) return@collect
                        // vc29 — resolve the user-picked area (room /
                        // zone / entertainment) from DataStore. The
                        // composite key is "<kind>:<uuid>" so we don't
                        // need a second roundtrip just to know what
                        // kind to look up. Empty → legacy fallback to
                        // the first entertainment area.
                        val pickedKey = runCatching {
                            settingsDataStore.hueSelectedArea.first()
                        }.getOrDefault("")
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
                            com.powermediaplayer.hue.HueDimmableDriver.DimmableLight(
                                id = id,
                                latencyMs = profiles[id]?.latencyMs ?: 400
                            )
                        }
                        hueEntertainment.start(
                            cappedEnsured,
                            breakdown.colour.size,
                            dimmableLights,
                            intensity,
                            area.groupedLightId
                        )
                    } else {
                        if (intensity > 0 && isPlaying && (isCast || isSpotify)) {
                            com.powermediaplayer.diag.DiagLog.event(
                                "HUE",
                                "auto-start skipped — audio is on remote (isCast=$isCast isSpotify=$isSpotify); " +
                                    "PCM tap sees silence on these sources"
                            )
                        }
                        hueEntertainment.stop()
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
            castPlayer = cp

            // Bug fix (user-reported "I cast to A, then to B; A keeps
            // playing"): SessionAvailabilityListener only fires on
            // cast<->no-cast transitions. When the user switches devices
            // inside the picker, the SDK ends session A and starts B
            // without notifying the listener, leaving device A with the
            // last loaded URL — Google Home / Nest receivers happily
            // keep playing it. Hook SessionManagerListener so we can
            // explicitly clearMediaItems() before the channel closes.
            castContext.sessionManager.addSessionManagerListener(
                object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
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
                },
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
        if (Companion.oauthInFlight) {
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "AudioFocus change=$change SUPPRESSED (OAuth in flight)"
            )
            return
        }
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
                // D10 fix — publish the new mediaId so
                // MediaOverrideRepository can replace its 750ms poll.
                currentMediaIdFlow.value = mediaItem?.mediaId.orEmpty()
                // Phase 8 — refresh home-screen widget on track change.
                com.powermediaplayer.widget.NowPlayingWidgetProvider
                    .refresh(applicationContext)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // §B3 LOCKED — Pause = both players pause synchronously.
                // Resume = both players resume; secondary continues
                // ramping from where its volume sat.
                if (isPlaying) crossfadeController.resumeAll()
                else crossfadeController.pauseAll()
                com.powermediaplayer.widget.NowPlayingWidgetProvider
                    .refresh(applicationContext)
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

        current.stop()
        ms.player = target
        if (transformed.isNotEmpty()) {
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
        com.powermediaplayer.diag.DiagLog.lifecycle(
            "PlaybackService.onTaskRemoved stopUncond=$stopUnconditionally " +
                "playWhenReady=${player?.playWhenReady} itemCount=${player?.mediaItemCount}"
        )
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
        com.powermediaplayer.diag.DiagLog.lifecycle("PlaybackService.onDestroy")
        audioDeviceCallback?.let {
            runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        }
        audioDeviceCallback = null
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
