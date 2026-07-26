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
 * Google Drive integration via Google Sign-In + drive.readonly (read)
 * and drive.file (backup write) scopes.
 *
 * Flow on first run:
 *   1. User taps "Pick Drive folder" → [buildSignInIntent] launches the
 *      official Google account chooser + consent screen.
 *   2. After consent, [handleSignInResult] stores the
 *      [GoogleSignInAccount] and primes a [GoogleAccountCredential].
 *   3. The cloud screen opens the NATIVE in-app folder browser
 *      (DriveFolderBrowser), which lists Drive folders via
 *      [listSubFolders] (drive.readonly) and returns a folder id to
 *      add to the user's "picked folders" list. No WebView.
 *
 * Flow on subsequent launches: cached account + token refresh, no UI.
 *
 * SCOPE HISTORY — why drive.readonly now (was drive.file):
 * The app originally used drive.file (non-sensitive, no verification).
 * drive.file grants per-file access to files the user explicitly picks
 * or the app creates. Google then RESTRICTED drive.file folder-selection
 * so picking a FOLDER no longer grants its pre-existing files — only the
 * folder resource itself. Device-verified 2026-07-25 on a fresh install
 * (Oppo WebView 150 AND emulator WebView 133, both OAuth clients):
 *   FOLDER.get 200, every child files.get 404, files.list → 0 of 5.
 * vc46 (2026-07-03) used the identical drive.file + Picker + REST path
 * and worked only because older grants were honoured (grandfathered);
 * fresh picks after the restriction return nothing. drive.readonly reads
 * the user's Drive unconditionally, so a freshly-picked folder's files
 * are listable/downloadable at once. Cost: drive.readonly is SENSITIVE
 * → Google verification needed to remove the "unverified app" screen for
 * the public + lift the 100-user cap. drive.file is KEPT alongside for
 * the backup UPLOAD path (readonly cannot write).
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
        // drive.readonly: full READ of the user's Drive. Required because Google
        // restricted drive.file folder-selection so a picked folder no longer
        // grants its pre-existing files (device-verified 2026-07-25: FOLDER.get
        // 200 but every child files.get 404). readonly reads the picked folder's
        // contents directly. drive.file is KEPT for the backup UPLOAD path (the
        // app writes its own backup file; readonly cannot write).
        .requestScopes(
            Scope(SCOPE_DRIVE_READONLY),
            Scope(SCOPE_DRIVE_FILE)
        )
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
     * True when signed in but the account has NOT yet granted
     * drive.readonly — e.g. a returning user last authorised under the
     * old drive.file-only scope. The Cloud "Add folder" flow gates on
     * this (via driveReadyForBrowse): when true it re-launches sign-in,
     * which incrementally adds the scope and keeps the picked folders.
     */
    fun needsReadConsent(): Boolean {
        val acc = account ?: return false
        return !GoogleSignIn.hasPermissions(acc, Scope(SCOPE_DRIVE_READONLY))
    }

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
     * Synchronously fetch a fresh READ (drive.readonly) access token.
     * Blocks on the Google Auth call, so MUST be called off Main. Returns
     * null when not signed in or refresh fails. Scoped to readonly ONLY —
     * NOT combined with drive.file — so reads (listing, download, cast,
     * playback) never hard-fail just because the write scope is missing;
     * readonly alone suffices for all reads. Backup WRITES use
     * [fetchWriteTokenBlocking] instead.
     */
    fun fetchAccessTokenBlocking(): String? = tokenForScopes("oauth2:$SCOPE_DRIVE_READONLY")

    /**
     * READ-token's write sibling: a fresh drive.file token for the backup
     * upload/update paths (readonly cannot write). Kept separate so the
     * two scopes are never coupled in one all-or-nothing getToken call.
     */
    fun fetchWriteTokenBlocking(): String? = tokenForScopes("oauth2:$SCOPE_DRIVE_FILE")

    private fun tokenForScopes(scopeSpec: String): String? {
        val acc = account?.account ?: return null
        return try {
            // GoogleAuthUtil returns the OAuth access token synchronously,
            // refreshing if needed. The "oauth2:" prefix is required by the
            // older API surface.
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, acc, scopeSpec)
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "DriveOAuth.getToken failed for $scopeSpec", e)
            null
        }
    }

    /**
     * Called from the native folder browser (DriveFolderBrowser) after
     * the user adds a folder. Persists it to the picked-folders list.
     */
    suspend fun rememberPickedFolder(folderId: String, folderName: String) {
        settingsDataStore.addDriveOauthPickedFolder(folderId, folderName)
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "DriveOAuth picked folder $folderName ($folderId)")
        _isLoggedIn.value = true
    }

    suspend fun forgetPickedFolder(folderId: String) {
        settingsDataStore.removeDriveOauthPickedFolder(folderId)
    }

    /** The signed-in Google account email, or null. Used by the Cloud UI
     *  to gate the native folder browser (see driveReadyForBrowse). */
    fun currentAccountEmail(): String? = account?.email

    /**
     * Native folder-browser support: list the sub-folders under [parentId].
     *
     * Virtual roots: [LOC_ROOT] "locations" = the top chooser (My Drive +
     * Shared with me + each Shared Drive); [MY_DRIVE_ID] "root" = My Drive top;
     * [SHARED_WITH_ME_ID] "sharedWithMe" = folders others shared with you. Any
     * other id is a real folder (in My Drive, a Shared Drive, or shared with you)
     * whose children come from `'<id>' in parents`. drive.readonly reads the whole
     * Drive; Shared-Drive items additionally need supportsAllDrives +
     * includeItemsFromAllDrives (added in [folderQuery]) — NOT corpora=allDrives,
     * which would needlessly broaden a parent-scoped query. Access still flows
     * through [listFiles]/download after a folder is added.
     */
    suspend fun listSubFolders(parentId: String): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            try {
                val token = fetchAccessTokenBlocking()
                    ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
                when (parentId) {
                    LOC_ROOT -> {
                        // Top-level location chooser: My Drive, Shared with me, then
                        // each Shared Drive the user belongs to (drives.list).
                        val out = mutableListOf<CloudMediaItem>()
                        out += virtualFolder(MY_DRIVE_ID, "My Drive", LOC_ROOT)
                        out += virtualFolder(SHARED_WITH_ME_ID, "Shared with me", LOC_ROOT)
                        out += listSharedDrives(token)
                        Result.success(out)
                    }
                    SHARED_WITH_ME_ID -> folderQuery(
                        token,
                        "mimeType = '$MIME_FOLDER' and sharedWithMe = true and trashed = false",
                        parentId
                    )
                    else -> folderQuery(
                        token,
                        "mimeType = '$MIME_FOLDER' and '$parentId' in parents and trashed = false",
                        parentId
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Build a navigable folder [CloudMediaItem] (browser + location entries). */
    private fun virtualFolder(id: String, name: String, parentId: String? = null): CloudMediaItem =
        CloudMediaItem(
            id = id,
            name = name,
            mimeType = MIME_FOLDER,
            size = 0L,
            downloadUrl = "",
            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
            isFolder = true,
            parentId = parentId
        )

    /** Paginate a folder-only files.list [q] into folder items. supportsAllDrives +
     *  includeItemsFromAllDrives make Shared-Drive folders resolve; both are harmless
     *  for My Drive / shared-with-me queries (a My-Drive parent has no Shared-Drive
     *  children, so its result set is unchanged). Blocking HTTP — call on IO. */
    private fun folderQuery(token: String, q: String, parentId: String): Result<List<CloudMediaItem>> {
        val out = mutableListOf<CloudMediaItem>()
        var pageToken: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files?" +
                "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                "&fields=nextPageToken,files(id,name)&orderBy=name&pageSize=1000" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            http.newCall(Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token").build()).execute().use { resp ->
                if (!resp.isSuccessful)
                    return Result.failure(IllegalStateException("Drive folders HTTP ${resp.code}"))
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optJSONArray("files")?.let { files ->
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        out += virtualFolder(f.getString("id"), f.optString("name"), parentId)
                    }
                }
                pageToken = json.optString("nextPageToken").ifBlank { null }
            }
        } while (pageToken != null)
        return Result.success(out)
    }

    /** Enumerate the user's Shared Drives (team drives) via drives.list, each as a
     *  navigable folder whose id IS the driveId (also the drive's root folder id, so
     *  `'<driveId>' in parents` lists its top-level contents). Empty on none/error so
     *  the location chooser still shows My Drive + Shared with me. Blocking HTTP — IO. */
    private fun listSharedDrives(token: String): List<CloudMediaItem> {
        val out = mutableListOf<CloudMediaItem>()
        var pageToken: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/drives?" +
                "pageSize=100&fields=nextPageToken,drives(id,name)" +
                (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val more = runCatching {
                http.newCall(Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $token").build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Degrade gracefully (chooser still shows My Drive + Shared with
                        // me) but log so a "my Shared Drive is missing" report is
                        // diagnosable rather than a silent empty list.
                        com.powermediaplayer.util.Diag.w(
                            "PMP_DIAG",
                            "DriveOAuth.listSharedDrives HTTP ${resp.code} — Shared Drives hidden"
                        )
                        return@use false
                    }
                    val json = JSONObject(resp.body?.string().orEmpty())
                    json.optJSONArray("drives")?.let { drives ->
                        for (i in 0 until drives.length()) {
                            val d = drives.getJSONObject(i)
                            out += virtualFolder(d.getString("id"), d.optString("name"), LOC_ROOT)
                        }
                    }
                    pageToken = json.optString("nextPageToken").ifBlank { null }
                    pageToken != null
                }
            }.getOrElse {
                com.powermediaplayer.util.Diag.w("PMP_DIAG", "DriveOAuth.listSharedDrives failed", it)
                false
            }
            if (!more) break
        } while (true)
        return out
    }

    // ── Drive REST (drive.readonly — reads the user's whole Drive) ──

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
                // supportsAllDrives + includeItemsFromAllDrives so a picked SHARED-DRIVE
                // folder's files list (U7). These do NOT change a My-Drive folder's
                // children (a My-Drive parent has no Shared-Drive children), and
                // shared-with-me already resolved on the default corpus. Deliberately
                // NOT corpora=allDrives — that broadens a parent-scoped query and was the
                // thing removed 2026-07-24; only the capability + include flags are added.
                // Pagination follows the 1000-entry page cap to completion.
                val base = "https://www.googleapis.com/drive/v3/files?" +
                    "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&fields=nextPageToken,files(id,name,mimeType,size,parents,thumbnailLink)" +
                    "&pageSize=1000" +
                    "&supportsAllDrives=true&includeItemsFromAllDrives=true"
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
            "&fields=files(id,name,mimeType,size,parents,thumbnailLink)&pageSize=200" +
            "&supportsAllDrives=true&includeItemsFromAllDrives=true"
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
            .url("https://www.googleapis.com/drive/v3/files/$fileId?fields=name&supportsAllDrives=true")
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
            "fields=id,name,mimeType,size,parents,thumbnailLink&supportsAllDrives=true"
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
        val token = fetchWriteTokenBlocking()
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
        val token = fetchWriteTokenBlocking()
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

    /** M3 restore — newest app-created file with this exact name, or null. Uses the
     *  drive.file (WRITE) token so the whole backup triad (find/download/upload/update)
     *  shares ONE app-created corpus: the search sees only files THIS app created, never
     *  a same-named file elsewhere in the user's Drive (which the readonly token would
     *  Drive-wide false-match, then 404 on the drive.file download), and backup/restore
     *  works even when the user declined the sensitive readonly grant. */
    suspend fun findNewestFileByName(name: String): String? = withContext(Dispatchers.IO) {
        val token = fetchWriteTokenBlocking() ?: return@withContext null
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
        val token = fetchWriteTokenBlocking()
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
            downloadUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true",
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
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        // Folder-browser virtual roots (also the non-addable set the UI gates on).
        const val LOC_ROOT = "locations"
        const val MY_DRIVE_ID = "root"
        const val SHARED_WITH_ME_ID = "sharedWithMe"
    }
}
