package com.powermediaplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextTertiary
import com.powermediaplayer.util.DownloadProgressBus

/**
 * Live download UI for one item, keyed by [id] (podcast guid OR Drive file id).
 * Shows a determinate bar + "12.0 / 45.3 MB" once the byte total is known, and
 * an indeterminate spinner while it's still connecting / size unknown. Used in
 * the podcast episode rows + Drive file/favourite rows during a download.
 */
@Composable
fun DownloadProgressMini(
    id: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null
) {
    val map by DownloadProgressBus.flow.collectAsStateWithLifecycle()
    val p = map[id]
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.width(60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (p != null && p.total > 0L) {
                LinearProgressIndicator(
                    progress = { p.fraction },
                    color = TealAccent,
                    trackColor = TealAccent.copy(alpha = 0.2f),
                    modifier = Modifier.width(52.dp).height(4.dp)
                )
                Text(
                    "${fmtMb(p.done)}/${fmtMb(p.total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            } else {
                CircularProgressIndicator(
                    color = TealAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Stop download",
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun fmtMb(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1fG".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.0fM".format(b / (1024.0 * 1024))
    b >= 1024L -> "%.0fK".format(b / 1024.0)
    else -> "${b}B"
}
