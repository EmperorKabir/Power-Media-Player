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

        // Build ExoPlayer with audio focus and wake lock.
        // Audio offload is left at the default (DISABLED) so AudioSink can
        // be re-initialised on sample-rate changes mid-stream — common in
        // chapter-concatenated M4B files.
        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
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

        // Snap seeks to the nearest keyframe instead of decoding from the
        // previous keyframe to the exact frame. Backward seeks on H.264/
        // HEVC 4K content are catastrophically slow with the default EXACT
        // mode (decoder must rewind a whole GOP and re-decode forward,
        // sometimes 200–500 ms of stall during which the video freezes).
        // CLOSEST_SYNC trades a few frames of seek precision for instant
        // response — what the user perceives as "scrubbing back" stutter
        // and "skip-button" lag.
        player!!.setSeekParameters(SeekParameters.CLOSEST_SYNC)

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
            val player = session.player
            val currentPos = player.currentPosition

            when (customCommand.customAction) {
                ACTION_SKIP_BACK_5 -> player.seekTo(maxOf(0, currentPos - 5_000))
                ACTION_SKIP_BACK_10 -> player.seekTo(maxOf(0, currentPos - 10_000))
                ACTION_SKIP_BACK_15 -> player.seekTo(maxOf(0, currentPos - 15_000))
                ACTION_SKIP_BACK_20 -> player.seekTo(maxOf(0, currentPos - 20_000))
                ACTION_SKIP_BACK_30 -> player.seekTo(maxOf(0, currentPos - 30_000))
                ACTION_SKIP_FORWARD_5 -> player.seekTo(currentPos + 5_000)
                ACTION_SKIP_FORWARD_10 -> player.seekTo(currentPos + 10_000)
                ACTION_SKIP_FORWARD_15 -> player.seekTo(currentPos + 15_000)
                ACTION_SKIP_FORWARD_20 -> player.seekTo(currentPos + 20_000)
                ACTION_SKIP_FORWARD_30 -> player.seekTo(currentPos + 30_000)
            }

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
