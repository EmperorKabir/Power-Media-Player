# project_operational_state.md
*Generated 2026-05-05. Application: Power Media Player. Repo: github.com/EmperorKabir/Power-Media-Player. Branch: main.*

## 1. Project Objectives

**Application purpose**: offline-first Android audio + video player for personal use, distributed first via Play Store Internal Testing then Production. Target: friends-and-family scale (≤100 users initially), no analytics / ads / telemetry.

**Key features (all currently implemented)**:
- Local audio + video playback (any format Android Media3 supports: MP3, FLAC, AAC, OGG, OPUS, WAV, M4A, M4B audiobooks, MP4, MKV, WEBM, MOV, AVI, FLV, WMV, TS).
- 10-band equaliser with custom presets, BassBoost, LoudnessEnhancer.
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
- Per-track speed (0.5×–2.0×), pitch-independent shift, ReplayGain.
- Subtitle / audio delay sliders.
- Crossfade between tracks.
- Folder-as-audiobook aggregator with absolute cross-file chapters.
- Cover-art extraction via MMR + M4B chapter parser.
- Audio output indicator: codec · channel-layout · sample-rate (AAC/AC-3/E-AC-3/E-AC-3-JOC-Atmos/AC-4/TrueHD/DTS/DTS-HD/PCM/FLAC/Opus/Vorbis).
- Audio effects: 5 reverb presets (EnvironmentalReverb, decay 1.1s–9.0s, reverbLevel up to +2000), Stereo flip (L↔R), Mono mix, Multi-channel passthrough toggle.
- Video effects: Mirror H, Flip V, Black & white, Sepia, Invert colours, 0/90/180/270 rotation, all stackable.
- Cloud: Google Drive via OAuth + drive.file scope + WebView Drive Picker (no Google verification required).
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
- adb-driven smoke test before claiming functional fixes verified.
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
│   └── StereoTransformProcessor.kt      AudioProcessor for stereo flip + mono mix
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
│   │   ├── AppDatabase.kt               Room v5
│   │   ├── dao/
│   │   │   ├── BookmarkDao              per-mediaUri
│   │   │   ├── HistoryBookmarkDao       per-session FK historyId
│   │   │   ├── FavouriteBookmarkDao     per-pin FK favouriteId
│   │   │   ├── PlaybackHistoryDao       autogen id
│   │   │   ├── HistoryFavouriteDao      autogen id, full snapshot
│   │   │   ├── PlaybackStateDao
│   │   │   ├── EqualizerPresetDao
│   │   │   └── FavoriteDao              local-file favourites (legacy uri set)
│   │   └── entity/                      mirrors above DAOs
│   ├── preferences/
│   │   └── SettingsDataStore.kt         All non-Room prefs incl. fav strips, picked roots, drive_first_pick_warning_seen
│   └── repository/
│       └── LastPlayedRepository.kt      Recents + Pinned + sessionId state
├── di/
│   └── AppModule.kt                     Hilt providers
├── service/
│   ├── PlaybackService.kt               MediaSessionService + ExoPlayer + StereoTransformProcessor injection
│   ├── PlaybackConnection.kt            MediaController IPC + PlayerState mapping
│   └── SpotifyBounceService.kt          10-second foreground-service for BAL exemption
├── ui/
│   ├── cloud/                           CloudBrowserScreen + CloudViewModel
│   ├── lastplayed/                      LastPlayedScreen + ViewModel
│   ├── library/                         LibraryScreen + ViewModel + MediaSelectionComponents
│   ├── player/                          PlayerScreen (Compact + Expanded layouts) + ViewModel + UiState
│   │   └── components/                  AudioEffectsButton, VideoEffectsButton, BluetoothButton, CastButton, VideoSurface, ProgressSliders, SecondaryControls, PlaybackControls, MiniPlayerBar, ChapterPickerDialog, CoverArtBackground
│   ├── equalizer/                       EQ tab
│   ├── settings/                        Settings tab
│   ├── components/                      shared
│   ├── theme/                           Color palette, typography
│   └── navigation/                      AppNavigation + tab routing
└── util/                                TextNormalizer, TimeFormatter, M4bChapterParser, MediaMetadataHelper, FolderChapterAggregator, BluetoothHelper, BrightnessHelper, PaletteHelper, TextRecognitionManager
```

**Database schema (Room v5, destructive migration)**:
- `bookmarks` — id PK, mediaUri, positionMs, label, createdAtMs.
- `playback_history` — autogen id PK, mediaUri (indexed, non-unique), title/subtitle/artworkUri/source/mediaKindOrdinal/lastPositionMs/durationMs/lastPlayedAt.
- `history_bookmarks` — id PK, historyId FK CASCADE, positionMs, label, createdAtMs.
- `history_favourites` — autogen id PK, full snapshot of mediaUri/title/subtitle/artwork/source/mediaKindOrdinal/lastPositionMs/durationMs/pinOrder/pinnedAtMs.
- `favourite_bookmarks` — id PK, favouriteId FK CASCADE, positionMs, label, createdAtMs.
- `favorites` (local files), `equalizer_presets`, `playback_state` — pre-existing.

**OAuth/scope state**:
- Google: `https://www.googleapis.com/auth/drive.file` (non-sensitive). Consent screen Production-published. Authorised domain `emperorkabir.github.io`. SHA-1 `BD:78:32:1D:87:BC:14:76:F8:F3:D6:A5:3E:22:07:7E:B6:DE:BF:AE`. Picker API enabled. `DRIVE_PICKER_APP_ID=184142114356`, `DRIVE_PICKER_API_KEY` restricted to Picker API only.
- Spotify: PKCE Authorization-Code; Development Mode (25-user cap). Extension Request not yet submitted.

**Manifest highlights**:
- minSdk 30, targetSdk 35, compileSdk 35, versionCode 2, versionName "1.0.0-friends".
- `<queries>`: `com.google.android.apps.docs`, `com.microsoft.skydrive`, `com.dropbox.android`, `com.spotify.music`.
- Foreground service types declared: `mediaPlayback` (PlaybackService), `specialUse` (SpotifyBounceService) with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=spotify_bounce_back`.
- Activities: MainActivity (LAUNCHER, audio/video VIEW intent), DrivePickerActivity (single-task themed).

## 4. Active State

**Unresolved bugs**:

| ID | Symptom | Diagnostic hypothesis | Evidence |
|----|---------|----------------------|----------|
| B-1 | Spotify cold-start bounce-back fails on Samsung One UI 6 + "Optimize" widget tapped | `balDontBringExistingBackgroundTaskStackToFg=true` set by Samsung battery policy; no Android API can override | logcat `BAL_BLOCK result code=3` despite full opt-in chain (creator + sender ALLOW_BAL, foreground-service process state, MODE_BACKGROUND_ACTIVITY_START_ALLOWED on both ends) |
| B-2 | Spotify bounce intermittently fails even without Optimize widget | User-action token may expire near 1500ms boundary; race conditions between Spotify foreground transition and our bounce fire | Not yet captured in logcat; user-reported only |
| B-3 | Reverb may still be subtle on certain output paths | EnvironmentalReverb auxSendLevel 1.0f is API max; cannot push higher without DSP-side processing | Not measured — verification deferred to user audio test |
| B-4 | Friend reports "No documents" inside Drive Picker | Picker MIME filter set to folders-only; sub-folder navigation shows no files (intentional) — UX confusion | Mitigated by one-time warning dialog (commit `a6186a2`) but not validated in field |

**Resolved-this-session, latest-state**:
- Video effect stacking after toggle cycle: fixed by unified TextureView + Compose graphicsLayer (`d93b4c9`).
- Spotify favourites missing from Last Played: fixed by adding recordCloudPlay to playSpotifyFavourite (`18f1bed`).
- Player tab showing stale local video after Spotify mirror: fixed by gating cold-start resume on spotifyState (`8335640`).
- Reverb inaudible: PresetReverb was not aux-routed; switched to EnvironmentalReverb + setAuxEffectInfo (`559ee8c`).
- Effect popup auto-dismiss: 3s timer with interaction-reset (`8335640`).
- Spotify polling latency / album-art delay: 200ms burst for first 10 iterations (`69b1473`).

**Precise next algorithmic steps**:

1. **Capture failing logcat for B-2**: instruct user to run `adb logcat -d *:S PMP_DIAG:I ActivityTaskManager:V` immediately after a failure and paste output. Without, no fix.
2. **Spotify Extension Request** (operational, not code): record 2–3 min demo screencast on phone, submit form at developer.spotify.com/dashboard with paste-ready text already written in `release/PLAYBOOK.md` §8.
3. **Play Store Internal Testing upload** (operational): generate release keystore via `keytool`, populate `RELEASE_*` keys in `local.properties`, run `./gradlew :app:bundleRelease`, upload to Play Console per `release/PLAYBOOK.md` §9.
4. **Optional**: relax DrivePicker MIME filter to also surface files (greyed for selection) to further mitigate B-4. Trade-off: introduces second confusion vector ("why can't I select this audio file?").
5. **Optional**: add `USE_FULL_SCREEN_INTENT` permission + post a full-screen-intent Notification as ultimate bounce-back fallback for B-1 (requires Android 14+ user grant for non-default-handler apps).

## 5. Chronological Decision Log

Format: `commit | change | rationale | testing | result`.

| Commit | Date | Change | Rationale | Testing | Result |
|--------|------|--------|-----------|---------|--------|
| `2082c6c` | 2026-05-03 | Replace Drive REST + drive.readonly + GoogleSignIn with SAF DocumentFile flow | Eliminate Google verification requirement (CASA Tier 2) by using non-OAuth file access | Manual install on Z Fold 6 | Drive REST gone; SAF picker on Z Fold landscape did not expose Drive in source drawer — required follow-up |
| `7f1e33d` | 2026-05-04 | Schema rewrite: per-session PlaybackHistory autogen id; HistoryFavouriteEntity with full snapshot; new HistoryBookmark + FavouriteBookmark tables; DB v4→v5 destructive | User contract: A→B→A produces 3 Recents rows; Player deletions don't cascade to Last Played; Pinned snapshots independent | adb dump verified 3 distinct rows after A/B/A play sequence; bookmark dropdowns survive Player delete | Success — schema enables session-scoped semantics required by user spec |
| `ff369be` | 2026-05-04 | Source-chooser dialog (Drive/OneDrive/local) for SAF picker + simultaneous-audio fix | SAF on Z Fold landscape doesn't expose Drive; chooser deep-links via EXTRA_INITIAL_URI; simultaneous local+Spotify audio confused users | adb verified chooser dialog appears with Drive/OneDrive/Phone storage entries | Partial — Drive deep-link rejected by Drive DocumentsProvider (synthetic root docId); chooser path retained for OneDrive only |
| `7772c21` | 2026-05-04 | Add DriveOAuthProvider (drive.file + Google Sign-In) + DrivePickerActivity (WebView with Picker JS) | SAF unable to reach Drive on Z Fold; drive.file scope avoids verification entirely; WebView Picker bypasses SAF source-drawer issue | adb-driven full flow: Sign-In → consent → Picker → folder pick → REST list → m4b play with bearer-token streaming | Success — confirmed end-to-end via PMP_DIAG `picked folder ... ID` + parseAndApply cache write |
| `5213ea0` | 2026-05-04 | Swipe-to-dismiss on Recents rows + per-bookmark swipe + Clear all button | User explicit ask | Visual check via UI dump | Compiled, deployed; user accepted |
| `2ee2f10` | 2026-05-04 | Auto-launch Spotify when no Connect device + bounce-back via Activity.startActivity | "No Spotify device found" error blocked playback when Spotify was force-stopped | adb cold-start: track tap → Spotify launch → bounce → playback (~2.4s end-to-end) | Initial success on warm-start |
| `eae67fa` | 2026-05-04 | Spotify-bounce two-attempt retry (250ms + 1250ms) with NEW_TASK + CLEAR_TOP + SINGLE_TOP | First-attempt bounce dropped intermittently; double attempt covers slow Spotify cold-start | adb verified | Success on default-bucket; failed on restricted-bucket |
| `8335640` | 2026-05-05 | Bounce via PendingIntent + cold-start resume guard (skip when spotifyState != null) + 3s sheet auto-dismiss | PendingIntent captures launch privileges at construction; cold-start resume was clobbering live Spotify mirror on tab navigation | adb tab-switch test | Cold-start resume guard verified; bounce still BAL_BLOCK on restricted device |
| `5d83eef` | 2026-05-05 | Full BAL opt-in chain (creator + sender ALLOW_BAL ActivityOptions) + SpotifyBounceService 10s foreground service + AlarmManager fallback | Logcat showed `balRequireOptInByPendingIntentCreator=true` + Freecess freezing our process | adb cold-start with both apps force-stopped | Coroutines kept running (foreground exemption working); ActivityTaskManager still rejected with `BAL_BLOCK` due to Samsung `balDontBringExistingBackgroundTaskStackToFg` policy — system-level block, not bypassable from app code |
| `559ee8c` | 2026-05-05 | Replace PresetReverb with EnvironmentalReverb (custom decay 1.1–9.0s, reverbLevel up to +2000) | User reported reverb inaudible; PresetReverb's fixed presets cap reverbLevel; EnvironmentalReverb exposes raw knobs | Code change only; user-side audio test | Compiled; user has not reported back on audibility |
| `d93b4c9` | 2026-05-05 | Unified TextureView path (drop SurfaceView fast-path) + Compose Modifier.graphicsLayer for flip/rotation | Path-switching SurfaceView↔TextureView on each toggle caused detach/reattach + stale view dimensions in setTransform pivot → off-frame video and stuck-effect state | Code review: graphicsLayer pivot is layout-correct, no view.post race; cost ~80ms peak frame at 4K acceptable | Compiled; deployed; awaits user verification |
| `a6186a2` | 2026-05-05 | One-time AlertDialog warning before first Drive Picker launch ("Pick a FOLDER, not a file") | Friend tester tapped INTO a folder, saw "No documents" because Picker is folder-only filtered, concluded app couldn't see files | Compile-only sanity (no device) | Compiled; deployed via push only — not yet field-validated |

**Key abandoned approaches**:
- AlarmManager.set with PendingIntent for bounce (`5d83eef`): system-fired path doesn't carry sender BAL opt-in; replaced with same-process coroutine PendingIntent.send.
- PresetReverb attached to ExoPlayer audioSession (`5095f09`): aux effects require setAuxEffectInfo routing; replaced with global session 0 + AuxEffectInfo wiring.
- view.post + view.setTransform for video effects (`18f1bed`): pivot raced view measurement; replaced with Compose graphicsLayer.
- SAF DocumentFile-only Drive path (`2082c6c`): system picker on Z Fold landscape doesn't expose Drive source drawer; supplanted by drive.file OAuth + WebView Picker for Drive while SAF retained for non-Drive sources.
- Stricter source-chooser dialog (`ff369be`): user wanted Drive-only direct path with no source picker; chooser bypassed in `5213ea0` for Drive; SAF code retained but unreachable from Drive UI now.
