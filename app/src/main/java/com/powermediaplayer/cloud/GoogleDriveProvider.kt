package com.powermediaplayer.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.powermediaplayer.data.preferences.DrivePickedRoot
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud / external folder integration via the Storage Access Framework.
 *
 * Despite the legacy class name, this provider is no longer Drive-specific:
 * it works with any DocumentsProvider Android knows about (Google Drive,
 * OneDrive, Dropbox, USB-OTG, network shares). The user picks a folder
 * once via [buildSignInIntent] (which fires ACTION_OPEN_DOCUMENT_TREE);
 * Android grants the app a persistent `content://` tree URI; we
 * enumerate via DocumentFile and stream via ContentResolver. No OAuth,
 * no Google verification, no API keys.
 *
 * Class name retained for binary compatibility with the rest of the
 * codebase — the public surface (buildSignInIntent / handleSignInResult /
 * listFiles / etc.) is unchanged so existing call sites keep working.
 */
@Singleton
class GoogleDriveProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : CloudStorageProvider {

    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // "Logged in" semantics under SAF: at least one root has been
        // picked. Track the picked-roots flow so the cloud UI lights up
        // / dims as the user adds or removes roots.
        scope.launch {
            settingsDataStore.drivePickedRoots.collect { roots ->
                _isLoggedIn.value = roots.isNotEmpty()
            }
        }
    }

    /**
     * Returns an Intent that fires the Android system folder picker.
     *
     * Default (no [initialUri]): the picker opens at its last-used
     * location (usually internal storage). On many Samsung / fold
     * devices the source drawer that exposes Drive / OneDrive /
     * Dropbox is unreachable from this view — the user only sees
     * local files. Use [buildDeepLinkedSignInIntent] with a root URI
     * from [queryDocumentRoots] to land directly inside a chosen
     * source instead.
     */
    fun buildSignInIntent(): Intent = buildDeepLinkedSignInIntent(initialUri = null)

    fun buildDeepLinkedSignInIntent(initialUri: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            putExtra("android.provider.extra.SHOW_ADVANCED", true)
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
            // Pin to Google DocumentsUI when installed (it always is on
            // Google-certified devices). Falls through to the system
            // resolver if the package is missing.
            val pm = context.packageManager
            val googleDocsui = "com.google.android.documentsui"
            try {
                pm.getApplicationInfo(googleDocsui, 0)
                setPackage(googleDocsui)
            } catch (_: Exception) {
                // Not installed — let the resolver pick whatever's available.
            }
        }

    /**
     * Snapshot of a [DocumentsContract] root surfaced by some installed
     * DocumentsProvider (Drive, OneDrive, Internal Storage, USB-OTG, …).
     */
    data class CloudRoot(
        val authority: String,
        val rootId: String,
        val documentId: String,
        val title: String,
        val summary: String?,
        val packageName: String
    ) {
        /** The URI to hand to [DocumentsContract.EXTRA_INITIAL_URI]. */
        fun initialUri(): Uri = DocumentsContract.buildDocumentUri(authority, documentId)
    }

    /**
     * Return a list of well-known DocumentsProviders that are installed
     * on the device, paired with a best-effort initial URI we can hand
     * to the SAF picker so it lands inside that source. Used by the
     * Cloud screen's chooser dialog.
     *
     * Why this is hardcoded rather than enumerated: querying
     * [DocumentsContract.buildRootsUri] returns SecurityException for
     * almost every provider — `MANAGE_DOCUMENTS` is signature-only,
     * granted only to system DocumentsUI. Apps cannot enumerate roots
     * directly. The fallback is to recognise the well-known providers
     * by package name, then use the picker's `EXTRA_INITIAL_URI`
     * mechanism to deep-link.
     */
    suspend fun queryDocumentRoots(): List<CloudRoot> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = mutableSetOf<String>()
        for (pkg in pm.getInstalledPackages(PackageManager.GET_PROVIDERS)) {
            installed.add(pkg.packageName)
        }
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Installed pkg count for chooser: ${installed.size}")
        val out = mutableListOf<CloudRoot>()
        // Google Drive — initial URI uses the documented "root" doc ID;
        // even when the picker's drawer is unreachable, this navigates
        // to the user's My Drive root.
        if ("com.google.android.apps.docs" in installed) {
            out.add(
                CloudRoot(
                    authority = "com.google.android.apps.docs.storage",
                    rootId = "root",
                    documentId = "root",
                    title = "Google Drive",
                    summary = "Stream from My Drive",
                    packageName = "com.google.android.apps.docs"
                )
            )
        }
        // Microsoft OneDrive
        if ("com.microsoft.skydrive" in installed) {
            out.add(
                CloudRoot(
                    authority = "com.microsoft.skydrive.content.external",
                    rootId = "root",
                    documentId = "root",
                    title = "OneDrive",
                    summary = "Stream from your OneDrive",
                    packageName = "com.microsoft.skydrive"
                )
            )
        }
        // Dropbox
        if ("com.dropbox.android" in installed) {
            out.add(
                CloudRoot(
                    authority = "com.dropbox.product.android.dbapp.document_provider.documents",
                    rootId = "root",
                    documentId = "root",
                    title = "Dropbox",
                    summary = "Stream from your Dropbox",
                    packageName = "com.dropbox.android"
                )
            )
        }
        // Internal storage / SD card / USB-OTG via the system external
        // storage provider. Always present.
        out.add(
            CloudRoot(
                authority = "com.android.externalstorage.documents",
                rootId = "primary",
                documentId = "primary:",
                title = "Phone storage",
                summary = "Internal / SD card / USB",
                packageName = "com.android.externalstorage"
            )
        )
        com.powermediaplayer.util.Diag.i("PMP_DIAG", "Chooser roots: ${out.size}")
        out
    }

    /**
     * Process the picker result: take a persistable URI permission so
     * the grant survives reboots, then persist the tree URI + display
     * name in DataStore.
     */
    suspend fun handleSignInResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val treeUri = data?.data
                ?: return@withContext Result.failure(IllegalStateException("No folder picked"))
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: SecurityException) {
                // Some DocumentsProviders (e.g. internal storage on
                // ancient devices) don't support persistable grants —
                // fall through; the URI works for this session only.
                com.powermediaplayer.util.Diag.w("PMP_DIAG", "Drive: takePersistableUriPermission failed", e)
            }
            val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
                ?: "Picked folder"
            settingsDataStore.addDrivePickedRoot(treeUri.toString(), displayName)
            com.powermediaplayer.util.Diag.i("PMP_DIAG", "Drive picked root: $displayName ($treeUri)")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Release every persisted URI permission and clear the DataStore
     * list. UI state ([_isLoggedIn]) reacts via the picked-roots flow.
     */
    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val roots = settingsDataStore.drivePickedRoots.first()
            roots.forEach { root ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(root.treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            settingsDataStore.clearDrivePickedRoots()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove a single picked root (release its persisted permission too).
     */
    suspend fun forgetPickedRoot(treeUri: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settingsDataStore.removeDrivePickedRoot(treeUri)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authenticate(context: Context): Result<Unit> =
        Result.failure(UnsupportedOperationException("Use buildSignInIntent + handleSignInResult"))

    /**
     * List children of [folderId]. When [folderId] is null, return the
     * picked roots as virtual folders (so the user lands on a list of
     * "Music", "Audiobooks", etc. — whatever they picked). When
     * [folderId] is a `content://document/` URI, return its direct
     * children, filtered to audio/video plus sub-folders.
     */
    override suspend fun listFiles(folderId: String?): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            try {
                if (folderId == null) {
                    val roots = settingsDataStore.drivePickedRoots.first()
                    val items = roots.map { root ->
                        CloudMediaItem(
                            id = root.treeUri,
                            name = root.name,
                            mimeType = MIME_FOLDER,
                            size = 0L,
                            downloadUrl = root.treeUri,
                            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                            isFolder = true,
                            parentId = null
                        )
                    }
                    return@withContext Result.success(items)
                }
                val folderUri = runCatching { Uri.parse(folderId) }.getOrNull()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Cannot open folder (permission revoked?)")
                    )
                val items = listChildrenFast(folderUri)
                    .mapNotNull { child -> toCloudItem(child, parentId = folderId) }
                    .sortedWith(compareByDescending<CloudMediaItem> { it.isFolder }
                        .thenBy { it.name.lowercase() })
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Search across every picked root. Uses [DocumentFile] recursive
     * walk; matches case-insensitive substring on file name. Folders
     * are NOT returned by search — only playable files. Limited to 200
     * matches per call so a typo on a 50k-file tree doesn't lock the UI.
     */
    suspend fun searchFiles(query: String): Result<List<CloudMediaItem>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(emptyList())
            try {
                val needle = query.lowercase()
                val roots = settingsDataStore.drivePickedRoots.first()
                com.powermediaplayer.util.Diag.i(
                    "PMP_DIAG", "DriveSAF.searchFiles q='$query' safRoots=${roots.size}"
                )
                val out = mutableListOf<CloudMediaItem>()
                // visited[0] caps TRAVERSAL, not just matches — the old
                // walk kept crawling a 50k-node tree when nothing matched.
                val visited = intArrayOf(0)
                for (root in roots) {
                    val rootUri = runCatching { Uri.parse(root.treeUri) }.getOrNull() ?: continue
                    walkForSearch(rootUri, needle, out, parentTreeUri = root.treeUri, visited = visited)
                    if (out.size >= 200 || visited[0] >= SEARCH_TRAVERSAL_CAP) break
                }
                if (visited[0] >= SEARCH_TRAVERSAL_CAP) {
                    com.powermediaplayer.util.Diag.i(
                        "PMP_DIAG",
                        "Drive search stopped at $SEARCH_TRAVERSAL_CAP nodes, tree larger than the walk budget"
                    )
                }
                Result.success(out.take(200))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun walkForSearch(
        folderUri: Uri,
        needle: String,
        out: MutableList<CloudMediaItem>,
        parentTreeUri: String,
        visited: IntArray
    ) {
        if (out.size >= 200 || visited[0] >= SEARCH_TRAVERSAL_CAP) return
        for (child in listChildrenFast(folderUri)) {
            if (out.size >= 200 || visited[0] >= SEARCH_TRAVERSAL_CAP) return
            visited[0]++
            if (child.isDirectory) {
                walkForSearch(child.documentUri, needle, out, parentTreeUri, visited)
            } else {
                if (!isPlayable(child.mime, child.name)) continue
                if (!child.name.lowercase().contains(needle)) continue
                toCloudItem(child, parentId = parentTreeUri)?.let { out.add(it) }
            }
        }
    }

    /**
     * Stream URI for [item]. Under SAF the `content://` URI in
     * `item.downloadUrl` is itself the playable URI — no token, no
     * redirect. ExoPlayer's DefaultDataSource handles `content://`
     * URIs natively via ContentResolver, so the existing pipeline in
     * PlaybackService works unchanged.
     */
    override suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri> =
        Result.success(Uri.parse(item.downloadUrl))

    /**
     * Compatibility stub. The OAuth token flow is gone — SAF authorises
     * via persistent URI grants instead. Returns null so PlaybackService's
     * ResolvingDataSource hook becomes a no-op for `content://` URIs
     * (the host check skips them anyway).
     */
    fun fetchAccessTokenBlocking(): String? = null

    /**
     * Download a byte range to local cache. Used by the M4B chapter
     * parser, MediaMetadataRetriever, and other off-band parsers that
     * cannot read directly from a `content://` URI without seeking.
     *
     * SAF-friendly implementation: opens a ParcelFileDescriptor, wraps
     * it in FileInputStream, seeks via channel.position() and writes
     * to a cache file.
     */
    suspend fun downloadRangeToCache(
        item: CloudMediaItem,
        rangeStart: Long?,
        rangeEnd: Long?,
        suffix: String = "tmp",
        progressId: String? = null,
        // Issue 5: a FULL offline download stages into filesDir (persistent) so a
        // cache-wipe app can't destroy an in-flight partial. head/tail metadata
        // reads stay in cacheDir. null → cacheDir.
        destDir: java.io.File? = null
    ): java.io.File? = withContext(Dispatchers.IO) {
        val tag = "PowerMediaPlayer"
        val sourceUri = runCatching { Uri.parse(item.downloadUrl) }.getOrNull()
            ?: return@withContext null
        // Audit 5.11 — head/tail caches are 32-64MB per browsed item and
        // only OS cache pressure ever reclaimed them; an audiobook-folder
        // binge could park hundreds of MB. LRU-trim before each download.
        trimDriveCache()
        val baseDir = (destDir ?: context.cacheDir).apply { runCatching { mkdirs() } }
        val cacheFile = java.io.File(baseDir, "drive_${item.id.hashCode()}_$suffix")
        val start = rangeStart ?: 0L
        val end = rangeEnd ?: Long.MAX_VALUE
        com.powermediaplayer.util.Diag.i(
            tag,
            "Drive cache download: ${item.name} ($suffix) start=$start end=$end size=${item.size}"
        )
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    channel.position(start.coerceAtLeast(0L))
                    val maxBytes = if (end == Long.MAX_VALUE) Long.MAX_VALUE
                    else (end - start + 1).coerceAtLeast(0L)
                    // 2026-07-22: Google Drive's DocumentsProvider omits COLUMN_SIZE
                    // in its folder listing, so item.size is 0 for Drive files and
                    // the total fell back to maxBytes (effectively infinite) —
                    // progress computed as written/∞ ≈ 0%, so the bar sat at zero
                    // for the whole download ("no download progress"). The opened
                    // descriptor knows the real size; use it when the listing
                    // didn't supply one.
                    val fdSize = runCatching { pfd.statSize }.getOrDefault(-1L)
                    val progTotal = when {
                        item.size > 0L -> item.size
                        fdSize > 0L -> fdSize
                        else -> maxBytes
                    }
                    cacheFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        var lastReport = -1L
                        while (true) {
                            com.powermediaplayer.util.DownloadProgressBus.throwIfCancelled(progressId)
                            val toRead = if (maxBytes == Long.MAX_VALUE) buf.size
                            else minOf(buf.size.toLong(), maxBytes - written).toInt()
                            if (toRead <= 0) break
                            val n = fis.read(buf, 0, toRead)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (progressId != null && (lastReport < 0 || written - lastReport >= 256L * 1024)) {
                                com.powermediaplayer.util.DownloadProgressBus.update(progressId, written, progTotal)
                                lastReport = written
                            }
                        }
                        if (progressId != null) {
                            com.powermediaplayer.util.DownloadProgressBus.update(progressId, written, progTotal)
                        }
                        com.powermediaplayer.util.Diag.i(tag, "Drive cache wrote $written bytes to ${cacheFile.name}")
                    }
                }
            } ?: run {
                com.powermediaplayer.util.Diag.e(tag, "Drive cache: openFileDescriptor returned null for $sourceUri")
                return@withContext null
            }
            cacheFile
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.e(tag, "Drive cache download exception", e)
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
     * Full-file download (≤4 GB) — used as a fallback when partial reads
     * yield no metadata. Real-world ceiling is whatever the SAF provider
     * is willing to stream; on Drive's Android client this is bounded
     * by available storage rather than a fixed size.
     */
    suspend fun downloadFullToCache(item: CloudMediaItem, progressId: String? = null): java.io.File? {
        val cap = 4L * 1024 * 1024 * 1024
        if (item.size in 1L..Long.MAX_VALUE && item.size > cap) {
            com.powermediaplayer.util.Diag.w(
                "PowerMediaPlayer",
                "Drive full download skipped: ${item.name} size=${item.size} > cap=$cap"
            )
            return null
        }
        // Issue 5: stage the full download in filesDir/offline/tmp (persistent), not cacheDir.
        val stageDir = java.io.File(context.filesDir, "offline/tmp")
        return downloadRangeToCache(item, 0L, null, "full", progressId, destDir = stageDir)
    }

    // ── Helpers ─────────────────────────────────────────────────

    private data class ChildDoc(
        val documentUri: Uri,
        val name: String,
        val mime: String,
        val size: Long
    ) {
        val isDirectory: Boolean
            get() = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
    }

    /**
     * One ContentResolver query per folder instead of 3-4 binder round
     * trips per child via DocumentFile (each .name/.type/.length is its
     * own provider query on TreeDocumentFile — a 300-file Drive folder
     * cost 1200+ provider calls, each potentially a network hop on cloud
     * DocumentsProviders).
     */
    private fun listChildrenFast(folderUri: Uri): List<ChildDoc> {
        val parentDocId =
            if (android.provider.DocumentsContract.isDocumentUri(context, folderUri)) {
                android.provider.DocumentsContract.getDocumentId(folderUri)
            } else {
                android.provider.DocumentsContract.getTreeDocumentId(folderUri)
            }
        val childrenUri = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(folderUri, parentDocId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE
        )
        // 2026-07-22: Google Drive's DocumentsProvider is NETWORK-backed — its
        // first query returns an EMPTY cursor with extras[EXTRA_LOADING]=true
        // while it fetches, then signals when data arrives. A one-shot query
        // therefore showed a real folder as "Empty folder" until you backed out
        // and re-entered. Wait for the provider to stop reporting loading (hard
        // cap), so contents appear on the FIRST open. Local providers report
        // loading=false immediately and still cost a single query.
        val out = mutableListOf<ChildDoc>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000L
        while (true) {
            out.clear()
            var loading = false
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                loading = c.extras?.getBoolean(
                    android.provider.DocumentsContract.EXTRA_LOADING, false
                ) == true
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    out += ChildDoc(
                        documentUri = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(folderUri, docId),
                        name = c.getString(1).orEmpty(),
                        mime = c.getString(2).orEmpty(),
                        size = if (c.isNull(3)) 0L else c.getLong(3)
                    )
                }
            }
            if (!loading || out.isNotEmpty() ||
                android.os.SystemClock.elapsedRealtime() >= deadline
            ) break
            Thread.sleep(400)
        }
        com.powermediaplayer.diag.DiagLog.event(
            "DRIVESAF", "listChildren uri=${folderUri.lastPathSegment} count=${out.size}"
        )
        return out
    }

    private fun toCloudItem(child: ChildDoc, parentId: String?): CloudMediaItem? {
        if (child.name.isEmpty()) return null
        val isFolder = child.isDirectory
        if (!isFolder && !isPlayable(child.mime, child.name)) return null
        return CloudMediaItem(
            id = child.documentUri.toString(),
            name = child.name,
            mimeType = if (isFolder) MIME_FOLDER else child.mime,
            size = if (isFolder) 0L else child.size.coerceAtLeast(0L),
            downloadUrl = child.documentUri.toString(),
            sourceProvider = CloudProviderType.GOOGLE_DRIVE,
            isFolder = isFolder,
            parentId = parentId,
            thumbnailUri = null
        )
    }

    /**
     * Decide whether a SAF entry should appear in the cloud browser.
     * MIME from DocumentsProviders is reliable for media — fall back to
     * extension matching when the provider returns "application/octet-
     * stream" (common for cloud providers that don't sniff content).
     */
    private fun isPlayable(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/") || mime.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PLAYABLE_EXTENSIONS
    }

    private fun trimDriveCache() {
        runCatching {
            val files = context.cacheDir.listFiles { f -> f.name.startsWith("drive_") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= DRIVE_CACHE_CAP_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= DRIVE_CACHE_CAP_BYTES) break
                total -= f.length()
                runCatching { f.delete() }
            }
        }
    }

    companion object {
        private const val MIME_FOLDER = "vnd.android.document/directory"
        /** Search walks at most this many tree nodes per query. */
        private const val SEARCH_TRAVERSAL_CAP = 5_000
        /** drive_* download-cache budget; oldest entries evicted first. */
        private const val DRIVE_CACHE_CAP_BYTES = 256L * 1024 * 1024
        private val PLAYABLE_EXTENSIONS = setOf(
            // Audio
            "mp3", "flac", "ogg", "oga", "opus", "wav", "wave", "aac",
            "m4a", "m4b", "m4p", "m4r", "aiff", "aif", "ape", "wma",
            // Video
            "mp4", "mkv", "webm", "mov", "avi", "flv", "wmv", "ts", "m4v",
            "3gp", "3g2"
        )
    }
}
