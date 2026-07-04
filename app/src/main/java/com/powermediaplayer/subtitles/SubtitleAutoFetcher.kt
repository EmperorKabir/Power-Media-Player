package com.powermediaplayer.subtitles

import android.content.Context
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §C9 — coordinator: when a video starts and no sibling .srt exists,
 * call OpenSubtitles, persist the SRT either next to the video or in
 * the cache dir, and return the resulting File. Honours the
 * A2.2-A2.5 flags (languages, match-by-hash, save-next-to-video,
 * override-existing).
 */
@Singleton
class SubtitleAutoFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val credStore: OpenSubsCredStore
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, "auto-subs").also { it.mkdirs() }
    }
    // Audit B6: concurrent video starts (cast + local race) mutated a plain
    // HashSet across threads; newKeySet matches the fetcher-side pattern.
    private val tried: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun fetchIfNeeded(
        mediaUri: String,
        videoFilename: String,
        localFilePath: String? = null
    ): File? = withContext(Dispatchers.IO) {
        if (mediaUri.isBlank() || videoFilename.isBlank()) return@withContext null
        if (mediaUri in tried) return@withContext null

        val token = credStore.getToken().ifBlank {
            settingsDataStore.openSubsToken.first()
        }
        val apiKey = com.powermediaplayer.BuildConfig.OPENSUBS_API_KEY
            .ifBlank { credStore.getApiKey() }
            .ifBlank { settingsDataStore.openSubsApiKey.first() }
        if (token.isBlank() || apiKey.isBlank()) return@withContext null

        // §C9 A2.2-A2.5 — read user prefs.
        val languages = settingsDataStore.openSubsLanguages.first()
            .takeIf { it.isNotEmpty() } ?: setOf("en")
        val matchByHash = settingsDataStore.openSubsMatchByHash.first()
        val saveNextToVideo = settingsDataStore.openSubsSaveNextToVideo.first()
        val overrideExisting = settingsDataStore.openSubsOverrideExisting.first()

        tried += mediaUri

        // §C9 A2.4 — destination dir.
        val baseName = videoFilename.substringBeforeLast('.')
        val targetDir = if (saveNextToVideo && localFilePath != null) {
            runCatching { File(localFilePath).parentFile }.getOrNull() ?: cacheDir
        } else cacheDir
        val target = File(targetDir, "$baseName.srt")
        if (target.exists() && target.length() > 0 && !overrideExisting) {
            return@withContext target
        }

        val client = OpenSubtitlesClient(apiKey)
        // §C9 A2.3 — moviehash when we have a local file path.
        val hash = if (matchByHash && localFilePath != null)
            OpenSubsHash.compute(localFilePath).orEmpty() else ""
        val langCsv = languages.joinToString(",")
        val fileId = client.searchBestSubtitleFileId(token, videoFilename, langCsv, hash)
            ?: return@withContext null
        val link = client.fetchDownloadLink(token, fileId) ?: return@withContext null
        val ok = client.downloadToFile(link, target)
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "C9 OpenSubs ${if (ok) "downloaded" else "failed"} fileId=$fileId " +
                "lang=$langCsv hash=${if (hash.isBlank()) "-" else hash.take(8)} " +
                "→ ${target.absolutePath}"
        )
        if (ok) target else null
    }

    fun cachedSrtFor(videoFilename: String): File? {
        if (videoFilename.isBlank()) return null
        val target = File(cacheDir, "${videoFilename.substringBeforeLast('.')}.srt")
        return target.takeIf { it.exists() && it.length() > 0 }
    }
}
