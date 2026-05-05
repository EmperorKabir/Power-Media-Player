# project_operational_state.md
*Generated 2026-05-05 (revision 2). Application: Power Media Player. Repo: github.com/EmperorKabir/Power-Media-Player. Branch: main. HEAD: 146887a.*

## 1. Project Objectives

**Application purpose**: offline-first Android audio + video player for personal use, distributed first via Play Store Internal Testing then Production. Target: friends-and-family scale (≤100 users initially), no analytics / ads / telemetry.

**Key features (all currently implemented)**:
- Local audio + video playback (any format Android Media3 supports: MP3, FLAC, AAC, OGG, OPUS, WAV, M4A, M4B audiobooks, MP4, MKV, WEBM, MOV, AVI, FLV, WMV, TS).
- 10-band equaliser with audibility-tuned default presets (Flat, Classical, Rock, Pop, Jazz, Bass Boost, Treble Boost, Vocal, Electronic, Acoustic), custom user presets, BassBoost via the Bass Boost preset.
- A-B loop with Spotify-aware seek routing.
- Frame-by-frame video stepping.
- Sleep timer + sleep-at-end-of-chapter.
- Bookmarks at any position; per-mediaUri (Player tab) + per-session (Recents) + per-pin (Pinned snapshots).
- Persistent MiniPlayerBar across non-Player tabs.
- Last Played: 20-row Recents (per-session) + 10-row Pinned (independent snapshots), bookmark dropdowns, swipe-to-dismiss, Clear all.
- Background playback foreground service + lock-screen notification.
- Bluetooth A2DP/AVRCP control with re-mappable next/prev keys.
- Chromecast streaming (play-services-cast-framework).
- Picture-in-picture (PiP) auto-enter on home press.
- Per-track speed (0.5×–2.0×), pitch-independent shift, ReplayGain (now full ±15 dB — boost via LoudnessEnhancer, attenuation via ExoPlayer.volume).
- Audio delay slider (0–2000 ms positive; via custom AudioDelayProcessor PCM ring buffer).
- Crossfade slider (0–10000 ms; volume-ramp on track transitions, skipped on queue's last track).
- Subtitle delay slider (UI plumbed; logged-only — pending subtitle pipeline implementation).
- Folder-as-audiobook aggregator with absolute cross-file chapters.
- Cover-art extraction via MMR + M4B chapter parser.
- Audio output indicator: codec · channel-layout · sample-rate (AAC/AC-3/E-AC-3/E-AC-3-JOC-Atmos/AC-4/TrueHD/DTS/DTS-HD/PCM/FLAC/Opus/Vorbis).
- Audio effects: 5 reverb presets (EnvironmentalReverb at platform-max wet bus + cold-start retry loop), Stereo flip (L↔R), Mono mix, Multi-channel passthrough toggle.
- Audio output detection: true-mono speakers disable Stereo flip + Mono mix toggles with a hint; phones with stereo speakers and BT/wired/cast outputs unaffected.
- Video effects: Mirror H, Flip V, Black & white, Sepia, Invert colours, 0/90/180/270 rotation, all stackable.
- Cloud: Google Drive via OAuth + drive.file scope + WebView Drive Picker (no Google verification required) + first-pick warning dialog.
- Cloud: Spotify Web API + Connect (PKCE OAuth via AppAuth, Premium-only full playback, LRCLib synced lyrics, auto-launch + bounce-back on Spotify cold-start).
- Storage Access Framework path retained for OneDrive / USB / phone storage (alongside Drive).
- Drive favourites strip (folders + files) + Spotify favourites strip + Library favourites; star icon throughout (no hearts).
- Privacy policy live at `https://emperorkabir.github.io/Power-Media-Player/privacy.html`.
- Release-signing config wired to `local.properties` (gitignored).

## 2. Operational Directives

**Version control**:
- All work committed to `main` after each logical change.
- Every code change pushed to `origin/main` immediately.
- Commit message format: imperative subject ≤72 chars; body explains why and root cause when relevant.
- Never commit secrets. `local.properties`, `*.jks`, `*.keystore`, `*.aab`, `*.apk`, `dbg*.png`, `screen*.png`, `ui-dump.xml` are gitignored.
- Never use `--no-verify`, `--amend` on pushed commits, or `push --force` to main without explicit user approval.

**Permissions & automation**:
- adb permitted: full device control via `C:/Users/Kabir/AppData/Local/Android/Sdk/platform-tools/adb`. UI automation, logcat, screencap, package management.
- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`, `:app:installDebug`, `:app:bundleRelease` permitted without prompt.
- Skill / Agent invocations follow the Superpowers framework when applicable.
- All generated edits must be idempotent; no half-finished implementations.
- Editing rules: prefer `Edit` over `Write` for existing files; never create unrequested `*.md` documentation files.
- Code style: bullet-point operational artefacts, terse production code, no narrative comments.

**Required workflows**:
- Build verification before commit when material code change touches compile path.
- adb-driven smoke test before claiming functional fixes verified (waived only when user explicitly says "no phone connection").
- logcat evidence required for diagnosis claims; no guessing.
- TaskCreate/TaskUpdate for multi-step (≥3) work.

## 3. Current Architecture

**Top-level package**: `com.powermediaplayer`.

**Module dependencies**:
- Compose BOM 2025.04.00, Material 3, foundation, navigation-compose, lifecycle-runtime-compose, activity-compose.
- Media3 1.6.0 (exoplayer, hls, dash, rtsp, session, ui, common, cast, extractor + Mp4Extractor edit-list workaround).
- Hilt 2.59.2 (DI) + KSP 2.1.20-1.0.32, hilt-navigation-compose.
- Room 2.7.1 (runtime, ktx, compiler).
- DataStore-Preferences 1.1.7.
- Coil 3.1.0 (compose + network-okhttp for HTTPS fetcher).
- ML Kit text-recognition (Latin / Chinese / Devanagari / Japanese / Korean) for OCR subtitles.
- Google Sign-In (`play-services-auth:21.3.0`) — Drive drive.file OAuth.
- AppAuth 0.11.1 — Spotify PKCE OAuth.
- OkHttp 4.12.0 — Spotify + Drive REST.
- google-android-cast-framework:21.5.0 + mediarouter:1.7.0.
- androidx.documentfile:1.0.1 — SAF wrapper.
- sh.calvin.reorderable:2.5.0 — Pinned drag-reorder.
- Gson 2.12.1, Guava 33.4.0-android.
- kotlinx-coroutines-android/guava/play-services 1.10.1.

**Removed dependencies**: google-api-client-android, google-api-services-drive, google-http-client-gson (replaced by raw OkHttp REST + drive.file).

**Package structure**:
```
com.powermediaplayer
├── MainActivity.kt                      single-activity Compose host + MainActivityHolder
├── PowerMediaPlayerApp.kt               Hilt application
├── audio/
│   ├── StereoTransformProcessor.kt      AudioProcessor for stereo flip + mono mix
│   ├── AudioDelayProcessor.kt           PCM-16 ring buffer for 0–2000 ms audio delay
│   ├── AudioOutputDetector.kt           AudioDeviceCallback singleton; isTrueMonoOutput Flow
│   └── EqualizerEffectController.kt     10-band Equalizer, attaches on playerFlow
├── cast/
│   └── CastOptionsProviderImpl.kt       Chromecast init
├── cloud/
│   ├── CloudMediaItem.kt
│   ├── CloudStorageProvider.kt          Sealed interface
│   ├── GoogleDriveProvider.kt           SAF DocumentFile path (OneDrive / USB / phone)
│   ├── DriveOAuthProvider.kt            drive.file REST via OkHttp + Sign-In
│   ├── DrivePickerActivity.kt           WebView host for Google JS Picker
│   ├── SpotifyProvider.kt               Web API + Connect + auto-launch + LRCLib lyrics
│   └── SpotifyTokenStore.kt             AuthState JSON in DataStore
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt               Room v7 (destructive migration)
│   │   ├── dao/                         BookmarkDao, HistoryBookmarkDao, FavouriteBookmarkDao,
│   │   │                                PlaybackHistoryDao, HistoryFavouriteDao, PlaybackStateDao,
│   │   │                                EqualizerPresetDao, FavoriteDao
│   │   └── entity/                      mirrors above DAOs (BookmarkEntity now Index('mediaUri'))
│   ├── preferences/
│   │   └── SettingsDataStore.kt         All non-Room prefs incl. fav strips, picked roots,
│   │                                    drive_first_pick_warning_seen, audioDelayMs,
│   │                                    crossfadeMs, replayGainEnabled, reverbPreset,
│   │                                    stereoFlip, monoMix, passthroughAudio, video effects
│   └── repository/
│       └── LastPlayedRepository.kt      Recents + Pinned + sessionId state
├── di/
│   └── AppModule.kt                     Hilt providers
├── service/
│   ├── PlaybackService.kt               MediaSessionService + ExoPlayer + StereoTransformProcessor
│   │                                    + AudioDelayProcessor injection + crossfade controller
│   │                                    + volume mixer (replayGainFactor × crossfadeFactor)
│   ├── PlaybackConnection.kt            MediaController IPC + PlayerState mapping (cached
│   │                                    audioFormatLabel + cumulative window-offset table)
│   └── SpotifyBounceService.kt          10-second foreground-service for BAL exemption
├── ui/
│   ├── cloud/                           CloudBrowserScreen + CloudViewModel (stable LazyColumn key)
│   ├── lastplayed/                      LastPlayedScreen + ViewModel
│   ├── library/                         LibraryScreen + ViewModel + MediaSelectionComponents
│   ├── player/                          PlayerScreen + ViewModel + UiState + reverb retry loop
│   │   └── components/                  AudioEffectsButton (mono-output hint), VideoEffectsButton,
│   │                                    BluetoothButton, CastButton, VideoSurface, ProgressSliders,
│   │                                    SecondaryControls, PlaybackControls, MiniPlayerBar,
│   │                                    ChapterPickerDialog, CoverArtBackground
│   ├── equalizer/                       EQ tab (audibility-tuned defaults)
│   ├── settings/                        Settings tab (injects AudioOutputDetector)
│   ├── components/                      shared
│   ├── theme/                           Color palette, typography
│   └── navigation/                      AppNavigation + tab routing
└── util/                                TextNormalizer, TimeFormatter, M4bChapterParser,
                                         MediaMetadataHelper, FolderChapterAggregator,
                                         BluetoothHelper, BrightnessHelper, PaletteHelper,
                                         TextRecognitionManager
```

**Database schema (Room v7, destructive migration)**:
- `bookmarks` — id PK, mediaUri (indexed), positionMs, label, createdAtMs.
- `playback_history` — autogen id PK, mediaUri (indexed, non-unique), title/subtitle/artworkUri/source/mediaKindOrdinal/lastPositionMs/durationMs/lastPlayedAt.
- `history_bookmarks` — id PK, historyId FK CASCADE, positionMs, label, createdAtMs.
- `history_favourites` — autogen id PK, full snapshot.
- `favourite_bookmarks` — id PK, favouriteId FK CASCADE, positionMs, label, createdAtMs.
- `favorites`, `equalizer_presets`, `playback_state` — pre-existing.

**OAuth/scope state**:
- Google: `https://www.googleapis.com/auth/drive.file` (non-sensitive). Consent screen Production-published. Authorised domain `emperorkabir.github.io`. SHA-1 `BD:78:32:1D:87:BC:14:76:F8:F3:D6:A5:3E:22:07:7E:B6:DE:BF:AE`. Picker API enabled. `DRIVE_PICKER_APP_ID=184142114356`, `DRIVE_PICKER_API_KEY` restricted to Picker API only.
- Spotify: PKCE Authorization-Code; Development Mode (25-user cap). Extension Request not yet submitted.

**Manifest highlights**:
- minSdk 30, targetSdk 35, compileSdk 35, versionCode 2, versionName "1.0.0-friends".
- `<queries>`: `com.google.android.apps.docs`, `com.microsoft.skydrive`, `com.dropbox.android`, `com.spotify.music`.
- Foreground service types declared: `mediaPlayback` (PlaybackService), `specialUse` (SpotifyBounceService) with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=spotify_bounce_back`.
- Activities: MainActivity (LAUNCHER, audio/video VIEW intent), DrivePickerActivity (single-task themed).

**Audio chain (in order)**:
1. ExoPlayer decoder.
2. `StereoTransformProcessor` (stereo flip / mono mix; rejects non-PCM-16-stereo so multichannel passes through).
3. `AudioDelayProcessor` (0–2000 ms positive delay; rejects non-PCM-16 so passthrough surround unaffected).
4. `DefaultAudioSink` → AudioTrack.
5. Side-channel: `EnvironmentalReverb` aux bus (session 0, AuxEffectInfo sendLevel=1.0) + `LoudnessEnhancer` boost + `Equalizer` 10-band, all attached to ExoPlayer's audio session.

**Volume mixer (PlaybackService.Companion)**:
- `replayGainFactor` × `crossfadeFactor` → ExoPlayer.volume.
- Sources: PlayerViewModel ReplayGain attenuation path, internal crossfade controller. Either source's setter applies the mixed product so neither overwrites the other.

## 4. Active State

**Unresolved bugs**:

| ID | Symptom | Diagnostic hypothesis | Evidence |
|----|---------|----------------------|----------|
| B-1 | Spotify cold-start bounce-back fails on Samsung One UI 6 + "Optimize" widget tapped | `balDontBringExistingBackgroundTaskStackToFg=true` set by Samsung battery policy; no Android API can override | logcat `BAL_BLOCK result code=3` despite full opt-in chain |
| B-2 | Spotify bounce intermittently fails even without Optimize widget | User-action token may expire near 1500ms boundary; race between Spotify foreground transition and our bounce fire | Not yet captured in logcat; user-reported only |
| B-3 | Subtitle delay slider is a no-op | `setSubtitleDelayMs` in PlaybackConnection is a logged placeholder; full sync requires custom subtitle pipeline | code review |
| B-4 | Audio delay is positive-only (negative slider value collapses to 0) | An audio AudioProcessor cannot make audio play earlier than video; would require video-side delay (out of scope) | by design — slider remains bidirectional in UI |

**Resolved (feature-complete; awaiting on-device verification by user)**:
- Reverb audibility + cold-start retry (`146887a` shipped over `6f43622`'s prior boost).
- Audio delay slider implemented via `AudioDelayProcessor` (`146887a`).
- Crossfade slider implemented via volume-ramp controller in `PlaybackService` (`146887a`).
- ReplayGain attenuation routing fixed (`146887a`) — negative track-gains no longer dropped.
- Mono-speaker hint disables Stereo flip / Mono mix on true-mono outputs (`146887a`).
- EQ Classical + Acoustic preset audibility (`146887a`; DB v6→v7 destructive reseed).
- StackOverflow on Add folder tap (`2dfe943`).
- Video effect stacking after toggle cycle (`d93b4c9`).

**Precise next algorithmic steps**:

1. **Capture failing logcat for B-2**: `adb logcat -d *:S PMP_DIAG:I ActivityTaskManager:V` immediately after a Spotify-bounce failure.
2. **Spotify Extension Request**: 2–3 min demo screencast → developer.spotify.com/dashboard form per `release/PLAYBOOK.md` §8.
3. **Play Store Internal Testing upload**: keytool keystore → `local.properties` → `./gradlew :app:bundleRelease` → Play Console per `release/PLAYBOOK.md` §9.
4. **B-3 follow-up**: implement subtitle delay via custom RenderersFactory.buildTextRenderers override; out of scope for friends release.
5. **Optional**: relax DrivePicker MIME filter to surface files greyed-out.

## 5. Chronological Decision Log

Format: `commit | change | rationale | testing | result`.

| Commit | Date | Change | Rationale | Testing | Result |
|--------|------|--------|-----------|---------|--------|
| `2082c6c` | 2026-05-03 | Replace Drive REST + drive.readonly + GoogleSignIn with SAF DocumentFile flow | Eliminate Google verification (CASA Tier 2) | Manual install on Z Fold 6 | Drive REST gone; SAF picker on Z Fold landscape didn't expose Drive |
| `7f1e33d` | 2026-05-04 | Schema rewrite: per-session PlaybackHistory autogen id; HistoryFavouriteEntity full snapshot; new bookmark snapshot tables; DB v4→v5 | A→B→A produces 3 Recents rows; Pinned snapshots independent | adb dump | Success |
| `ff369be` | 2026-05-04 | Source-chooser dialog (Drive/OneDrive/local) + simultaneous-audio fix | SAF on Z Fold doesn't expose Drive; chooser deep-links via EXTRA_INITIAL_URI | adb verified | Partial — Drive deep-link rejected |
| `7772c21` | 2026-05-04 | DriveOAuthProvider (drive.file) + DrivePickerActivity (WebView Picker) | drive.file avoids verification; WebView Picker bypasses SAF source-drawer | adb full flow incl. m4b play with bearer-token streaming | Success |
| `5213ea0` | 2026-05-04 | Swipe-to-dismiss on Recents + per-bookmark swipe + Clear all | User explicit ask | UI dump | Accepted |
| `2ee2f10` | 2026-05-04 | Auto-launch Spotify when no Connect device + bounce-back via Activity.startActivity | "No Spotify device found" blocked playback | adb cold-start | Success on warm-start |
| `eae67fa` | 2026-05-04 | Spotify-bounce two-attempt retry (250ms + 1250ms) | First-attempt drop intermittent | adb verified | Success default-bucket; failed restricted-bucket |
| `8335640` | 2026-05-05 | PendingIntent bounce + cold-start resume guard + 3s sheet auto-dismiss | PendingIntent captures launch privileges; cold-start clobbered Spotify mirror | adb tab-switch | Resume guard verified; bounce still BAL_BLOCK |
| `5d83eef` | 2026-05-05 | Full BAL opt-in chain + SpotifyBounceService 10s FGS + AlarmManager fallback | logcat showed `balRequireOptInByPendingIntentCreator=true` + Freecess freezing | adb cold-start with both apps force-stopped | Coroutines stayed alive; ATM still BAL_BLOCK due to Samsung policy |
| `559ee8c` | 2026-05-05 | Replace PresetReverb with EnvironmentalReverb + custom decay/level | PresetReverb's fixed presets cap reverbLevel | Code only | User reported still inaudible |
| `d93b4c9` | 2026-05-05 | Unified TextureView path + Compose Modifier.graphicsLayer for flip/rotation | Path-switching SurfaceView↔TextureView caused detach/reattach + stale pivot | Code review | Compiled, deployed |
| `a6186a2` | 2026-05-05 | One-time AlertDialog warning before first Drive Picker launch | Friend tapped INTO folder, saw "No documents" because Picker is folder-filtered | Compile-only | Deployed |
| `2dfe943` | 2026-05-05 | Fix StackOverflowError: launchDriveOAuth() recursed instead of calling driveOAuthLauncher.launch() | Typo in `a6186a2` self-call | adb logcat captured the recursion | Fixed |
| `6f43622` | 2026-05-05 | Audible reverb (option b): roomLevel=0 + reverbLevel=+2000 across all 5 presets + cold-start retry loop on AudioFlinger error -3; safe optimisations bundle (Pair removal, BookmarkEntity index, audioFormat cache, playlist duration cache, LazyColumn key) | User reported reverb still inaudible; logcat showed `Cannot initialize effect engine ... Error: -3` cold-start race | Compile-only (no phone) | Deployed |
| `146887a` | 2026-05-05 | Implement audio delay slider (AudioDelayProcessor PCM ring buffer); implement crossfade slider (volume-ramp controller in PlaybackService); fix ReplayGain attenuation routing (negative track-gains via ExoPlayer.volume); mono-speaker hint via AudioOutputDetector + AudioDeviceCallback; EQ Classical + Acoustic preset audibility; volume mixer (replayGain × crossfade); DB v6→v7 reseed | Sense-check found three sliders were no-ops + EQ presets too subtle on phone speakers + ReplayGain dropped negatives | Compile-only (no phone per user instruction) | Deployed (current HEAD) |

**Key abandoned approaches**:
- AlarmManager.set with PendingIntent for bounce: system-fired path doesn't carry sender BAL opt-in.
- PresetReverb attached to ExoPlayer audioSession alone: aux effects need setAuxEffectInfo routing.
- view.post + view.setTransform for video effects: pivot raced view measurement; replaced with Compose graphicsLayer.
- SAF DocumentFile-only Drive path on Z Fold: system picker doesn't expose Drive source drawer.
- ReplayGain `coerceAtLeast(0)`: silently dropped negative track-gain tags (loud-track normalisation, the more common case).
