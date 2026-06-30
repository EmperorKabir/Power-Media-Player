package com.powermediaplayer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.data.preferences.BluetoothMediaActions
import com.powermediaplayer.data.preferences.BtMediaKind
import kotlinx.coroutines.launch
import com.powermediaplayer.ui.theme.*
import com.powermediaplayer.BuildConfig

/**
 * §D LOCKED order (1 → 15). Each section header carries its §D-NN
 * marker so a future editor can verify alignment without reading
 * the spec.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: androidx.compose.material3.windowsizeclass.WindowSizeClass? = null,
    onOpenDownloads: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val autoplay by viewModel.autoplaySettings.collectAsStateWithLifecycle()
    val voiceBoost by viewModel.voiceBoost.collectAsStateWithLifecycle()
    val btMappingSet by viewModel.btMappingSet.collectAsStateWithLifecycle()
    val subtitleTextSize by viewModel.subtitleTextSizeFlow.collectAsStateWithLifecycle()
    var showHiddenSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }
    var showAlarmsSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showAlarmsSheet) {
        com.powermediaplayer.alarm.AlarmsSheet(
            settingsDataStore = viewModel.settingsDataStore,
            onDismiss = { showAlarmsSheet = false }
        )
    }
    if (showHiddenSheet) {
        com.powermediaplayer.ui.library.HiddenFilesSheet(
            hiddenUris = uiState.hiddenUris,
            onUnhide = { viewModel.unhideUri(it) },
            onUnhideAll = { viewModel.unhideAll() },
            onDismiss = { showHiddenSheet = false }
        )
    }
    if (showStatsSheet) {
        com.powermediaplayer.ui.stats.StatsSheet(
            dao = viewModel.playbackHistoryDao,
            onDismiss = { showStatsSheet = false }
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all settings?", color = ErrorRed, style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Every preference will be restored to its default value. " +
                        "This includes: theme, auto-hide timers, crossfade settings, " +
                        "alarm preferences, and per-file overrides like saved speed " +
                        "and A-B loop markers. Your music files, playback history, " +
                        "and bookmarks (Recents) are NOT affected.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAllSettings()
                    showResetConfirm = false
                }) { Text("Reset", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = OledBlack
        )
    }

    // vc31 §D-REORG — search field + data-driven 8-group catalog.
    // Display order is the user-specified 1·3·4·5·2·8·7·6. Every
    // control from the former flat §D layout is relocated VERBATIM
    // into an item content() lambda (no behaviour change); the group
    // header replaces each old SettingsSectionHeader and the render
    // loop supplies the dividers. A2 inventory guard: SETTINGS_ITEM_IDS.
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Tap the already-active Settings tab again → clear the search filter (the
    // settings "top level" is the unfiltered list).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.powermediaplayer.ui.navigation.TabReselectBus.events.collect { route ->
            if (route == "settings") searchQuery = ""
        }
    }

    // The catalog is structurally static; the content lambdas read
    // the DELEGATED uiState property, so they always see the live
    // value. Audit 4.2: remember(uiState) re-allocated all 8 groups,
    // 20 items and their keyword lists on EVERY settings write -
    // including continuous slider drags. Allocate once.
    val groups = remember { listOf(
        // 1 — Playback (folds old Playback + Crossfade)
        SettingsGroup("Playback", listOf(
            SettingsItem(
                "playback", "Playback & audio focus",
                listOf("focus", "call", "notification", "headphone", "autoplay",
                    "swipe", "gapless", "latency", "buffer", "bluetooth", "resume",
                    "bookmark", "replay", "cold start", "backoff", "interrupt", "duck",
                    "restore", "launch", "last played", "autoplay", "auto play")
            ) {
                Text(
                    text = "What this app does when something else needs the speakers — phone calls, alarms, navigation, or another music app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                AudioFocusRow("Phone call", uiState.audioFocusOnCall) { viewModel.setAudioFocusOnCall(it) }
                AudioFocusRow("Other notification", uiState.audioFocusOnNotification) { viewModel.setAudioFocusOnNotification(it) }
                AudioFocusRow("Other media app", uiState.audioFocusOnOtherMedia) { viewModel.setAudioFocusOnOtherMedia(it) }
                // Auto-resume + cast-interruption toggles (own mini-VM so the big
                // settings combine is untouched).
                val intVm: com.powermediaplayer.ui.settings.InterruptionSettingsViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val resumeAfterInterruption by intVm.resumeAfter.collectAsStateWithLifecycle()
                val pauseCastOnInterruption by intVm.pauseCast.collectAsStateWithLifecycle()
                SettingsToggleItem(
                    title = "Resume after interruptions",
                    description = "When a phone call, navigation prompt, or another app's " +
                        "temporary audio ends, automatically resume playback. Off = stay " +
                        "paused until you press play.",
                    icon = Icons.Filled.PlayArrow,
                    checked = resumeAfterInterruption,
                    onCheckedChange = { intVm.setResumeAfter(it) }
                )
                SettingsToggleItem(
                    title = "Interruptions pause casting",
                    description = "When casting to a TV or speaker, also pause it on a phone " +
                        "call or notification. Off (default) = the cast keeps playing, because " +
                        "the call is on your phone, not the cast device. Bluetooth is local " +
                        "audio and always follows the per-scenario setting above.",
                    icon = Icons.Filled.Cast,
                    checked = pauseCastOnInterruption,
                    onCheckedChange = { intVm.setPauseCast(it) }
                )
                SettingsToggleItem(
                    title = "Auto-play on headphone connect",
                    description = "When you plug in headphones (or connect a Bluetooth audio device), " +
                        "automatically resume playback if a track is paused. Off by default to " +
                        "avoid surprise audio.",
                    icon = Icons.Filled.Headphones,
                    checked = uiState.headphonePlugAutoplay,
                    onCheckedChange = { viewModel.setHeadphonePlugAutoplay(it) }
                )
                SettingsToggleItem(
                    title = "Stop playback on swipe-away",
                    description = "When you swipe the app off the Recents list, stop the music. " +
                        "Off by default — most music apps keep playing in the background " +
                        "after a swipe-away, which is what you want for podcasts in the car.",
                    icon = Icons.Filled.Close,
                    checked = uiState.stopOnTaskRemoved,
                    onCheckedChange = { viewModel.setStopOnTaskRemoved(it) }
                )
                SettingsToggleItem("Gapless playback", "Seamless transitions between tracks.",
                    Icons.Filled.SkipNext, uiState.gaplessPlayback) { viewModel.setGapless(it) }
                SettingsToggleItem(
                    title = "Skip silence",
                    description = "Automatically shorten silent gaps in the audio — handy " +
                        "for podcasts and audiobooks. Per-file overrides can turn it on " +
                        "for one item. Local/Drive only (not Spotify or Cast).",
                    icon = Icons.Filled.FastForward,
                    checked = uiState.crossfadeSkipSilence,
                    onCheckedChange = { viewModel.setCrossfadeSkipSilence(it) }
                )
                SettingsToggleItem(
                    title = "Voice boost (speech clarity)",
                    description = "Lift the presence band (~2.7 kHz) so speech in podcasts, " +
                        "audiobooks and dialogue is clearer. Per-file overrides can set it " +
                        "per item. Local/Drive only (not Spotify or Cast).",
                    icon = Icons.Filled.RecordVoiceOver,
                    checked = voiceBoost,
                    onCheckedChange = { viewModel.setVoiceBoost(it) }
                )
                SettingsToggleItem(
                    title = "Faster effect response",
                    description = "Makes pitch, speed and effect changes take hold a touch " +
                        "faster. On a busy phone it can cause occasional brief glitches. " +
                        "Start a track after switching this for it to apply.",
                    icon = Icons.Filled.Speed,
                    checked = uiState.audioBufferLowLatency,
                    onCheckedChange = { viewModel.setAudioBufferLowLatency(it) }
                )
                SettingsToggleItem("Auto-play on Bluetooth connect",
                    "Start playing when a Bluetooth audio device connects, and when " +
                        "the app launches with one already connected. Independent of " +
                        "the other triggers below — any enabled trigger can start " +
                        "playback (all share the conditions further down).",
                    Icons.Filled.Bluetooth, uiState.resumeOnBt) { viewModel.setResumeOnBt(it) }
                SettingsToggleItem("Auto-play on Cast connect",
                    "Start playing when you connect to a Cast device (TV / speaker), " +
                        "and when the app launches with a Cast session already active.",
                    Icons.Filled.Cast, autoplay.resumeOnCast) { viewModel.setResumeOnCast(it) }
                SliderRow("Bookmark replay context", "${uiState.bookmarkReplayContextSec} s",
                    uiState.bookmarkReplayContextSec.toFloat(), 0f..30f,
                    default = 5f) { viewModel.setBookmarkReplayContextSec(it.toInt()) }
                Text(
                    text = "Tap a bookmark and the seek lands a few seconds before the saved moment for context. 0 = exact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                SettingsToggleItem(
                    title = "Restore last played on launch",
                    description = "When the app starts, load the most recent " +
                        "local or Drive item into the player, paused where " +
                        "you left off. Separate from 'Resume on Bluetooth " +
                        "connect' and headphone auto-play, which act on " +
                        "whatever is already in the player.",
                    icon = Icons.Filled.History,
                    checked = uiState.restoreLastOnLaunch,
                    onCheckedChange = { viewModel.setRestoreLastOnLaunch(it) }
                )
                // Backoff only matters while the launch restore is on.
                if (uiState.restoreLastOnLaunch) {
                    SettingsToggleItem(
                        title = "Auto-play on launch",
                        description = "Start playing the moment the app opens, from " +
                            "where you left off. Independent of the Bluetooth / Cast / " +
                            "headphone triggers — any enabled trigger can start " +
                            "playback. Off by default so opening the app never makes " +
                            "unexpected sound.",
                        icon = Icons.Filled.PlayArrow,
                        checked = uiState.autoplayOnLaunch,
                        onCheckedChange = { viewModel.setAutoplayOnLaunch(it) }
                    )
                    SliderRow("Cold-start resume backoff", "${uiState.coldStartResumeBackoffSec} s",
                        uiState.coldStartResumeBackoffSec.toFloat(), 0f..30f,
                        default = 5f) { viewModel.setColdStartResumeBackoffSec(it.toInt()) }
                    Text(
                        text = "When re-opening the app after a force-stop, rewind by this many seconds before resuming. Helpful for re-finding context in podcasts and audiobooks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }
                // ── Auto-play CONDITIONS (gate every trigger above) ──────────
                Text(
                    text = "Auto-play conditions",
                    style = MaterialTheme.typography.titleSmall,
                    color = TealAccent,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
                SettingsToggleItem(
                    title = "Only if it was playing when you closed",
                    description = "Auto-play only when something was actually playing " +
                        "as you left the app. If you'd paused, it stays paused on resume.",
                    icon = Icons.Filled.PlayArrow,
                    checked = autoplay.onlyIfWasPlaying,
                    onCheckedChange = { viewModel.setAutoplayOnlyIfWasPlaying(it) }
                )
                SettingsToggleItem(
                    title = "Auto-play podcasts & audiobooks",
                    description = "Allow auto-play for spoken-word items.",
                    icon = Icons.Filled.Headphones,
                    checked = autoplay.kindSpoken,
                    onCheckedChange = { viewModel.setAutoplayKindSpoken(it) }
                )
                SettingsToggleItem(
                    title = "Auto-play music",
                    description = "Allow auto-play for music tracks. Off by default so " +
                        "music never auto-blasts on connect/launch.",
                    icon = Icons.Filled.MusicNote,
                    checked = autoplay.kindMusic,
                    onCheckedChange = { viewModel.setAutoplayKindMusic(it) }
                )
                SettingsToggleItem(
                    title = "Auto-play video",
                    description = "Allow auto-play for video. Off by default.",
                    icon = Icons.Filled.Movie,
                    checked = autoplay.kindVideo,
                    onCheckedChange = { viewModel.setAutoplayKindVideo(it) }
                )
                SettingsToggleItem(
                    title = "Auto-play next podcast episode",
                    description = "When a podcast episode ends, automatically continue " +
                        "to the next episode in the show (queues the show from the one " +
                        "you tapped). Off = play one episode then stop.",
                    icon = Icons.Filled.Podcasts,
                    checked = autoplay.podcastAutoplayNext,
                    onCheckedChange = { viewModel.setPodcastAutoplayNext(it) }
                )
                // ── Auto-play FEEL ───────────────────────────────────────────
                SettingsToggleItem(
                    title = "Volume ease-in on auto-play",
                    description = "Fade up from silence when auto-play starts, so a " +
                        "resume on launch / connect isn't jarring. Manual play is " +
                        "unaffected.",
                    icon = Icons.Filled.VolumeUp,
                    checked = autoplay.fadeIn,
                    onCheckedChange = { viewModel.setAutoplayFadeIn(it) }
                )
                if (autoplay.fadeIn) {
                    SliderRow("Ease-in length", "%.1f s".format(autoplay.fadeInMs / 1000f),
                        autoplay.fadeInMs.toFloat(), 0f..5000f,
                        default = 1500f) { viewModel.setAutoplayFadeInMs(it.toInt()) }
                }
            },
            SettingsItem(
                "crossfade", "Crossfade",
                listOf("fade", "gapless", "transition", "mix", "blend")
            ) {
                SliderRow("Crossfade", "${uiState.crossfadeMs} ms",
                    uiState.crossfadeMs.toFloat(), 0f..10_000f,
                    default = 0f) { viewModel.setCrossfade(it.toInt()) }
                Text(
                    text = "How long one track fades into the next. The finer settings (fade shape and when it kicks in) are on the Player tab's Crossfade panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
        )),
        // 3 — Library & cloud
        SettingsGroup("Library & cloud", listOf(
            SettingsItem(
                "library", "Library",
                listOf("scan", "deep scan", "stats", "listening", "hidden",
                    "metadata", "enrichment", "musicbrainz", "discogs",
                    "replaygain", "loudness", "normalise", "normalize")
            ) {
                SettingsToggleItem(
                    title = "Deep Scan",
                    description = "When you tap 'Refresh metadata' on a single track, read " +
                            "deeper inside the file to recover details — artist, album, " +
                            "cover art — that the quick library scan can miss. The normal " +
                            "library scan always uses the fast method, so this setting " +
                            "doesn't slow scanning down.",
                    icon = Icons.Filled.DocumentScanner,
                    checked = uiState.useDeepScan,
                    onCheckedChange = { viewModel.setDeepScan(it) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStatsSheet = true }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.QueryStats, contentDescription = null,
                        tint = TealAccent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Listening stats",
                            style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("Total plays, time listened, top titles + artists.",
                            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHiddenSheet = true }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null,
                        tint = TealAccent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hidden files (${uiState.hiddenUris.size})",
                            style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("Files hidden from the Library list. Tap to view and unhide.",
                            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                }
                SettingsToggleItem(
                    title = "Online metadata enrichment",
                    description = "When a track has missing info (artist, album, year, genre, " +
                        "cover art), look them up in the free online music databases " +
                        "MusicBrainz and Discogs and fill in the blanks. Off by default, so " +
                        "the player doesn't use the internet unless you ask it to.",
                    icon = Icons.Filled.CloudDownload,
                    checked = uiState.metadataEnrichmentEnabled,
                    onCheckedChange = { viewModel.setMetadataEnrichmentEnabled(it) }
                )
                com.powermediaplayer.ui.settings.EnrichmentSubToggles()
                com.powermediaplayer.ui.settings.ReplayGainScanRow()
                com.powermediaplayer.ui.settings.ReplayGainModeRow()
                SettingsToggleItem(
                    title = "Measure loudness of new files automatically",
                    description = "Measure the loudness of each new audio file as it's found, so " +
                        "tracks recorded at different volumes all play at a similar level. Off by " +
                        "default — the first measurement of a big library can take a while.",
                    icon = Icons.Filled.GraphicEq,
                    checked = uiState.replayGainAutoScan,
                    onCheckedChange = { viewModel.setReplayGainAutoScan(it) }
                )
                SettingsToggleItem("Volume normalisation (even out track volumes)",
                    "Automatic loudness normalisation — plays every track at a similar volume so you're not reaching for the volume between songs. Uses each file's own loudness measurement (known as ReplayGain), targeting a consistent level.",
                    Icons.Filled.GraphicEq, uiState.replayGainEnabled) { viewModel.setReplayGain(it) }
            },
            SettingsItem(
                "cloud", "Cloud",
                listOf("offline", "storage", "prefetch", "pre-fetch", "buffer", "drive",
                    "stream", "download", "folder", "podcast", "location")
            ) {
                com.powermediaplayer.ui.settings.OfflineStorageLimitRow()
                // Part 4.3 — global storage-location pickers + Downloads manager.
                StorageFoldersRow(onOpenDownloads = onOpenDownloads)
                SettingsToggleItem("Pre-fetch next cloud track",
                    "Buffer the next item in a cloud queue for seamless transition.",
                    Icons.Filled.CloudDownload, uiState.prefetchNextCloud) { viewModel.setPrefetchNextCloud(it) }
            },
            SettingsItem(
                "backup-restore", "Back up & restore",
                listOf("backup", "back up", "restore", "export", "import", "settings",
                    "migrate", "transfer", "drive", "save", "metadata", "favourites", "history")
            ) {
                com.powermediaplayer.ui.settings.BackupRestoreSection()
            }
        )),
        // 4 — Connectivity
        SettingsGroup("Connectivity", listOf(
            SettingsItem(
                "bluetooth-car", "Bluetooth car controls (per file type)",
                listOf("bluetooth", "car", "stereo", "remote", "button", "prev", "next",
                    "skip", "audiobook", "podcast", "music", "video", "chapter", "episode")
            ) {
                Text(
                    text = "Remap the Previous and Next buttons on your car stereo " +
                        "(or any Bluetooth remote), independently for each kind of media. " +
                        "Audiobooks default to chapter navigation, podcasts to skip, and " +
                        "music and video to track. Works with any car that already " +
                        "controls media over Bluetooth — no setup needed in the car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                listOf(
                    Triple(BtMediaKind.AUDIOBOOK, "Audiobooks", btMappingSet.audiobook),
                    Triple(BtMediaKind.PODCAST, "Podcasts", btMappingSet.podcast),
                    Triple(BtMediaKind.MUSIC, "Music", btMappingSet.music),
                    Triple(BtMediaKind.VIDEO, "Video", btMappingSet.video)
                ).forEach { (kind, title, m) ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TealAccent,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                    )
                    BluetoothActionPicker(
                        label = "Previous button does",
                        currentAction = m.prevAction,
                        seconds = m.skipBackSeconds,
                        options = PREV_OPTIONS,
                        onActionChange = { viewModel.setBtPrevAction(kind, it) },
                        onSecondsChange = { viewModel.setBtSkipBackSeconds(kind, it) }
                    )
                    BluetoothActionPicker(
                        label = "Next button does",
                        currentAction = m.nextAction,
                        seconds = m.skipForwardSeconds,
                        options = NEXT_OPTIONS,
                        onActionChange = { viewModel.setBtNextAction(kind, it) },
                        onSecondsChange = { viewModel.setBtSkipForwardSeconds(kind, it) }
                    )
                }
            },
            SettingsItem(
                "bt-av-offset", "Bluetooth A/V sync offset",
                listOf("bluetooth", "offset", "sync", "delay", "lip", "latency", "video", "audio")
            ) {
                BtVideoAudioOffsetRow(
                    valueMs = uiState.btVideoAudioOffsetMs,
                    onValueChange = { viewModel.setBtVideoAudioOffsetMs(it) }
                )
            },
            SettingsItem(
                "cast-av-offset", "Cast A/V sync offset",
                listOf("cast", "chromecast", "offset", "sync", "delay", "lip",
                    "latency", "video", "audio", "speaker", "google home")
            ) {
                CastVideoAudioOffsetRow(
                    valueMs = uiState.castVideoAudioOffsetMs,
                    onValueChange = { viewModel.setCastVideoAudioOffsetMs(it) }
                )
            }
        )),
        // 5 — Audio
        SettingsGroup("Audio", listOf(
            SettingsItem(
                "audio-effects", "Audio effects",
                listOf("eq", "equaliser", "equalizer", "reverb", "echo", "bass",
                    "stereo", "mono", "passthrough", "volume", "boost", "pitch",
                    "reverse", "backwards", "headphone")
            ) {
                com.powermediaplayer.ui.settings.HeadphoneEqSection()
                Text(
                    "Reverb",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                val reverbOptions = listOf(
                    0 to "Off", 1 to "Room", 2 to "Medium hall",
                    3 to "Large hall", 4 to "Plate", 5 to "Cave"
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    reverbOptions.forEach { (id, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setReverbPreset(id) }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = uiState.reverbPreset == id,
                                onClick = { viewModel.setReverbPreset(id) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = TextPrimary)
                        }
                    }
                }
                SettingsToggleItem("Stereo flip (L↔R)",
                    "Swap left and right channels. Useful when headphones are mis-wired or for spatial-audio experiments.",
                    Icons.Filled.SwapHoriz, uiState.stereoFlip) { viewModel.setStereoFlip(it) }
                SettingsToggleItem("Mono mix",
                    "Mix both channels into a centred mono image (still output as stereo so headphones receive the same on both ears).",
                    Icons.Filled.Adjust, uiState.monoMix) { viewModel.setMonoMix(it) }
                SettingsToggleItem("Surround sound passthrough",
                    "If you're plugged into a home-cinema receiver or soundbar (over HDMI or USB), turn this on to pass surround sound (Dolby / DTS) through untouched, so the receiver decodes it. Leave it off for headphones or your phone speaker — the player then mixes everything down to normal stereo.",
                    Icons.Filled.Speaker, uiState.passthroughAudio) { viewModel.setPassthroughAudio(it) }
                SliderRow("Volume boost", "+${uiState.volumeBoostMb / 100} dB",
                    uiState.volumeBoostMb.toFloat(), 0f..2000f,
                    default = 0f) { viewModel.setVolumeBoost(it.toInt()) }
                SliderRow("Independent pitch", "${"%.2f".format(uiState.pitch)}×",
                    uiState.pitch, 0.5f..2.0f,
                    default = 1.0f) { viewModel.setPitch(it) }
                SettingsToggleItem("Reverse audio (audio only)",
                    "Plays a song or recording backwards — audio only, one " +
                        "file at a time. The first play takes a moment while " +
                        "the file is flipped; after that it's instant. Works " +
                        "with files on your phone (up to 60 minutes long) and " +
                        "Drive files (up to 50 MB). Doesn't work with video " +
                        "or Spotify.",
                    Icons.Filled.SwapHoriz, uiState.audioReverseLocal) { viewModel.setAudioReverseLocal(it) }
            }
        )),
        // 2 — Video & subtitles (folds Video + Subtitles + Auto-hide)
        SettingsGroup("Video & subtitles", listOf(
            SettingsItem(
                "video", "Video",
                listOf("decode", "decoding", "software", "hardware", "codec", "audio delay", "sync")
            ) {
                SettingsToggleItem(
                    title = "Software Decoding",
                    description = "Hardware decoding (default) uses your phone's dedicated video chip " +
                            "for smooth, battery-efficient playback. Switch to Software decoding if you " +
                            "see visual corruption, green screens, or freezing — this uses the CPU instead, " +
                            "which is slower but more compatible.",
                    icon = Icons.Filled.Memory,
                    checked = uiState.useSoftwareDecoding,
                    onCheckedChange = { viewModel.setSoftwareDecoding(it) }
                )
                Row(
                    modifier = Modifier.padding(start = 72.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.useSoftwareDecoding) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (uiState.useSoftwareDecoding) WarningAmber else SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.useSoftwareDecoding) "Using CPU decoding (slower, more compatible)"
                        else "Using hardware decoding (faster, battery efficient)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.useSoftwareDecoding) WarningAmber else SuccessGreen
                    )
                }
                SliderRow("Audio delay", "${uiState.audioDelayMs} ms",
                    uiState.audioDelayMs.toFloat(), -2000f..2000f,
                    default = 0f) { viewModel.setAudioDelay(it.toInt()) }
            },
            SettingsItem(
                "subtitles", "Subtitles",
                listOf("subtitle", "captions", "caption", "srt", "vtt", "ass", "ssa",
                    "opensubtitles", "delay", "format")
            ) {
                SettingsSectionHeader("Subtitles — OpenSubtitles account")
                com.powermediaplayer.ui.settings.OpenSubtitlesSection()
                SettingsSectionHeader("Subtitles — Format Preference")
                val formats = listOf(
                    SubtitleFormatInfo(
                        code = "SRT",
                        name = "SRT — simple subtitles",
                        description = "Simple text subtitles. Just words on screen with basic timing. " +
                                "Works everywhere — the most universally supported format."
                    ),
                    SubtitleFormatInfo(
                        code = "VTT",
                        name = "VTT — web subtitles",
                        description = "The kind used by most streaming sites such as YouTube and Netflix. " +
                                "Can show simple styling like bold text and colours."
                    ),
                    SubtitleFormatInfo(
                        code = "ASS",
                        name = "ASS — fancy subtitles",
                        description = "Subtitles that can use custom fonts, place text anywhere on screen, " +
                                "and add effects such as sing-along (karaoke) highlighting. Often " +
                                "seen on anime and professionally subtitled films."
                    )
                )
                formats.forEach { format ->
                    SubtitleFormatOption(
                        format = format,
                        isSelected = uiState.subtitleFormat == format.code,
                        onSelect = { viewModel.setSubtitleFormat(format.code) }
                    )
                }
                SliderRow("Subtitle delay", "${uiState.subtitleDelayMs} ms",
                    uiState.subtitleDelayMs.toFloat(), -5000f..5000f,
                    default = 0f) { viewModel.setSubtitleDelay(it.toInt()) }
                SliderRow("Subtitle text size", "${(subtitleTextSize * 100).toInt()}%",
                    subtitleTextSize, 0.5f..2.0f,
                    default = 1f) { viewModel.setSubtitleTextSize(it) }
                Text(
                    text = "Positive = subtitles appear later; negative = earlier. " +
                        "Typical nudge is ±100–500 ms.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                )
            },
            SettingsItem(
                "autohide", "Auto-hide controls",
                listOf("auto hide", "hide", "controls", "timeout", "popup", "overlay",
                    "crossfade panel", "info sheet", "track menu")
            ) {
                Text(
                    text = "How long the on-screen controls stay visible after you " +
                        "stop touching the screen. 'Never' keeps them until you tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                AutoHideRow("Video controls", "Player buttons + slider while watching video.",
                    uiState.videoControlsHideSec, { viewModel.setVideoControlsHideSec(it) }, true)
                AutoHideRow("Audio effects popup", "Reverb / stereo flip / mono mix sub-popup.",
                    uiState.audioEffectsPopupHideSec, { viewModel.setAudioEffectsPopupHideSec(it) }, true)
                AutoHideRow("Video effects popup", "Mirror / B&W / sepia / rotation sub-popup.",
                    uiState.videoEffectsPopupHideSec, { viewModel.setVideoEffectsPopupHideSec(it) }, true)
                AutoHideRow("Crossfade panel", "Master crossfade + 9 sub-toggles popup.",
                    uiState.crossfadePopupHideSec, { viewModel.setCrossfadePopupHideSec(it) }, true)
                AutoHideRow("Info sheet (the 'i' icon)", "Per-tab help / explanation sheet.",
                    uiState.infoSheetHideSec, { viewModel.setInfoSheetHideSec(it) }, true)
                AutoHideRow("Long-press track menu", "Favourite / Hide / Share / Delete row sheet.",
                    uiState.trackContextSheetHideSec, { viewModel.setTrackContextSheetHideSec(it) }, true)
            }
        )),
        // 8 — Lighting
        SettingsGroup("Lighting", listOf(
            SettingsItem(
                "hue", "Philips Hue",
                listOf("hue", "lights", "lighting", "philips", "bulb", "colour", "color",
                    "bridge", "entertainment", "reactive", "scene")
            ) {
                HueSection(
                    bridgeIp = uiState.hueBridgeIp,
                    appKey = uiState.hueAppKey,
                    discoveredIp = viewModel.hueDiscoveredIp.collectAsStateWithLifecycle().value,
                    pairStatus = viewModel.huePairStatus.collectAsStateWithLifecycle().value,
                    reactiveIntensity = viewModel.settingsDataStore.hueReactiveIntensity
                        .collectAsStateWithLifecycle(initialValue = 0).value,
                    syncOffsetMs = viewModel.settingsDataStore.hueSyncOffsetMs
                        .collectAsStateWithLifecycle(initialValue = 200).value,
                    areas = viewModel.hueAreas.collectAsStateWithLifecycle().value,
                    isRefreshingAreas = viewModel.isRefreshingHueAreas
                        .collectAsStateWithLifecycle().value,
                    selectedAreaKey = viewModel.settingsDataStore.hueSelectedArea
                        .collectAsStateWithLifecycle(initialValue = "").value,
                    spreadBands = viewModel.settingsDataStore.hueSpreadBands
                        .collectAsStateWithLifecycle(initialValue = true).value,
                    driveDimmable = viewModel.settingsDataStore.hueDriveDimmable
                        .collectAsStateWithLifecycle(initialValue = true).value,
                    dimmableLagOffsetMs = viewModel.settingsDataStore.hueDimmableLagOffsetMs
                        .collectAsStateWithLifecycle(initialValue = 0).value,
                    onRefreshAreas = { viewModel.refreshHueAreas() },
                    onSelectArea = { viewModel.setHueSelectedArea(it) },
                    onClearArea = { viewModel.clearHueSelectedArea() },
                    onSpreadBands = { viewModel.setHueSpreadBands(it) },
                    onDriveDimmable = { viewModel.setHueDriveDimmable(it) },
                    onDimmableLagOffset = { viewModel.setHueDimmableLagOffsetMs(it) },
                    onDiscover = { viewModel.discoverHueBridge() },
                    onPair = { manualIp -> viewModel.completeHuePair(manualIp) },
                    onUnpair = { viewModel.unpairHue() },
                    onAllOn = { viewModel.setHueAll(true) },
                    onAllOff = { viewModel.setHueAll(false) },
                    onApplyScene = { viewModel.applyHueScene(it) },
                    onIntensity = { viewModel.setHueReactiveIntensity(it) },
                    onSyncOffset = { viewModel.setHueSyncOffsetMs(it) }
                )
            },
            SettingsItem(
                "smart-home", "Smart home",
                listOf("smart home", "automation", "matter", "placeholder")
            ) {
                SmartHomePlaceholder()
            }
        )),
        // 7 — Automation
        SettingsGroup("Automation", listOf(
            SettingsItem(
                "alarms", "Wake-up alarms",
                listOf("alarm", "wake", "schedule", "timer", "recurring")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAlarmsSheet = true }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Alarm, contentDescription = null,
                        tint = TealAccent, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wake-up alarms (${uiState.scheduledAlarms.size})",
                            style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("Schedule playback at a chosen time. One-shot or recurring days-of-week.",
                            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                }
            },
            SettingsItem(
                "webhooks", "Webhooks",
                listOf("webhook", "ifttt", "http", "automation", "trigger", "url", "event")
            ) {
                WebhooksSection(
                    url = uiState.webhookUrl,
                    onUrlChange = { viewModel.setWebhookUrl(it) },
                    onPlay = uiState.webhookOnPlay,
                    onPause = uiState.webhookOnPause,
                    onResume = uiState.webhookOnResume,
                    onSkipNext = uiState.webhookOnSkipNext,
                    onSkipPrev = uiState.webhookOnSkipPrev,
                    onEnd = uiState.webhookOnEnd,
                    setPlay = { viewModel.setWebhookOnPlay(it) },
                    setPause = { viewModel.setWebhookOnPause(it) },
                    setResume = { viewModel.setWebhookOnResume(it) },
                    setSkipNext = { viewModel.setWebhookOnSkipNext(it) },
                    setSkipPrev = { viewModel.setWebhookOnSkipPrev(it) },
                    setEnd = { viewModel.setWebhookOnEnd(it) },
                    testStatus = viewModel.webhookTestStatus.collectAsStateWithLifecycle().value,
                    onTest = { viewModel.testWebhook() }
                )
            },
            SettingsItem(
                "external-control", "External app control",
                listOf("tasker", "macrodroid", "intent", "automation", "external", "api")
            ) {
                SettingsToggleItem(
                    title = "External app control (Tasker / Macrodroid)",
                    description = "Let automation apps like Tasker or MacroDroid control playback — " +
                        "play, pause, skip and jump. Off by default; only turn it on for apps " +
                        "you trust.",
                    icon = Icons.Filled.Code,
                    checked = uiState.taskerIntentsEnabled,
                    onCheckedChange = { viewModel.setTaskerIntentsEnabled(it) }
                )
            }
        )),
        // 6 — Appearance & system
        SettingsGroup("Appearance & system", listOf(
            SettingsItem(
                "display", "Display & cover art",
                listOf("display", "artwork", "cover", "art", "fit", "fill", "scale")
            ) {
                Text(
                    text = "Choose whether the now-playing cover art shows the " +
                        "whole image with margins (Fit) or fills the screen, " +
                        "cropping edges if needed (Fill).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                ArtworkScalePicker(
                    currentMode = uiState.artworkScaleMode,
                    onModeChange = { viewModel.setArtworkScaleMode(it) }
                )
            },
            SettingsItem(
                "player-text-colour", "Player text colour",
                listOf("text", "colour", "color", "title", "pill", "dynamic", "custom",
                    "legibility", "contrast", "cover text")
            ) {
                val ptcMode by viewModel.playerTextColourMode.collectAsStateWithLifecycle()
                val ptcCustom by viewModel.playerCustomTextColour.collectAsStateWithLifecycle()
                var pickerOpen by remember { mutableStateOf(false) }
                Text(
                    text = "Colour of the title and details shown over the cover. Default picks " +
                        "black or white for contrast. Custom uses a colour you choose. Dynamic " +
                        "lets the app pick a colour from each cover's own palette, so it differs " +
                        "per file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        com.powermediaplayer.util.PlayerTextColour.MODE_DEFAULT to "Default",
                        com.powermediaplayer.util.PlayerTextColour.MODE_CUSTOM to "Custom",
                        com.powermediaplayer.util.PlayerTextColour.MODE_DYNAMIC to "Dynamic"
                    ).forEach { (m, label) ->
                        FilterChip(
                            selected = ptcMode == m,
                            onClick = { viewModel.setPlayerTextColourMode(m) },
                            label = { Text(label) }
                        )
                    }
                }
                if (ptcMode == com.powermediaplayer.util.PlayerTextColour.MODE_CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(ptcCustom))
                                .border(1.dp, androidx.compose.ui.graphics.Color(0x55FFFFFF), CircleShape)
                                .clickable { pickerOpen = true }
                        )
                        TextButton(onClick = { pickerOpen = true }) { Text("Choose colour") }
                    }
                }
                if (pickerOpen) {
                    ColourPickerDialog(
                        initial = androidx.compose.ui.graphics.Color(ptcCustom),
                        onDismiss = { pickerOpen = false },
                        onPick = { picked ->
                            viewModel.setPlayerCustomTextColour(picked.toArgb())
                            viewModel.setPlayerTextColourMode(
                                com.powermediaplayer.util.PlayerTextColour.MODE_CUSTOM
                            )
                            pickerOpen = false
                        }
                    )
                }
            },
            SettingsItem(
                "font-size", "Font & hitbox size",
                listOf("font", "text size", "scale", "hitbox", "accessibility", "large")
            ) {
                // Hybrid font / hitbox scale. Slider 0.85x..2.00x with live
                // preview. Drag commits continuously so the rest of the UI
                // recomposes in real time and the user can see exactly how
                // every screen looks at the chosen scale.
                FontSizeScalePicker(
                    current = uiState.fontSizeScale,
                    onChange = { viewModel.setFontSizeScale(it) }
                )
            },
            SettingsItem(
                "theme", "Theme",
                listOf("theme", "colour", "color", "dark", "oled", "appearance")
            ) {
                com.powermediaplayer.ui.settings.ThemeSection()
            },
            SettingsItem(
                "diag-log", "Diagnostic logging",
                listOf("diagnostic", "debug", "logs", "log", "battery", "trace", "forensic")
            ) {
                // Diagnostic logging (opt-in). Off by default. When on, writes
                // technical events (BT remote opcodes, audio-effect attach
                // results, key lifecycle, crashes) to app-private external
                // storage so testers can pull the file via adb or Files app.
                DiagLogPicker(
                    enabled = uiState.diagLogEnabled,
                    path = viewModel.diagLogPath(),
                    bytes = viewModel.diagLogBytes(),
                    onToggle = { viewModel.setDiagLogEnabled(it) },
                    onClear = { viewModel.clearDiagLog() }
                )
            },
            SettingsItem(
                "about", "About & reset",
                listOf("about", "version", "reset", "default", "restore", "build")
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        text = "Power Media Player",
                        style = MaterialTheme.typography.titleMedium,
                        color = TealAccent
                    )
                    Text(
                        text = "Version " + BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Built with Media3, FFmpeg and Jetpack Compose",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showResetConfirm = true }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null,
                        tint = ErrorRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reset all settings",
                            style = MaterialTheme.typography.titleSmall, color = ErrorRed)
                        Text("Restore every preference to its default. Playback history, bookmarks, and downloaded files are NOT touched.",
                            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                }
            }
        ))
    ) }

    val q = searchQuery.trim().lowercase()
    val visibleGroups = groups
        .map { g -> g to (if (q.isEmpty()) g.items else g.items.filter { it.matches(q) }) }
        .filter { it.second.isNotEmpty() }

    // Audit 8.1 (F4) — Expanded windows get a ListDetailPaneScaffold
    // two-pane (group index left, group content right; search results
    // span the detail pane). Compact/Medium keep the expandable single
    // column EXACTLY as before — the collapsed-group search semantics
    // (matrix DEP) live in the compact path unchanged; the two-pane
    // path renders every item of the selected group, nothing hidden.
    val twoPane = windowSizeClass?.widthSizeClass ==
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded
    if (twoPane) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OledBlack)
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TealAccent
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
            )
            SettingsSearchField(value = searchQuery, onValueChange = { searchQuery = it })
            SettingsTwoPane(
                groups = groups,
                visibleGroups = visibleGroups,
                searching = q.isNotEmpty(),
                query = searchQuery
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OledBlack)
                .verticalScroll(rememberScrollState())
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TealAccent
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OledBlack)
            )
        SettingsSearchField(value = searchQuery, onValueChange = { searchQuery = it })

            if (visibleGroups.isEmpty()) {
                Text(
                    text = "No settings match “${searchQuery.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                )
            } else {
                visibleGroups.forEach { (group, items) ->
                    // vc32: every group is expandable. Keyed on the group
                    // title so collapse states survive the search filter
                    // removing/reinserting groups (positional rememberSaveable
                    // would mix states up). Default collapsed — the 8 headers
                    // form a compact index; flagged for [VISUAL] sign-off.
                    // While searching, matches are force-shown regardless of
                    // the remembered state (filtering happens on the DATA
                    // above, so collapsed groups are still searched).
                    var expanded by rememberSaveable(key = "grp_${group.title}") {
                        mutableStateOf(false)
                    }
                    val searching = q.isNotEmpty()
                    SettingsGroupHeader(
                        title = group.title,
                        expanded = expanded,
                        searching = searching,
                        onToggle = { expanded = !expanded }
                    )
                    AnimatedVisibility(
                        visible = expanded || searching,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column {
                            items.forEachIndexed { idx, item ->
                                item.content()
                                if (idx < items.lastIndex) SettingsDivider()
                            }
                        }
                    }
                    SettingsDivider()
                }
            }

            // vc31: was 80dp — marginal above a 3-button nav bar under
            // edge-to-edge. 96dp keeps the Reset button clear of the bar.
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

// ── Reusable Setting Components ──────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TealAccent,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * vc32: clickable expand/collapse group header. The chevron hides
 * while searching because search force-shows matches regardless of the
 * remembered collapse state.
 */
@Composable
private fun SettingsGroupHeader(
    title: String,
    expanded: Boolean,
    searching: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !searching) { onToggle() }
            .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TealAccent,
            modifier = Modifier.weight(1f)
        )
        if (!searching) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = TealAccent
            )
        }
    }
}

/** vc32 (E7 idea 2): collapsible sub-section inside a settings item. */
@Composable
private fun ExpandableSubsection(
    title: String,
    stateKey: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(key = "sub_$stateKey") {
        mutableStateOf(initiallyExpanded)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) TealAccent else DisabledGrey,
            modifier = Modifier
                .size(24.dp)
                .padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TealAccent,
                checkedTrackColor = Teal800,
                uncheckedThumbColor = DisabledGrey,
                uncheckedTrackColor = SurfaceElevated
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float? = null,
    onChange: (Float) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = TextPrimary,
                modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = TealAccent)
            // Reset-to-default icon. Only renders when a default is
            // declared AND the current value differs from it. Matches
            // ResetSliderRow's pattern for visual consistency.
            if (default != null && kotlin.math.abs(local - default) > 0.0001f) {
                IconButton(
                    onClick = {
                        local = default
                        onChange(default)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset $label to default",
                        tint = TealAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Slider(
            value = local,
            valueRange = range,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            colors = SliderDefaults.colors(
                thumbColor = TealAccent,
                activeTrackColor = TealAccent,
                inactiveTrackColor = TealAccent.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        color = SurfaceElevated
    )
}

// ── vc31 §D-REORG data-driven catalog model ──────────────────────
//
// A SettingsGroup is a titled section; a SettingsItem is one searchable
// unit whose content() renders the existing control(s). The same list
// drives both render and the live search filter (title OR any keyword).

private class SettingsItem(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val content: @androidx.compose.runtime.Composable () -> Unit
)

private class SettingsGroup(
    val title: String,
    val items: List<SettingsItem>
)

/** Case-insensitive match on the visible title OR any curated keyword. */
private fun SettingsItem.matches(query: String): Boolean =
    settingsItemMatches(title, keywords, query)

/**
 * Pure, Compose-free filter predicate (B1/B2/B4). Extracted so the
 * search behaviour is unit-testable without a Compose harness. Query is
 * already trimmed+lowercased by the caller; empty query matches all.
 */
internal fun settingsItemMatches(
    title: String,
    keywords: List<String>,
    query: String
): Boolean {
    if (query.isEmpty()) return true
    if (title.lowercase().contains(query)) return true
    return keywords.any { it.lowercase().contains(query) }
}

/**
 * A2 inventory guard — the complete set of catalog item ids. Any
 * relocation that drops or renames a control changes this list, which
 * the verification gate diffs against the expected 20-item set.
 */
internal val SETTINGS_ITEM_IDS: List<String> = listOf(
    "playback", "crossfade",
    "library", "cloud",
    "bluetooth-car", "bt-av-offset",
    "audio-effects",
    "video", "subtitles", "autohide",
    "hue", "smart-home",
    "alarms", "webhooks", "external-control",
    "display", "font-size", "theme", "diag-log", "about"
)

data class SubtitleFormatInfo(
    val code: String,
    val name: String,
    val description: String
)

private data class BtActionOption(
    val token: String,
    val label: String,
    val needsSeconds: Boolean
)

private val PREV_OPTIONS = listOf(
    BtActionOption(BluetoothMediaActions.PREV_TRACK,    "Previous track / chapter", false),
    BtActionOption(BluetoothMediaActions.SKIP_BACK,     "Skip backward …",          true),
    BtActionOption(BluetoothMediaActions.RESTART_TRACK, "Restart current track",    false),
    BtActionOption(BluetoothMediaActions.PREV_CHAPTER,  "Previous chapter only",    false)
)

private val NEXT_OPTIONS = listOf(
    BtActionOption(BluetoothMediaActions.NEXT_TRACK,    "Next track / chapter", false),
    BtActionOption(BluetoothMediaActions.SKIP_FORWARD,  "Skip forward …",       true),
    BtActionOption(BluetoothMediaActions.NEXT_CHAPTER,  "Next chapter only",    false)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BluetoothActionPicker(
    label: String,
    currentAction: String,
    seconds: Int,
    options: List<BtActionOption>,
    onActionChange: (String) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    val selected = options.firstOrNull { it.token == currentAction } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt.label,
                                color = if (opt.token == currentAction) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onActionChange(opt.token)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        if (selected.needsSeconds) {
            Spacer(Modifier.height(8.dp))
            SecondsStepper(
                seconds = seconds,
                onChange = onSecondsChange
            )
        }
    }
}

@Composable
private fun SecondsStepper(seconds: Int, onChange: (Int) -> Unit) {
    val presets = listOf(5, 10, 15, 30, 60, 90)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$seconds s",
                style = MaterialTheme.typography.titleMedium,
                color = TealAccent,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            IconButton(onClick = { onChange((seconds - 5).coerceAtLeast(1)) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease seconds", tint = TealAccent)
            }
            IconButton(onClick = { onChange(seconds + 5) }) {
                Icon(Icons.Filled.Add, contentDescription = "Increase seconds", tint = TealAccent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { p ->
                AssistChip(
                    onClick = { onChange(p) },
                    label = { Text("${p}s", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (p == seconds) Teal800 else SurfaceElevated,
                        labelColor = if (p == seconds) TealAccent else TextSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun SubtitleFormatOption(
    format: SubtitleFormatInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = TealAccent,
                unselectedColor = DisabledGrey
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = format.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) TealAccent else TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = format.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun AudioFocusRow(
    label: String,
    current: String,
    onChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // §C14 — chip labels are intentionally plain English. "Duck"
            // is the audio-engineering term for "drop volume to ~30%" but
            // it reads as the bird to most users; "Lower volume" is what
            // we actually do. Token strings stay the same so persisted
            // settings keep working.
            listOf(
                "pause" to "Pause",
                "duck" to "Lower volume",
                "ignore" to "Keep playing"
            ).forEach { (token, name) ->
                FilterChip(
                    selected = current == token,
                    onClick = { onChange(token) },
                    label = { Text(name) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoHideRow(
    label: String,
    description: String,
    currentSeconds: Int,
    onChange: (Int) -> Unit,
    allowNever: Boolean
) {
    val options: List<Pair<Int, String>> = buildList {
        if (allowNever) add(0 to "Never")
        addAll(listOf(1 to "1 s", 2 to "2 s", 3 to "3 s", 4 to "4 s", 6 to "6 s", 8 to "8 s"))
    }
    val selected = options.firstOrNull { it.first == currentSeconds } ?: options.last()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { (sec, lbl) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = lbl,
                                color = if (sec == currentSeconds) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onChange(sec)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtworkScalePicker(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    val options = listOf(
        "fit" to "Fit (show whole cover)",
        "fill" to "Fill (no margins, may crop edges)"
    )
    val selected = options.firstOrNull { it.first == currentMode } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Cover-art sizing",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { (token, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (token == currentMode) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onModeChange(token)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Hybrid font + hitbox scale picker. Slider 0.85x..2.00x in 0.05x
 * steps with a live preview row beneath so the user can see how
 * typography reflows at the chosen scale. The slider commits as the
 * user drags so the rest of the app reflows in real time too.
 */
@Composable
private fun FontSizeScalePicker(
    current: Float,
    onChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Font size",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Scales text and touch targets across the whole app. " +
                "Icon glyphs stay at their design size. Drag the slider " +
                "to preview — every screen reflows live.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "0.85×",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            Slider(
                value = current.coerceIn(0.85f, 2.0f),
                onValueChange = onChange,
                valueRange = 0.85f..2.0f,
                steps = 22, // 0.05x increments between min and max
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(
                text = "2.0×",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Current: " + "%.2f".format(current) + "×",
                style = MaterialTheme.typography.bodySmall,
                color = TealAccent,
                modifier = Modifier.weight(1f)
            )
            // Reset to 1.0x default — only when away from default.
            if (kotlin.math.abs(current - 1.0f) > 0.0001f) {
                IconButton(
                    onClick = { onChange(1.0f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset font size to 1.00×",
                        tint = TealAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagLogPicker(
    enabled: Boolean,
    path: String,
    bytes: Long,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Diagnostic logging",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Writes technical events (Bluetooth remote commands, audio effects, crashes) " +
                        "to a file you can share. Off by default. No personal media titles or paths " +
                        "are recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "File location:",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = TealAccent
            )
            Text(
                text = "Current size: " + "%.1f".format(bytes / 1024.0) + " KB",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Clear logs", color = ErrorRed)
            }
        }
    }
}

/**
 * Reusable slider row with a small RestartAlt reset icon to the right.
 * The reset icon only renders when [value] != [default], keeping the
 * row uncluttered at default state. Used across every slider in the
 * app for consistent reset behaviour.
 */
@Composable
fun ResetSliderRow(
    label: String,
    value: Float,
    default: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueLabel: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (valueLabel != null) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Spacer(Modifier.width(4.dp))
            }
            if (kotlin.math.abs(value - default) > 0.0001f) {
                IconButton(
                    onClick = { onValueChange(default) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset $label to default",
                        tint = TealAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Bluetooth audio-video offset slider for the Settings → Display
 * section. ±1000 ms range, 10 ms steps, default 0. Used when watching
 * video over Bluetooth speakers and the audio is noticeably ahead of
 * or behind the picture.
 */
@Composable
fun BtVideoAudioOffsetRow(
    valueMs: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "Bluetooth video audio offset",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = "Watching video through Bluetooth speakers or headphones can " +
                "make the sound arrive slightly after the picture (Bluetooth adds " +
                "a short delay). Slide right to hold the picture back so it matches " +
                "the sound. Range 0-1 second.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        ResetSliderRow(
            label = "Offset",
            value = valueMs.toFloat(),
            default = 0f,
            valueRange = 0f..1000f,
            steps = 99, // 10 ms increments
            valueLabel = "${valueMs} ms",
            onValueChange = { onValueChange(it.toInt()) }
        )
    }
}

/**
 * Cast audio-video offset slider — the counterpart to
 * [BtVideoAudioOffsetRow] for the local-video-while-casting feature. When
 * casting audio to an audio-only device the picture keeps playing on the
 * phone; this nudges the on-phone video vs the cast audio so lip-sync is
 * right per device/room. INDEPENDENT of the Bluetooth offset. ±1000 ms,
 * 10 ms steps, default 0. Same stored value as the Cast button popup slider.
 */
@Composable
fun CastVideoAudioOffsetRow(
    valueMs: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "Cast video audio offset",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(
            text = "When casting audio to a speaker, the video keeps playing " +
                "on your phone. If the picture leads or lags the cast audio, " +
                "slide to nudge it into sync. Independent of the Bluetooth " +
                "offset. Range ±1 second.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        ResetSliderRow(
            label = "Offset",
            value = valueMs.toFloat(),
            default = 0f,
            valueRange = -1000f..1000f,
            steps = 199, // 10 ms increments
            valueLabel = "${valueMs} ms",
            onValueChange = { onValueChange(it.toInt()) }
        )
    }
}

/**
 * Smart-home integrations panel — Philips Hue + generic Webhooks. Both
 * zero-cost; details surface as the features land in subsequent
 * versions. Placeholder for now so the Settings layout is final.
 *
 * IFTTT-specific branding intentionally dropped — IFTTT moved Webhooks
 * to Pro tier (paid for end users) post-2023. The Webhooks feature
 * compatibly drives Home Assistant, Pipedream, n8n, Make.com free
 * tiers, Tasker, and IFTTT Pro — user chooses which receiver to point
 * the webhook URLs at.
 */
@Composable
fun SmartHomePlaceholder() {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            text = "Smart home",
            style = MaterialTheme.typography.titleSmall,
            color = TealAccent
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "This player can work with your smart home in two ways, both " +
                "free and set up elsewhere in Settings:\n\n" +
                "• Philips Hue — make your lights pulse in time with the music. " +
                "Set it up just above, under 'Philips Hue'.\n" +
                "• Webhooks — tell another app (such as Home Assistant or IFTTT) " +
                "when your music starts or stops, so it can react. Set it up " +
                "under Settings → Automation → Webhooks.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebhooksSection(
    url: String,
    onUrlChange: (String) -> Unit,
    onPlay: Boolean, onPause: Boolean, onResume: Boolean,
    onSkipNext: Boolean, onSkipPrev: Boolean, onEnd: Boolean,
    setPlay: (Boolean) -> Unit, setPause: (Boolean) -> Unit,
    setResume: (Boolean) -> Unit, setSkipNext: (Boolean) -> Unit,
    setSkipPrev: (Boolean) -> Unit, setEnd: (Boolean) -> Unit,
    testStatus: String, onTest: () -> Unit
) {
    var draft by rememberSaveable(url) { mutableStateOf(url) }
    SettingsSectionHeader("Webhooks (send events to other apps)")
    Text(
        text = "A webhook lets this player tell another app when your music " +
            "starts, stops or changes — so that app can react (for example, dim " +
            "the lights when a track begins). You paste in a web address from a " +
            "home-automation app (such as Home Assistant, IFTTT or Tasker — its " +
            "app gives you the address), choose which events to send below, then " +
            "tap Save. Your privacy is protected: the player never sends song " +
            "names or file locations — only a short code for the track, the time, " +
            "and how far through it is.",
        style = MaterialTheme.typography.bodySmall,
        color = TextTertiary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Web address from your automation app") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onTest) {
            Text("Send a test", color = TealAccent)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onUrlChange(draft) }) {
            Text("Save", color = TealAccent)
        }
    }
    if (testStatus.isNotBlank()) {
        Text(
            text = testStatus,
            style = MaterialTheme.typography.bodySmall,
            color = TealAccent,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
    SettingsToggleItem(
        title = "When a track starts",
        description = "Send an event each time a new track begins playing.",
        icon = Icons.Filled.PlayArrow,
        checked = onPlay,
        onCheckedChange = setPlay
    )
    SettingsToggleItem(
        title = "When you pause",
        description = "Send an event when YOU pause. Automatic pauses — a phone call, another app — don't count.",
        icon = Icons.Filled.Pause,
        checked = onPause,
        onCheckedChange = setPause
    )
    SettingsToggleItem(
        title = "When you resume",
        description = "Send an event when you start playing again after a pause.",
        icon = Icons.Filled.PlayCircle,
        checked = onResume,
        onCheckedChange = setResume
    )
    SettingsToggleItem(
        title = "When you skip to the next track",
        description = "Send an event when you press Next — in the app, or on a Bluetooth remote or car stereo.",
        icon = Icons.Filled.SkipNext,
        checked = onSkipNext,
        onCheckedChange = setSkipNext
    )
    SettingsToggleItem(
        title = "When you skip to the previous track",
        description = "Send an event when you press Previous.",
        icon = Icons.Filled.SkipPrevious,
        checked = onSkipPrev,
        onCheckedChange = setSkipPrev
    )
    SettingsToggleItem(
        title = "When a track finishes",
        description = "Send an event once when a track plays all the way to the end.",
        icon = Icons.Filled.Stop,
        checked = onEnd,
        onCheckedChange = setEnd
    )
}

@Composable
private fun HueSection(
    bridgeIp: String,
    appKey: String,
    discoveredIp: String,
    pairStatus: String,
    reactiveIntensity: Int,
    syncOffsetMs: Int,
    areas: List<SettingsViewModel.HueAreaSummary>,
    isRefreshingAreas: Boolean,
    selectedAreaKey: String,
    spreadBands: Boolean,
    driveDimmable: Boolean,
    dimmableLagOffsetMs: Int,
    onRefreshAreas: () -> Unit,
    onSelectArea: (String) -> Unit,
    onClearArea: () -> Unit,
    onSpreadBands: (Boolean) -> Unit,
    onDriveDimmable: (Boolean) -> Unit,
    onDimmableLagOffset: (Int) -> Unit,
    onDiscover: () -> Unit,
    onPair: (String) -> Unit,
    onUnpair: () -> Unit,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onApplyScene: (com.powermediaplayer.hue.HueProvider.ScenePreset) -> Unit,
    onIntensity: (Int) -> Unit,
    onSyncOffset: (Int) -> Unit
) {
    // First open after pair → fetch the area list once.
    LaunchedEffect(bridgeIp, appKey) {
        if (bridgeIp.isNotBlank() && appKey.isNotBlank()) onRefreshAreas()
    }
    val paired = bridgeIp.isNotBlank() && appKey.isNotBlank()
    var manualIp by rememberSaveable { mutableStateOf("") }
    SettingsSectionHeader("Philips Hue")
    Text(
        text = if (paired)
            "Paired with bridge $bridgeIp. Tap a scene to apply it to every light."
        else
            "To pair, two steps: (1) tap Discover so we find the bridge IP. " +
                "(2) walk to the bridge, press the round button on top with the " +
                "three lights, then tap Pair within 30 seconds.",
        style = MaterialTheme.typography.bodySmall,
        color = TextTertiary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )
    if (pairStatus.isNotBlank()) {
        Text(
            text = pairStatus,
            style = MaterialTheme.typography.bodySmall,
            color = TealAccent,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
    if (!paired) {
        // vc32 (E7 idea 2) — sub-section 1/4. Open by default when
        // unpaired so first-time pairing is immediately discoverable.
        ExpandableSubsection("Bridge connection", "hue_bridge", initiallyExpanded = true) {
        // Manual IP override — for networks that block Philips' cloud
        // N-UPnP discovery or for bridges not registered with the cloud.
        // The Hue mobile app shows the bridge IP under Settings →
        // Bridge → Network info.
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("Bridge IP (optional — find in Hue app)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDiscover) {
                Text("Discover", color = TealAccent)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onPair(manualIp.trim()) },
                enabled = manualIp.isNotBlank() || discoveredIp.isNotBlank()
            ) {
                Text("Pair", color = TealAccent)
            }
        }
        }
    } else {
        // vc32 (E7 idea 2): four expandable sub-sections — every control
        // moved VERBATIM, only wrapped + regrouped (A2-style no-drop
        // inventory: 16 interactive controls before and after). The
        // audio-reactive intro texts moved down into the Tuning
        // sub-section they describe.
        ExpandableSubsection("Rooms & zones", "hue_rooms", initiallyExpanded = true) {
        // ── vc29 — area picker (Rooms + Zones + Entertainment areas) ─
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Reactive lighting target",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (isRefreshingAreas) {
                CircularProgressIndicator(
                    color = TealAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 8.dp)
                )
            }
            TextButton(
                onClick = onRefreshAreas,
                enabled = !isRefreshingAreas
            ) {
                Text(
                    text = if (isRefreshingAreas) "Refreshing…" else "Refresh",
                    color = if (isRefreshingAreas) TextSecondary else TealAccent
                )
            }
        }
        Text(
            text = "Pick a room or zone (or an Entertainment area you've already " +
                "set up in the Hue app). Only the lights you pick are used. The " +
                "counts below sort each bulb into one group: 'colour' = can show " +
                "any colour; 'white (warm/cool)' = white only, but you can make it " +
                "warmer or cooler; 'white-only' = brightness only. Smart plugs are " +
                "ignored.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        if (areas.isEmpty()) {
            Text(
                text = "No rooms / zones found yet — tap Refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        } else {
            for (a in areas) {
                val selected = a.key == selectedAreaKey
                val kindLabel = when (a.kind) {
                    com.powermediaplayer.hue.HueProvider.HueArea.Kind.ROOM -> "room"
                    com.powermediaplayer.hue.HueProvider.HueArea.Kind.ZONE -> "zone"
                    com.powermediaplayer.hue.HueProvider.HueArea.Kind.ENTERTAINMENT -> "entertainment"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectArea(a.key) }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${a.displayName} ($kindLabel)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) TealAccent else TextPrimary
                        )
                        // Buckets are mutually exclusive — a colour bulb
                        // is NOT counted again under "white-only". Make
                        // that explicit in the wording (the prior label
                        // "dimmable" was ambiguous because every colour
                        // bulb is also dimmable in the strict sense).
                        Text(
                            text = buildString {
                                val parts = mutableListOf<String>()
                                if (a.colour > 0) parts += "${a.colour} colour"
                                if (a.ambiance > 0) parts += "${a.ambiance} white (warm/cool)"
                                if (a.dimmable > 0) parts += "${a.dimmable} white-only (brightness)"
                                if (a.onoff > 0) {
                                    parts += "${a.onoff} smart plug${if (a.onoff > 1) "s" else ""} (ignored)"
                                }
                                if (parts.isEmpty()) append("no lights")
                                else append(parts.joinToString(" + "))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = TealAccent
                        )
                    }
                }
            }
        }

        // ── Connection controls ─────────────────────────────────────
        // Surfaced near the picker so leaving an area / unpairing the
        // bridge doesn't require digging through the basic-controls
        // section. vc32: disconnect clears ONLY the area — the
        // stream stops because the collector treats a blank area as off;
        // sensitivity is preserved so a re-pick works immediately.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onClearArea,
                enabled = selectedAreaKey.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect room/zone")
            }
            OutlinedButton(
                onClick = onUnpair,
                modifier = Modifier.weight(1f)
            ) {
                Text("Unpair bridge")
            }
        }
        Text(
            text = "'Disconnect room/zone' clears your selection " +
                "and stops streaming until you pick again. 'Unpair bridge' " +
                "forgets the bridge entirely — you'll need to press the " +
                "bridge button to re-pair.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        }

        // vc32 sub-section: the fiddly tuning, collapsed by default.
        ExpandableSubsection("Audio-reactive tuning", "hue_tuning", initiallyExpanded = false) {
        // ── PRIMARY — audio-reactive lighting ──────────────────────
        Text(
            text = "Audio-reactive lighting",
            style = MaterialTheme.typography.titleMedium,
            color = TealAccent,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        Text(
            text = "Lights pulse with the music — bass kicks flash bright, " +
                "treble runs cool blues, mids track melody. The colour-cycle " +
                "rate is BPM-driven so transitions feel musical, and rises " +
                "with the slider below. Sensitivity controls both brightness " +
                "swing and colour-shift speed: 0 turns reactive lighting off, " +
                "low values feel calm + occasional, high values feel pulsing " +
                "+ dramatic.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        Text(
            text = "Works with any room or zone in your Hue app — the player " +
                "auto-creates the Entertainment configuration the first time " +
                "you pick one. Colour bulbs are streamed over Hue's low-latency " +
                "Entertainment protocol; white-only bulbs are pulsed via a " +
                "single group brightness command per cycle so the bridge stays " +
                "comfortable even with many lights in the room. No microphone " +
                "permission needed.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        Text(
            text = "Doesn't apply to Spotify Connect or Cast — those play " +
                "audio on a remote device, so the analyser has nothing to " +
                "see. Reactive lighting auto-pauses on those sources.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )

        // ── Mode toggles ────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Spread bands across colour lights",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = "ON: each colour light handles a different audio band " +
                        "(bass → reds, treble → blues) — wide spatial spectrum. " +
                        "OFF: all colour lights show the same colour together — " +
                        "unified feel. Auto-falls back to OFF if fewer than 3 " +
                        "colour lights in the pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Switch(checked = spreadBands, onCheckedChange = onSpreadBands)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pulse plain white bulbs too",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Make plain white bulbs (ones that can't change colour) " +
                        "brighten and dim with the beat as well. The player flashes " +
                        "them at a steady rate that it picks automatically to suit the " +
                        "bulbs in the room, and eases off if the Hue bridge gets busy. " +
                        "Smart plugs are left alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Switch(checked = driveDimmable, onCheckedChange = onDriveDimmable)
        }

        // ── Dimmable lag offset (only shown when Drive-dimmable is on) ──
        if (driveDimmable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Dimmable lag offset",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (dimmableLagOffsetMs == 0) "Auto" else
                        (if (dimmableLagOffsetMs > 0) "+" else "") + "${dimmableLagOffsetMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TealAccent
                )
            }
            Slider(
                value = dimmableLagOffsetMs.toFloat(),
                onValueChange = { onDimmableLagOffset(it.toInt()) },
                valueRange = -300f..300f,
                steps = 11,  // 50 ms increments
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                text = "Only needed if plain white bulbs still feel slightly off " +
                    "the beat. Slide left to flash them a touch earlier, right to " +
                    "flash a touch later. 'Auto' (0 ms) suits most setups — this is " +
                    "a small nudge on top of the timing the player already works " +
                    "out for you.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Sensitivity",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (reactiveIntensity == 0) "Off" else "$reactiveIntensity",
                style = MaterialTheme.typography.bodyMedium,
                color = if (reactiveIntensity == 0) TextSecondary else TealAccent
            )
        }
        Slider(
            value = reactiveIntensity.toFloat(),
            onValueChange = { onIntensity(it.toInt()) },
            valueRange = 0f..100f,
            steps = 9,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            text = "0 = off. Low (1-30) feels ambient: white lights gently " +
                "breathe, colours change every ~2 seconds, only the biggest " +
                "beats flash. Mid (40-70) is melody-reactive: white lights " +
                "swing with the bass, colours change ~once per second. High " +
                "(80-100) is pulse-driven: white lights sit at a low " +
                "baseline and JUMP on every transient (beats, drops, " +
                "transitions), colours change about twice a second. Some " +
                "non-Hue bulbs react a little slower at the highest settings; " +
                "genuine Hue colour bulbs keep up best.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        // Sync offset: lights are driven from PCM samples we tap BEFORE
        // the speaker output buffer. Without compensation they'd flash
        // ~200 ms ahead of the audio. Default 200 ms tuned for phone
        // speaker + wired output; dial up to ~400-500 ms for BT A2DP
        // (codec adds latency); dial down if your output is low-latency
        // (USB-C DAC, Low-latency audio buffer toggle on).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Light/sound sync offset",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${syncOffsetMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = TealAccent
            )
        }
        Slider(
            value = syncOffsetMs.toFloat(),
            onValueChange = { onSyncOffset(it.toInt()) },
            valueRange = 0f..1000f,
            steps = 19, // 50ms increments
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            text = "Adjust so the light flashes line up with what you hear. " +
                "Roughly: phone speaker or a cable, 150-250 ms; Bluetooth, " +
                "300-500 ms; a USB-C headphone adapter, near 0 ms. The player " +
                "also adds your 'Audio delay' and Bluetooth video offset " +
                "automatically, so changing those won't throw the lights off.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )

        }

        // ── SECONDARY — basic Hue control (third-party-style) ──────
        // vc32 sub-section 4/4.
        ExpandableSubsection("Power & scenes", "hue_scenes", initiallyExpanded = true) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Basic controls",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Text(
            text = "Generic Hue scene + on-off — duplicates what the Hue app " +
                "already does. Useful when you want to set the mood without " +
                "leaving the player.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = false,
                onClick = onAllOn,
                label = { Text("All On") }
            )
            FilterChip(
                selected = false,
                onClick = onAllOff,
                label = { Text("All Off") }
            )
            FilterChip(
                selected = false,
                onClick = onUnpair,
                label = { Text("Unpair") }
            )
        }
        com.powermediaplayer.hue.HueProvider.ScenePreset.values().forEach { preset ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onApplyScene(preset) }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = "Apply",
                    tint = TealAccent
                )
            }
        }
        }
    }
}


// ── F4 (audit 8.1): adaptive Settings ────────────────────────────

@Composable
private fun SettingsSearchField(value: String, onValueChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text("Search settings…", color = TextTertiary) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
            },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealAccent,
                unfocusedBorderColor = DisabledContent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = TealAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
}

@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SettingsTwoPane(
    groups: List<SettingsGroup>,
    visibleGroups: List<Pair<SettingsGroup, List<SettingsItem>>>,
    searching: Boolean,
    query: String,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                val selectedKey = navigator.currentDestination?.contentKey ?: groups.first().title
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(groups, key = { it.title }) { group ->
                        val selected = group.title == selectedKey
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        navigator.navigateTo(
                                            ListDetailPaneScaffoldRole.Detail,
                                            group.title
                                        )
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) TealAccent else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${group.items.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextTertiary
                            )
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (searching) {
                        // Search results span the detail pane — every match
                        // across every group, grouped under small headers.
                        if (visibleGroups.isEmpty()) {
                            Text(
                                text = "No settings match \u201c${query.trim()}\u201d",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                            )
                        } else {
                            visibleGroups.forEach { (group, matchedItems) ->
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TealAccent,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                                matchedItems.forEachIndexed { idx, item ->
                                    item.content()
                                    if (idx < matchedItems.lastIndex) SettingsDivider()
                                }
                                SettingsDivider()
                            }
                        }
                    } else {
                        val key = navigator.currentDestination?.contentKey ?: groups.first().title
                        val group = groups.firstOrNull { it.title == key } ?: groups.first()
                        // The selected group renders always-expanded here.
                        group.items.forEachIndexed { idx, item ->
                            item.content()
                            if (idx < group.items.lastIndex) SettingsDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(96.dp))
                }
            }
        }
    )
}

