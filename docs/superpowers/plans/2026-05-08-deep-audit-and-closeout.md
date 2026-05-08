# 2026-05-08 — Deep audit + close-out plan

> **Source:** Today's deep audit pass against the locked plan
> `2026-05-07-info-icons-crossfade-power-features.md` and the working
> plan `2026-05-08-pending-work-and-tests.md`. Honest accounting of
> gaps, partial-ships, unilateral decisions, contradictions, and
> untested paths. Implementation order at the bottom.

---

## §A — Items completely missing (claimed shipped but ABSENT)

### A1. Crossfade engine §B3 — true 2-player overlap is a stub
- A1.1 Mid-crossfade interactions (Pause = both pause synchronously, Scrub = abort + jump A, Next = abort + B becomes A, Prev = abort + jump A back, Stop = both stop). Transport buttons not routed through `CrossfadeController`. `abort()` exposed but never called.
- A1.2 Audio-effect chain handover (4 effects hot-swap at midpoint with 50 ms cubic smoothing). Not implemented.
- A1.3 Metadata handoff at crossfade START. Not implemented.
- A1.4 Cast / Spotify activation releases playerB + master auto-greys. Not implemented.
- A1.5 Shared `AudioFocusRequest` between both players. Not implemented.
- A1.6 `effectiveCrossfadeEnabled: StateFlow<Boolean>` auto-revert + Snackbar. Not implemented.
- A1.7 Audiobook crossfade greying with "Allow on audiobooks" override. Not implemented.
- A1.8 Pre-fade trigger slider — UI present, engine never reads it.
- A1.9 Skip-silence toggle — UI present, no trim ever performed.

### A2. C9 OpenSubtitles — half the spec missing
- A2.1 Baked API key in BuildConfig. Shipped: user-entered key.
- A2.2 Languages multi-select chip set. Hard-coded "en".
- A2.3 Match-by-hash / Filename radio. Filename only.
- A2.4 Save next to video / Save in app cache radio. Always cache.
- A2.5 Override existing .srt switch. Not exposed.
- A2.6 SRT actually attached to player MediaItem. **Critical** — fetcher writes SRT to disk; player never picks it up.
- A2.7 Sign-in opens browser to opensubtitles.com sign-up. Form-only sign-in.
- A2.8 Credentials in EncryptedSharedPreferences. Plain DataStore.

### A3. C10 Podcasts — audio download path absent
- A3.1 Placement. Spec: Cloud tab below Spotify favourites. Shipped: Settings.
- A3.2 iTunes / Apple-Podcasts directory search. Not done.
- A3.3 Per-show settings (auto-download / retention / notify). Not done.
- A3.4 Auto-download to fixed folder `Movies/PowerMediaPlayer/podcasts/<showSlug>/`. Worker upserts metadata only; **no audio download**.

### A4. C12 Wake-up alarm — major UI gaps
- A4.1 Editor exposes only hour, minute, days, mediaUri. Missing: startVolumePct, endVolumePct, rampSeconds, holdMinutes, windDownSeconds, snoozeEnabled, snoozeMinutes, maxSnoozes, snoozeRestartFromStart, skipNextCount, stopMethod, vibration.
- A4.2 Snooze "continue ramp / restart from start" — `snoozeRestartFromStart` parsed/serialised but never read at fire time.
- A4.3 Visual missing: cover art square, day-of-week under time, "Auto-stops in 28 min" countdown, large rounded 70%-width Snooze, swipe-to-confirm Stop, edge-to-edge dark theme.
- A4.4 Onboarding banner placement — spec said "first alarm save". Shipped: every AlarmsSheet open.
- A4.5 Track / playlist / smart-playlist source — picker lists bookmarks/favourites/recents only.

### A5. §A3 Bookmark mirror universalisation — path 3 missing
- Path 1 (cold-start): ✅
- Path 2 (Spotify mirror first-emit): ✅
- A5.1 Path 3 (notification-tap resume after process kill): partial — overlaps with cold-start branch but not its own evidence-locked branch.

### A6. C13 Headphone-aware EQ — wrong shape
- A6.1 Spec: per-paired-device dropdown at bottom of EQ tab; lists `BluetoothAdapter.bondedDevices`.
- A6.2 Shipped: single global preset under Settings.

### A7. C20 Widget — deep-link + foldable resize missing
- A7.1 Tap should deep-link to Player tab. Shipped: tap → MainActivity default tab.
- A7.2 `useUpdateForCollections="true"` not in `widget_now_playing_info.xml`.
- A7.3 Large layout missing cover art.

### A8. C28 Drive offline — three big gaps
- A8.1 "Downloaded" badge on cloud row. Not rendered.
- A8.2 Offline storage limit setting. Not done.
- A8.3 LRU eviction. Not done.

### A9. C25 long-press menu — Edit tags unwired
- A9.1 `onEditTags` slot exists; never passed; no tag editor UI.

### A10. C7 per-file overrides — 2 axes inert + auto-clear unwired
- A10.1 `replayGainMode` column never read at apply time.
- A10.2 `eqPresetId` column never read at apply time.
- A10.3 `clearOverridesIfUnfavourited` / `clearOverridesForUri` never called from un-favourite / unpin paths.

### A11. C17 metadata enrichment — UI controls absent
- A11.1 Discogs alternative not implemented.
- A11.2 Sub-toggles "Fetch missing artwork / year / genre" not exposed.
- A11.3 "Apply to: All files / Only files with no embedded tags" not exposed.
- A11.4 Cache results not implemented.

### A12. C18 ReplayGain scanner — Track / Album mode toggle absent
- A12.1 -14 LUFS target locked; shipped -18 LUFS.
- A12.2 `replayGainAutoScan` setting is dead code.

### A13. §C16 Cloud tab refresh-on-open — not done.

### A14. §D Settings 15-section reorg — only 4 sections moved.

### A15. §J Phase 11 Cast bug investigation — 8 of 9 tasks not run.

### A16. §E Spotify Connect E2E test — 0 of 10 steps run.

### A17. §I.5 release APK + sideload check — never run.

### A18. §I.2 Phase regression suite — never run.

---

## §B — Items partially shipped (data / behaviour mismatch)

- B1 C7 indicator chip lights for inert axes (replayGainMode / eqPresetId).
- B2 **CONTRADICTION** — END_OF_CHAPTER reads `chapter_starts_ms`; M4bChapterParser writes `chapter_count` / `chapter_start_<i>` / `chapter_end_<i>` / `chapter_title_<i>`. END_OF_CHAPTER always falls back to track-end on M4B files.
- B3 SleepTimerState shape — enum + dispatcher shipped, sealed-class hierarchy locked but not built.
- B4 Theme accent — 45 references to `Teal50`/`Teal100`/…/`Teal900`/`TealBright` are still hard-coded; secondary accent HSL hue rotation not done.
- B5 C20 widget Large layout — no cover art; spec wants "cover art + title/artist + transport row".
- B6 OpenSubtitles client — login + search + download + save SRT works; player never picks up the SRT.
- B7 PodcastSyncWorker — fetches metadata; no audio download.

---

## §C — Decisions taken without permission

- C1 OpenSubtitles API key — user-supplied vs baked.
- C2 Headphone-aware EQ — single global vs per-paired-device.
- C3 C28 storage — DataStore Set vs Room `offline_copy` table.
- C4 ReplayGain target — -18 LUFS vs locked -14 LUFS.
- C5 SleepTimerState — enum + ad-hoc state vs sealed class.
- C6 Snooze ID generation — negative-ID with millisecond modulo; never validated against PendingIntent collision space.
- C7 Theme picker — `MutableState<Color>` global accessor vs CompositionLocal.
- C8 Smart playlist editor — form + raw-JSON escape hatch (raw JSON wasn't asked for).
- C9 Auto-hide popup defaults flipped to "Never" — **user-approved**, listed for completeness.
- C10 Settings placement — Smart playlists / Podcasts / OpenSubtitles all under Settings (vs Library / Cloud / Video respectively per §D).
- C11 MediaOverrideRepository polling on Main every 750 ms vs subscribing to Player.Listener.
- C12 Cloud Override-* flip-flopped (deferred → wired SAF-only) without confirming.
- C13 Skip-silence trim — UI without engine.
- C14 Pre-fade trigger — UI without engine.
- C15 Indicator chip wording: "Custom audio + video + speed for this file" vs locked "Custom audio/video/speed for this file".

---

## §D — Contradictions / silently-broken paths

- D1 END_OF_CHAPTER reads non-existent extras key (B2).
- D2 `TrackContextSheet.kt` doc comment lies — says "Edit tags / Override*: leave null until later phases land them", but Override-* are wired now.
- D3 AudioFocus listener silently swallows requestAudioFocus failure with no retry → playback may proceed without focus owner.
- D4 Override clear hooks defined but never invoked (A10.3).
- D5 `replayGainAutoScan` is dead code.
- D6 `crossfadeSkipSilence` is dead code.
- D7 `crossfadePreFadeTriggerS` is dead code.
- D8 `chapter_starts_ms` extras key not written anywhere.
- D9 Phase-4 curves applied twice (once in `applyCrossfadeTick`, again in `CrossfadeController.curveFactors`); not energy-coherent.
- D10 `MediaOverrideRepository` polls every 750 ms forever even when DB empty — no early-exit.
- D11 `WorkManager.enqueueIfNeeded(KEEP)` fires every cold start.
- D12 `bookmarks` table — `observeAll()` orders by `createdAtMs`; column may not be indexed.

---

## §E — Items not tested

- E1 Live alarm fire (set/lock/observe ramp/snooze/math/shake).
- E2 Headphone plug/unplug EQ swap.
- E3 AudioFocus per-scenario (call/notification/other-media).
- E4 Theme picker live recolour.
- E5 Smart-playlist rail tap → resolve → play.
- E6 Drive offline copy round-trip (Wi-Fi off).
- E7 OpenSubtitles sign-in against live API.
- E8 Podcast feed add → episodes populate → tap to play.
- E9 C7 override sliders applying live.
- E10 C20 widget post-3-size-split re-add.
- E11 C25 Cloud Override-* on a real SAF Drive favourite.
- E12 C12 alarm DND override.
- E13 Cast — Phase 11 bisect.
- E14 Spotify Connect — Phase 10 E2E.
- E15 Release APK build.
- E16 Test coverage — 16 unit tests cover ~0.3% of code; no instrumented suite.

---

## §F — Stylistic drifts

- F1 Plan §-1 audit table claims 100% Tier-1 closure — misrepresents reality.
- F2 Commit messages "verified clean" / "verified on Z Fold 6" lowered the bar to "no FATAL on launch".
- F3 `exportSchema = false` blocks migration column-by-column tests.
- F4 Two parallel data paths (DataStore set + planned Room table) for both `OFFLINE_DRIVE_PAIRS` and `SCHEDULED_ALARMS`.
- F5 Mixed error-surfacing (Diag.w / runCatching swallow / printStackTrace).
- F6 `enqueueIfNeeded(this)` synchronous in `Application.onCreate` — latent crash on slower devices.

---

## §G — Implementation order (this session)

Priorities chosen for **highest user-visible value per minute** + closing the most-egregious silent-broken paths. Each item builds + installs on emulator + Z Fold 6 + checked via logcat before the next.

### G1 — Silent-broken-path closeouts (must-fix)
1. **D1/B2** END_OF_CHAPTER key rename → reads `chapter_start_<i>` series the parser actually writes. ☑ acceptance: setting END_OF_CHAPTER on an M4B pauses at the next chapter boundary, not file end.
2. **A10.3 / D4** Wire `clearOverridesIfUnfavourited` / `clearOverridesForUri` into the un-favourite / unpin code paths so override rows don't survive the gating change.
3. **C4 / A12.1** ReplayGain LUFS target fixed at -14 LUFS (locked spec value).
4. **D2** TrackContextSheet stale doc-comment refresh.

### G2 — Dead-code → live-code conversions
5. **D5** `replayGainAutoScan` → MediaScanner triggers `ReplayGainScanner.scan()` after a deep-scan import when toggle on.
6. **A2.6 / B6** OpenSubtitles SRT auto-attach: when `SubtitleAutoFetcher.fetchIfNeeded` returns a File, rebuild MediaItem with `subtitleConfigurations = listOf(...)` so the player surfaces the track in the Track Selection menu.
7. **A7.2 / B5** Widget — add `useUpdateForCollections="true"` + cover-art `setImageViewBitmap` for Large layout.
8. **A7.1** Widget — tap deep-links to Player tab via Intent extra `EXTRA_OPEN_TAB=player`.

### G3 — Bigger UI gaps
9. **A4.1 / A4.2** Alarm editor exposes every spec field (ramp / hold / wind-down / snooze / skipNextCount / stopMethod / vibration). Honour `snoozeRestartFromStart` at fire time.
10. **A6 / C2** Headphone EQ moved to bottom of EQ tab with per-paired-device picker driven by `BluetoothAdapter.bondedDevices`.

### G4 — Tests
11. Unit tests for: end-of-chapter sleep timer (mocked extras), ReplayGain target -14, override clear-on-unfavourite hook.

### G5 — Live verification (emulator)
- `emulator-5554` (Medium_Phone_API_36.0) booted; install + cold-start logcat after each batch above.

---

## §H — Items deliberately deferred this session

- A1 (true 2-player crossfade engine — §B3 full spec). Risky audio-pipeline rewrite; would need its own session.
- A3 / A4 / A8 / A11 / A14 / A15 / A16 / A17 / A18 — out of scope this turn; all logged in §A above.
- A2.1 / A2.7 / A2.8 — baked API key + browser sign-in + EncryptedSharedPreferences need product-level decisions.

These remain on the to-do list — not silently deferred. End-of-turn summary will name them again so the gap stays visible.
