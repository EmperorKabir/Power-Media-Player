package com.powermediaplayer.ui.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Per-tab "i" info icon: rounded-square blue box with a white "i".
 * Distinct from the app's teal accent so it reads unambiguously as
 * "help / info". Visual spec locked from user 2026-05-07 ("square box
 * for i logo, all in blue").
 *
 * - 24×24 dp filled rounded-square (`RoundedCornerShape(6.dp)`).
 * - Background `Color(0xFF1E88E5)` — Material Blue 600.
 * - Bold white "i" centred.
 * - 48×48 dp touch target via outer Box (Material accessibility minimum).
 *
 * Tap → caller's `onClick`. Used by every tab's app-bar to open the
 * tab's [InfoSheet].
 */
@Composable
fun InfoIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Open help for this tab"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = InfoIconBlue,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "i",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

/** Material Blue 600 — locked info-icon background per Q1 (2026-05-07). */
val InfoIconBlue: Color = Color(0xFF1E88E5)
