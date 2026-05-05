# Investigation CHANGELOG

## Stop-the-world rule
This investigation MUST NOT be paused mid-phase to ship unrelated fixes.
If the user requests an unrelated fix, finish the current step, capture
its artefact, and only then context-switch. Past failures came from
interleaved sessions that "improved" code mid-investigation and broke
evidence chains.

## Run log

### 2026-05-05 — investigation opens
- HEAD at start: `8df2da39603a465f75209b7ace91089d9c39f966`
- Device fingerprint: samsung/q7qxeea/q7q:16/BP2A.250605.031.A3/F966BXXS9AZC8_OXM9AZC8:user/release-keys
- App version info:
    versionCode=2 minSdk=30 targetSdk=35
    versionName=1.0.0-friends
- Window size: Physical size: 1968x2184
- Window density: Physical density: 420 Override density: 311

### Phase 1.1 — corpus picked from device library

Library has 64 video entries. Sort taken via MediaStore content provider
(which is what the in-app Library tab also queries).

| Role | Filename | Duration | Size | WxH |
|---|---|---|---|---|
| **Primary probe** (top by Duration desc, also #1 by Size desc) | `/storage/emulated/0/DCIM/Camera/20260323_130942.mp4` | 168 238 ms (≈ 2 min 48 s) | 3 032 122 320 B (≈ 2.82 GiB) | 3840×2160 |
| **Secondary probe** (#2 by Size desc, distinct from primary) | `/storage/emulated/0/DCIM/Camera/20260429_195640.mp4` | 68 287 ms (≈ 68 s) | 1 231 202 869 B (≈ 1.15 GiB) | 3840×2160 |
| **Control probe** (1080p, similar duration to secondary, ~13× smaller bitrate) | `/storage/emulated/0/DCIM/DJI Album/DJI_20260117_230023_17_null_video.mp4` | 70 060 ms (≈ 70 s) | 91 330 547 B (≈ 87 MiB) | 1920×1080 |

**Plan deviation note:** The plan called for the bottom of the Duration sort
as the control. The actual bottom-3 are 1.27 s / 2.00 s / 2.59 s, all far
too short for a meaningful scrub gesture (the bug is observed during a
~600 ms drag). Substituted the longest 1080p file (70 s) as the control
because:
  1. duration-class matches the 4K secondary probe (~70 s), eliminating
     "duration-driven decoder cost" as a confound;
  2. resolution differs (1920×1080 vs 3840×2160 = 4× pixel count, ≈ 4×
     decoder cost), which preserves the falsifiability target for any
     pixel-rate / decoder-pressure substrate;
  3. all three files share the same source process (DCIM camera-style
     MP4), keeping container/codec variance low.

The original "shortest possible" control is dropped from this corpus.
If a hypothesis specifically demands a sub-second file we'll add one then.

### Phase 1.2 — reproduction scripts (locked)

Both scripts use a **single finger** and the **on-screen track-slider thumb**
(the slider in `ProgressSliders`). No BT, no adb input synthesis — the bug
is described as visible during in-app interaction; isolate that path first.

**Script A — backward scrub (the failing direction):**
1. Cold-start app: `adb shell am force-stop com.powermediaplayer` then launch via Library tap.
2. Library tab → tap the file under test (Primary / Secondary / Control).
3. Wait until the first frame renders and playback is steady. Hold for 3 s of steady playback (no buffering spinner, no chapter sheet visible).
4. Wait for the position to advance to roughly 80% of the duration (or scrub once forward to ~80% via a single short forward gesture, then hold 3 s steady).
5. Press the on-screen track-slider thumb at ~80% width with ONE finger and drag continuously to ~30% width over ~600 ms.
6. Release.
7. Continue observing the transport-controls cluster for ≥ 2 s post-release.

**Script B — forward scrub (the passing direction):**
1. Cold-start app, open file, hold steady playback as in (A).
2. Position at ~30% (start of file is fine for the first run).
3. Press the on-screen track-slider thumb at ~30% width with ONE finger and drag continuously to ~80% width over ~600 ms.
4. Release.
5. Continue observing for ≥ 2 s post-release.

**Script C — backward step (skip-back-N button):**
This catches the "jump on backward skip but not forward skip" sub-symptom
the user described. After steady playback at ~50%:
1. Tap the in-app skip-back-30 IconButton once. Observe.
2. Wait 2 s. Tap skip-back-30 again. Observe.
3. Wait 2 s. Tap skip-forward-30 once (control). Observe — should NOT jump.

Each script is run **3 times per file × per direction** for repeatability.
