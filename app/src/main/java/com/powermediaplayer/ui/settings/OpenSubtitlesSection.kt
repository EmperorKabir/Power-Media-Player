package com.powermediaplayer.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val credStore = remember(ctx) {
        com.powermediaplayer.subtitles.OpenSubsCredStore(ctx.applicationContext)
    }
    val token by ds.openSubsToken.collectAsState(initial = "")
    val savedEmail by ds.openSubsEmail.collectAsState(initial = "")
    val savedApiKey by ds.openSubsApiKey.collectAsState(initial = "")
    val signedIn = token.isNotBlank() || credStore.getToken().isNotBlank()

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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // §C9 LOCKED — "Sign up" launches the user's browser to
            // opensubtitles.com so a free account can be created
            // without leaving the app context.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = {
                runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            "https://www.opensubtitles.com/users/sign_up"
                        )
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            }) { Text("Sign up at opensubtitles.com", color = TealAccent) }
            if (signedIn) {
                TextButton(onClick = {
                    scope.launch {
                        ds.setOpenSubsToken("")
                        credStore.clear()
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
                            // §C9 LOCKED — credentials in
                            // EncryptedSharedPreferences. Plain DataStore
                            // mirror kept for legacy callers + offline-
                            // initialised classes that haven't been
                            // refactored to inject the cred store yet.
                            credStore.setApiKey(apiKey)
                            credStore.setEmail(email)
                            credStore.setToken(newToken)
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

        // §C9 A2.2-A2.5 — search & save preferences.
        Spacer(Modifier.height(12.dp))
        OpenSubsSearchPrefs(ds, scope)
    }
}

/**
 * §C9 A2.2-A2.5 — language chips, match-by-hash radio, save-next-to-
 * video radio, override-existing switch.
 */
@Composable
private fun OpenSubsSearchPrefs(
    ds: SettingsDataStore,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val langs by ds.openSubsLanguages.collectAsState(initial = setOf("en"))
    val matchByHash by ds.openSubsMatchByHash.collectAsState(initial = false)
    val saveNextToVideo by ds.openSubsSaveNextToVideo.collectAsState(initial = false)
    val overrideExisting by ds.openSubsOverrideExisting.collectAsState(initial = false)

    Text(
        "Languages", style = MaterialTheme.typography.titleSmall,
        color = TextPrimary
    )
    val available = listOf(
        "en" to "English", "es" to "Spanish", "fr" to "French",
        "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "ru" to "Russian", "zh" to "Chinese", "ja" to "Japanese",
        "ko" to "Korean", "ar" to "Arabic", "hi" to "Hindi"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        available.forEach { (code, label) ->
            androidx.compose.material3.FilterChip(
                selected = code in langs,
                onClick = {
                    scope.launch {
                        val updated = if (code in langs) langs - code else langs + code
                        ds.setOpenSubsLanguages(updated)
                    }
                },
                label = { Text(label) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Search method", style = MaterialTheme.typography.titleSmall,
        color = TextPrimary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(
            selected = matchByHash,
            onClick = { scope.launch { ds.setOpenSubsMatchByHash(true) } },
            label = { Text("Match by hash") }
        )
        androidx.compose.material3.FilterChip(
            selected = !matchByHash,
            onClick = { scope.launch { ds.setOpenSubsMatchByHash(false) } },
            label = { Text("Filename") }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Save location", style = MaterialTheme.typography.titleSmall,
        color = TextPrimary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(
            selected = saveNextToVideo,
            onClick = { scope.launch { ds.setOpenSubsSaveNextToVideo(true) } },
            label = { Text("Next to video") }
        )
        androidx.compose.material3.FilterChip(
            selected = !saveNextToVideo,
            onClick = { scope.launch { ds.setOpenSubsSaveNextToVideo(false) } },
            label = { Text("App cache") }
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(
            checked = overrideExisting,
            onCheckedChange = { scope.launch { ds.setOpenSubsOverrideExisting(it) } }
        )
        Text(
            "  Override existing .srt files",
            color = TextPrimary,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
