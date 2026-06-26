# Resume & Auto-play — finer-grained controls + override-on-resume guarantee

Date: 2026-06-26
Status: DONE + DEVICE-VERIFIED 2026-06-26 (commits 1c8643e/7c61d0b/5bb4f72)

## Goal
1. Replace the current 3-flag launch-autoplay logic with a granular, power-user
   "Resume & auto-play" settings group (sensible ordering, parent→child gating).
2. Guarantee EVERY per-file override (speed, pitch, volume boost, reverb, stereo
   flip, mono mix, EQ preset, replay-gain mode, video flips/bw/sepia/invert/rotation)
   re-applies on resume. (Speed/pitch already device-verified; audio+video to be
   device-verified as part of this.)

## Current state (verified in code)
- DataStore: `restoreLastOnLaunch`(true), `coldStartResumeBackoffSec`(5),
  `autoplayOnLaunch`(false), `resumeOnBt`(false).
- Launch autoplay decided by `resolveLaunchAutoplay()` (PlaybackSessionCoordinator):
  "BT branch wins" — btConnected→resumeOnBt else autoplayOnLaunch.
- Event-driven BT resume already exists in `PlaybackService.onAudioDevicesAdded`
  (`:1356`) — resumes the LOCAL player / Spotify Connect when a BT A2DP sink
  connects AND `resumeOnBt`.
- Cast session events handled by an existing `castSessionListener`
  (SessionManagerListener<CastSession>).
- Volume is driven via the crossfade VOLUME FACTOR (`setCrossfadeFactor`), never a
  direct `player.volume` write — reuse this for the ease-in.
- Item kind: `MediaClassifier.AudioSubKind` (PODCAST/AUDIOBOOK/…) + history row
  `mediaKindOrdinal` + a video check.

## Settings model (new group "Resume & auto-play", ordered)
A. RESTORE
  1. Restore last item on launch — master, default ON *(existing)*
  2. ↳ Resume backoff seconds — default 5 *(existing; gated by #1)*
B. AUTO-PLAY TRIGGERS (independent; OR'd)
  3. On app launch — default OFF *(existing `autoplayOnLaunch`)*
  4. On Bluetooth connect — default OFF *(existing `resumeOnBt`)*
  5. On wired-headphone connect — default OFF *(new `resumeOnWired`)*
  6. On Cast connect — default OFF *(new `resumeOnCast`)*
C. AUTO-PLAY CONDITIONS (AND-gate every trigger)
  7. Only if it was playing when closed — default ON *(new `autoplayOnlyIfWasPlaying`)*
  8. Auto-play media types: Podcasts&audiobooks ON / Music OFF / Video OFF
     *(new `autoplayKindSpoken`/`autoplayKindMusic`/`autoplayKindVideo`)*
D. AUTO-PLAY FEEL
  9. Volume ease-in on auto-play — default ON *(new `autoplayFadeIn`)*
  10. ↳ Ease-in length seconds — default 1.5 *(new `autoplayFadeInMs`, gated by #9)*

Defaults preserve out-of-box behaviour: all triggers OFF → nothing auto-plays →
no surprise audio. Per-type defaults (music/video OFF) only bite once a trigger is
enabled; documented in the setting subtitle.

## Decision logic (pure, unit-tested)
`AutoplayDecision.shouldAutoPlay(trigger, prefs, ctx)`:
```
triggerEnabled = when(trigger){ LAUNCH->onLaunch; BT->onBt; WIRED->onWired; CAST->onCast }
gateState   = !onlyIfWasPlaying || wasPlayingAtClose
gateType    = when(kind){ SPOKEN->kindSpoken; MUSIC->kindMusic; VIDEO->kindVideo }
return triggerEnabled && gateState && gateType
```
- LAUNCH trigger evaluated at cold-start (PlaybackSessionCoordinator).
- BT/WIRED/CAST triggers evaluated event-driven (device/session connect), reusing
  the existing `onAudioDevicesAdded` + `castSessionListener`; resume the loaded
  paused item (or Spotify Connect) when the gate passes.

## wasPlaying persistence
Persist `lastWasPlaying` to DataStore in `startBackgroundPositionSave` (onStop) and
the 5s tick (player.isPlaying / Spotify isPlaying). Read in the gate.

## Volume ease-in
On any auto-start: `setCrossfadeFactor(0f)` then ramp →1f over `autoplayFadeInMs`
(reuse crossfade tick; never write player.volume directly). Manual play untouched.

## Override-on-resume (already works; verify)
All axes derive from the single `MediaOverrideRepository.activeOverride` StateFlow,
proven to emit + be consumed on cold-start (speed/pitch device-verified). No code
change needed; ADD device verification for an audio-effect override (stereo flip)
and a video-effect override on resume.

## Components touched
- `SettingsDataStore.kt`: 8 new keys + flows + setters.
- `playback/AutoplayDecision.kt` (NEW): pure decision + kind classification helper.
- `PlaybackSessionCoordinator.kt`: rewrite `resolveLaunchAutoplay`→use decision;
  persist wasPlaying; trigger ease-in on cold-start autoplay.
- `PlaybackService.kt`: extend `onAudioDevicesAdded` (wired) + `castSessionListener`
  (cast) to call the decision + resume + ease-in.
- `SettingsScreen.kt` (+ SettingsViewModel): new ordered group with gating.

## Testing
- Unit: `AutoplayDecisionTest` — trigger×gate matrix (all combinations), kind gating,
  wasPlaying gating, defaults. Pure → JVM.
- Unit: kind-classification helper (spoken/music/video) from row fields.
- Device: cold-start launch autoplay on/off; BT/wired connect resume; per-type gate;
  ease-in audible ramp; override re-applies (speed+stereoFlip+a video flip).

## Out of scope
- "Resume live" favourite scoped override on cold-start (cold-start applies the
  plain-uri override; per-favourite scoping is a separate concern).
