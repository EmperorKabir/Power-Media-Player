package com.powermediaplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.powermediaplayer.cloud.GoogleDriveProvider
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

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
        exoPlayerRef = null          // clear before release so UI gets null not dead reference
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

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Resolve media items with URIs for playback
            val resolvedItems = mediaItems.map { item ->
                item.buildUpon()
                    .setUri(item.requestMetadata.mediaUri ?: item.mediaId.let { android.net.Uri.parse(it) })
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }
    }
}
