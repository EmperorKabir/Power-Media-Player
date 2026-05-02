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
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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

    private val authService: AuthorizationService by lazy { AuthorizationService(context) }
    private val gson = Gson()
    private val http = OkHttpClient()

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.spotify.com/authorize"),
        Uri.parse("https://accounts.spotify.com/api/token")
    )

    private val scopes = listOf(
        "user-library-read",
        "user-read-email",
        "user-read-private",
        "playlist-read-private"
    ).joinToString(" ")

    /**
     * Builds the AppAuth authorization request. The launcher in the UI layer
     * fires this and routes the result to [handleAuthResponse].
     */
    fun buildAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.SPOTIFY_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.SPOTIFY_REDIRECT_URI)
        )
            .setScope(scopes)
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchange the authorization code for tokens and persist the AuthState.
     * Called from the UI's ActivityResult callback after the Custom Tab returns.
     */
    suspend fun handleAuthResponse(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        if (data == null) return@withContext Result.failure(IllegalStateException("No auth result data"))
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) return@withContext Result.failure(ex ?: IllegalStateException("Auth canceled"))

        val authState = AuthState(serviceConfig).apply { update(resp, ex) }

        suspendCancellableCoroutine<Result<Unit>> { cont ->
            authService.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
                authState.update(tokenResp, tokenEx)
                if (tokenResp != null) {
                    val json = authState.jsonSerializeString()
                    kotlinx.coroutines.runBlocking { tokenStore.write(json) }
                    _isLoggedIn.value = true
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(tokenEx ?: IllegalStateException("Token exchange failed")))
                }
            }
        }
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
            val token = currentAccessToken() ?: return@withContext Result.failure(
                IllegalStateException("Not authenticated")
            )

            val url = "https://api.spotify.com/v1/me/library?type=track,album,playlist&limit=50"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val items = mutableListOf<CloudMediaItem>()
            try {
                http.newCall(req).execute().use { resp ->
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
