package com.powermediaplayer.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive integration.
 *
 * Authentication: Google Sign-In (Android-type OAuth client, no client secret —
 * the package name + SHA-1 fingerprint registered in the GCP project identifies
 * the app to Google's auth servers).
 *
 * Listing: Drive REST v3 via google-api-services-drive.
 *
 * Streaming: produces a Drive `?alt=media` Uri; the actual Bearer-authenticated
 * read is handled by [GoogleDriveDataSourceFactory] which plugs into Media3.
 */
@Singleton
class GoogleDriveProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CloudStorageProvider {

    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions
        .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
        .build()

    private val signInClient: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(context, signInOptions)
    }

    private var account: GoogleSignInAccount? = null
    private var driveService: Drive? = null

    init {
        // Pick up cached sign-in if present (returning user)
        GoogleSignIn.getLastSignedInAccount(context)?.let { acc ->
            attach(acc)
        }
    }

    fun buildSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Hand the activity-result Intent back here from the launcher; this
     * resolves the GoogleSignInAccount and primes the Drive service.
     */
    suspend fun handleSignInResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acc = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            attach(acc)
            Result.success(Unit)
        } catch (e: Exception) {
            _isLoggedIn.value = false
            Result.failure(e)
        }
    }

    private fun attach(acc: GoogleSignInAccount) {
        account = acc
        // Use selectedAccountName (email) — does not require GET_ACCOUNTS
        // runtime permission. The previous selectedAccount + synthetic
        // Account(email, "com.google") fallback failed silently for users
        // who hadn't granted contacts/accounts perms, returning a null
        // OAuth token at stream time.
        val credential = GoogleAccountCredential
            .usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY))
            .apply { selectedAccountName = acc.email }
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Power Media Player")
            .build()
        _isLoggedIn.value = true
    }

    override suspend fun authenticate(context: Context): Result<Unit> =
        Result.failure(UnsupportedOperationException("Use buildSignInIntent + handleSignInResult"))

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            signInClient.signOut().result // blocking — already off main
        } catch (_: Exception) {}
        account = null
        driveService = null
        _isLoggedIn.value = false
        Result.success(Unit)
    }

    override suspend fun listFiles(folderId: String?): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            val drive = driveService ?: return@withContext Result.failure(
                IllegalStateException("Not authenticated")
            )
            try {
                val parent = folderId ?: "root"
                val query = "'$parent' in parents and trashed = false " +
                    "and (mimeType contains 'audio/' or mimeType contains 'video/' " +
                    "or mimeType = 'application/vnd.google-apps.folder')"
                val result = drive.files().list()
                    .setQ(query)
                    .setFields("files(id, name, mimeType, size, parents, thumbnailLink)")
                    .setPageSize(200)
                    .execute()
                val items = result.files.orEmpty().map { f ->
                    CloudMediaItem(
                        id = f.id,
                        name = f.name ?: "Unnamed",
                        mimeType = f.mimeType ?: "",
                        size = f.getSize() ?: 0L,
                        downloadUrl = "https://www.googleapis.com/drive/v3/files/${f.id}?alt=media",
                        sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                        isFolder = f.mimeType == "application/vnd.google-apps.folder",
                        parentId = folderId,
                        thumbnailUri = f.thumbnailLink?.let { Uri.parse(it) }
                    )
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri> =
        Result.success(Uri.parse(item.downloadUrl))

    /**
     * Synchronously fetches a current OAuth2 access token. MUST be called
     * off the main thread — GoogleAccountCredential blocks during refresh.
     * Used by [GoogleDriveDataSourceFactory].
     */
    fun fetchAccessTokenBlocking(): String? {
        val acc = account ?: return null
        return try {
            val credential = GoogleAccountCredential
                .usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY))
                .apply { selectedAccountName = acc.email }
            credential.token
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download a Drive file fully to cache so out-of-band parsers (M4B
     * chapter extractor, MediaMetadataRetriever, etc.) can access it via
     * a regular file URI. Returns null if not authenticated, the file is
     * larger than [maxBytes], or the download fails.
     *
     * Caller is responsible for deleting the cache file when done.
     */
    /**
     * Download a byte range of a Drive file to cache. [rangeStart] / [rangeEnd]
     * are inclusive. Pass null for either to anchor to the start or end of
     * the file. Returns null on auth/network failure.
     */
    suspend fun downloadRangeToCache(
        item: CloudMediaItem,
        rangeStart: Long?,
        rangeEnd: Long?,
        suffix: String = "tmp"
    ): java.io.File? = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking() ?: return@withContext null
        val cacheFile = java.io.File(context.cacheDir, "drive_${item.id}_$suffix")
        val rangeHeader = "bytes=${rangeStart ?: ""}-${rangeEnd ?: ""}"
        try {
            val req = okhttp3.Request.Builder()
                .url(item.downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Range", rangeHeader)
                .build()
            okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.byteStream()?.use { input ->
                    cacheFile.outputStream().use { out -> input.copyTo(out) }
                }
            }
            cacheFile
        } catch (_: Exception) {
            runCatching { cacheFile.delete() }
            null
        }
    }

    /** Convenience: first 32 MB. */
    suspend fun downloadToCache(item: CloudMediaItem): java.io.File? =
        downloadRangeToCache(item, 0L, 32L * 1024 * 1024 - 1, "head")

    /** Last 32 MB — for files whose moov atom is at the end. */
    suspend fun downloadTailToCache(item: CloudMediaItem): java.io.File? {
        val size = item.size
        return if (size > 0) {
            val start = (size - 32L * 1024 * 1024).coerceAtLeast(0L)
            downloadRangeToCache(item, start, size - 1, "tail")
        } else {
            downloadRangeToCache(item, null, 32L * 1024 * 1024, "tail")
        }
    }

    /**
     * Full-file download — used as a fallback when partial downloads fail to
     * yield metadata. Capped at 1 GB to avoid eating the user's storage.
     * Slow, so callers should only invoke this after the head/tail passes
     * have failed.
     */
    suspend fun downloadFullToCache(item: CloudMediaItem): java.io.File? {
        val cap = 1024L * 1024 * 1024
        if (item.size > cap) return null
        return downloadRangeToCache(item, 0L, null, "full")
    }
}
