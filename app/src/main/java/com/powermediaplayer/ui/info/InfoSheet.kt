package com.powermediaplayer.ui.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.powermediaplayer.ui.theme.SurfaceCard
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary

/**
 * One logical group of info bullets within a tab's info sheet.
 *  - [title] is the section header (collapsed view).
 *  - [bullets] each render as a bulleted line when expanded.
 *
 * Sections are organised by purpose (Q7 LOCKED Option B — logical
 * groups). Verbatim copy lives in [InfoContent].
 */
data class InfoSection(
    val title: String,
    val bullets: List<String>
)

/**
 * Per-tab info content shape: the tab name (for the sheet header) and
 * the ordered list of [InfoSection]s.
 */
data class InfoSheetData(
    val tab: String,
    val sections: List<InfoSection>
)

/**
 * Bottom sheet shown when the user taps the info icon. Contains an
 * accordion of [InfoSection]s — exactly one open at a time. Tapping a
 * collapsed section expands it and collapses the previously-open one.
 *
 * Visual:
 *  - Tab name in TealAccent at the top.
 *  - Each section header is a row with title + chevron (rotates 0→180°
 *    on expand). Tap → toggle expansion.
 *  - Bullets render as `• <text>` lines below the header when expanded.
 *  - All sections collapsed by default.
 *
 * State preserved across configuration changes via [rememberSaveable].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    data: InfoSheetData,
    onDismiss: () -> Unit,
    settingsVm: com.powermediaplayer.ui.settings.SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    rememberCoroutineScope()
    // null = nothing open. Otherwise the index of the currently-open
    // section (accordion: at most one).
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val sState by settingsVm.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sState.infoSheetHideSec, openIndex) {
        // Auto-dismiss only if the user has chosen a positive timer.
        // openIndex restart cancels (interaction restarts the timer).
        if (sState.infoSheetHideSec > 0) {
            kotlinx.coroutines.delay(sState.infoSheetHideSec * 1000L)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "${data.tab} — what each control does",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap a section to expand. Tap again to collapse.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(Modifier.height(16.dp))
            data.sections.forEachIndexed { index, section ->
                val isOpen = openIndex == index
                SectionRow(
                    section = section,
                    isOpen = isOpen,
                    onToggle = {
                        openIndex = if (isOpen) null else index
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionRow(
    section: InfoSection,
    isOpen: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isOpen) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "info-chevron-rotation"
    )
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (isOpen) "Collapse section" else "Expand section",
                        tint = TealAccent
                    )
                }
            }
            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn(tween(150)) + expandVertically(tween(200)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    section.bullets.forEach { bullet ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "•",
                                color = TealAccent,
                                modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                            )
                            Text(
                                text = bullet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
