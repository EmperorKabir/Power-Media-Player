# Pre-Play-Store Audit — 2026-05-06

Three parallel auditors covered: (a) orphaned/dead code, (b) redundant code & quality, (c) Play Store readiness + perf. Each finding was grep-verified before any action. Below: what was acted on, what's deferred and needs your call, what's a known build-toolchain issue.

---

## Acted on (this commit)

### Dead code removed (3 items, all grep-verified zero callers)
- `cloud/SpotifyProvider.kt:877` — `BOUNCE_PI_REQUEST_CODE = 0x10A1`. Orphan from the pre-bridge Spotify bounce-back; the bridge fix at `0549ca8` removed the only caller.
- `data/db/dao/EqualizerPresetDao.kt:21` — `getDefaultPresets()`.
- `data/db/dao/EqualizerPresetDao.kt:24` — `getUserPresets()`. `getAllPresets()` is the only DAO query the EqualizerViewModel uses; the WHERE-isDefault variants were never called.

### Logging gate (116 sites)
- New `util/Diag.kt` — inline `Diag.i/w/e/d` wrappers gated by `BuildConfig.DEBUG`. R8 strips each call entirely in release builds (no logd IPC, no tag string in DEX).
- All 116 `android.util.Log.*` call-sites across 15 files now route through `Diag.*`. This was a sed-based mechanical replacement; verified via grep that the only remaining `android.util.Log.` reference is inside `Diag.kt` itself.
- Why this matters: pre-fix, every `PMP_DIAG`, `PMP_PIP`, `PowerMediaPlayer` log line would emit unconditionally in production. Worst case for a busy session (`PlaybackConnection` position-tick + skip events): hundreds of log lines per minute, all of which leak diagnostic info to anyone with `adb logcat` access.

### ProGuard rules expanded
- `app/proguard-rules.pro` — added keeps for **Coil 3** (`coil3.**`, `io.coil_kt.**`), **Room** (`androidx.room.**`, `RoomDatabase` subtypes, `@Entity`), **OkHttp**, **AppAuth**, and **Cast framework**. Pre-fix: only Media3, Hilt, Gson, and Room entities had keeps; R8 minification (already enabled) could have stripped reflectively-loaded classes from Coil/OkHttp/AppAuth and produced runtime crashes only visible in the release build.

### `data_extraction_rules.xml`
- New `res/xml/data_extraction_rules.xml` referenced from the manifest. Allows auto-backup of Room db (bookmarks / favourites / history) but **excludes**:
  - `datastore/settings.preferences_pb` — contains the Spotify PKCE token + Drive picked-folder IDs; restoring on a new device produces stale invalid tokens.
  - `app_webview/` + `webview.db` — temporary auth state from Drive Picker.
- `android:allowBackup="true"` retained; without the rules file, Android 12+ would have warned at install time.

---

## Deferred — needs your call

### High severity: database migration strategy
- `di/AppModule.kt:41` — `.fallbackToDestructiveMigration(true)`. **At every Room schema bump, every user loses all their bookmarks, favourites, and history.** Currently at v7 (per `project_operational_state.md`).
- For a friends-tier release this was acceptable. For Play Store with real users, it's hostile.
- Two paths:
  - **(a) Document v1.0 = fresh start.** Keep destructive in code; release notes say "first Play Store release; data does not migrate from beta builds." Zero code change. Acceptable if v1 is the first public release.
  - **(b) Implement explicit migrations from v7 onwards.** Every future schema change requires writing a `Migration` object. This is the production-safe path for a long-lived app.
- I did not change anything here — pick your path and tell me.

### Medium severity: hardcoded user-facing strings
- `res/values/strings.xml` has exactly **one** string (`app_name`). All UI labels (tab names, button text, error messages, settings descriptions) are inline literals in Composables.
- Fine for English-only ship; blocks any future localisation. ~150+ literals to extract — a multi-day task.
- Recommend: ship as-is for v1.0, file a "localisation" issue for v1.1. Tell me if you want me to start the extraction now.

### Medium severity: code-quality refactors (deferred to avoid scope creep before release)
- `LibraryViewModel.scanAudioFiles()` and `scanVideoFiles()` are 75% duplicate (audit citation: lines 387-446 vs 448-497). Could share a parameterised helper.
- `MediaItem.Builder` pattern repeated ≥ 4 times across `LibraryViewModel`, `PlayerViewModel`, `LastPlayedViewModel`, `CloudViewModel`. Could be a single `buildMediaItem(...)` helper.
- Magic strings: `"video/"`, `"is_video_hint"`, `"LOCAL"/"DRIVE"/"SPOTIFY"` source codes — should be constants/enums.
- Mixed `Favorite` (American) vs `Favourite` (British) DAO/Entity names. Renaming touches the Room schema → migration concern → coupled to (a).
- Four files exceed 1000 lines (`SpotifyProvider`, `PlayerScreen`, `CloudBrowserScreen`, `PlayerViewModel`). Refactoring to split is a separate plan, not pre-release scope.
- None of these are *broken* — they're DRY/readability improvements. Will cause no functional difference if shipped as-is.

---

## Known infrastructure issue (independent of audit)

### `:app:bundleRelease` / `:app:assembleRelease` fails on `collectReleaseDependencies`
- Reproducible on stock `main` (verified by stashing my changes; same failure).
- Error: `Problems reading data from Binary store in C:\Users\Kabir\.gradle\.tmp\gradle*.bin`.
- Toolchain: Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.2.21 (very recent versions). This is a known compatibility issue.
- Workarounds to try (one of these usually unblocks it):
  - Downgrade AGP from 9.1.0 to 8.7.x — most-tested combination with Gradle 9.x.
  - Upgrade Gradle wrapper to a newer 9.x patch when available.
  - Pin AGP to a specific known-working version.
- **This blocks Play Store upload.** You cannot generate the AAB until this is fixed. Recommend addressing before any other release work.

---

## Files modified

```
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/xml/data_extraction_rules.xml                                     (new)
app/src/main/java/com/powermediaplayer/util/Diag.kt                                (new)
app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt                    (dead code + Log→Diag)
app/src/main/java/com/powermediaplayer/data/db/dao/EqualizerPresetDao.kt           (dead code)
app/src/main/java/com/powermediaplayer/MainActivity.kt                             (Log→Diag)
app/src/main/java/com/powermediaplayer/audio/EqualizerEffectController.kt          (Log→Diag)
app/src/main/java/com/powermediaplayer/cloud/DriveOAuthProvider.kt                 (Log→Diag)
app/src/main/java/com/powermediaplayer/cloud/GoogleDriveProvider.kt                (Log→Diag)
app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt               (Log→Diag)
app/src/main/java/com/powermediaplayer/service/PlaybackService.kt                  (Log→Diag)
app/src/main/java/com/powermediaplayer/service/SpotifyBounceBridgeActivity.kt     (Log→Diag)
app/src/main/java/com/powermediaplayer/service/SpotifyBounceService.kt             (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt                  (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt              (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt                (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/player/components/CastButton.kt          (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/player/components/CoverArtBackground.kt  (Log→Diag)
app/src/main/java/com/powermediaplayer/ui/player/components/PlaybackControls.kt    (Log→Diag)
docs/superpowers/audits/2026-05-06-pre-play-store-audit.md                         (this file)
```
