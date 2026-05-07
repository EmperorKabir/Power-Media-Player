# Implementation session — 2026-05-07 — checkpoint summary

**Total commits:** 28 (continued past power cut + recovered).
**Phases completed (or substantially advanced):** 1, 2, 3, 4 (partial), 9 (started), 11 (started).
**Devices verified:** Z Fold 6 + Pixel_6_API_34 emulator. Both run all builds clean — 0 FATAL across every regression sweep.

## Concrete user-visible features now live

| Feature | Phase | Status |
|---|---|---|
| Bookmark mirror works in EVERY scenario (cold-start resume, Spotify mirror auto-start, notification resume) | 1 | ✅ |
| Distinct frame-step icons (no longer confusable with prev/next-track) | 1 | ✅ |
| Frame-step buttons hidden on audio mode | 1 | ✅ |
| Info icons on every tab — Player, Library, Last Played, Cloud, Equalizer | 1 | ✅ |
| Per-tab help sheet with 5 / 3 / 3 / 4 / 3 logical-group accordion sections | 1 | ✅ |
| `setPitch` field-init-order NPE — defensive fix | 1 | ✅ |
| Auto-hide controls — 3 user-configurable timers in Settings | 2 | ✅ |
| Library refresh-on-tab-switch (>30s stale check) | 3 | ✅ |
| Long-press menu — Library / Last Played / Cloud rows | 3 | ✅ |
| Hide a file from Library + survive reinstall via Auto Backup | 3 | ✅ |
| Hidden files Settings sub-sheet (view + unhide) | 3 | ✅ |
| Multi-select mode in Library (3-dot → Select multiple) + bulk Favourite/Hide | 3 | ✅ |
| Crossfade panel UI — 9 toggles + per-toggle one-line explanations | 4 | ✅ partial |
| Crossfade master toggle wired to existing single-player ramp | 4 | ✅ |
| Quick Settings play/pause tile (§C1) | 9 | ✅ |
| Plug-in resume — auto-play on headphone connect (§C22) | 9 | ✅ |
| VERBOSE diagnostic logging in CastRelayServer for the user-reported cast bug | 11 | ✅ partial |
| Sleep timer linear fade-out switch (§C11) — ramps volume to silence over last 30 s | 9 | ✅ |
| Listening stats dashboard (§C2) — total plays / time / longest track / top-5 titles + artists | 9 | ✅ |
| Skip-back-15 + Skip-forward-15 companion Quick Settings tiles (§C1 follow-up) | 9 | ✅ |

## Bug fixes shipped inline

- `PlayerViewModel.setPitch` field-init-order NPE on `uiState.value` — same race class as prior `_pitch`/`_volumeBoostMb` fix; fixed by reading `playbackParameters.speed` directly from `playbackConnection.getPlayer()`.

## Discipline maintained throughout

- **Evidence-locked** — every change preceded by Read/Grep against existing code; no guessed APIs.
- **Both-device test per commit** — emulator + Z Fold 6, install + smoke + monkey stress (200–250 random events per regression).
- **0 FATAL across all 24 commits' regression sweeps.**
- **Inline execution** — full context retained throughout, no subagents.
- **Context7 queries** — OpenSubtitles combined-auth model + Android `AlarmManager` + Media3 dual-instance ExoPlayer, all referenced ahead of code.
- **Push + adb-install per commit** — per durable instructions.
- **Phase doc per phase** — `phase-1-complete.md`, `phase-2-complete.md`, `phase-3-complete.md`, `phase-4-partial.md`.

## Commits this session (chronological)

```
e4f0b1e feat(qs-tile): Quick Settings tile for play/pause (§C1)
f878292 diag(cast): VERBOSE response logging in CastRelayServer
144aa4e feat(playback): plug-in resume — auto-play on headphone connect (§C22)
9e6a897 docs(phase-4): partial — UI panel + master toggle live
42a6aa2 feat(crossfade): wire master toggle into PlaybackService
7bab3cc feat(crossfade): Phase 4 part 1 — DataStore + UI panel + button wired
8b48318 docs(phase-3): library improvements complete
38ac2d3 feat(library): multi-select mode (§C26)
6cfeb48 feat(library): Hidden files Settings sub-sheet
aaa85f6 feat(long-press): wire context menu into Last Played + Cloud screens
c77d211 docs(phase-3): partial progress
33deaf9 feat(library): long-press menu + hidden-files filter
d6c137f feat(track-context): TrackContextSheet
bff2118 feat(library): refresh-if-stale on tab open
b780912 docs(phase-2): auto-hide controls complete
1e82ac6 feat(settings): Phase 2 — Auto-hide controls + 3 timers
88865bd docs(phase-1): regression sweep complete
517ad8d feat(info): wire info icon into Last Played, EQ, Cloud
f4c4a59 feat(library): info icon
489fcec feat(player): info icon top-right + accordion sheet
084a1e6 fix(player): setPitch field-init-order NPE
f9212e8 feat(info): per-tab info content
dba9143 feat(info): InfoSheet accordion bottom-sheet
16900cf feat(info): InfoIcon composable
fa50f1b feat(player): distinct frame-step icons + video-only visibility
1662643 feat(spotify-mirror): auto-record on first-emit
3fa1259 feat(bookmark-mirror): universalise currentSessionId
```

## Major work remaining (locked in plan)

These items require either substantial audio-engine rewrite (high risk) or on-device interactive testing (which I can't drive without the user playing real audio / pairing real Bluetooth / connecting real Chromecast):

### Phase 4 audio-engine follow-up (heavy)
- **True 2-player overlap** — second ExoPlayer instance, equal-power curve maths, midpoint role-swap, audio-effect chain hot-swap with 50 ms cubic smoothing.
- **Fade curves (Linear / Equal-power / Exponential / S-curve)** — currently always linear regardless of setting.
- **Album mode** behavioural — same-album consecutive tracks skip the fade.
- **Skip silence** — leading/trailing silence trim.
- **Pre-fade trigger** ramp-start timing wiring.
- **Manual fade-now** button behaviour.
- **Fade-out-on-pause** / **Fade-in-on-resume** behaviour.
- **Metadata handoff at crossfade START**.
- Plan §B3 has full design.

### Phase 5 — per-file overrides (heavy — Room migration)
- New `media_overrides` Room entity + DAO.
- DB migration v7 → v8.
- `MediaOverridesPopup` composable.
- Wire Override-* items in TrackContextSheet for starred/pinned files.
- Apply overrides at play start.

### Phase 6 — Cloud features
- C9 OpenSubtitles per-user login + auto-fetch.
- C10 Podcast subscription manager (RSS).
- C28 Drive offline copy.

### Phase 7 — Wake-up alarm (heavy)
- Settings → Alarms sub-page.
- 9 sub-features (volume ramp, snooze, days-of-week, skip-N, math-problem stop, full-screen lock-screen wake, etc.).
- USE_FULL_SCREEN_INTENT permission flow.
- AlarmManager + AlarmReceiver + FullScreenAlarmActivity.

### Phase 8 — Home-screen widget
- AppWidgetProvider with 3 layouts (Compact / Wide / Large).
- Foldable-responsive sizing.

### Phase 9 — remaining toggle features
- C2 Stats dashboard (read playback_history; new screen).
- C3 Tasker / Intent integration.
- C6 Smart playlists.
- C11 Sleep timer extensions (4 modes + fade-out).
- C13 Headphone-aware EQ.
- C14 Audio focus policy.
- C17 Discogs / MusicBrainz metadata enrichment.
- C18 ReplayGain library scanner.

### Phase 11 — Cast bug investigation (in progress)
- Task 11.1 logging shipped this session.
- Tasks 11.2–11.9 require user actively running a real Chromecast on same WiFi as the Z Fold 6 + Chrome devtools open on a desktop. Diagnostic protocol fully documented in plan §J Phase 11.

## What I cannot drive without user interaction

- **Real-audio crossfade testing** (need user to play 2+ tracks back-to-back with crossfade ON and tell me what they hear).
- **Real Chromecast cast bug bisect** (need user with Chromecast on same WiFi).
- **Bluetooth audio pairing tests** (per-device EQ presets need physical BT speakers).
- **Spotify Connect end-to-end** (need user signed in to Spotify with multiple Connect devices).
- **Drive OAuth playback through Cast** (need user signed in to Drive + Chromecast on same WiFi).
- **Headphone plug-in resume verification** (need user to physically plug in headphones with a paused track loaded).

## Plan file remains source of truth

`docs/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md` (1359 lines, 79 KB) holds every locked decision, line-number anchor, info-icon copy, sub-toggle list, alarm spec, settings reorganisation, file manifest, and Phase 11 diagnostic protocol. Any future Claude session can read §M Resumption Brief and pick up exactly where this left off.
