package com.powermediaplayer.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
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
     * Returns an Intent that fires the Android system folder picker. The
     * cloud screen launches it via ActivityResultContracts, then hands
     * the result to [handleSignInResult].
     */
    fun buildSignInIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
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
                android.util.Log.w("PMP_DIAG", "Drive: takePersistableUriPermission failed", e)
            }
            val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
                ?: "Picked folder"
            settingsDataStore.addDrivePickedRoot(treeUri.toString(), displayName)
            android.util.Log.i("PMP_DIAG", "Drive picked root: $displayName ($treeUri)")
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
                val docFile = resolveFolder(folderId)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Cannot open folder (permission revoked?)")
                    )
                val items = docFile.listFiles()
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
                val out = mutableListOf<CloudMediaItem>()
                for (root in roots) {
                    val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(root.treeUri))
                        ?: continue
                    walkForSearch(rootDoc, needle, out, parentTreeUri = root.treeUri)
                    if (out.size >= 200) break
                }
                Result.success(out.take(200))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun walkForSearch(
        node: DocumentFile,
        needle: String,
        out: MutableList<CloudMediaItem>,
        parentTreeUri: String
    ) {
        if (out.size >= 200) return
        for (child in node.listFiles()) {
            if (out.size >= 200) return
            if (child.isDirectory) {
                walkForSearch(child, needle, out, parentTreeUri)
            } else {
                val name = child.name.orEmpty()
                val mime = child.type.orEmpty()
                if (!isPlayable(mime, name)) continue
                if (!name.lowercase().contains(needle)) continue
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
        suffix: String = "tmp"
    ): java.io.File? = withContext(Dispatchers.IO) {
        val tag = "PowerMediaPlayer"
        val sourceUri = runCatching { Uri.parse(item.downloadUrl) }.getOrNull()
            ?: return@withContext null
        val cacheFile = java.io.File(context.cacheDir, "drive_${item.id.hashCode()}_$suffix")
        val start = rangeStart ?: 0L
        val end = rangeEnd ?: Long.MAX_VALUE
        android.util.Log.i(
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
                    cacheFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val toRead = if (maxBytes == Long.MAX_VALUE) buf.size
                            else minOf(buf.size.toLong(), maxBytes - written).toInt()
                            if (toRead <= 0) break
                            val n = fis.read(buf, 0, toRead)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                        }
                        android.util.Log.i(tag, "Drive cache wrote $written bytes to ${cacheFile.name}")
                    }
                }
            } ?: run {
                android.util.Log.e(tag, "Drive cache: openFileDescriptor returned null for $sourceUri")
                return@withContext null
            }
            cacheFile
        } catch (e: Exception) {
            android.util.Log.e(tag, "Drive cache download exception", e)
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
    suspend fun downloadFullToCache(item: CloudMediaItem): java.io.File? {
        val cap = 4L * 1024 * 1024 * 1024
        if (item.size in 1L..Long.MAX_VALUE && item.size > cap) {
            android.util.Log.w(
                "PowerMediaPlayer",
                "Drive full download skipped: ${item.name} size=${item.size} > cap=$cap"
            )
            return null
        }
        return downloadRangeToCache(item, 0L, null, "full")
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun resolveFolder(folderId: String): DocumentFile? {
        val uri = runCatching { Uri.parse(folderId) }.getOrNull() ?: return null
        // A picked root URI (tree-only) — use fromTreeUri.
        // A nested folder URI that already includes /document/ — use the
        // tree's root and walk via children. DocumentFile.fromTreeUri
        // accepts both forms in practice on AOSP since 23+.
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun toCloudItem(file: DocumentFile, parentId: String?): CloudMediaItem? {
        val name = file.name ?: return null
        val mime = file.type.orEmpty()
        val isFolder = file.isDirectory
        if (!isFolder && !isPlayable(mime, name)) return null
        return CloudMediaItem(
            id = file.uri.toString(),
            name = name,
            mimeType = if (isFolder) MIME_FOLDER else mime,
            size = if (isFolder) 0L else file.length().coerceAtLeast(0L),
            downloadUrl = file.uri.toString(),
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

    companion object {
        private const val MIME_FOLDER = "vnd.android.document/directory"
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
