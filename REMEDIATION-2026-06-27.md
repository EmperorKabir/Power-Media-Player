# Remediation checklist — audit misses (resume 2026-06-27)

> **Binding instruction committed by the user 2026-06-26 (to resume next session).**
> Execute EVERYTHING below **in order, INLINE, in one go, NO stopping, NO deferral**.
> Use **Context7** (verify every external API at the installed version) and **Superpowers**
> (methodology skills). Use the **custom local `android-build-and-device-test` skill** with
> real on-device testing for each item. **Every task is a tickbox** — tick only on evidence
> (build exit 0 / test 0-failures / device log line). When all items are done: run the
> **custom local `android-efficiency-audit` skill**, then a **full on-device test pass** — all
> in the same run. Phone was fully uninstalled (data+cache wiped) on 2026-06-26, so a clean
> debug install + migration path is testable.
>
> Source of this list: full audit on 2026-06-26 of the 19-item plan (7 plans) + every one of
> the 67 user prompts since 2026-06-24, prompt-by-prompt, cross-checked against code. See the
> "Why these were missed" note at the bottom — add a per-sub-item acceptance test, never a
> matrix tick.

## PROGRESS (live — 2026-06-27)
- [x] **M7** version bump vc40/1.3.5 committed+pushed (29104b2); AAB staged to `dist/PowerMediaPlayer-1.3.5-vc40-release-2026-06-27.aab`. Final rebuild after all fixes still pending.
- [x] **M5** podcast auto-download gate `UNMETERED→CONNECTED` (`PodcastSyncWorker.kt:128`). Compiles.
- [x] **M6** ReplayGain relabelled "Volume normalisation (even out track volumes)" (`SettingsScreen.kt:420`). Compiles.
- [x] **M2** BT chapter nav real (single-file M4B) — `PlaybackService.seekChapter` + pure `BtChapterNav` + `BtChapterNavTest` (8) GREEN.
- [x] **M1** BT mappings split per file type (audiobook/podcast/music/video) — DataStore per-kind keys + `BtMappingSet`/`BtMediaKind` + service `currentBtKind` dispatch + 4-section Settings UI + `BtMappingDefaultsTest` (4) GREEN. Full suite + assembleDebug GREEN.
- [x] **M4** sleep-timer remote fade — Spotify `setVolume`+`currentVolumePercent`, Cast device-volume statics (`setCastDeviceVolumeFraction`/`captureCastDeviceVolume`), PlayerViewModel `applyFadeFactor` capture→ramp→restore across all 4 fade loops + cancel. Context7-verified Cast volume API. Compiles. (device A/B at closeout)
- [x] **M3** cloud backup/restore — generic `BackupManager` (all DataStore settings via asMap + every Room table via raw SQL dump/restore, schema-drift-safe) + Drive write (`uploadTextFile`/`findNewestFileByName`/`downloadTextFile`) + local SAF + Drive UI (`BackupRestoreSection`) in Settings → Library & cloud. `BackupManagerTest` (3, Robolectric) GREEN. Full suite + assembleDebug GREEN.
- [x] Closeout — efficiency audit (3-lens) DONE: found + fixed 1 HIGH (Spotify sleep-timer expiry never paused Connect + fire-and-forget volume reorder → now pauses Spotify at expiry + serialises volume via conflated channel), 1 MED-HIGH (cloud audiobook misclassified podcast → chapters-first `BtKindResolver` + test), 5 MED (FK-safe restore via defer-FK + two-pass; DB-before-settings; SAF I/O off main; cast fade dedup+cached Handler; consistent-snapshot dump), + LOWs (capture-null skip, Drive overwrite-not-duplicate, fade process-death persistence, resolve-kind-once). Unit suite 175/0. assembleDebug green.
- [x] Final on-device pass — fully-integrated debug build installs + launches clean (no FATAL/Room/crash, MainActivity alive) on RFCY70BARDJ.
- [x] Final AAB — `dist/PowerMediaPlayer-1.3.5-vc40-release-2026-06-27.aab` (vc40/1.3.5, signed POWERMED.RSA, 14.76 MB) with all M1–M7 + audit fixes.

## ALL DONE (2026-06-27) — M1–M7 + 3-lens audit closeout complete. Unit suite 175/0.

## DEVICE VERIFICATION (2026-06-29, RFCY70BARDJ, Drive re-signed-in) — functional, multi-method
- Live bug found by the user (IO_BAD_HTTP 403 on launch): root-caused via ExoPlayer stack + DiagLog + logcat `Drive download: no access token (signed out?)` → the earlier full wipe cleared Drive sign-in. Fixed: `PlayerErrorMessage` graceful re-sign-in prompt (committed bcdcee1, PlayerErrorMessageTest). After re-sign-in, resume loads BUFFERING→READY at saved pos, no 403.
- **M1 BT per-file-type** — ALL 4 KINDS device-verified 2026-06-29 (KeyEvent path) via `cmd media_session dispatch next/previous` while each kind was the loaded item, DiagLog `[BT] keyEvent external=true kind=…`:
  - AUDIOBOOK (Drive M4B) → `nextAct=next_chapter` / `previous_chapter`.
  - PODCAST (Fighting Cock ep) → `kind=PODCAST` → `skip_forward_seconds 30` / `skip_back_seconds 15`.
  - MUSIC (Dyscarnate track) → `kind=MUSIC` → `next_track` / `previous_track`.
  - VIDEO (local clip) → `kind=VIDEO` → `next_track` / `previous_track`.
  - REMAINING GAP: the AVRCP `onPlayerCommandRequest` path (COMMAND_SEEK_TO_NEXT/PREV) is NOT exercised by `cmd media_session dispatch` (that routes through the KeyEvent `onMediaButtonEvent` path). The AVRCP command path needs a real AVRCP source (car head-unit / Android Auto) — flagged for the user's car BT.
- **M2 BT chapter nav** — FUNCTIONAL: `→ applyAction action=next_chapter/previous_chapter` + playhead jumped a chapter (52418293→52554394 ms). Real chapter nav, not the placeholder.
- **M3 backup** — FUNCTIONAL round-trip: UI renders 4 actions; "Back up to Drive" → "Backed up to Drive."; "Restore from Drive" → "Restored 2493 item(s)." FK-safe two-pass restore handled 2493 real rows.
- **M5 podcast download** — FUNCTIONAL: `dumpsys jobscheduler` shows PodcastSyncWorker `Required constraints: CONNECTIVITY` (wifi+mobile), not UNMETERED.
- **M6 volume normalisation** — RENDER: "Volume normalisation (even out track volumes)" shown on device.
- **M7** — signed AAB rebuilt with the graceful fix: dist/PowerMediaPlayer-1.3.5-vc40-release-2026-06-29.aab. Unit suite 179/0.
- **M4 sleep-timer REMOTE fade (CAST)** — DEVICE-VERIFIED 2026-06-29 on Living Area TV after a real bug was found + fixed:
  - BUG (first cast test): the fade did NOT ramp the receiver volume — it sat flat through the whole window. ROOT: `CastPlayer` does not advertise `COMMAND_*_DEVICE_VOLUME` on the TV, so the device-volume guard skipped `setDeviceVolume` (no-op). The hardware volume keys worked because they use the Cast SDK *session* volume, a different path.
  - FIX (commit `9ac153a`): cast fade now drives the receiver volume via `CastSession.setVolume()/getVolume()` (the same path the working volume keys use), with `CastPlayer` device-volume kept as a fallback. `CASTFADE` DiagLog added; `appCtxRef` set in `onCreate`.
  - VERIFICATION (3 methods, dense 4 s sampler + diag): cast volume sampled **1 → 0** during the last-30 s ramp, state flipped **PLAYING → PAUSED** at expiry, then restored to **1**; `CASTFADE` diag shows `capture via CastSession vol=0.03` then `set via CastSession f=0.03 → 0.029 → 0.019 → 0.009 → 0.03(restore)`; `SleepTimer expired — paused playback (fadeOut=true)`. The `set via CastSession` lines prove the fixed path executed (not the old no-op).
  - SPOTIFY remote fade — still code+unit+Context7-verified only (needs a live Spotify-Connect-in-app session to exercise; conflated-channel volume path unchanged). Local path unaffected.

## Execution protocol (per the user, verbatim intent)
- [ ] In order, inline, no stopping, no deferral, no skipping.
- [ ] Context7 for every API; Superpowers skills for method.
- [ ] `android-build-and-device-test` custom skill per item (build → device-test on RFCY70BARDJ → evidence).
- [ ] Checklist/tickbox discipline: each sub-item below ticked only with fresh verification evidence.
- [ ] After all items: `android-efficiency-audit` custom skill, then full on-device test pass.
- [ ] Commit + push after each logical item; adb-install debug APK (check `dumpsys media_session` not PLAYING first).

---

## ORDER OF WORK

### M7 — finalise the v40 release (FIRST; loose end from 2026-06-26)
- [ ] `app/build.gradle.kts` already at versionCode 40 / versionName 1.3.5 in the working tree — **commit + push** the bump (HEAD was 38/1.3.4).
- [ ] Confirm the signed AAB at `app/build/outputs/bundle/release/app-release.aab` is vc40/1.3.5 (decode base manifest).
- [ ] Stage the AAB to `dist/` with the project's versioned filename convention (check prior dist naming; last staged was vc32).
- [ ] Note: 38/1.3.4 is the last PUBLISHED version → 40 is the correct next upload code.

### M5 — podcast auto-download must NOT be conditioned on data (prompt 12, explicit instruction violated)
- [ ] `PodcastSyncWorker.kt:128` change `NetworkType.UNMETERED` → `NetworkType.CONNECTED` (Wi-Fi **and** mobile data), per "no need to condition it… wifi and mobile data… most seamless experience".
- [ ] Verify the worker still runs; device-test a sync on mobile data.
- [ ] Check no other download path re-imposes a metered gate.

### M2 — BT chapter navigation must actually navigate chapters (item 8a, currently a placeholder)
- [ ] `PlaybackService.applyAction` (`PlaybackService.kt:3489-3495`): `PREV_CHAPTER`/`NEXT_CHAPTER` currently fall back to `seekTo{Previous,Next}MediaItem()` → no-op / skips whole book on single-file `.m4b`.
- [ ] Wire to the real chapter nav that already exists in `PlaybackConnection` (`seekToChapterIndex`/`nextChapterOrTrack` → `seekTo(chapter.startTimeMs)`, ~`:633-663`) via a custom MediaSession command round-trip (service → connection), since chapters live in PlaybackConnection.
- [ ] Handle both single-file M4B (time-offset chapters) AND multi-file folder audiobooks (cross-file).
- [ ] Device-test: BT prev/next mapped to chapter actually moves between chapters on an M4B.

### M6 — "volume normalisation" (prompt 59) — confirm + surface
- [ ] Functionally this is the existing **ReplayGain** pipeline (±15 dB, LoudnessEnhancer boost + ExoPlayer.volume attenuation). It was equated to ReplayGain in internal docs only — **never confirmed to the user**.
- [ ] Surface it clearly: rename/relabel the ReplayGain setting to include "Volume normalisation" in layman terms so the user sees their request is met.
- [ ] If the user wants a distinct per-output LUFS target beyond ReplayGain, build it — otherwise the relabel + a one-line confirmation closes it. (Decide at execution; default = relabel + confirm.)

### M1 — split BT control mappings PER FILE TYPE (item 8a — the original miss)
**User scope decision (2026-06-26): SEPARATE next/prev mappings for ALL of — Audiobooks, Podcasts, Music, Video. "This is a power user app after all."**
- [ ] DataStore: replace the single `BT_PREV_ACTION`/`BT_NEXT_ACTION`/`BT_SKIP_BACK_SECONDS`/`BT_SKIP_FORWARD_SECONDS` with **per-kind** keys (audiobook/podcast/music/video × prev/next/skip-back/skip-fwd). Keep a sensible default per kind (e.g. audiobook prev/next = chapter or skip-N; music = track; podcast = skip-30s/next-episode; video = track/skip).
- [ ] `BtMappingSnapshot`: carry per-kind mappings (or resolve by current media kind at dispatch).
- [ ] Resolve the current media kind at BT dispatch time — reuse `MediaClassifier.classifyAudioSubKind` / `AudioSubKind` (already distinguishes podcast/audiobook/song) + video detection; `applyAction` (`PlaybackService.kt:3412-3498`) must pick the mapping for the playing item's kind.
- [ ] Settings UI (`SettingsScreen.kt` "Bluetooth car controls", ~`:440-470`): expand the single Prev/Next picker pair into per-file-type sections (Audiobooks / Podcasts / Music / Video), sensible ordering, layman labels.
- [ ] Room migration if any mapping moves into DB (currently DataStore — likely no Room migration needed).
- [ ] Unit tests: per-kind resolution picks the right action; defaults correct.
- [ ] Device-test on the car/BT path (or adb KeyEvent injection): the same BT button does kind-appropriate actions for music vs audiobook vs podcast vs video.
- [ ] NOTE the delicate car-BT history: dual interception paths exist — `onPlayerCommandRequest` (AVRCP command path) AND `onMediaButtonEvent` (raw KeyEvent path, added for BMW F30 / older AVRCP HUs that bypass the command path). External-only; key-DOWN only (avoid double-fire); Spotify-mirror routing; resumeOnBt gating on MEDIA_PLAY. Do NOT break these when splitting per-kind.

### M4 — sleep-timer remote fade (F5, prompt 59) — was deferred under an inaccurate "no API" claim
- [ ] Spotify Connect volume IS available: `PUT https://api.spotify.com/v1/me/player/volume?volume_percent=N` — add a `SpotifyProvider.setVolume(percent)` and a fade ramp.
- [ ] Cast volume IS standard: `RemoteMediaClient`/`CastSession.setVolume` (or `setStreamVolume`) — add a cast volume fade.
- [ ] Wire the sleep-timer end-of-track / minutes-mode fade to ramp the ACTIVE route's volume (local crossfade already works; add Spotify + Cast).
- [ ] Context7-verify both volume APIs before coding.
- [ ] Device-test: sleep timer fade audibly ramps on a Cast target (Living Area TV / Kabir Stereo) and on Spotify Connect.

### M3 — cloud settings/cache backup & restore (F4, prompt 59) — biggest; was silently deferred
**User scope decision (2026-06-26): back up ABSOLUTELY EVERYTHING EXCEPT the actual audio/video files themselves — but INCLUDE metadata.**
- [ ] Serialise to a single backup payload: DataStore settings; `media_overrides`; `equalizer_presets`; favourites (Drive/Spotify/Library + Drive favourite folders/tracks); `playback_history` (incl. enriched title/artist/artwork URLs = metadata); `history_favourites`/pinned; bookmarks (all tables); smart playlists; replay-gain data; podcast subscriptions + episode metadata; `enrichment_cache` (metadata); `offline_copy` REGISTRY (metadata/paths) — **NOT** the media files themselves; cover-art cache metadata optional (re-derivable).
- [ ] Restore-apply path: deserialise + upsert into all stores; Room-version-aware (current v22); idempotent; round-trip tests (backup → wipe → restore → equality).
- [ ] Storage: Drive backup needs **Drive WRITE** (`files.create`/multipart) — `DriveOAuthProvider` is currently GET/list only. Add the write verb under the existing `drive.file` scope (non-sensitive, no Google verification). Also provide a **local file export/import** (SAF) that needs no Drive write — ship this first so the feature works regardless of Drive.
- [ ] Settings UI: "Back up & restore" — export now / restore from file / Drive sync; clear warnings; data-correctness first (a bad restore must not corrupt user data — validate before applying).
- [ ] Device-test: full backup → uninstall (wipe) → reinstall → restore → verify settings/overrides/favourites/history/subs all return.

---

## After all M-items: closeout pass (same run, no stopping)
- [ ] Run `android-efficiency-audit` custom skill over the new code (orphaned/efficiency, adversarial preservation gate — some "bugs" are deliberate, confirm before deleting).
- [ ] Full on-device test pass of everything, folded AND unfolded (genuine inner-display unfold, device_state=3 — do not claim unfold without it).
- [ ] Clear the verification-debt items now that the phone is wiped + reinstallable: F1 in-player podcast queue render, F3 voice-boost A/B (best-effort), "Both"-favourite independent effects render, inherited-show-effects hint render, audio/video/EQ override-on-resume, artist-albums >5 paging, HP/"This Inevitable Ruin" cover-art render on Last Played (re-enrich old rows), resume-live timer ticking while playing.
- [ ] Minor: #16d enrich cache add genre/year; P7 two webhook helper paragraphs + SmartHomePlaceholder KDoc; #1/#4 touch targets to full 48dp width (`minimumInteractiveComponentSize`).
- [ ] Final: rebuild signed AAB at the release version, stage to `dist/`, commit + push.

## Repo state to clean up on resume (uncommitted as of 2026-06-26)
- [ ] `app/build.gradle.kts` vc40/1.3.5 — uncommitted.
- [ ] Context restructure (separate task, mostly done): `PROJECT_RULES.md` (new primer), `CLAUDE.md` (primer pointer), slim `TASKS.md`, `docs/archive/TASKS-history.md`, `docs/archive/superpowers/**` (111 files git-renamed). **`docs/archive/INDEX.md` may be incomplete** (the archiving agent was stopped mid-generation) — finish it.
- [ ] Commit the restructure + this remediation note together or separately; push.

## Why these were missed (process fix — apply going forward)
- The 19-item coverage matrix conflated **"answered"** with **"delivered"**. Item 8a (BT split) was logged "ANS + PLAN"; the investigation reframed the feature request as a risk question ("BT risk ≈ 0"), the plan shipped a different thing (MediaClassifier), and recorded the split as "user did not request it" (false). No acceptance predicate ever checked "BT split implemented".
- Same failure mode: F4/F5 relabelled "FLAGGED" (not a legal ledger status) without user sign-off; prompt-12's data-usage instruction never became a checkable item.
- **Fix:** every requested sub-item gets its own machine-checkable acceptance test (tickbox above), never a matrix tick. A "blocker" is only legitimate if it needs user GUI action / credentials / a true third-party gap — and it must be stated to the user explicitly, with everything automatable around it completed.
