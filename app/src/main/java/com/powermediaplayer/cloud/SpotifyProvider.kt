package com.powermediaplayer.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonParser
import com.powermediaplayer.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of what's currently playing on the user's active Spotify
 * device. Driven by polling /v1/me/player at 1 Hz from
 * [SpotifyProvider.spotifyState]. All fields nullable / safe defaults
 * so the consumer (PlayerViewModel) can map straight into PlayerUiState.
 */
data class SpotifyPlaybackState(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val trackUri: String,
    val deviceName: String?,
    // Plain (non-synced) lyrics fetched from LRCLib if available.
    // Spotify Web API does NOT expose lyrics — they're licensed from
    // Musixmatch and only surface inside the official Spotify clients.
    // LRCLib is a free, unauthenticated, community-maintained source.
    val lyrics: String? = null,
    // Synced lyrics (when LRCLib has them) — parsed [mm:ss.xx]Line
    // pairs. Empty when only plain text is available. Drives the
    // current-line highlight + tap-to-seek in the player UI.
    val syncedLyrics: List<LyricLine> = emptyList()
)

/** A single time-tagged line from a LRC-format lyric file. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Coarse Spotify "section" the user can drill into. Each maps to a
 * single Spotify Web API endpoint. Order matches the user-confirmed
 * default for the section picker UI.
 */
enum class SpotifySection(val label: String) {
    LIKED_SONGS("Liked Songs"),
    RECENT("Recently Played"),
    SAVED_ALBUMS("Saved Albums"),
    SAVED_PLAYLISTS("Saved Playlists"),
    SAVED_EPISODES("Saved Episodes"),
    SAVED_SHOWS("Podcasts (saved shows)"),
    TOP_TRACKS("Top Tracks"),
    TOP_ARTISTS("Top Artists"),
    NEW_RELEASES("New Releases"),
    FEATURED_PLAYLISTS("Featured Playlists")
}

/**
 * Spotify Web API integration via OAuth 2.0 + PKCE (no client secret required).
 *
 * Uses the 2026 generic library endpoint `/v1/me/library?type=track,album,...`
 * which supersedes the per-type endpoints. See: developer.spotify.com docs.
 *
 * Note: Spotify does NOT permit Media3 to stream full tracks for free-tier
 * accounts; [getMediaStreamUri] returns the 30-s preview URL when available
 * and an unplayable spotify:track:... URI otherwise. Full playback requires
 * the Spotify Android SDK + Premium account, out of scope for this revision.
 */
@Singleton
class SpotifyProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStore: SpotifyTokenStore
) : CloudStorageProvider {

    override val providerType: CloudProviderType = CloudProviderType.SPOTIFY

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Mirror of what's playing on the active Spotify device. null when
    // nothing is playing or polling hasn't started. Updated at 1 Hz by
    // [pollJob] which starts on the first successful playTrack call and
    // stops automatically when the app is backgrounded for >30 s.
    private val _spotifyState = MutableStateFlow<SpotifyPlaybackState?>(null)
    val spotifyState: StateFlow<SpotifyPlaybackState?> = _spotifyState.asStateFlow()

    // True while we're waiting for the first fully-resolved Spotify
    // metadata for the current track — covers (a) the gap between
    // startPlaybackPolling and the first non-null state arriving, and
    // (b) the LRCLib lyrics fetch on every track change. Drives the
    // shared "Loading metadata… please wait…" banner alongside the
    // Drive cloudFetchInProgress flag.
    private val _spotifyMetadataFetching = MutableStateFlow(false)
    val spotifyMetadataFetching: StateFlow<Boolean> = _spotifyMetadataFetching.asStateFlow()

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    init {
        // Restore the signed-in flag on cold start. Without this every
        // app restart shows the consent screen even though the token
        // is persisted in DataStore — the UI was driven by
        // _isLoggedIn which always started as false.
        pollScope.launch {
            tokenStore.observe().collect { json ->
                _isLoggedIn.value = !json.isNullOrBlank()
            }
        }
    }

    private val authService: AuthorizationService by lazy { AuthorizationService(context) }
    private val http = OkHttpClient()

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.spotify.com/authorize"),
        Uri.parse("https://accounts.spotify.com/api/token")
    )

    // Premium-only scopes for full-track playback via Spotify Connect.
    // user-modify-playback-state lets us PUT /v1/me/player/play; the
    // read scopes let us list devices and read current state. Premium
    // is required by Spotify for these endpoints to function — free
    // accounts get HTTP 403 on /me/player/play.
    private val scopes = listOf(
        "user-library-read",
        "user-read-email",
        "user-read-private",
        "playlist-read-private",
        "user-modify-playback-state",
        "user-read-playback-state",
        "streaming"
    ).joinToString(" ")

    /**
     * Builds the AppAuth authorization request. The launcher in the UI layer
     * fires this and routes the result to [handleAuthResponse].
     */
    fun buildAuthIntent(): Intent {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.buildAuthIntent start")
        val t0 = System.currentTimeMillis()
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.SPOTIFY_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.SPOTIFY_REDIRECT_URI)
        )
            .setScope(scopes)
            .build()
        val intent = authService.getAuthorizationRequestIntent(request)
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.buildAuthIntent done ${System.currentTimeMillis() - t0}ms")
        return intent
    }

    /**
     * Exchange the authorization code for tokens and persist the AuthState.
     * Called from the UI's ActivityResult callback after the Custom Tab returns.
     */
    suspend fun handleAuthResponse(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.handleAuthResponse start data=${data != null}")
        if (data == null) return@withContext Result.failure(IllegalStateException("No auth result data"))
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.handleAuthResponse parsed resp=${resp != null} ex=${ex?.message}")
        if (resp == null) return@withContext Result.failure(ex ?: IllegalStateException("Auth canceled"))

        val authState = AuthState(serviceConfig).apply { update(resp, ex) }

        val t0 = System.currentTimeMillis()
        val result = suspendCancellableCoroutine<Result<Unit>> { cont ->
            authService.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Spotify.tokenRequest cb ${System.currentTimeMillis() - t0}ms ok=${tokenResp != null} ex=${tokenEx?.message}"
                )
                authState.update(tokenResp, tokenEx)
                if (tokenResp != null) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(tokenEx ?: IllegalStateException("Token exchange failed")))
                }
            }
        }
        // Persist token on IO thread (NOT inside the AppAuth callback's
        // runBlocking — that was blocking the AppAuth executor and caused
        // the post-consent freeze).
        if (result.isSuccess) {
            tokenStore.write(authState.jsonSerializeString())
            _isLoggedIn.value = true
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.handleAuthResponse persisted token")
        }
        result
    }

    override suspend fun authenticate(context: Context): Result<Unit> {
        // Spotify auth needs Activity-result wiring; the UI layer drives
        // [buildAuthIntent] + [handleAuthResponse] directly. This entry
        // point is left as a stub for the interface contract.
        return Result.failure(UnsupportedOperationException("Use buildAuthIntent + handleAuthResponse"))
    }

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        // Tear down the polling job + spotify mirror state so the
        // Player tab doesn't keep showing the last-known track after
        // sign-out.
        stopPlaybackPolling()
        tokenStore.write(null)
        _isLoggedIn.value = false
        Result.success(Unit)
    }

    /**
     * Fetch saved items from `/me/library` (2026 generic endpoint).
     * Returns the union of saved tracks + albums + playlists, paged once.
     */
    override suspend fun listFiles(folderId: String?): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listFiles start")
            val t0 = System.currentTimeMillis()
            val token = currentAccessToken()
            if (token == null) {
                com.powermediaplayer.util.Diag.w("PMP_DIAG", "Spotify.listFiles no token")
                return@withContext Result.failure(IllegalStateException("Not authenticated"))
            }
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listFiles got token ${System.currentTimeMillis() - t0}ms")

            val url = "https://api.spotify.com/v1/me/library?type=track,album,playlist&limit=50"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val items = mutableListOf<CloudMediaItem>()
            try {
                http.newCall(req).execute().use { resp ->
                    com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listFiles http=${resp.code} ${System.currentTimeMillis() - t0}ms")
                    if (!resp.isSuccessful) {
                        // Fall back to per-type endpoints if generic 404s on this account
                        return@withContext fetchPerType(token)
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JsonParser.parseString(body).asJsonObject
                    val arr = root.getAsJsonArray("items") ?: return@withContext Result.success(items)
                    for (el in arr) {
                        val obj = el.asJsonObject
                        val type = obj.get("type")?.asString ?: continue
                        val item = obj.getAsJsonObject(type) ?: obj
                        items.add(jsonToCloudItem(item, type))
                    }
                    com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listFiles parsed=${items.size}")
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            Result.success(items)
        }

    /**
     * Fetch one named Spotify section. Each endpoint has a slightly
     * different envelope so the parsing is per-section. Returns an
     * empty list on non-200 (e.g. 403 from the top-tracks endpoint if
     * the user hasn't granted the long-term-listening scope or doesn't
     * have enough history).
     */
    suspend fun listSection(section: SpotifySection): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Not authenticated")
            )
            val url = when (section) {
                SpotifySection.LIKED_SONGS -> "https://api.spotify.com/v1/me/tracks?limit=50"
                SpotifySection.SAVED_ALBUMS -> "https://api.spotify.com/v1/me/albums?limit=50"
                SpotifySection.SAVED_PLAYLISTS -> "https://api.spotify.com/v1/me/playlists?limit=50"
                SpotifySection.RECENT -> "https://api.spotify.com/v1/me/player/recently-played?limit=50"
                SpotifySection.TOP_TRACKS -> "https://api.spotify.com/v1/me/top/tracks?limit=50"
                SpotifySection.TOP_ARTISTS -> "https://api.spotify.com/v1/me/top/artists?limit=50"
                SpotifySection.NEW_RELEASES -> "https://api.spotify.com/v1/browse/new-releases?limit=50"
                SpotifySection.FEATURED_PLAYLISTS -> "https://api.spotify.com/v1/browse/featured-playlists?limit=50"
                SpotifySection.SAVED_EPISODES -> "https://api.spotify.com/v1/me/episodes?limit=50"
                SpotifySection.SAVED_SHOWS -> "https://api.spotify.com/v1/me/shows?limit=50"
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val items = mutableListOf<CloudMediaItem>()
            try {
                val body: String = http.newCall(req).execute().use { resp ->
                    com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.section $section http=${resp.code}")
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
                if (body.isNotBlank()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    parseSectionInto(section, root, items)
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "Spotify.section $section parsed=${items.size}"
                    )
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseSectionInto(
        section: SpotifySection,
        root: com.google.gson.JsonObject,
        items: MutableList<CloudMediaItem>
    ) {
        when (section) {
            SpotifySection.LIKED_SONGS, SpotifySection.SAVED_EPISODES -> {
                val arr = root.getAsJsonArray("items") ?: return
                val key = if (section == SpotifySection.LIKED_SONGS) "track" else "episode"
                val type = key
                for (el in arr) {
                    val core = el.asJsonObject.getAsJsonObject(key) ?: continue
                    items.add(jsonToCloudItem(core, type))
                }
            }
            SpotifySection.SAVED_ALBUMS -> {
                val arr = root.getAsJsonArray("items") ?: return
                for (el in arr) {
                    val core = el.asJsonObject.getAsJsonObject("album") ?: continue
                    items.add(jsonToCloudItem(core, "album"))
                }
            }
            SpotifySection.SAVED_SHOWS -> {
                val arr = root.getAsJsonArray("items") ?: return
                for (el in arr) {
                    val core = el.asJsonObject.getAsJsonObject("show") ?: continue
                    items.add(jsonToCloudItem(core, "show"))
                }
            }
            SpotifySection.SAVED_PLAYLISTS, SpotifySection.TOP_TRACKS,
            SpotifySection.TOP_ARTISTS -> {
                val arr = root.getAsJsonArray("items") ?: return
                val type = when (section) {
                    SpotifySection.SAVED_PLAYLISTS -> "playlist"
                    SpotifySection.TOP_TRACKS -> "track"
                    SpotifySection.TOP_ARTISTS -> "artist"
                    else -> "track"
                }
                for (el in arr) items.add(jsonToCloudItem(el.asJsonObject, type))
            }
            SpotifySection.RECENT -> {
                val arr = root.getAsJsonArray("items") ?: return
                for (el in arr) {
                    val core = el.asJsonObject.getAsJsonObject("track") ?: continue
                    items.add(jsonToCloudItem(core, "track"))
                }
            }
            SpotifySection.NEW_RELEASES -> {
                val arr = root.getAsJsonObject("albums")?.getAsJsonArray("items") ?: return
                for (el in arr) items.add(jsonToCloudItem(el.asJsonObject, "album"))
            }
            SpotifySection.FEATURED_PLAYLISTS -> {
                val arr = root.getAsJsonObject("playlists")?.getAsJsonArray("items") ?: return
                for (el in arr) items.add(jsonToCloudItem(el.asJsonObject, "playlist"))
            }
        }
    }

    private fun fetchPerType(token: String): Result<List<CloudMediaItem>> {
        val items = mutableListOf<CloudMediaItem>()
        val endpoints = listOf(
            "track" to "https://api.spotify.com/v1/me/tracks?limit=50",
            "album" to "https://api.spotify.com/v1/me/albums?limit=50",
            "playlist" to "https://api.spotify.com/v1/me/playlists?limit=50"
        )
        for ((type, url) in endpoints) {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val root = JsonParser.parseString(body).asJsonObject
                    val arr = root.getAsJsonArray("items") ?: return@use
                    for (el in arr) {
                        val obj = el.asJsonObject
                        val core = obj.getAsJsonObject(type) ?: obj
                        items.add(jsonToCloudItem(core, type))
                    }
                }
            } catch (_: Exception) { /* skip endpoint on failure */ }
        }
        return Result.success(items)
    }

    private fun jsonToCloudItem(obj: com.google.gson.JsonObject, type: String): CloudMediaItem {
        val id = obj.get("id")?.asString ?: ""
        val name = obj.get("name")?.asString ?: "Untitled"
        val preview = obj.get("preview_url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val spotifyUri = obj.get("uri")?.asString.orEmpty()
        // For tracks the track's album URI gives next/previous a context
        // to traverse — without it Spotify Connect /next stops after the
        // single track. For albums and playlists the URI itself IS the
        // context so we pass it through.
        val contextUri = when (type) {
            "track" -> obj.getAsJsonObject("album")?.get("uri")?.takeIf { !it.isJsonNull }?.asString
            "album", "playlist" -> spotifyUri
            else -> null
        }
        return CloudMediaItem(
            id = id,
            name = name,
            mimeType = if (type == "track") "audio/mpeg" else "application/spotify-$type",
            size = 0L,
            downloadUrl = preview.ifEmpty { spotifyUri },
            sourceProvider = CloudProviderType.SPOTIFY,
            isFolder = type != "track",
            contextUri = contextUri
        )
    }

    /**
     * Preview URL when available (30-second clip) — full streaming requires
     * the Spotify Android SDK + a Premium account, not implemented here.
     */
    override suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri> {
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Spotify.getMediaStreamUri name=${item.name} url=${item.downloadUrl.take(80)}"
        )
        // Tracks without a preview clip have downloadUrl set to spotify:track:…
        // which ExoPlayer cannot resolve — surface a clear error instead of
        // letting it fail later with ERROR_CODE_IO_NETWORK_CONNECTION_FAILED.
        if (item.downloadUrl.isBlank()) {
            return Result.failure(IllegalStateException("No playable URL for this track"))
        }
        if (item.downloadUrl.startsWith("spotify:")) {
            return Result.failure(
                IllegalStateException(
                    "Spotify removed the 30-second preview for this track. " +
                        "Full-track playback requires the Spotify Android SDK + Premium."
                )
            )
        }
        return Result.success(Uri.parse(item.downloadUrl))
    }

    /**
     * Premium-only: tell Spotify Connect to play [spotifyUri] on the
     * user's currently-active device. Returns success on HTTP 204.
     *
     * Failure modes the UI translates to user-readable messages:
     *   • 401: token expired and refresh failed → user must re-auth
     *   • 403: account is not Premium
     *   • 404: NO_ACTIVE_DEVICE — user needs to open Spotify on a
     *     phone or other Spotify Connect device first. Before
     *     reporting this, we try to auto-pick the first available
     *     device via /me/player/devices and PUT /me/player to transfer
     *     playback there, then retry the play call.
     */
    suspend fun playTrackOnConnectDevice(
        spotifyUri: String,
        contextUri: String? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "Spotify.playTrackOnConnectDevice $spotifyUri context=$contextUri"
            )
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Spotify session expired — sign in again")
            )
            // If caller didn't supply a context but the URI is a track,
            // resolve the track's album so /next + /previous work.
            val resolvedContext = contextUri ?: resolveTrackAlbumUri(token, spotifyUri)
            val firstAttempt = playRequest(token, spotifyUri, resolvedContext, deviceId = null)
            if (firstAttempt.isSuccess) return@withContext firstAttempt

            // 404 NO_ACTIVE_DEVICE — pick first device and retry.
            val errMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
            if (!errMsg.contains("NO_ACTIVE_DEVICE", ignoreCase = true) &&
                !errMsg.contains("404")
            ) {
                return@withContext firstAttempt
            }

            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.play no active device — listing")
            var devices = listDevices(token)
            if (devices.isEmpty()) {
                // Spotify isn't running anywhere. Auto-launch the
                // installed Spotify app so it registers as a Connect
                // device, then immediately bounce our app back to the
                // foreground so the user feels they never left.
                val launched = launchSpotifyAndReturn()
                if (launched) {
                    // Spotify takes ~1–3 s to start up + register with
                    // Connect. Poll the devices endpoint up to 5 s,
                    // breaking out the moment a device is visible so
                    // we don't keep hitting the API for 5 more seconds.
                    for (attempt in 0 until 10) {
                        kotlinx.coroutines.delay(500)
                        devices = listDevices(token)
                        if (devices.isNotEmpty()) {
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Spotify.play device appeared after ${(attempt + 1) * 500}ms"
                            )
                            break
                        }
                    }
                }
            }
            val first = devices.firstOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "Spotify isn't installed or didn't start. Open/install Spotify on this " +
                            "phone or another device, then try again."
                    )
                )
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.play activating device ${first.first} (${first.second})")
            val transferred = transferPlayback(token, first.first)
            if (transferred.isFailure) return@withContext transferred
            // Tiny gap so Spotify finishes activating the device before
            // we send the play command.
            kotlinx.coroutines.delay(400)
            playRequest(token, spotifyUri, resolvedContext, deviceId = first.first)
        }

    /**
     * Wake the Spotify app via its launch intent, then schedule our
     * own MainActivity to come back to the foreground a moment later.
     * The user briefly sees Spotify's splash, then is dropped back
     * into our app — which by then has a Connect device to play to.
     *
     * Returns true if Spotify is installed and the launch intent
     * fired successfully; false (with no UI side-effect) when the
     * user doesn't have Spotify installed.
     */
    private fun launchSpotifyAndReturn(): Boolean {
        // Delegate to SpotifyBounceBridgeActivity — a translucent, in-
        // task Activity that owns the Spotify auto-launch and the
        // 1.5 s deferred bounce-back. The bridge's startActivity
        // qualifies for Android's BAL grace period (system logs
        // `BAL_ALLOW_GRACE_PERIOD`), which sidesteps Samsung One UI's
        // `balDontBringExistingBackgroundTaskStackToFg=true` policy
        // that BAL_BLOCK's the previous PendingIntent.send approach.
        // See docs/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/.
        return try {
            val pm = context.packageManager
            if (pm.getLaunchIntentForPackage("com.spotify.music") == null) {
                com.powermediaplayer.util.Diag.w("PMP_DIAG", "Spotify auto-launch skipped — app not installed")
                return false
            }
            val bridge = Intent(
                context,
                com.powermediaplayer.service.SpotifyBounceBridgeActivity::class.java
            ).apply {
                // FLAG_ACTIVITY_NEW_TASK is required when starting an
                // Activity from a non-Activity Context (provider).
                // The bridge's manifest does NOT declare its own
                // taskAffinity, so it lands in MainActivity's task.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(bridge)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify auto-launch dispatched via BounceBridge")
            true
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "Spotify auto-launch (bridge) failed", e)
            false
        }
    }

    /**
     * Look up the album URI for a given spotify:track:ID via
     * GET /v1/tracks/{id}. Used when the caller doesn't already know
     * the playback context. Returns null on any failure (caller falls
     * back to single-URI play).
     */
    private fun resolveTrackAlbumUri(token: String, spotifyTrackUri: String): String? {
        if (!spotifyTrackUri.startsWith("spotify:track:")) return null
        val trackId = spotifyTrackUri.removePrefix("spotify:track:")
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/tracks/$trackId")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val root = JsonParser.parseString(body).asJsonObject
                val album = root.getAsJsonObject("album") ?: return@use null
                album.get("uri")?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (_: Exception) { null }
    }

    private fun playRequest(
        token: String,
        spotifyUri: String,
        contextUri: String?,
        deviceId: String?
    ): Result<Unit> {
        val url = StringBuilder("https://api.spotify.com/v1/me/player/play")
        if (deviceId != null) url.append("?device_id=").append(deviceId)
        // When a context is available, send context_uri + offset so the
        // Spotify queue is the album/playlist starting at this track.
        // Without it, fall back to single-track play (legacy behaviour).
        val bodyJson = if (contextUri != null) {
            """{"context_uri":"$contextUri","offset":{"uri":"$spotifyUri"}}"""
        } else {
            """{"uris":["$spotifyUri"]}"""
        }
        val body = okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            bodyJson
        )
        val req = Request.Builder()
            .url(url.toString())
            .put(body)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.playRequest http=${resp.code}")
                when (resp.code) {
                    in 200..299 -> Result.success(Unit)
                    401 -> Result.failure(IllegalStateException("Spotify session expired — sign in again"))
                    403 -> Result.failure(IllegalStateException("Spotify Premium required for full playback. If you have Premium, sign out and sign in again to grant the new playback permissions."))
                    404 -> Result.failure(IllegalStateException("NO_ACTIVE_DEVICE"))
                    else -> Result.failure(IllegalStateException("Spotify HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Public wrapper for the device-picker UI in the cloud Spotify
     * section. Returns id-name pairs of every Connect device the
     * user's Spotify account currently sees, including Google Home /
     * Nest speakers when the user has linked their Google account to
     * Spotify in the Google Home app. Empty list when no device is
     * registered or the request fails.
     */
    suspend fun listConnectDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext emptyList()
        listDevices(token)
    }

    /**
     * Public wrapper to transfer Spotify playback to [deviceId].
     * Used by the device-picker UI; the resulting Connect target then
     * receives subsequent playTrackOnConnectDevice calls.
     */
    suspend fun transferPlaybackTo(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired — sign in again")
        )
        transferPlayback(token, deviceId)
    }

    private fun listDevices(token: String): List<Pair<String, String>> {
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/me/player/devices")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Pair<String, String>>()
                val body = resp.body?.string().orEmpty()
                val arr = JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("devices") ?: return@use emptyList()
                arr.mapNotNull {
                    val o = it.asJsonObject
                    val id = o.get("id")?.asString ?: return@mapNotNull null
                    val name = o.get("name")?.asString ?: id
                    id to name
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun transferPlayback(token: String, deviceId: String): Result<Unit> {
        val bodyJson = """{"device_ids":["$deviceId"],"play":false}"""
        val body = okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            bodyJson
        )
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/me/player")
            .put(body)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.transferPlayback http=${resp.code}")
                if (resp.code in 200..299) Result.success(Unit)
                else Result.failure(IllegalStateException("Transfer playback HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Start polling /v1/me/player at 1 Hz so the app's Player tab can
     * mirror what's playing on Spotify. Idempotent — calling twice
     * doesn't double the polling rate.
     */
    /**
     * Spotify Web API /v1/search across track + album + playlist +
     * show + episode. Returns a flat CloudMediaItem list; tracks are
     * tappable, the rest currently land on the "browse not implemented"
     * error path until the section UI lands.
     */
    /**
     * Drill into a Spotify album / playlist / show URI and return the
     * tracks (or episodes for shows). Each child carries the parent's
     * URI as contextUri so /next + /previous operate within the
     * container.
     */
    suspend fun listContainer(containerUri: String): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Not authenticated")
            )
            val parts = containerUri.split(":")
            if (parts.size < 3) return@withContext Result.success(emptyList())
            val type = parts[1]
            val id = parts[2]
            // Use container-object endpoints (which return the items
            // inline) instead of /{id}/tracks endpoints — Spotify's
            // late-2024 API restriction returns 403 from /tracks for
            // non-approved third-party apps but still allows the
            // parent /{id} endpoint to surface the items.
            val url = when (type) {
                "album" -> "https://api.spotify.com/v1/albums/$id?market=from_token"
                "playlist" -> "https://api.spotify.com/v1/playlists/$id?market=from_token"
                "show" -> "https://api.spotify.com/v1/shows/$id?market=from_token"
                "artist" -> "https://api.spotify.com/v1/artists/$id/top-tracks?market=from_token"
                else -> return@withContext Result.success(emptyList())
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            try {
                val items = mutableListOf<CloudMediaItem>()
                val body: String = http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val keys = if (resp.isSuccessful) {
                        runCatching {
                            JsonParser.parseString(raw).asJsonObject.keySet().joinToString(",")
                        }.getOrDefault("?")
                    } else ""
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "Spotify.listContainer $containerUri http=${resp.code} bytes=${raw.length} rootKeys=[$keys]"
                    )
                    if (!resp.isSuccessful) "" else raw
                }
                if (body.isNotBlank()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    when (type) {
                        "album", "playlist", "show" -> {
                            // Spotify response shape varies. Try
                            // root.items (raw array), then
                            // root.items.items (paged wrapper at root),
                            // then root.tracks.items / root.episodes.items
                            // (legacy nested). All three fail safely.
                            val containerKey = if (type == "show") "episodes" else "tracks"
                            val arr: com.google.gson.JsonArray? = runCatching {
                                root.get("items")?.let { v ->
                                    when {
                                        v.isJsonArray -> v.asJsonArray
                                        v.isJsonObject -> v.asJsonObject.getAsJsonArray("items")
                                        else -> null
                                    }
                                } ?: root.getAsJsonObject(containerKey)?.getAsJsonArray("items")
                            }.getOrNull()
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Spotify.listContainer parsed arrSize=${arr?.size() ?: -1}"
                            )
                            arr ?: return@withContext Result.success(items)
                            val childType = if (type == "show") "episode" else "track"
                            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listContainer iterating arr.size=${arr.size()}")
                            if (arr.size() > 0) {
                                val first = arr[0]
                                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listContainer firstElem=${first.toString().take(800)}")
                            }
                            for (el in arr) {
                                if (!el.isJsonObject) continue
                                val obj = el.asJsonObject
                                // Probe across known Spotify shapes for the
                                // child object that actually has an `id`.
                                val candidates = listOfNotNull(
                                    obj.takeIf { it.has("id") && !it.get("id").isJsonNull },
                                    // Spotify's current playlist response wraps the
                                    // track under "item" (singular). Older docs say
                                    // "track" — check both.
                                    obj.getAsJsonObject("item"),
                                    obj.getAsJsonObject("track"),
                                    obj.getAsJsonObject("episode"),
                                    obj.getAsJsonObject("show")
                                )
                                val core = candidates.firstOrNull {
                                    it.has("id") && !it.get("id").isJsonNull
                                } ?: continue
                                val item = jsonToCloudItem(core, childType)
                                items.add(item.copy(contextUri = containerUri))
                            }
                            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.listContainer items=${items.size}")
                        }
                        "artist" -> {
                            val arr = root.getAsJsonArray("tracks") ?: return@withContext Result.success(items)
                            for (el in arr) items.add(jsonToCloudItem(el.asJsonObject, "track"))
                        }
                    }
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun search(query: String): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(emptyList())
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Not authenticated")
            )
            val url = "https://api.spotify.com/v1/search?" +
                "q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&type=track,album,playlist,show,episode&limit=10"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val results = mutableListOf<CloudMediaItem>()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Spotify search HTTP ${resp.code}")
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JsonParser.parseString(body).asJsonObject
                    listOf("tracks" to "track", "albums" to "album",
                           "playlists" to "playlist", "shows" to "show",
                           "episodes" to "episode").forEach { (key, type) ->
                        val arr = root.getAsJsonObject(key)
                            ?.getAsJsonArray("items") ?: return@forEach
                        for (el in arr) {
                            val obj = el.asJsonObject
                            results.add(jsonToCloudItem(obj, type))
                        }
                    }
                }
                com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.search q='$query' n=${results.size}")
                Result.success(results)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // Generation token — incremented every stop. Polling loop captures
    // its generation; writes to _spotifyState are suppressed when a
    // newer generation has been started (or when polling has been
    // stopped). Closes the race where an inflight HTTP for /v1/me/player
    // resolves AFTER stopPlaybackPolling sets the state to null and
    // overwrites the null with stale snap, leaving the Spotify mirror
    // visible while local m4b plays underneath.
    @Volatile private var pollGen: Int = 0

    fun startPlaybackPolling() {
        if (pollJob?.isActive == true) return
        val gen = ++pollGen
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.startPlaybackPolling gen=$gen")
        // Banner ON until the first fully-resolved emit arrives.
        _spotifyMetadataFetching.value = true
        pollJob = pollScope.launch {
            var lastTrackUri = ""
            var lastLyrics: String? = null
            var lastSynced: List<LyricLine> = emptyList()
            // Iteration counter — first ~10 iterations poll at 200 ms
            // because Spotify's /v1/me/player is eventually-consistent
            // for a second or two after a /play call. Without the burst
            // the album art / title / artist on the player tab can be
            // stale for 1.5 s+ after the user taps a new track.
            var iter = 0
            while (isActive) {
                val token = currentAccessToken()
                if (token != null) {
                    val snap = fetchCurrentState(token)
                    if (gen != pollGen) return@launch
                    if (snap != null) {
                        if (snap.trackUri != lastTrackUri) {
                            // Mid-session track change also re-fires the
                            // banner — the upcoming lyrics fetch is the
                            // slow point and the user shouldn't see a
                            // half-resolved track.
                            if (lastTrackUri.isNotEmpty()) {
                                _spotifyMetadataFetching.value = true
                            }
                            lastTrackUri = snap.trackUri
                            val pair = fetchLyricsLrclib(snap.title, snap.artist, snap.album, snap.durationMs)
                            if (gen != pollGen) return@launch
                            lastLyrics = pair?.first
                            lastSynced = pair?.second.orEmpty()
                        }
                        _spotifyState.value = snap.copy(lyrics = lastLyrics, syncedLyrics = lastSynced)
                        // Metadata fully resolved for this track.
                        _spotifyMetadataFetching.value = false
                    } else {
                        _spotifyState.value = null
                        lastTrackUri = ""
                        lastLyrics = null
                        lastSynced = emptyList()
                        // No Spotify activity → nothing to wait for.
                        _spotifyMetadataFetching.value = false
                    }
                }
                iter++
                delay(if (iter < 10) 200 else 1000)
            }
        }
    }

    /**
     * Fetch plain lyrics from LRCLib by track title / artist / album /
     * duration. Returns null on miss. LRCLib is free and doesn't
     * require an API key. https://lrclib.net/docs
     */
    private fun fetchLyricsLrclib(
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): Pair<String, List<LyricLine>>? {
        if (title.isBlank() || artist.isBlank()) return null
        val firstArtist = artist.substringBefore(',').trim()
        val durSec = (durationMs / 1000).toString()
        val url = "https://lrclib.net/api/get?" +
            "track_name=" + java.net.URLEncoder.encode(title, "UTF-8") +
            "&artist_name=" + java.net.URLEncoder.encode(firstArtist, "UTF-8") +
            "&album_name=" + java.net.URLEncoder.encode(album, "UTF-8") +
            "&duration=" + durSec
        val req = Request.Builder().url(url).build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val root = JsonParser.parseString(body).asJsonObject
                val plain = root.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString
                val syncedRaw = root.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                val parsed = syncedRaw?.let { parseLrc(it) }.orEmpty()
                val plainFallback = plain ?: syncedRaw?.let { stripLrcTimestamps(it) }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Spotify.lyrics LRCLib synced=${parsed.size} plain=${plainFallback?.length ?: 0} title='$title' artist='$firstArtist'"
                )
                if (plainFallback == null && parsed.isEmpty()) null
                else (plainFallback.orEmpty()) to parsed
            }
        } catch (_: Exception) { null }
    }

    private val lrcTagRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")

    private fun parseLrc(synced: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (rawLine in synced.lineSequence()) {
            val matches = lrcTagRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.replace(lrcTagRegex, "").trim()
            for (m in matches) {
                val mins = m.groupValues[1].toLong()
                val secs = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val timeMs = (mins * 60 + secs) * 1000 + frac
                out.add(LyricLine(timeMs, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun stripLrcTimestamps(synced: String): String {
        return synced.lineSequence()
            .map { it.replace(lrcTagRegex, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun stopPlaybackPolling() {
        pollGen++
        pollJob?.cancel()
        pollJob = null
        _spotifyState.value = null
        _spotifyMetadataFetching.value = false
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Spotify.stopPlaybackPolling gen=$pollGen")
    }

    private fun fetchCurrentState(token: String): SpotifyPlaybackState? {
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/me/player")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 204 || !resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val root = JsonParser.parseString(body).asJsonObject
                val item = root.getAsJsonObject("item") ?: return@use null
                val artists = item.getAsJsonArray("artists")
                    ?.joinToString(", ") { it.asJsonObject.get("name")?.asString.orEmpty() }
                    .orEmpty()
                val album = item.getAsJsonObject("album")
                val artwork = album?.getAsJsonArray("images")?.firstOrNull()
                    ?.asJsonObject?.get("url")?.asString
                val device = root.getAsJsonObject("device")
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Spotify.fetchCurrentState artwork=${artwork ?: "null"}"
                )
                SpotifyPlaybackState(
                    title = item.get("name")?.asString.orEmpty(),
                    artist = artists,
                    album = album?.get("name")?.asString.orEmpty(),
                    artworkUrl = artwork,
                    positionMs = root.get("progress_ms")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    durationMs = item.get("duration_ms")?.asLong ?: 0L,
                    isPlaying = root.get("is_playing")?.asBoolean ?: false,
                    trackUri = item.get("uri")?.asString.orEmpty(),
                    deviceName = device?.get("name")?.asString
                )
            }
        } catch (_: Exception) { null }
    }

    suspend fun togglePlayPause(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired")
        )
        val playing = _spotifyState.value?.isPlaying ?: false
        val endpoint = if (playing) "pause" else "play"
        simplePut(token, "https://api.spotify.com/v1/me/player/$endpoint")
    }

    /**
     * Unconditionally pause Spotify Connect — regardless of cached
     * `_spotifyState`. Used by Library when starting local playback so
     * the user is not left with two audio streams. Independent of
     * togglePlayPause which depends on cached state and would
     * otherwise resume playback after stopPlaybackPolling cleared it.
     */
    suspend fun pause(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired")
        )
        simplePut(token, "https://api.spotify.com/v1/me/player/pause")
    }

    suspend fun skipNext(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired")
        )
        simplePost(token, "https://api.spotify.com/v1/me/player/next")
    }

    suspend fun skipPrevious(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired")
        )
        simplePost(token, "https://api.spotify.com/v1/me/player/previous")
    }

    suspend fun seekTo(positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val token = currentAccessToken() ?: return@withContext Result.failure(
            IllegalStateException("Spotify session expired")
        )
        simplePut(token, "https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")
    }

    private fun simplePut(token: String, url: String): Result<Unit> {
        val req = Request.Builder()
            .url(url)
            .put(okhttp3.RequestBody.create(null, ByteArray(0)))
            .addHeader("Authorization", "Bearer $token")
            .build()
        return execControl(req)
    }

    private fun simplePost(token: String, url: String): Result<Unit> {
        val req = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .addHeader("Authorization", "Bearer $token")
            .build()
        return execControl(req)
    }

    private fun execControl(req: Request): Result<Unit> = try {
        http.newCall(req).execute().use { resp ->
            if (resp.code in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException("Spotify HTTP ${resp.code}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Returns a fresh access token, refreshing if needed.
     */
    private suspend fun currentAccessToken(): String? = withContext(Dispatchers.IO) {
        val json = tokenStore.read() ?: return@withContext null
        val state = AuthState.jsonDeserialize(json)
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { token, _, ex ->
                if (token != null) {
                    val refreshed = state.jsonSerializeString()
                    // Persist on the provider's pollScope (IO) — the
                    // previous `runBlocking` blocked AppAuth's executor
                    // and could deadlock against DataStore's actor.
                    pollScope.launch { runCatching { tokenStore.write(refreshed) } }
                    _isLoggedIn.value = true
                    cont.resume(token)
                } else {
                    if (ex != null) _isLoggedIn.value = false
                    cont.resume(null)
                }
            }
        }
    }
}
