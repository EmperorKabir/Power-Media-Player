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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
 * — Google verification is not required, and other users can sign in
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
    private val http = com.powermediaplayer.util.SharedHttp.base  // shared pool/cache (audit 5.3)
    // File-CONTENT downloads (metadata enrichment, offline pins) can run far
    // longer than the shared 30 s callTimeout — a full audiobook m4b is
    // hundreds of MB. SharedHttp's comment says long media transfers must
    // override callTimeout with 0; this client does that (shares the pool/
    // cache via newBuilder, keeps the per-read 20 s stall guard). Without it
    // the full-file fetch the M4B/MMR enricher needs always aborts at 30 s →
    // embedded title/artist/album never load and the filename is shown.
    private val downloadHttp = com.powermediaplayer.util.SharedHttp.base.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
                // 2026-07-22: the old server-side filter (mimeType contains
                // 'audio/'|'video/') SILENTLY DROPPED media that Drive labels
                // application/octet-stream — notably .m4b audiobooks. List all
                // non-trashed children and filter client-side by name OR mime.
                val q = "'$folderId' in parents and trashed = false"
                // supportsAllDrives + includeItemsFromAllDrives are REQUIRED to
                // list a Shared Drive, or a folder someone else owns and shared
                // with the user. Without them those folders silently return zero
                // children. Harmless for ordinary My Drive folders.
                val base = "https://www.googleapis.com/drive/v3/files?" +
                    "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&fields=nextPageToken,files(id,name,mimeType,size,parents,thumbnailLink)" +
                    "&pageSize=1000" +
                    "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                    "&corpora=allDrives"
                // Drive caps a page at 1000 entries and returns nextPageToken for
                // the rest. A single un-paged request therefore TRUNCATED any
                // folder holding more than a page of files, with no error — the
                // tail simply never appeared. Follow the token to completion.
                val items = mutableListOf<CloudMediaItem>()
                var rawCount = 0
                var pageToken: String? = null
                do {
                    val url = if (pageToken.isNullOrEmpty()) base
                    else base + "&pageToken=" + java.net.URLEncoder.encode(pageToken, "UTF-8")
                    val req = Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer $token").build()
                    pageToken = http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = resp.body?.string()?.take(300).orEmpty()
                            com.powermediaplayer.diag.DiagLog.event(
                                "DRIVE", "listFiles HTTP ${resp.code} folder=$folderId err=$err"
                            )
                            return@withContext Result.failure(
                                IllegalStateException("Drive list HTTP ${resp.code}")
                            )
                        }
                        val body = resp.body?.string().orEmpty()
                        val root = JsonParser.parseString(body).asJsonObject
                        val arr = root.getAsJsonArray("files")
                        if (arr != null) {
                            rawCount += arr.size()
                            arr.forEach { el ->
                                val f = el.asJsonObject
                                val nm = f.get("name")?.asString.orEmpty()
                                val mm = f.get("mimeType")?.asString.orEmpty()
                                // Keep folders + real media; drop docs/images/etc.
                                if (mm == MIME_FOLDER ||
                                    com.powermediaplayer.util.MediaClassifier.isMediaByName(nm, mm)
                                ) toCloudItem(f, parentId = folderId)?.let { items += it }
                            }
                        }
                        root.get("nextPageToken")?.takeIf { !it.isJsonNull }?.asString
                    }
                } while (!pageToken.isNullOrEmpty())
                com.powermediaplayer.diag.DiagLog.event(
                    "DRIVE", "listFiles folder=$folderId driveReturned=$rawCount kept=${items.size}"
                )
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
            val picked = settingsDataStore.driveOauthPickedFolders.first()
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "DriveOAuth.searchFiles START q='$query' tokenOk=${token != null} pickedFolders=${picked.size}"
            )
            if (token == null) return@withContext Result.failure(IllegalStateException("Not authenticated"))
            if (picked.isEmpty()) return@withContext Result.success(emptyList())
            try {
                // PARALLEL breadth-first walk. Under drive.file a global
                // `name contains` query does NOT surface a granted folder's
                // descendants (only files the app opened) — but listing children
                // via `'<id>' in parents` DOES (what browsing uses). Each DEPTH
                // level is queried CONCURRENTLY so a deep tree resolves in ~1-2 s
                // instead of one-folder-at-a-time. Bounded so a huge tree can't run away.
                val needle = query.lowercase()
                val out = java.util.Collections.synchronizedList(mutableListOf<CloudMediaItem>())
                var level = picked.map { it.id }.distinct()
                val seen = HashSet<String>(level)
                var folderQueries = 0
                val cap = 600
                while (level.isNotEmpty() && out.size < 200 && folderQueries < cap) {
                    folderQueries += level.size
                    val nextLevel = coroutineScope {
                        level.map { folderId ->
                            async { searchOneFolder(token, folderId, needle, out) }
                        }.flatMap { it.await() }
                    }
                    level = nextLevel.filter { seen.add(it) }
                }
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG",
                    "Drive OAuth search '$query' → ${out.size} (walked $folderQueries folders)"
                )
                Result.success(out.toList().take(200))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** List [folderId]'s children: append name-matching audio/video files to
     *  [out] and return the sub-folder ids to descend into. Blocking HTTP — run
     *  inside an async on the IO dispatcher. */
    private fun searchOneFolder(
        token: String,
        folderId: String,
        needle: String,
        out: MutableList<CloudMediaItem>
    ): List<String> {
        val subFolders = mutableListOf<String>()
        val q = "'$folderId' in parents and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?" +
            "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
            "&fields=files(id,name,mimeType,size,parents,thumbnailLink)&pageSize=200"
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token").build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val arr = JsonParser.parseString(resp.body?.string().orEmpty())
                    .asJsonObject.getAsJsonArray("files") ?: return@use
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val f = el.asJsonObject
                    val id = f.get("id")?.asString ?: continue
                    val mime = f.get("mimeType")?.asString.orEmpty()
                    val name = f.get("name")?.asString.orEmpty()
                    val matches = name.lowercase().contains(needle)
                    if (mime == MIME_FOLDER) {
                        subFolders.add(id)
                        if (matches) toCloudItem(f, folderId)?.let { out.add(it) }
                    } else if (matches && (mime.contains("audio/") || mime.contains("video/"))) {
                        toCloudItem(f, folderId)?.let { out.add(it) }
                    }
                }
            }
        }
        return subFolders
    }

    /** §C28 — fetch a Drive file's display name by id (for backfilling the
     *  Downloads list of copies saved before names were tracked). Null on failure. */
    suspend fun fetchFileName(fileId: String): String? = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking() ?: return@withContext null
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?fields=name")
            .addHeader("Authorization", "Bearer $token")
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JsonParser.parseString(resp.body?.string().orEmpty())
                    .asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString
            }
        }.getOrNull()
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
    /**
     * M3 backup — upload a small text file (the backup JSON) to Drive via a
     * multipart create. Uses the drive.file scope (the app can always read
     * back files it created), so no extra Google verification. Returns the id.
     */
    suspend fun uploadTextFile(
        fileName: String,
        content: String,
        mimeType: String = "application/json"
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking()
            ?: return@withContext Result.failure(IllegalStateException("Drive sign-in required"))
        runCatching {
            val boundary = "pmp" + java.util.UUID.randomUUID().toString().replace("-", "")
            val meta = JSONObject().put("name", fileName).put("mimeType", mimeType).toString()
            val payload = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(meta).append("\r\n")
                append("--").append(boundary).append("\r\n")
                append("Content-Type: ").append(mimeType).append("\r\n\r\n")
                append(content).append("\r\n")
                append("--").append(boundary).append("--")
            }
            val reqBody = payload.toRequestBody("multipart/related; boundary=$boundary".toMediaType())
            val req = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .addHeader("Authorization", "Bearer $token")
                .post(reqBody)
                .build()
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Drive upload HTTP ${resp.code}: $bodyStr")
                JSONObject(bodyStr).optString("id").ifBlank { error("Drive upload returned no id") }
            }
        }
    }

    /** M3 backup — overwrite an existing backup file's content (PATCH media),
     *  so repeated "Back up to Drive" reuses one file instead of piling up
     *  duplicates that consume the user's Drive quota. */
    suspend fun updateTextFile(
        fileId: String,
        content: String,
        mimeType: String = "application/json"
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking()
            ?: return@withContext Result.failure(IllegalStateException("Drive sign-in required"))
        runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media&fields=id")
                .addHeader("Authorization", "Bearer $token")
                .patch(content.toRequestBody(mimeType.toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Drive update HTTP ${resp.code}: $s")
                JSONObject(s).optString("id").ifBlank { fileId }
            }
        }
    }

    /** M3 restore — newest app-created file with this exact name, or null. */
    suspend fun findNewestFileByName(name: String): String? = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking() ?: return@withContext null
        runCatching {
            val q = java.net.URLEncoder.encode("name = '$name' and trashed = false", "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$q" +
                "&orderBy=modifiedTime%20desc&pageSize=1&fields=files(id,name)"
            val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                if (files != null && files.length() > 0) files.getJSONObject(0).optString("id") else null
            }
        }.getOrNull()
    }

    /** M3 restore — full text content of a (small) Drive file by id. */
    suspend fun downloadTextFile(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = fetchAccessTokenBlocking()
            ?: return@withContext Result.failure(IllegalStateException("Drive sign-in required"))
        runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Drive download HTTP ${resp.code}")
                s
            }
        }
    }

    suspend fun downloadRangeToCache(
        item: CloudMediaItem,
        rangeStart: Long?,
        rangeEnd: Long?,
        suffix: String = "tmp",
        progressId: String? = null
    ): java.io.File? = withContext(Dispatchers.IO) {
        val tag = "PowerMediaPlayer"
        val token = fetchAccessTokenBlocking() ?: run {
            com.powermediaplayer.util.Diag.e(tag, "Drive download: no access token (signed out?)")
            return@withContext null
        }
        // Hash the id for the cache filename (the raw id can contain unsafe
        // path chars, and a stable hash avoids collisions across the id space).
        val cacheFile = java.io.File(context.cacheDir, "drive_${item.id.hashCode()}_$suffix")
        val rangeHeader = "bytes=${rangeStart ?: ""}-${rangeEnd ?: ""}"
        try {
            val req = Request.Builder()
                .url(item.downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Range", rangeHeader)
                .build()
            // downloadHttp = no overall callTimeout (large media transfers);
            // the 20 s read timeout still aborts a genuinely stalled connection.
            downloadHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    com.powermediaplayer.util.Diag.e(tag, "Drive download failed: HTTP ${resp.code}")
                    return@withContext null
                }
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: item.size
                resp.body?.byteStream()?.use { raw ->
                    val input = if (progressId != null)
                        com.powermediaplayer.util.ProgressInputStream(raw, progressId, total) else raw
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

    suspend fun downloadFullToCache(item: CloudMediaItem, progressId: String? = null): java.io.File? {
        val cap = 4L * 1024 * 1024 * 1024
        if (item.size > cap) return null
        return downloadRangeToCache(item, 0L, null, "full", progressId)
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
            // Drive builds a video-FRAME thumbnail from the MP4 container. A .m4b
            // audiobook is MP4 (mimeType video/mp4) with no real video track, so the
            // frame is a solid BLACK square that would paint over the type icon.
            // Keep Drive's thumbnail only for genuine videos; audio falls back to the
            // icon (its true embedded cover arrives via the enricher / favourite art).
            thumbnailUri = thumb
                ?.takeIf { com.powermediaplayer.util.MediaClassifier.isVideoByName(name, mime) }
                ?.let { Uri.parse(it) }
        )
    }

    companion object {
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }
}
