# Phase 1 — Foundation — COMPLETE

**Completed:** 2026-05-07.
**Tasks:** 12. All committed + pushed.

## Tasks recap

| # | Subject | Commit |
|---|---|---|
| 1.1 | Universalise currentSessionId cold-start path | `feat(bookmark-mirror): universalise currentSessionId across resume paths` |
| 1.2 | Universalise currentSessionId Spotify mirror path | `feat(spotify-mirror): auto-record on first-emit` |
| 1.3 | Frame-step icons + video-only visibility | `feat(player): distinct frame-step icons + video-only visibility` |
| 1.4 | InfoIcon composable | `feat(info): InfoIcon composable — rounded-square blue box` |
| 1.5 | InfoSheet accordion bottom-sheet | `feat(info): InfoSheet accordion bottom-sheet` |
| 1.6 | Per-tab info content (5 tabs) | `feat(info): per-tab info content` |
| 1.7 | Wire info icon into Player tab | `feat(player): info icon top-right + accordion sheet` |
| (bug fix) | setPitch field-init-order NPE | `fix(player): setPitch field-init-order NPE on uiState.value` |
| 1.8 | Wire info icon into Library tab | `feat(library): info icon in top-app-bar actions slot` |
| 1.9–1.11 | Wire info icon into Last Played, EQ, Cloud | `feat(info): wire info icon into Last Played, Equalizer, Cloud tabs` |

## Regression results

Devices: emulator `Pixel_6_API_34` (Android 14) + Z Fold 6 (`SM_F966B / RFCY70BARDJ`).

### Launch stress
- Cold start, force-stop + relaunch: **PASS** both devices.
- am kill + relaunch (process-recreation): **PASS** both devices.
- Back-key press + relaunch: **PASS** both devices.

### Memory pressure
- `am send-trim-memory COMPLETE` + relaunch: **PASS** both devices.

### Random-event stress
- Android monkey: 100 events, 80% touch / 10% nav, 100 ms throttle, seed 1.
- Z Fold 6: 0 FATAL.
- Emulator: 0 FATAL.

### Behavioural checks (logcat-confirmed)
- `Cold-start restored '...' @ ...ms (session 6)` log line confirmed on Z Fold 6 across every relaunch — proves Task 1.1's `adoptSession` fires.
- No `FATAL EXCEPTION` across all installations.

## Bug surfaced + fixed during Phase 1

**setPitch field-init-order NPE on uiState.value** — the pitchIndependent collector at PlayerViewModel:160 calls `setPitch(p)` synchronously on Dispatchers.Main.immediate before the `uiState` StateFlow property at line 382 has been initialised. Reading `uiState.value.playbackSpeed` then NPEs.

Same race-class as the prior `_pitch` / `_volumeBoostMb` field-init-order fix; the previous fix moved those backing fields BEFORE init but missed the second-read of uiState inside setPitch.

Surfaced reliably on Z Fold 6 launch after Task 1.7 changes shifted the init-block timing window. Fixed by reading `playbackParameters.speed` directly from `playbackConnection.getPlayer()?.playbackParameters?.speed` (defensive null + `runCatching`), avoiding the uiState dependency at construction time.

## Logs

- `dist/logs/phase-1-regression-RFCY70BARDJ-*.log` — Z Fold 6 logcat captures.
- `dist/logs/phase-1-regression-emulator-5554-*.log` — emulator logcat captures.

## Items NOT covered by automated regression (require user-driven setup)

These tests deferred to Phase 10's full E2E sweep + manual checks:

- Bluetooth car-control regression (paired BT speaker required).
- Spotify Connect device picker + transferPlayback flow (requires user signed in to Spotify).
- Drive OAuth tokenflow (requires Google sign-in).
- EQ-during-cast UI grey-out (requires Cast receiver on same WiFi).
- Audiobook chapter navigation (requires a chapterised m4b file).
- Notification + lockscreen controls (visual confirmation).
- Headphone-plug auto-resume (requires physical headphone connect).
- Real bookmark + Last Played mirror exercise (requires user playing a file then adding a bookmark; Z Fold 6 logcat already shows session 6 adoption from existing playback history).

These are scheduled into Phase 10 (Spotify Connect E2E + first-run deep-scan) and Phase 4-9 per-feature smoke tests.

## Next phase

**Phase 2 — Settings tab reorganisation** (UI-only, no audio risk). Tasks 2.1 onward — see plan §J Phase 2.
