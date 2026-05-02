package com.powermediaplayer.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
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
    val deviceName: String?
)

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

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val authService: AuthorizationService by lazy { AuthorizationService(context) }
    private val gson = Gson()
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
        android.util.Log.i("PMP_DIAG", "Spotify.buildAuthIntent start")
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
        android.util.Log.i("PMP_DIAG", "Spotify.buildAuthIntent done ${System.currentTimeMillis() - t0}ms")
        return intent
    }

    /**
     * Exchange the authorization code for tokens and persist the AuthState.
     * Called from the UI's ActivityResult callback after the Custom Tab returns.
     */
    suspend fun handleAuthResponse(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        android.util.Log.i("PMP_DIAG", "Spotify.handleAuthResponse start data=${data != null}")
        if (data == null) return@withContext Result.failure(IllegalStateException("No auth result data"))
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        android.util.Log.i("PMP_DIAG", "Spotify.handleAuthResponse parsed resp=${resp != null} ex=${ex?.message}")
        if (resp == null) return@withContext Result.failure(ex ?: IllegalStateException("Auth canceled"))

        val authState = AuthState(serviceConfig).apply { update(resp, ex) }

        val t0 = System.currentTimeMillis()
        val result = suspendCancellableCoroutine<Result<Unit>> { cont ->
            authService.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
                android.util.Log.i(
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
            android.util.Log.i("PMP_DIAG", "Spotify.handleAuthResponse persisted token")
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
            android.util.Log.i("PMP_DIAG", "Spotify.listFiles start")
            val t0 = System.currentTimeMillis()
            val token = currentAccessToken()
            if (token == null) {
                android.util.Log.w("PMP_DIAG", "Spotify.listFiles no token")
                return@withContext Result.failure(IllegalStateException("Not authenticated"))
            }
            android.util.Log.i("PMP_DIAG", "Spotify.listFiles got token ${System.currentTimeMillis() - t0}ms")

            val url = "https://api.spotify.com/v1/me/library?type=track,album,playlist&limit=50"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val items = mutableListOf<CloudMediaItem>()
            try {
                http.newCall(req).execute().use { resp ->
                    android.util.Log.i("PMP_DIAG", "Spotify.listFiles http=${resp.code} ${System.currentTimeMillis() - t0}ms")
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
                    android.util.Log.i("PMP_DIAG", "Spotify.listFiles parsed=${items.size}")
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            Result.success(items)
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
        return CloudMediaItem(
            id = id,
            name = name,
            mimeType = if (type == "track") "audio/mpeg" else "application/spotify-$type",
            size = 0L,
            downloadUrl = preview.ifEmpty { spotifyUri },
            sourceProvider = CloudProviderType.SPOTIFY,
            isFolder = type != "track"
        )
    }

    /**
     * Preview URL when available (30-second clip) — full streaming requires
     * the Spotify Android SDK + a Premium account, not implemented here.
     */
    override suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri> {
        android.util.Log.i(
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
    suspend fun playTrackOnConnectDevice(spotifyUri: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            android.util.Log.i("PMP_DIAG", "Spotify.playTrackOnConnectDevice $spotifyUri")
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Spotify session expired — sign in again")
            )
            val firstAttempt = playRequest(token, spotifyUri, deviceId = null)
            if (firstAttempt.isSuccess) return@withContext firstAttempt

            // 404 NO_ACTIVE_DEVICE — pick first device and retry.
            val errMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
            if (!errMsg.contains("NO_ACTIVE_DEVICE", ignoreCase = true) &&
                !errMsg.contains("404")
            ) {
                return@withContext firstAttempt
            }

            android.util.Log.i("PMP_DIAG", "Spotify.play no active device — listing")
            val devices = listDevices(token)
            val first = devices.firstOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "No Spotify device found. Open Spotify on this phone or another device first."
                    )
                )
            android.util.Log.i("PMP_DIAG", "Spotify.play activating device ${first.first} (${first.second})")
            val transferred = transferPlayback(token, first.first)
            if (transferred.isFailure) return@withContext transferred
            // Tiny gap so Spotify finishes activating the device before
            // we send the play command.
            kotlinx.coroutines.delay(400)
            playRequest(token, spotifyUri, deviceId = first.first)
        }

    private fun playRequest(token: String, spotifyUri: String, deviceId: String?): Result<Unit> {
        val url = StringBuilder("https://api.spotify.com/v1/me/player/play")
        if (deviceId != null) url.append("?device_id=").append(deviceId)
        val bodyJson = """{"uris":["$spotifyUri"]}"""
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
                android.util.Log.i("PMP_DIAG", "Spotify.playRequest http=${resp.code}")
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
                android.util.Log.i("PMP_DIAG", "Spotify.transferPlayback http=${resp.code}")
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
    fun startPlaybackPolling() {
        if (pollJob?.isActive == true) return
        android.util.Log.i("PMP_DIAG", "Spotify.startPlaybackPolling")
        pollJob = pollScope.launch {
            while (isActive) {
                val token = currentAccessToken()
                if (token != null) {
                    val snap = fetchCurrentState(token)
                    _spotifyState.value = snap
                }
                delay(1000)
            }
        }
    }

    fun stopPlaybackPolling() {
        pollJob?.cancel()
        pollJob = null
        _spotifyState.value = null
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
                    kotlinx.coroutines.runBlocking { tokenStore.write(refreshed) }
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
