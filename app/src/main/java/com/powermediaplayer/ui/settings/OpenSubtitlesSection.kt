package com.powermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.subtitles.OpenSubtitlesClient
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §C9 — OpenSubtitles account section. Fields: API key (per-user
 * dev key, free), email, password. "Sign in" button posts to the
 * REST endpoint and persists the resulting bearer token.
 *
 * Once signed in, [com.powermediaplayer.subtitles.SubtitleAutoFetcher]
 * fires whenever a video starts with no sibling SRT, and writes the
 * downloaded subtitle into the app cache directory next to the video.
 */
@Composable
fun OpenSubtitlesSection(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ds: SettingsDataStore = viewModel.settingsDataStore
    val token by ds.openSubsToken.collectAsState(initial = "")
    val savedEmail by ds.openSubsEmail.collectAsState(initial = "")
    val savedApiKey by ds.openSubsApiKey.collectAsState(initial = "")
    val signedIn = token.isNotBlank()

    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var email by remember(savedEmail) { mutableStateOf(savedEmail) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Subtitles, contentDescription = null,
                tint = TealAccent
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (signedIn) "OpenSubtitles — signed in as $savedEmail"
                    else "OpenSubtitles — sign in to auto-fetch subtitles",
                    style = MaterialTheme.typography.titleSmall, color = TextPrimary
                )
                Text(
                    "Free dev API key required (opensubtitles.com → Profile " +
                        "→ Consumers). Plays a video without an SRT? We " +
                        "look up + cache one for you.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Email
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (!signedIn) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (signedIn) {
                TextButton(onClick = {
                    scope.launch {
                        ds.setOpenSubsToken("")
                        status = "Signed out."
                    }
                }) { Text("Sign out", color = TextSecondary) }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        if (apiKey.isBlank() || email.isBlank() || password.isBlank()) {
                            status = "Fill all three fields first."
                            return@launch
                        }
                        status = "Signing in…"
                        val newToken = withContext(Dispatchers.IO) {
                            OpenSubtitlesClient(apiKey).login(email, password)
                        }
                        if (newToken == null) {
                            status = "Sign-in failed — check API key + credentials."
                        } else {
                            ds.setOpenSubsApiKey(apiKey)
                            ds.setOpenSubsEmail(email)
                            ds.setOpenSubsToken(newToken)
                            status = "Signed in as $email."
                        }
                    }
                }) { Text("Sign in", color = TealAccent) }
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
