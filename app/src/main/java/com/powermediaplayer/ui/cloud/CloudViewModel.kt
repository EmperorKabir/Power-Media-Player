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
        if (stack.size <= 1) return
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
        // Build a MediaItem and hand it to the playback connection. The
        // PlaybackService DataSource pipeline injects the Drive bearer
        // token automatically for googleapis.com URLs.
        viewModelScope.launch {
            val streamResult = when (item.sourceProvider) {
                CloudProviderType.GOOGLE_DRIVE -> driveProvider.getMediaStreamUri(item)
                CloudProviderType.SPOTIFY -> spotifyProvider.getMediaStreamUri(item)
                else -> return@launch
            }
            val uri = streamResult.getOrNull() ?: return@launch
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

            // Drive M4B / MP4 audio: chapters live in the moov atom which
            // MediaExtractor cannot reach over an authenticated HTTPS URL.
            // Download the file in the background, parse chapters, and push
            // them to the player via setLocalChapters. Streaming continues
            // unaffected; chapters arrive a few seconds in.
            if (item.sourceProvider == CloudProviderType.GOOGLE_DRIVE && !item.isFolder) {
                val nameLower = item.name.lowercase()
                val looksChapterable = nameLower.endsWith(".m4b") ||
                    nameLower.endsWith(".m4a") ||
                    nameLower.endsWith(".mp4") ||
                    item.mimeType.contains("mp4") ||
                    item.mimeType.contains("m4b")
                if (looksChapterable) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val tempFile = driveProvider.downloadToCache(item) ?: return@launch
                        try {
                            val bundle = M4bChapterParser.extractChaptersAsBundle(
                                context, android.net.Uri.fromFile(tempFile)
                            )
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
                        } finally {
                            tempFile.delete()
                        }
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
