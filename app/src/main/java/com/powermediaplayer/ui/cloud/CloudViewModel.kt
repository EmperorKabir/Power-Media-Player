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

    fun openItem(item: CloudMediaItem) {
        if (item.isFolder) {
            when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> browseDrive(item.id, item.name)
                else -> { /* Spotify "folders" (albums/playlists) — paged browse not implemented this round */ }
            }
            return
        }
        // Defensive — any unexpected throw inside this entire flow used
        // to bubble up to the default uncaught handler and force-close
        // the app. Log + display via errorMessage instead.
        viewModelScope.launch {
            try {
                openItemInternal(item)
            } catch (t: Throwable) {
                android.util.Log.e("PowerMediaPlayer", "openItem failed", t)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Couldn't play: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    private suspend fun openItemInternal(item: CloudMediaItem) {
        // Build a MediaItem and hand it to the playback connection. The
        // PlaybackService DataSource pipeline injects the Drive bearer
        // token automatically for googleapis.com URLs.
        run {
            val streamResult = when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> driveProvider.getMediaStreamUri(item)
                CloudProviderType.SPOTIFY -> spotifyProvider.getMediaStreamUri(item)
                else -> return
            }
            val uri = streamResult.getOrNull() ?: return
            // mediaId MUST be the URI string and requestMetadata MUST carry
            // the URI — MediaController IPC strips localConfiguration.uri
            // before the service receives the item, so the service-side
            // PlayerSessionCallback.onAddMediaItems recovers it from one of
            // those two preserved fields.
            val isVideo = item.mimeType.startsWith("video/")
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
            // Authoritative video flag from the cloud item's MIME type.
            playbackConnection.setVideoModeHint(item.mimeType.startsWith("video/"))

            // Drive: chapters + metadata + artwork live INSIDE the file
            // (moov box for MP4/M4B, ID3 for MP3, etc.) and MediaExtractor /
            // MediaMetadataRetriever cannot reach an authenticated HTTPS URL
            // directly. Download to cache once, run BOTH parsers, push the
            // results to the player. Streaming continues unaffected.
            if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder) {
                viewModelScope.launch(Dispatchers.IO) {
                    playbackConnection.setCloudFetchInProgress(true)
                    val tempFile = try {
                        driveProvider.downloadToCache(item)
                    } catch (e: Throwable) {
                        // Catch Errors too — OutOfMemoryError on huge files
                        // would otherwise crash the whole process.
                        null
                    }
                    if (tempFile == null) {
                        playbackConnection.setCloudFetchInProgress(false)
                        return@launch
                    }
                    try {
                        val tempUri = android.net.Uri.fromFile(tempFile)

                        // Tags + artwork from MediaMetadataRetriever
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
                                            title = title,
                                            artist = artist,
                                            album = album,
                                            artworkBytes = artBytes
                                        )
                                    )
                                }
                            }
                        }

                        // Chapters from the M4B parser — guarded so a parser
                        // bug never tears down the whole process.
                        runCatching {
                            val bundle = M4bChapterParser.extractChaptersAsBundle(context, tempUri)
                            val count = bundle.getInt("chapter_count", 0)
                            if (count > 0) {
                                val chapters = (0 until count).mapNotNull { i ->
                                    val title = bundle.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
                                    val start = bundle.getLong("chapter_start_$i", -1)
                                    val end = bundle.getLong("chapter_end_$i", -1)
                                    if (start >= 0) ChapterInfo(title, start, end, i) else null
                                }
                                playbackConnection.setLocalChapters(chapters)
                            }
                        }
                    } catch (_: Throwable) {
                        // Defensive — the file we just wrote is being read
                        // by external parsers; any failure here is recoverable.
                    } finally {
                        runCatching { tempFile.delete() }
                        playbackConnection.setCloudFetchInProgress(false)
                    }
                }
            }
        }
    }

    fun signOutDrive() {
        viewModelScope.launch { driveProvider.signOut() }
    }

    fun signOutSpotify() {
        viewModelScope.launch { spotifyProvider.signOut() }
    }
}
