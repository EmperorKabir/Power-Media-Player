package com.powermediaplayer.ui.downloads

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.db.dao.OfflineCopyDao
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Part 3.3 — unified Downloads view: every offline item in one place —
 * downloaded podcast episodes (PodcastDao) + Drive offline copies
 * (OfflineCopyDao) — with per-row delete, total usage vs the cap, grouped
 * by source. Spotify is absent by design (DRM; can't be downloaded).
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val podcastDao: PodcastDao,
    private val offlineCopyDao: OfflineCopyDao,
    private val settings: SettingsDataStore
) : ViewModel() {

    data class DownloadRow(
        val key: String,
        val title: String,
        val isPodcast: Boolean,
        val bytes: Long,
        val podcastGuid: String? = null,
        val podcastPath: String? = null,
        val driveId: String? = null
    )

    data class DownloadsState(
        val podcasts: List<DownloadRow> = emptyList(),
        val drive: List<DownloadRow> = emptyList(),
        val totalBytes: Long = 0L,
        val limitBytes: Long = 0L,
        val loaded: Boolean = false
    )

    val state: StateFlow<DownloadsState> = combine(
        podcastDao.observeDownloaded(),
        offlineCopyDao.observeAll(),
        settings.offlineStorageLimitBytes
    ) { podcasts, drive, limit ->
        val pRows = podcasts.map { e ->
            DownloadRow(
                key = "pod_${e.guid}",
                title = e.title,
                isPodcast = true,
                bytes = e.localBytes,
                podcastGuid = e.guid,
                podcastPath = e.localPath
            )
        }
        val dRows = drive.map { r ->
            DownloadRow(
                key = "drv_${r.driveFileId}",
                title = r.displayName.ifBlank { nameFromPath(r.localPath) },
                isPodcast = false,
                bytes = r.byteSize,
                driveId = r.driveFileId
            )
        }
        DownloadsState(
            podcasts = pRows,
            drive = dRows,
            totalBytes = pRows.sumOf { it.bytes } + dRows.sumOf { it.bytes },
            limitBytes = limit,
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadsState())

    fun delete(row: DownloadRow) {
        viewModelScope.launch(Dispatchers.IO) {
            if (row.isPodcast && row.podcastGuid != null) {
                row.podcastPath?.takeIf { it.isNotBlank() }?.let {
                    com.powermediaplayer.util.SafStorage.delete(appContext, Uri.parse(it))
                }
                podcastDao.clearLocalPath(row.podcastGuid)
            } else if (row.driveId != null) {
                val r = offlineCopyDao.get(row.driveId)
                r?.localPath?.let {
                    if (it.startsWith("content://")) {
                        com.powermediaplayer.util.SafStorage.delete(appContext, Uri.parse(it))
                    } else {
                        runCatching { java.io.File(it).delete() }
                    }
                }
                offlineCopyDao.delete(row.driveId)
                settings.removeOfflineDrive(row.driveId)
            }
        }
    }

    private fun nameFromPath(path: String): String =
        if (path.startsWith("content://")) {
            runCatching {
                val docId = android.provider.DocumentsContract.getDocumentId(Uri.parse(path))
                docId.substringAfterLast('/').substringAfterLast(':')
            }.getOrNull()?.ifBlank { null } ?: "Drive file"
        } else {
            java.io.File(path).name.ifBlank { "Drive file" }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    vm: DownloadsViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = OledBlack,
        topBar = {
            TopAppBar(
                title = { Text("Downloads", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp)
        ) {
            item(key = "usage") {
                val used = formatBytes(s.totalBytes)
                val cap = if (s.limitBytes <= 0L) "unlimited" else formatBytes(s.limitBytes)
                Text("Storage used", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("$used of $cap", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                if (s.limitBytes > 0L) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (s.totalBytes.toFloat() / s.limitBytes).coerceIn(0f, 1f) },
                        color = TealAccent,
                        trackColor = TealAccent.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
            }

            if (!s.loaded) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TealAccent)
                    }
                }
            } else if (s.podcasts.isEmpty() && s.drive.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing downloaded yet. Download podcast episodes or save Drive files offline to manage them here. Spotify content can't be downloaded (DRM).",
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }

            if (s.podcasts.isNotEmpty()) {
                item(key = "h_pod") {
                    SectionHeader(Icons.Filled.Podcasts, "Podcasts (${s.podcasts.size})")
                }
                items(s.podcasts, key = { it.key }) { row -> DownloadRowView(row) { vm.delete(row) } }
            }
            if (s.drive.isNotEmpty()) {
                item(key = "h_drv") {
                    SectionHeader(Icons.Filled.Cloud, "Google Drive (${s.drive.size})")
                }
                items(s.drive, key = { it.key }) { row -> DownloadRowView(row) { vm.delete(row) } }
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TealAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DownloadRowView(row: DownloadsViewModel.DownloadRow, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(formatBytes(row.bytes), color = TextTertiary, style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, "Delete", tint = ErrorRed)
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b <= 0L -> "0 MB"
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.0f MB".format(b / (1024.0 * 1024))
    b >= 1024L -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
