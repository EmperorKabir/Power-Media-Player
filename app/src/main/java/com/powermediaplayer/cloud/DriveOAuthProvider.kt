package com.powermediaplayer.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive integration via Google Sign-In + drive.file scope.
 *
 * Flow on first run:
 *   1. User taps "Pick Drive folder" → [buildSignInIntent] launches the
 *      official Google account chooser + consent screen.
 *   2. After consent, [handleSignInResult] stores the
 *      [GoogleSignInAccount] and primes a [GoogleAccountCredential].
 *   3. The cloud screen launches [DrivePickerActivity] which embeds
 *      Google's JS Drive Picker. The picker returns folder ids
 *      to add to the user's "picked folders" list.
 *
 * Flow on subsequent launches: cached account + token refresh, no UI.
 *
 * Why drive.file (not drive.readonly): drive.file is **non-sensitive**
 * — Google verification is not required, and friends can sign in
 * without a 100-tester whitelist or 4–6-week CASA wait. The cost is
 * that the app can only see files/folders the user explicitly grants
 * via Picker (or that the app itself created). That matches a media
 * player's "pick the folder where my music lives" UX exactly.
 */
@Singleton
class DriveOAuthProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : CloudStorageProvider {

    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()

    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions
        .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
        .build()

    private val signInClient: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(context, signInOptions)
    }

    @Volatile
    private var account: GoogleSignInAccount? = null

    init {
        // Restore cached sign-in.
        GoogleSignIn.getLastSignedInAccount(context)?.let { acc ->
            attach(acc)
        }
        // Mirror picked-folder count into _isLoggedIn so the Cloud UI
        // shows the Drive card in the "browse" state without waiting
        // for the user to re-authenticate when an account is cached.
        settingsDataStore.driveOauthPickedFolders.onEach { folders ->
            _isLoggedIn.value = (account != null && folders.isNotEmpty())
        }.launchIn(scope)
    }

    fun buildSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Process the Google Sign-In activity result. On success, primes
     * the [GoogleAccountCredential] used to fetch OAuth tokens.
     */
    suspend fun handleSignInResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acc = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            attach(acc)
            // Sign-in alone doesn't grant any folder access — the user
            // still needs to go through the Picker. Stay in the
            // "signed-in but no folders yet" state until they do.
            Result.success(Unit)
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.e("PMP_DIAG", "DriveOAuth.handleSignInResult failed", e)
            _isLoggedIn.value = false
            Result.failure(e)
        }
    }

    private fun attach(acc: GoogleSignInAccount) {
        account = acc
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "DriveOAuth attached account=${acc.email}")
    }

    override suspend fun authenticate(context: Context): Result<Unit> =
        Result.failure(UnsupportedOperationException("Use buildSignInIntent + handleSignInResult"))

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            signInClient.signOut().result
        } catch (_: Exception) {}
        account = null
        settingsDataStore.clearDriveOauthPickedFolders()
        _isLoggedIn.value = false
        Result.success(Unit)
    }

    /**
     * Synchronously fetch a fresh OAuth access token. Blocks on the
     * Google Auth blocking call, so MUST be called off Main. Returns
     * null when not signed in or refresh fails.
     */
    fun fetchAccessTokenBlocking(): String? {
        val acc = account ?: return null
        return try {
            // GoogleAuthUtil is part of play-services-auth and returns
            // the OAuth access token synchronously, refreshing if
            // needed. The "oauth2:" prefix on the scope is required by
            // the older API surface; the scope itself is drive.file.
            com.google.android.gms.auth.GoogleAuthUtil.getToken(
                context,
                acc.account ?: return null,
                "oauth2:https://www.googleapis.com/auth/drive.file"
            )
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "DriveOAuth.fetchAccessTokenBlocking failed", e)
            null
        }
    }

    /**
     * Returned from [DrivePickerActivity] after the user picks a
     * folder in the JS Drive Picker.
     */
    suspend fun rememberPickedFolder(folderId: String, folderName: String) {
        settingsDataStore.addDriveOauthPickedFolder(folderId, folderName)
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "DriveOAuth picked folder $folderName ($folderId)")
        _isLoggedIn.value = true
    }

    suspend fun forgetPickedFolder(folderId: String) {
        settingsDataStore.removeDriveOauthPickedFolder(folderId)
    }

    /**
     * Snapshot account email so [DrivePickerActivity] can inject it
     * into the Picker JS for the user's primary Google identity.
     */
    fun currentAccountEmail(): String? = account?.email

    // ── Drive REST (drive.file scope — only picker-granted files visible) ──

    /**
     * List children of [folderId]. When [folderId] is null, surface
     * the picked-folders list as virtual root entries. When it's a
     * Drive folder ID, query Drive REST for direct children, filtered
     * to audio/video plus sub-folders.
     */
    override suspend fun listFiles(folderId: String?): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            try {
                if (folderId == null) {
                    val picked = settingsDataStore.driveOauthPickedFolders.first()
                    return@withContext Result.success(picked.map { f ->
                        CloudMediaItem(
                            id = f.id,
                            name = f.name,
                            mimeType = MIME_FOLDER,
                            size = 0L,
                            downloadUrl = "",
                            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                            isFolder = true,
                            parentId = null
                        )
                    })
                }
                val token = fetchAccessTokenBlocking()
                    ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
                val q = "'$folderId' in parents and trashed = false " +
                    "and (mimeType contains 'audio/' or mimeType contains 'video/' " +
                    "or mimeType = '$MIME_FOLDER')"
                val url = "https://www.googleapis.com/drive/v3/files?" +
                    "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&fields=files(id,name,mimeType,size,parents,thumbnailLink)" +
                    "&pageSize=200"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $token").build()
                val items = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Drive list HTTP ${resp.code}")
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JsonParser.parseString(body).asJsonObject
                    val arr = root.getAsJsonArray("files") ?: return@use emptyList()
                    arr.mapNotNull { el ->
                        val f = el.asJsonObject
                        toCloudItem(f, parentId = folderId)
                    }
                }
                Result.success(
                    items.sortedWith(
                        compareByDescending<CloudMediaItem> { it.isFolder }
                            .thenBy { it.name.lowercase() }
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Search across the union of all picked folders. Drive doesn't
     * support a single recursive query against a set of folders, so
     * we union per-folder name-contains searches. Capped to keep the
     * Cloud screen responsive.
     */
    suspend fun searchFiles(query: String): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(emptyList())
            val token = fetchAccessTokenBlocking()
                ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
            val picked = settingsDataStore.driveOauthPickedFolders.first()
            if (picked.isEmpty()) return@withContext Result.success(emptyList())
            try {
                val out = mutableListOf<CloudMediaItem>()
                val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
                for (root in picked) {
                    if (out.size >= 200) break
                    val q = "'${root.id}' in parents and name contains '$escaped' " +
                        "and trashed = false " +
                        "and (mimeType contains 'audio/' or mimeType contains 'video/' " +
                        "or mimeType = '$MIME_FOLDER')"
                    val url = "https://www.googleapis.com/drive/v3/files?" +
                        "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                        "&fields=files(id,name,mimeType,size,parents,thumbnailLink)" +
                        "&pageSize=100"
                    val req = Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer $token").build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        val root2 = JsonParser.parseString(body).asJsonObject
                        val arr = root2.getAsJsonArray("files") ?: return@use
                        for (el in arr) {
                            val f = el.asJsonObject
                            toCloudItem(f, parentId = root.id)?.let { out.add(it) }
                            if (out.size >= 200) return@use
                        }
                    }
                }
                Result.success(out.take(200))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri> =
        Result.success(Uri.parse(item.downloadUrl))

    /**
     * Fetch full metadata for a single Drive file by id and build a
     * playable [CloudMediaItem]. Used by the Cloud screen when the
     * user taps a starred-track row whose downloadUrl/mimeType
     * weren't persisted (favourites only store id + display name).
     */
    suspend fun getFileMetadata(id: String): CloudMediaItem? = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking() ?: return@withContext null
        val url = "https://www.googleapis.com/drive/v3/files/$id?" +
            "fields=id,name,mimeType,size,parents,thumbnailLink"
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val obj = JsonParser.parseString(body).asJsonObject
                toCloudItem(obj, parentId = null)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Range download to local cache. Used by the M4B chapter parser /
     * MediaMetadataRetriever / FFmpeg path that need a seekable file.
     */
    suspend fun downloadRangeToCache(
        item: CloudMediaItem,
        rangeStart: Long?,
        rangeEnd: Long?,
        suffix: String = "tmp"
    ): java.io.File? = withContext(Dispatchers.IO) {
        val tag = "PowerMediaPlayer"
        val token = fetchAccessTokenBlocking() ?: run {
            com.powermediaplayer.util.Diag.e(tag, "Drive download: no access token (signed out?)")
            return@withContext null
        }
        val cacheFile = java.io.File(context.cacheDir, "drive_${item.id}_$suffix")
        val rangeHeader = "bytes=${rangeStart ?: ""}-${rangeEnd ?: ""}"
        try {
            val req = Request.Builder()
                .url(item.downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Range", rangeHeader)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    com.powermediaplayer.util.Diag.e(tag, "Drive download failed: HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.byteStream()?.use { input ->
                    cacheFile.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
            cacheFile
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.e(tag, "Drive download exception", e)
            runCatching { cacheFile.delete() }
            null
        }
    }

    suspend fun downloadToCache(item: CloudMediaItem): java.io.File? =
        downloadRangeToCache(item, 0L, 32L * 1024 * 1024 - 1, "head")

    suspend fun downloadTailToCache(item: CloudMediaItem): java.io.File? {
        val size = item.size
        return if (size > 0) {
            val start = (size - 32L * 1024 * 1024).coerceAtLeast(0L)
            downloadRangeToCache(item, start, size - 1, "tail")
        } else {
            downloadRangeToCache(item, null, 32L * 1024 * 1024, "tail")
        }
    }

    suspend fun downloadFullToCache(item: CloudMediaItem): java.io.File? {
        val cap = 4L * 1024 * 1024 * 1024
        if (item.size > cap) return null
        return downloadRangeToCache(item, 0L, null, "full")
    }

    private fun toCloudItem(f: JsonObject, parentId: String?): CloudMediaItem? {
        val id = f.get("id")?.asString ?: return null
        val name = f.get("name")?.asString ?: "Unnamed"
        val mime = f.get("mimeType")?.asString.orEmpty()
        val size = f.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val isFolder = mime == MIME_FOLDER
        val thumb = f.get("thumbnailLink")?.takeIf { !it.isJsonNull }?.asString
        return CloudMediaItem(
            id = id,
            name = name,
            mimeType = mime,
            size = size,
            downloadUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media",
            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
            isFolder = isFolder,
            parentId = parentId,
            thumbnailUri = thumb?.let { Uri.parse(it) }
        )
    }

    companion object {
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }
}
