# Plan — fix the cast crash + Bluetooth issues + play/pause (evidence-based)

Date 2026-06-15. All root causes verified from code + logs (3 parallel
read-only investigations: BT offset `ae5b16cd`, BT resume `a12cfb32`, BT
disconnect `a25b89bf`; crash from logcat `cast_crash_logcat.txt`).

## 1. Cast force-close — DONE (commit 5edaa6b)
- Root (logcat 20:22:43, definitive stack): audio extraction SUCCEEDED (wrote
  4 MB m4a, 5988 samples), then `cast.setMediaItems` crashed —
  `IllegalArgumentException: The item must specify its mimeType`
  (`DefaultMediaItemConverter.toMediaQueueItem`). My m4a MediaItem had no MIME.
- Fix applied: `.setMimeType("audio/mp4")` on the item. Built + installed.
- The crash CASCADED into the other cast symptoms the user saw on reopen
  (stutters / low quality / gaps / auto-resume fail): the service died
  mid-cast, leaving a corrupt cast + resume state. → RE-TEST casting after the
  fix; these should clear. If stutters persist on the clean m4a, investigate
  the relay's streaming of the temp file (separate, evidence first).

## 2. BT "video audio offset" slider does NOTHING — root caused
- Path verified: `btVideoAudioOffsetMs` → `audioDelayFlag` →
  `AudioDelayProcessor` (correctly wired into the DefaultAudioSink chain).
- BUT the processor ONLY delays AUDIO and CLAMPS negatives to 0
  (`AudioDelayProcessor.kt queueInput`: `delayMsSupplier().coerceIn(0, MAX)`).
- For Bluetooth the audio is LATE (BT codec/buffer latency). Fixing lip-sync
  needs the VIDEO delayed (you can't advance already-late audio). So: slide
  right (positive) = delays audio FURTHER = worse; slide left (negative) =
  clamped to 0 = literally nothing. And the label ("slide right to delay the
  video") is INVERTED vs what the code does (delays audio).
- FIX: add a real VIDEO delay — a custom `MediaCodecVideoRenderer` (via the
  existing custom `RenderersFactory`) that offsets released-frame presentation
  times by the BT offset (positive = hold video back to match late BT audio).
  Route the BT offset to that, not the audio processor. Fix the helper text.
  (Bigger change — touches the renderer pipeline; do carefully + verify.)

## 3. BT "resume on connect" toggle FAILS — root caused
- `resumeOnBt` is only a PERMISSIVE GATE: it un-swallows an inbound
  `KEYCODE_MEDIA_PLAY` KeyEvent in `onMediaButtonEvent`
  (`PlaybackService.kt:2713-2733`). It NEVER proactively resumes.
- The BT-connect detector `AudioDeviceCallback.onAudioDevicesAdded`
  (`:1132-1139`) only writes a diag line — never calls `play()`.
- So plain BT headphones/speakers (which don't auto-emit a play KeyEvent on
  connect) never auto-resume. Settings copy over-promises.
- FIX: in `onAudioDevicesAdded`, when an added device is a BT A2DP sink AND
  `resumeOnBt` AND the LOCAL player (`exoPlayerRef`) is paused with a loaded
  item → request audio focus + `play()` the LOCAL player (mirror the
  headphone-plug receiver at `:789-802`). Target the local player explicitly so
  a leftover CastPlayer can't absorb it. Keep the KeyEvent gate for cars.
  (Low risk, additive.)

## 4. BT button: tap-when-connected should DISCONNECT (keep BT on) — feasible via reroute
- A true ACL disconnect of an A2DP sink is NOT possible from a 3rd-party app
  (`BluetoothA2dp.disconnect` is hidden @SystemApi needing BLUETOOTH_PRIVILEGED).
- The actual goal ("stop sending audio to the BT speaker, keep BT on") IS
  achievable via ROUTING: `ExoPlayer.setPreferredAudioDevice(builtinSpeaker)`
  (public, stable Media3 API; minSdk 30/target 35 OK). Reverts with `null`.
  Speaker `AudioDeviceInfo` via `getDevices(GET_DEVICES_OUTPUTS).first{ type ==
  TYPE_BUILTIN_SPEAKER }` (pattern already in `AudioOutputDetector.kt:107`).
- FIX: tap when `a2dpActive` → reroute media to the phone speaker (icon drops
  to BT-on-not-routing via the existing AudioDeviceCallback); preserve the
  current sheet on LONG-press. Plumb a `setPreferredAudioDevice` call from the
  BT button to the service's ExoPlayer (the button only has SettingsViewModel
  today — needs a player/MediaController handle). (Low-moderate; wiring.)

## 5. Play/pause shows wrong state for a frame on controls-show — root caused
- `PlaybackControls` reads `isPlaying` straight from `uiState.isPlaying`
  (`PlaybackControls.kt:123`), which reflects the SESSION player. During cast,
  the session = CastPlayer whose `isPlaying` flips on every buffer blip → the
  button shows the churn.
- With the crash fixed + audio-only cast (small stable m4a) the churn drops a
  lot. If it persists: derive the button from the user INTENT (`playWhenReady`)
  rather than `isPlaying`, or from the local player during cast-local-video.
  (Verify after the cast fix before changing — may already be resolved.)

## Order / risk
1. Cast crash — DONE; user re-tests (covers 1 + likely the stutter/resume
   aftermath + likely the play/pause churn).
2. BT resume proactive (low risk) + BT button reroute-disconnect (low-mod) —
   implement together.
3. BT offset → video-delay renderer (bigger/riskier) — implement carefully,
   fix the label, device-verify.
4. Play/pause — only if it survives the cast fix.

## Anti-skip
- No fix claimed done until built + the user device-verifies (cast can't be
  verified here). Each BT fix gets a device check. The video-delay renderer is
  the one real-risk item — isolate + verify it doesn't regress normal video.
