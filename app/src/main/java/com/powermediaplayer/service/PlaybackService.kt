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
import kotlinx.coroutines.cancel
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
    lateinit var settingsDataStore: SettingsDataStore

    private var player: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
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
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Configure renderers with FFmpeg extension support (when available)
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

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
                val token = googleDriveProvider.fetchAccessTokenBlocking()
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

        // Larger LoadControl buffers (120 s instead of the default ~50 s)
        // so a "far forward" scrub on a long video still lands inside
        // already-buffered content, avoiding the decoder re-init that
        // surfaces as the user-reported "stutter on far forward seeks".
        // Min playback buffer left at default; only the max is bumped.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                /* maxBufferMs */            120_000,
                /* bufferForPlaybackMs */    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs */
                                             DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

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
                /* handleAudioFocus */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
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

        // Publish the real ExoPlayer so VideoSurface can attach to it for rendering
        exoPlayerRef = java.lang.ref.WeakReference(player!!)

        // Create session activity intent for notification tap
        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build MediaSession with custom callback for skip actions
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(PlayerSessionCallback())
            .build()

        // Keep the Bluetooth mapping snapshot fresh. Combine into a
        // single Flow so we set the @Volatile field atomically.
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
                override fun onCastSessionUnavailable() = switchPlayer(player!!)
            })
            castPlayer = cp
        } catch (_: Exception) {
            // No cast — phone has no Play Services or unsupported device.
        }
    }

    /**
     * Migrate the current queue + position from the active player to [target]
     * so playback continues seamlessly when the user picks/leaves a cast device.
     */
    private fun switchPlayer(target: Player) {
        val ms = mediaSession ?: return
        val current = ms.player
        if (current === target) return

        val items = (0 until current.mediaItemCount).map { current.getMediaItemAt(it) }
        val currentIndex = current.currentMediaItemIndex
        val currentPosition = current.currentPosition
        val playWhenReady = current.playWhenReady

        current.stop()
        ms.player = target
        if (items.isNotEmpty()) {
            target.setMediaItems(items, currentIndex, currentPosition)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop playback when user swipes away app from recents
        val player = mediaSession?.player
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
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
        val base = if (pendingSeekTarget >= 0L) pendingSeekTarget else player.currentPosition
        val rawTarget = base + deltaMs
        val duration = player.duration.let { if (it == C.TIME_UNSET) Long.MAX_VALUE else it }
        val target = rawTarget.coerceIn(0L, duration)
        pendingSeekTarget = target
        player.seekTo(target)
        clearPendingSeekJob?.cancel()
        // 1500 ms is generous enough to cover even a slow human cadence
        // of "one tap per second", while still resetting before the user
        // moves to a different control.
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
                val target = (player.currentPosition - seconds * 1000L).coerceAtLeast(0L)
                player.seekTo(target)
            }
            BluetoothMediaActions.SKIP_FORWARD -> {
                player.seekTo(player.currentPosition + seconds * 1000L)
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
