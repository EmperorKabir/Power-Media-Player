package com.powermediaplayer.ui.cloud

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.powermediaplayer.cloud.CloudMediaItem
import com.powermediaplayer.cloud.CloudProviderType
import com.powermediaplayer.cloud.GoogleDriveProvider
import com.powermediaplayer.cloud.SpotifyProvider
import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.service.LocalMetadataOverride
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.util.M4bChapterParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudUiState(
    val driveLoggedIn: Boolean = false,
    val spotifyLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val activeProvider: CloudProviderType? = null,
    val items: List<CloudMediaItem> = emptyList(),
    val folderStack: List<Pair<String?, String>> = listOf(null to "Root")
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val driveProvider: GoogleDriveProvider,
    private val spotifyProvider: SpotifyProvider,
    private val playbackConnection: PlaybackConnection
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(driveProvider.isLoggedIn, spotifyProvider.isLoggedIn) { d, s -> d to s }
                .collect { (d, s) ->
                    _uiState.value = _uiState.value.copy(driveLoggedIn = d, spotifyLoggedIn = s)
                }
        }
    }

    fun buildDriveSignInIntent(): Intent = driveProvider.buildSignInIntent()
    fun buildSpotifyAuthIntent(): Intent = spotifyProvider.buildAuthIntent()

    fun handleDriveResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = driveProvider.handleSignInResult(data)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) browseDrive(null, "Root")
        }
    }

    fun handleSpotifyResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = spotifyProvider.handleAuthResponse(data)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) browseSpotify()
        }
    }

    fun browseDrive(folderId: String?, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                activeProvider = CloudProviderType.GOOGLE_DRIVE
            )
            val result = driveProvider.listFiles(folderId)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = if (folderId == null) listOf(null to "Root")
                else _uiState.value.folderStack + (folderId to label)
            )
        }
    }

    fun browseSpotify() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                activeProvider = CloudProviderType.SPOTIFY
            )
            val result = spotifyProvider.listFiles()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = result.getOrDefault(emptyList()),
                errorMessage = result.exceptionOrNull()?.message,
                folderStack = listOf(null to "Spotify Library")
            )
        }
    }

    fun navigateUp() {
        val stack = _uiState.value.folderStack
        // At root of a provider → leave the provider, go back to the
        // provider-selection screen so the user can pick a different one.
        if (stack.size <= 1) {
            _uiState.value = _uiState.value.copy(
                activeProvider = null,
                items = emptyList(),
                folderStack = listOf(null to "Root")
            )
            return
        }
        val parent = stack.dropLast(1).last()
        val active = _uiState.value.activeProvider
        if (active == CloudProviderType.GOOGLE_DRIVE) {
            viewModelScope.launch(Dispatchers.IO) {
                val result = driveProvider.listFiles(parent.first)
                _uiState.value = _uiState.value.copy(
                    items = result.getOrDefault(emptyList()),
                    folderStack = stack.dropLast(1)
                )
            }
        }
    }

    /**
     * @param onPlaybackStarted invoked ONLY when the item actually starts
     *   playing — used by the UI to navigate to the Player tab. Failures
     *   (Spotify previews removed, Drive 401, etc.) do not navigate.
     */
    fun openItem(item: CloudMediaItem, onPlaybackStarted: () -> Unit = {}) {
        if (item.isFolder) {
            when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> browseDrive(item.id, item.name)
                else -> { /* Spotify "folders" (albums/playlists) — paged browse not implemented this round */ }
            }
            return
        }
        viewModelScope.launch {
            try {
                if (openItemInternal(item)) {
                    onPlaybackStarted()
                }
            } catch (t: Throwable) {
                android.util.Log.e("PowerMediaPlayer", "openItem failed", t)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Couldn't play: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    /**
     * Returns true iff playback actually started. False means an error was
     * recorded into [_uiState.errorMessage] and the caller should NOT
     * navigate away from the cloud screen.
     */
    private suspend fun openItemInternal(item: CloudMediaItem): Boolean {
        // Build a MediaItem and hand it to the playback connection. The
        // PlaybackService DataSource pipeline injects the Drive bearer
        // token automatically for googleapis.com URLs.
        run {
            val streamResult = when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> driveProvider.getMediaStreamUri(item)
                CloudProviderType.SPOTIFY -> spotifyProvider.getMediaStreamUri(item)
                else -> return false
            }
            // If the provider failed to produce a playable URI (e.g. Spotify
            // track without a preview clip), surface the reason so the user
            // isn't left staring at a blank player.
            streamResult.exceptionOrNull()?.let { ex ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = ex.message ?: "Cannot play this item"
                )
                return false
            }
            val uri = streamResult.getOrNull() ?: run {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "No playable URL for this item"
                )
                return false
            }
            // mediaId MUST be the URI string and requestMetadata MUST carry
            // the URI — MediaController IPC strips localConfiguration.uri
            // before the service receives the item, so the service-side
            // PlayerSessionCallback.onAddMediaItems recovers it from one of
            // those two preserved fields.
            // Drive labels MP4-container files (including .m4b audiobooks)
            // with mime "video/mp4" because the container CAN hold video.
            // The file extension is the authoritative signal — without
            // this override, M4Bs were being treated as video, triggering
            // the 32-s auto-hide and replacing the cover-art surface with
            // an empty video surface. Audio extensions force isVideo=false.
            val audioExts = setOf(
                "m4b", "m4a", "m4p", "m4r", "mp3", "flac", "ogg", "oga",
                "opus", "wav", "wave", "aac", "aiff", "aif", "ape", "wma"
            )
            val nameExt = item.name.substringAfterLast('.', "").lowercase()
            val isVideo = when {
                nameExt in audioExts -> false
                else -> item.mimeType.startsWith("video/")
            }
            android.util.Log.i(
                "PowerMediaPlayer",
                "openItem: name=${item.name} ext=$nameExt mime=${item.mimeType} → isVideo=$isVideo"
            )
            val extras = android.os.Bundle().apply {
                putBoolean("is_video_hint", isVideo)
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(uri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setExtras(extras)
                        .build()
                )
                .build()
            playbackConnection.setMediaItems(listOf(mediaItem), 0)
            // Use the same extension-first decision used for is_video_hint.
            playbackConnection.setVideoModeHint(isVideo)
            // Instant placeholder metadata: filename as title + Drive's
            // thumbnail (auto-generated for many files, no auth needed).
            // Replaced by the post-download tags + embedded artwork when
            // the background extraction finishes.
            playbackConnection.setLocalMetadata(
                LocalMetadataOverride(
                    title = item.name,
                    artworkUri = item.thumbnailUri
                )
            )

            // Drive: chapters + metadata + artwork live INSIDE the file
            // (moov box for MP4/M4B, ID3 for MP3, etc.) and MediaExtractor /
            // MediaMetadataRetriever cannot reach an authenticated HTTPS URL
            // directly. Download to cache once, run BOTH parsers, push the
            // results to the player. Streaming continues unaffected.
            if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder) {
                viewModelScope.launch(Dispatchers.IO) {
                    playbackConnection.setCloudFetchInProgress(true)
                    // Three-pass strategy:
                    //   (1) head 32 MB — fast; works for moov-at-front
                    //   (2) full file (≤1 GB) — slow but reliable;
                    //       MediaMetadataRetriever needs a complete MP4
                    //       structure (ftyp + moov + mdat), so partial
                    //       tail-only downloads can't actually be parsed.
                    //   (3) skip — file too big or already extracted
                    var found = false
                    var tempFile = try {
                        driveProvider.downloadToCache(item)
                    } catch (_: Throwable) { null }
                    if (tempFile != null) {
                        found = parseAndApply(item, tempFile)
                        runCatching { tempFile.delete() }
                    }
                    if (!found) {
                        tempFile = try {
                            driveProvider.downloadFullToCache(item)
                        } catch (_: Throwable) { null }
                        if (tempFile != null) {
                            parseAndApply(item, tempFile)
                            runCatching { tempFile.delete() }
                        }
                    }
                    playbackConnection.setCloudFetchInProgress(false)
                }
            }
        }
        // Reaching here means setMediaItems was called — playback has been
        // handed to the service and (network permitting) will start.
        return true
    }

    /**
     * Run MediaMetadataRetriever + M4B chapter parser against a downloaded
     * Drive byte range. Pushes any extracted artwork / tags / chapters to
     * the player state. Returns true iff at least one was extracted.
     */
    private fun parseAndApply(item: CloudMediaItem, tempFile: java.io.File): Boolean {
        var found = false
        val tempUri = android.net.Uri.fromFile(tempFile)
        android.util.Log.i(
            "PowerMediaPlayer",
            "parseAndApply: file=${tempFile.absolutePath} bytes=${tempFile.length()}"
        )
        runCatching {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, tempUri)
                val title = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
                )
                val artist = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
                ) ?: mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
                )
                val album = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM
                )
                val artBytes = mmr.embeddedPicture
                if (!title.isNullOrBlank() || !artist.isNullOrBlank() ||
                    !album.isNullOrBlank() || artBytes != null) {
                    playbackConnection.setLocalMetadata(
                        LocalMetadataOverride(
                            title = title ?: item.name,
                            artist = artist,
                            album = album,
                            // Preserve the Drive thumbnail as a fallback when
                            // the file has no embedded picture — otherwise
                            // updating with artworkBytes=null would wipe the
                            // placeholder we set instantly on play.
                            artworkUri = item.thumbnailUri,
                            artworkBytes = artBytes
                        )
                    )
                    if (artBytes != null) found = true
                }
                android.util.Log.i(
                    "PowerMediaPlayer",
                    "MMR result: title=$title artist=$artist album=$album " +
                        "artBytes=${artBytes?.size ?: 0}"
                )
            }
        }
        runCatching {
            val bundle = M4bChapterParser.extractChaptersAsBundle(context, tempUri)
            val count = bundle.getInt("chapter_count", 0)
            android.util.Log.i("PowerMediaPlayer", "M4B parser: chapter_count=$count")
            if (count > 0) {
                val chapters = (0 until count).mapNotNull { i ->
                    val title = bundle.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
                    val start = bundle.getLong("chapter_start_$i", -1)
                    val end = bundle.getLong("chapter_end_$i", -1)
                    if (start >= 0) ChapterInfo(title, start, end, i) else null
                }
                playbackConnection.setLocalChapters(chapters)
                found = true
            }
        }
        return found
    }

    fun signOutDrive() {
        viewModelScope.launch { driveProvider.signOut() }
    }

    fun signOutSpotify() {
        viewModelScope.launch { spotifyProvider.signOut() }
    }
}
