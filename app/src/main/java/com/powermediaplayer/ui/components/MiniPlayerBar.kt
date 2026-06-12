package com.powermediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.player.PlayerViewModel
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.SurfaceElevated
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary

/**
 * Persistent slim transport bar shown on every non-Player tab.
 * Reads from the same PlayerViewModel used by PlayerScreen so state
 * is always in sync. Tapping the bar navigates to the Player tab via
 * the supplied [onClick]. Hides itself when nothing is loaded.
 */
@Composable
fun MiniPlayerBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val artBytes by viewModel.artworkBytes.collectAsStateWithLifecycle()

    val titleEmpty = ui.title.isEmpty() || ui.title == "No media loaded"
    if (titleEmpty && ui.artist.isEmpty()) return

    Surface(
        color = SurfaceElevated,
        contentColor = TextPrimary,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            // Artwork — bytes path first (cover-art extracted from
            // tags), then artworkUri fallback (Spotify CDN images),
            // then a music-note placeholder so the bar isn't empty.
            val art = artBytes
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OledBlack),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    // Decode off the composition (Main) thread via
                    // produceState so a large embedded JPEG doesn't
                    // stutter the bar's first frame.
                    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, art) {
                        value = withContext(Dispatchers.Default) {
                            runCatching {
                                // Audit 4.4 - a 3000px embedded cover became a
                                // ~36MB ARGB bitmap behind a 40dp box. Sample
                                // down to ~160px; the full-screen background
                                // keeps its own full-res decode.
                                val bounds = android.graphics.BitmapFactory.Options()
                                    .apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
                                var sample = 1
                                while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
                                val opts = android.graphics.BitmapFactory.Options()
                                    .apply { inSampleSize = sample }
                                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, opts)
                            }.getOrNull()
                        }
                    }
                    val b = bmp
                    if (b != null) {
                        androidx.compose.foundation.Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Filled.MusicNote, contentDescription = null,
                            tint = TealAccent, modifier = Modifier.size(20.dp))
                    }
                } else if (ui.artworkUri != null) {
                    coil3.compose.AsyncImage(
                        model = ui.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null,
                        tint = TealAccent, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = ui.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ui.artist.isNotEmpty()) {
                    Text(
                        text = ui.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = { viewModel.playPause() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (ui.isPlaying) "Pause" else "Play",
                    tint = TealAccent
                )
            }
        }
    }
}

