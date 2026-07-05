# Spotify Cold-Start Bounce-Back — Investigation Plan

> **For agentic workers:** This is a DIAGNOSIS plan. NO code fixes are written until the root cause is **evidence-locked** in Phase 5. Steps use checkbox (`- [ ]`) syntax. REQUIRED SUB-SKILL: superpowers:executing-plans (one phase at a time).

**Goal:** Identify, with logged on-device evidence, why opening a pinned Spotify track immediately after a force-close-and-reopen of the app launches Spotify but fails to bounce back to the app, while the same action in subsequent (warm) sessions works correctly.

**Hard constraints (same as the prior video-controls plan):**
- **No guessing.** A hypothesis is only "considered" once it appears on the ranked falsification list (Phase 4) with a defined evidence threshold. A hypothesis is only "accepted" once Phase 5 produces matching evidence.
- **No code deletion** during the diagnosis phases (1-5). Any new logging is purely additive, gated behind `PMP_DIAG_BOUNCE` so it can be removed in one revert.
- **Reproduce before instrumenting.** If we cannot reproduce the bug deterministically (Phase 1), we do not move to instrumentation.
- **Evidence types are explicit per step.** Every step states what artefact must be produced.
- **One change at a time** during instrumentation.
- **adb / logcat / direct device control are pre-authorised.**

**Test rig:** same as the prior plan — Z Fold 6 (`RFCY70BARDJ`), unfolded landscape, current `main` HEAD with the de2cba8 spotify-banner change installed. adb at `C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

**Pre-conditions for the bug to be possible at all:** the user has Spotify installed, has signed into our app via PKCE, has at least one pinned Spotify track in Last Played → Pinned. None of these are part of the investigation; they're rig setup.

**Evidence directory:** `docs/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/` — created in Phase 0.

---

## Phase 0 — Set-up

- [ ] **0.1 Create evidence directory and CHANGELOG.md.** Same template as the prior video-controls investigation (top-of-file stop-the-world rule).
- [ ] **0.2 Capture device fingerprint, current HEAD, app version, manifest declarations for SpotifyBounceService, BAL flags.**
- [ ] **0.3 Verify pre-conditions: Spotify installed, PKCE token present in DataStore, ≥1 pinned Spotify track exists.** All three captured to `phase0-step03-preconditions.txt`. Stop and tell user if any precondition fails.

---

## Phase 1 — Reproduce the bug deterministically

A reproduction is "solid" when both the failing cold-start case AND the passing warm-session case can be demonstrated on demand, with screen recordings of each.

- [ ] **1.1 Define the exact reproduction script (cold-start).** Verbatim, as for the prior plan:

> *Force-stop both apps:*
> ```
> adb shell am force-stop com.powermediaplayer
> adb shell am force-stop com.spotify.music
> ```
> *Launch our app via its launcher icon (NOT via adb am start — preserve the same input path the user uses).*
> *Navigate to Last Played → Pinned tab.*
> *Tap the pinned Spotify track.*
> *Observe: Spotify launches into foreground. Wait 5 seconds. Does our app come back?*

- [ ] **1.2 Define the contrast script (warm session).**

> *Without force-stopping anything, return to our app via Recents (overview).*
> *Tap the same pinned Spotify track.*
> *Observe: Spotify launches into foreground. Wait 5 seconds. Does our app come back?*

- [ ] **1.3 Capture screen recordings of both scripts (30 s each).**

```bash
"$ADB" shell rm -f /sdcard/repro_cold.mp4
"$ADB" shell screenrecord --bit-rate 16000000 --time-limit 30 /sdcard/repro_cold.mp4
# perform script 1.1 during the window
"$ADB" pull /sdcard/repro_cold.mp4 phase1-step03-cold.mp4
```

Same for warm. Both captures use `MSYS_NO_PATHCONV=1` (Bash tool path-mangling pitfall already documented).

- [ ] **1.4 Locked repro criteria.**

The reproduction is "solid" when:
- Cold-start case fails to bounce back across **3 of 3** attempts.
- Warm session case bounces back across **3 of 3** attempts.
- Both observable in screen recordings.

If reproduction is not solid, stop and re-spec with the user. Do NOT proceed to instrumentation on a flaky bug.

---

## Phase 2 — Capture rendering / process / BAL ground truth

- [ ] **2.1 PMP_DIAG logcat across the cold-start repro window.**

```powershell
& $adb logcat -c
# Run 1.1
& $adb logcat -d -v threadtime PMP_DIAG:I '*:S' > phase2-step01-pmp-cold.txt
```

Expected log lines (per existing instrumentation):
- `Spotify.startPlaybackPolling gen=N`
- `Spotify.play activating device ID (NAME)` (only after device list non-empty)
- `Spotify.play no active device — listing` (the path our cold-start probably hits)
- `Spotify auto-launch fired`
- `SpotifyBounceService started`
- `Bounce sent via PendingIntent (BAL opt-in)` ← KEY LINE
- `Bounce send failed` (with stack)
- `Spotify.play device appeared after Nms`
- `SpotifyBounceService self-stop`

Whether the "Bounce sent" or "Bounce send failed" line appears, and if so when, is the primary discriminator.

Also capture the warm equivalent (`phase2-step01-pmp-warm.txt`) for direct contrast.

- [ ] **2.2 Activity-task-manager + BAL logcat across both runs.**

```powershell
& $adb logcat -c
# Run 1.1
& $adb logcat -d -v threadtime ActivityTaskManager:V ActivityManager:V BackgroundActivityStartController:V '*:S' > phase2-step02-atm-cold.txt
```

`ActivityTaskManager` and `BackgroundActivityStartController` (the latter only on some OEMs) emit messages like `BAL_BLOCK`, `BAL_ALLOW`, `balRequireOptInByPendingIntentCreator=true`, etc., which directly tell us whether the bounce launch was blocked at the system layer.

- [ ] **2.3 ProcessStats / Freecess logcat.**

Samsung's Freecess (the One UI app-standby) freezes background processes. Logcat includes `Freecess` lines reporting which packages are frozen at any moment. Capture for both runs:

```powershell
& $adb logcat -d -v threadtime Freecess:V ProcessRecord:V '*:S' > phase2-step03-freecess-cold.txt
```

- [ ] **2.4 dumpsys activity & dumpsys deviceidle.**

```powershell
& $adb shell dumpsys activity activities > phase2-step04-activities-cold.txt
& $adb shell dumpsys deviceidle > phase2-step04-deviceidle-cold.txt
& $adb shell dumpsys jobscheduler | grep -i powermediaplayer > phase2-step04-jobs-cold.txt
& $adb shell dumpsys media_session > phase2-step04-mediasession-cold.txt
```

These capture process-state, deep-doze/buckets, scheduled jobs, and MediaSession state at the post-bug moment.

- [ ] **2.5 Cross-reference all four artefacts on a single timeline.**

`phase2-summary.md`: one timeline showing cold-start events from each substrate, side-by-side with warm-session events. Wall-clock alignment via the matching `PMP_DIAG` "Spotify auto-launch fired" line in each.

Output is *raw evidence only*; no interpretation here.

---

## Phase 3 — Pre-commit candidate substrates

Pre-committed BEFORE looking at evidence so we cannot retroactively narrow to a favourite suspect. Each gets a falsifiability test referencing Phase 2 artefacts.

| # | Substrate | Definition | Falsifiable by … |
|---|---|---|---|
| B1 | `SpotifyBounceService.start(context)` rejected on cold start | The FGS start call returns or throws before `startForeground(...)` runs | logcat `SpotifyBounceService start failed` line; absence of `SpotifyBounceService started` |
| B2 | `pollScope` coroutine never gets to `bouncePi.send()` because the process is frozen | The 1500 ms delay completes after the process is frozen by Freecess; the `runCatching { … }` block never executes | logcat: absence of `Bounce sent via PendingIntent` line; Freecess log shows our package frozen during the window |
| B3 | `bouncePi.send()` returns successfully but the system's BAL policy rejects the activity launch | "Bounce sent" logs but ATM emits `BAL_BLOCK` for our launch | `phase2-step02-atm-cold.txt` contains `BAL_BLOCK`; `phase2-step01-pmp-cold.txt` shows the success log |
| B4 | The FGS hasn't transitioned to "foreground" by the time `bouncePi.send` is called (1500 ms after start), so the system treats us as background | Race between `startForegroundService → onCreate → startForeground` and the bounce send | ATM/BAL logs around the bounce send; FGS notification visible vs not in screen recording |
| B5 | OAuth token refresh on cold start adds latency; the device-list polling completes after the FGS 10 s lifetime | First `currentAccessToken()` triggers a token refresh HTTP call; total time-from-tap to bounce exceeds 10 s | `phase2-step01-pmp-cold.txt` timestamps; FGS self-stop log preceding bounce send |
| B6 | The activity launch IS happening but in a NEW task, not bringing the existing task to front; from the user's PoV nothing visible changes | Cold-start: `MainActivity` doesn't yet have a task in the recents list, so `FLAG_ACTIVITY_CLEAR_TOP \| SINGLE_TOP` create a new instance instead of bringing forward | `phase2-step04-activities-cold.txt` shows two MainActivity records, or task list mutations during the window |
| B7 | Spotify itself doesn't take foreground (auto-launch silently rejected) so the user sees nothing change | `pm.getLaunchIntentForPackage("com.spotify.music")` returns null OR `startActivity` is rejected | `phase2-step01-pmp-cold.txt` "Spotify auto-launch skipped" or stack trace |
| B8 | Spotify takes foreground and `bouncePi.send` succeeds, but our MainActivity launches and IMMEDIATELY closes/crashes due to a cold-start initialisation bug | MainActivity throws during onCreate on cold-restart-via-PendingIntent | `phase2-step04-activities-cold.txt`; AndroidRuntime fatal logcat |
| B9 | Notification permission denied → FGS start succeeds but the system silently demotes us to background | Android 13+ requires POST_NOTIFICATIONS for FGS notifications | `dumpsys notification` shows our channel suppressed; FGS not in shown notifications |
| B10 | `pollScope` (a Singleton-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`) is created on first SpotifyProvider injection. On cold start that injection happens at first Hilt access, not at process start. The `pollScope.launch { delay(1500); bouncePi.send(...) }` may actually be running in a scope that gets garbage-collected if SpotifyProvider's lifecycle is short | Singleton lifetime ≠ process lifetime in pathological cases | additive log on pollScope coroutine entry/exit |
| B11 | The first cold-start invocation involves a fresh device-list HTTP call that takes much longer than warm; the bounce path is reached too late | HTTP timing before `Spotify auto-launch fired` log | timestamps in `phase2-step01-pmp-cold.txt` |
| B12 | The FGS start call succeeds but the system attributes it to a background-from-background path because our process state at the moment of `startForegroundService` is already PROCESS_STATE_TOP_SLEEPING (the touch event is consumed but the activity is being torn down by the about-to-launch Spotify) | A subtle PROCESS_STATE race | ProcessStateRecord lines in logcat |
| B13 | Subsequent (warm) sessions work because Spotify Connect already shows our device or the `lastTrackUri` is non-empty, so the cold-only code path is skipped | Difference between Phase 1.1 and Phase 1.2 logcat traces in WHICH code paths execute | direct diff |
| Bnull | The bug is environmental on this single device (Samsung One UI build, particular Spotify version, etc.) — not a code defect in our app | Repro on a second device | not testable until a second device is available; INCONCLUSIVE if so |

This list is exhaustive on purpose. New candidates may be added ONLY with explicit Phase-2-evidence justification.

---

## Phase 4 — Falsify candidates against Phase 2 evidence

- [ ] **4.1 Per-candidate verdict table.** Same format as the prior plan: PASS / FAIL / INCONCLUSIVE with citation. PASS requires a specific timestamp + file. Without a citation the verdict is INCONCLUSIVE, never "likely".
- [ ] **4.2 Cold-vs-warm asymmetry check.** For every PASS, the candidate's evidence must be present in the cold log AND absent (or weaker) in the warm log. A candidate firing identically in both cannot explain a cold-only bug. Mark `FAIL_COLD_WARM_ASYM` if so.
- [ ] **4.3 Repeatability check.** Re-run Phase 1.1 (cold-start) two more times to make 3 cold runs total. Each PASS candidate must fire in all three.
- [ ] **4.4 Two-substrate corroboration rule.** A candidate is only allowed onto the Phase 5 shortlist when it has supporting evidence from at least TWO of the four Phase-2 substrates (PMP_DIAG, ATM/BAL, Freecess, dumpsys/activity).
- [ ] **4.5 Build the shortlist** with predicted observations for Phase 5 instrumentation.

---

## Phase 5 — Targeted, additive instrumentation

Same rules as the prior plan: ONE diagnostic log per build, gated by `PMP_DIAG_BOUNCE` constant (new, distinct from `PMP_DIAG` and `PMP_DIAG_VIDEO`). One file (`util/DiagFlags.kt` reused or recreated; a single revert removes everything).

Likely instrumentation points (subset depends on shortlist):
- `SpotifyBounceService.onCreate / onStartCommand / onDestroy` — log entry + timestamps so we can see exactly when the FGS reaches foreground state.
- `pollScope.launch { delay(1500); bouncePi.send(...) }` — log entry, just-before-send, just-after-send, with the exception stack on failure.
- `currentAccessToken()` — log entry / refresh-required / refresh-completed.
- `playTrackOnConnectDevice` — log every branch traversal (token fetch, first attempt, devices listing, launch, polling loop iteration count).
- `MainActivity.onCreate / onNewIntent` — log so we can see when (and via which intent) the bounce reaches us, vs when our activity is cold-starting from the launcher.

Single PR per instrumentation block. Build → install → repro → capture → analyse → next.

- [ ] **5.4** Iterate until exactly one candidate is CONFIRMED with two-substrate corroboration. Multiple may be CONFIRMED if the bug has multiple necessary causes.

---

## Phase 6 — Root-cause document

Same template as the prior plan's `phase6-root-cause.md`:
1. Symptom (cite recordings).
2. Reproduction script (verbatim).
3. Confirmed substrates with code citations.
4. Causal chain.
5. Why warm sessions are unaffected (the cold-vs-warm divergence point).
6. Rejected candidates with evidence — protects future sessions.
7. Outstanding unknowns.

Plus: *no fix is in scope*. The fix is a separate plan, written only after the user signs off on the RCA.

---

## Phase 7 — Decommission instrumentation

After user signs off on the RCA, remove `PMP_DIAG_BOUNCE` calls and the `DiagFlags.kt` file in a single commit. Push and reinstall.

---

## Anti-patterns this plan deliberately rules out

- "It's probably the BAL policy" without an ATM log line. We capture the log line first, then decide.
- Removing the `pollScope.launch { delay(1500) }` because it "looks racy". Forbidden in Phases 1-5; even in a future fix, only after Phase 6 has located it.
- Fixing while investigating. Even if Phase 5.2 makes a fix obvious, file the RCA first; the fix is a separate plan.
- Capturing only the failing case. The warm session is the control without which "cold-start specific" cannot be proved.
- Trusting that "the previous fix worked" — the project_state's decision log shows three rounds of attempted fixes for this exact bug; another untested guess will be the fourth.
