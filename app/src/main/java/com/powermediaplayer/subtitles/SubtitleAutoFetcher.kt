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
 * call OpenSubtitles, persist the SRT into the cache dir alongside
 * the video filename. Returns the cached File when a download
 * succeeded, null otherwise.
 *
 * The actual ExoPlayer subtitle-track addition is handled by the
 * existing subtitle pipeline once the file lands on disk; this class
 * is a pure download helper. Caches lookups by mediaUri so repeat
 * plays of the same video don't trigger another network hit.
 */
@Singleton
class SubtitleAutoFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {
    private val dir: File by lazy {
        File(context.cacheDir, "auto-subs").also { it.mkdirs() }
    }
    private val tried = mutableSetOf<String>()

    suspend fun fetchIfNeeded(mediaUri: String, videoFilename: String): File? =
        withContext(Dispatchers.IO) {
            if (mediaUri.isBlank() || videoFilename.isBlank()) return@withContext null
            if (mediaUri in tried) return@withContext null

            val token = settingsDataStore.openSubsToken.first()
            // §C9 LOCKED — prefer the baked BuildConfig key (identifies
            // the app); fall back to user-supplied for legacy installs
            // that signed in before the BuildConfig path existed.
            val apiKey = com.powermediaplayer.BuildConfig.OPENSUBS_API_KEY
                .ifBlank { settingsDataStore.openSubsApiKey.first() }
            if (token.isBlank() || apiKey.isBlank()) return@withContext null

            tried += mediaUri

            // Idempotent: if we already have a cached SRT for this file,
            // skip the network entirely.
            val target = File(dir, "${videoFilename.substringBeforeLast('.')}.srt")
            if (target.exists() && target.length() > 0) return@withContext target

            val client = OpenSubtitlesClient(apiKey)
            val fileId = client.searchBestSubtitleFileId(token, videoFilename)
                ?: return@withContext null
            val link = client.fetchDownloadLink(token, fileId)
                ?: return@withContext null
            val ok = client.downloadToFile(link, target)
            com.powermediaplayer.util.Diag.i(
                "PMP_DIAG",
                "C9 OpenSubs ${if (ok) "downloaded" else "failed"} fileId=$fileId " +
                    "→ ${target.absolutePath}"
            )
            if (ok) target else null
        }

    fun cachedSrtFor(videoFilename: String): File? {
        if (videoFilename.isBlank()) return null
        val target = File(dir, "${videoFilename.substringBeforeLast('.')}.srt")
        return target.takeIf { it.exists() && it.length() > 0 }
    }
}
