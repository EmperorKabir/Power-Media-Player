# Phase 4 — Crossfade panel + master toggle — PARTIAL

**Updated:** 2026-05-07.
**Tasks completed:** 4 of ~22 planned.
**Status:** UI panel ships with all 9 toggles + DataStore-backed persistence + master toggle wired into the existing single-player ramp path. True 2-player overlap and the 8 sub-toggle behaviours (curve / album mode / skip silence / pre-fade trigger / manual fade-now / fade-out-on-pause / fade-in-on-resume) deferred — they require an audio-engine rewrite plus on-device real-audio testing.

## Tasks shipped this batch

| # | Subject | Outcome |
|---|---|---|
| 4.1 | Crossfade DataStore keys (9 toggles) | 8 new keys (master + curve + 6 sub-toggles); existing CROSSFADE_MS retained for duration. Reactive Flow getters + suspend setters. Defaults locked from §B2. |
| 4.2 | SettingsViewModel crossfade fields | UiState extended; combine pulls 8 new flows; 9 setters exposed. |
| 4.3 | CrossfadeButton + bottom-sheet panel | New composable in player transport row right of AudioEffectsButton (per §B1). ModalBottomSheet panel with 9 controls + per-row one-liner explanations. Greyed when video / cast / Spotify Connect (compatibility matrix first cut). |
| 4.4 | Master toggle wired into PlaybackService | combine(crossfadeMs, crossfadeEnabled) drives crossfadeMsFlag — when master is OFF, effective duration is forced to 0 so the existing ramp no-ops. User's preferred duration is preserved across toggle off→on. |

## Pre-flight Context7 evidence captured

- AndroidX Media3 dual-instance design: both ExoPlayers share `AudioAttributes`; secondary uses `setAudioAttributes(attrs, handleAudioFocus = false)` so they don't fight over focus.
- `setVolume(0..1f)` is the ramp lever per Player API.
- Build pattern: `ExoPlayer.Builder(context).setAudioAttributes(...)`.

## Commits

```
42a6aa2 feat(crossfade): wire master toggle into PlaybackService
7bab3cc feat(crossfade): Phase 4 part 1 — DataStore + UI panel + button wired
```

## Deferred (audio-engine follow-up — requires real-audio on-device testing)

- **True 2-player overlap.** Second ExoPlayer instance, equal-power curve maths (vA² + vB² = 1), 50 ms cubic smoothing on audio-effect chain hot-swap at midpoint, mid-crossfade pause/scrub/next/prev handlers. Plan §B3 has the full design.
- **Fade curves** — Linear / Equal-power / Exponential / S-curve. Currently the existing ramp is linear regardless of the curve setting.
- **Album mode** — actual same-album skip behaviour.
- **Skip silence** — leading/trailing silence trim.
- **Pre-fade trigger** slider — actual ramp-start timing.
- **Manual fade-now** button — fast-skip with fade.
- **Fade-out-on-pause** / **Fade-in-on-resume** — pause-button fade.
- **Audio-effect chain hot-swap** at midpoint — EQ/reverb/stereo-flip/mono-mix transition.
- **Metadata handoff at crossfade START** — notification + lockscreen flip to incoming track on ramp start.

## Regression results

- Build: clean.
- Z Fold 6: monkey 250 events seed=6, 0 FATAL.
- Emulator: monkey 250 events seed=6, 0 FATAL.
- Cold-start branch from Phase 1 still fires.

## What user can do today

1. Open player → tap new Crossfade button (right of Audio Effects).
2. Toggle "Master crossfade" ON.
3. Adjust duration slider 0..15s.
4. Pick a fade curve (cosmetic until engine lands).
5. Toggle Album mode / Skip silence / Pre-fade trigger / Manual fade-now / Fade-out-on-pause / Fade-in-on-resume — settings persist (engine wiring follows).
6. Settings stay greyed/disabled when video / Cast / Spotify Connect — UI signals it can't apply.

The single-player linear-ramp crossfade that's been in the codebase continues to work when master is ON — duration > 0. So crossfade IS audible today; the curve shape is just always linear and the 6 behavioural toggles don't yet do anything beyond persisting.
