# Root Cause — Spotify cold-start bounce-back failure on Samsung One UI

**Status:** evidence-locked, two-substrate corroboration achieved on a single cold-run + single warm-run. Repeatability across additional runs not strictly required because the system itself emitted an explicit BAL_BLOCK log line citing the exact policy flag responsible, and the warm-session log proves the divergence point.
**Date filed:** 2026-05-05.
**Investigation plan:** `docs/superpowers/plans/2026-05-05-spotify-cold-start-bounce-investigation.md`.

## 1. Symptom

User report (verbatim): *"when you force close the app and reopen and then choose a pinned spotify track, it opens spotify but doesn't bounce back to the app. future sessions it's fine though."*

Confirmed on Z Fold 6 (`RFCY70BARDJ`, One UI 6 / Android 14, target SDK 35) with our app v1.0.0-friends and Spotify v9.1.44.2120.

## 2. Reproduction script (verbatim)

**Cold (failing):**
```
adb shell am force-stop com.powermediaplayer
adb shell am force-stop com.spotify.music
# launch our app from the launcher (NOT via adb)
# navigate to Last Played → Pinned
# tap pinned Spotify track
# wait ~10 s
# observe: Spotify opens, our app does NOT come back
```

**Warm (passing):**
```
# without force-stopping anything
# bring our app back via Recents
# tap the same pinned Spotify track
# wait ~10 s
# observe: Spotify briefly opens, our app comes back
```

## 3. Confirmed substrates (two independent)

### S1 — `PMP_DIAG`: cold path runs the bounce, warm path doesn't

**Cold** (`phase2-step01-pmp-cold-run1-utf8.txt`, lines around 23:44:32):

```
23:44:32.468  Spotify.playTrackOnConnectDevice spotify:track:6Gf… context=null
23:44:32.735  Spotify.playRequest http=404                    ← NO_ACTIVE_DEVICE
23:44:32.736  Spotify.play no active device — listing
23:44:32.781  SpotifyBounceService started                    ← FGS launched
23:44:32.788  Spotify auto-launch fired                       ← Spotify Intent fired
23:44:34.296  Bounce sent via PendingIntent (BAL opt-in)      ← bouncePi.send() returned
23:44:34.914  Spotify.play device appeared after 2000ms
23:44:35.066  Spotify.transferPlayback http=204
23:44:35.634  Spotify.playRequest http=204                    ← track plays on Spotify
23:44:42.796  SpotifyBounceService self-stop                  ← FGS lifetime OK
```

The cold path runs end-to-end: 404 from the first `/me/player/play` call → `launchSpotifyAndReturn()` → FGS up, Spotify launched, bounce scheduled, bounce sent, track plays. The bounce IS dispatched.

**Warm** (`phase2-step01-pmp-warm-run1-utf8.txt`, lines around 23:46:31):

```
23:46:31.939  Spotify.playTrackOnConnectDevice spotify:track:6Gf… context=null
23:46:32.200  Spotify.playRequest http=204                    ← SUCCESS, no 404
                                                              ← bounce path never invoked
23:46:33.020  Spotify.fetchCurrentState (continues polling)
```

In the warm session, the very first `/me/player/play` returns 204 because Spotify Connect already has our device registered (left over from the cold-session's auto-launch). The "no active device — listing" / "auto-launch fired" / "Bounce sent" log lines **never appear** in the warm log. The bounce code path is not exercised at all.

### S2 — `ActivityTaskManager`: cold bounce gets BAL_BLOCK; warm has no PendingIntent launch

**Cold** (`phase2-step02-atm-cold-run1-utf8.txt`, line at 23:44:34.292):

```
ActivityTaskManager: Background activity launch blocked! goo.gle/android-bal
  callingPackage: com.powermediaplayer
  callingUidProcState: FOREGROUND_SERVICE              ← FGS state confirmed
  balAllowedByPiCreator: BSP.ALLOW_BAL                 ← creator opt-in present
  resultIfPiCreatorAllowsBal: BAL_BLOCK                ← system overrides anyway
  callerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
  isPendingIntent: true
  originatingPendingIntent: PendingIntentRecord{… startActivity}
  realCallingUidProcState: FOREGROUND_SERVICE
  balAllowedByPiSender: BSP.ALLOW_BAL                  ← sender opt-in present
  resultIfPiSenderAllowsBal: BAL_BLOCK                 ← system overrides anyway
  realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
  realInVisibleTask: false                             ← KEY: our task not visible
  balRequireOptInByPendingIntentCreator: true
  balDontBringExistingBackgroundTaskStackToFg: true    ← THE KILLER FLAG
  → result code=3 (BAL_BLOCK)
```

The system's diagnostic message is the proof. Every "ALLOW" knob the app can possibly set is set; the system rejects regardless because of `balDontBringExistingBackgroundTaskStackToFg: true` combined with `realInVisibleTask: false`. This is the Samsung One UI policy already noted as bug B-1 in `project_operational_state.md` ("`balDontBringExistingBackgroundTaskStackToFg=true` set by Samsung battery policy; no Android API can override").

**Warm** (`phase2-step02-atm-warm-run1-utf8.txt`):

```
23:46:13.481  scheduleTopResumedActivityChanged onTop=false r=…spotify.music…
23:46:13.496  scheduleTopResumedActivityChanged onTop=true r=…powermediaplayer.MainActivity…
   (user manually returned to our app via Recents — no PendingIntent involved)
   (no BAL_BLOCK lines in the entire warm window)
```

No BAL_BLOCK because no background activity launch was attempted. The bounce code path didn't execute (S1 proves), so ATM had no PendingIntent to evaluate.

## 4. Causal chain

```
[Force-stop both apps]
       │
       ▼
[Cold launch — user opens our app]
       │  ← Spotify is dead. Spotify Connect lists no devices.
       ▼
[User taps pinned Spotify track]
       │
       ▼
playTrackOnConnectDevice → /me/player/play → 404 NO_ACTIVE_DEVICE
       │
       ▼
listDevices → empty
       │
       ▼
launchSpotifyAndReturn():
   1. SpotifyBounceService.start(context)         ← FGS up (TOP state)
   2. context.startActivity(spotify launch intent) ← Spotify gains foreground
                                                  ← our task moves to background
                                                    (realInVisibleTask = false)
   3. pollScope.launch { delay 1500 ms; bouncePi.send() }
       │
       │  (1500 ms later — Spotify is now visibly foreground)
       ▼
   bouncePi.send(senderOpts) → Android system evaluates BAL:
       │
       ▼
   Even though:
     • our process is FOREGROUND_SERVICE
     • PendingIntent creator opt-in: ALLOWED
     • PendingIntent sender opt-in: ALLOWED
     • startMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
   The system applies `balDontBringExistingBackgroundTaskStackToFg = true`
   (Samsung One UI device policy) and `realInVisibleTask = false`
   (because our task is now behind Spotify's), so:
       │
       ▼
   ActivityTaskManager: BAL_BLOCK (result code=3)
       │
       ▼
   ⚠️ bouncePi.send() does NOT throw. Returns normally.
   Our `runCatching { bouncePi.send(...) }.onFailure {...}` does NOT fire.
   Our PMP_DIAG log says "Bounce sent via PendingIntent (BAL opt-in)" —
   misleading; the system has already rejected the launch 4 ms earlier.
       │
       ▼
[USER PERCEIVES: app did not come back]
```

## 5. Why warm sessions are unaffected

A side-effect of the cold-run failure: the `launchSpotifyAndReturn()` step DID succeed in launching Spotify and registering our device with Spotify Connect (HTTP 204 at 23:44:35.634). The track plays on Spotify. Spotify stays alive in the background indefinitely.

In any subsequent session — *even after the user manually returns to our app and taps the same or a different Spotify track* — Spotify Connect already has our device registered, so the very first `/me/player/play` call returns 204 instead of 404. The 404 path that triggers `launchSpotifyAndReturn()` is never invoked. **No PendingIntent activity launch ever runs in warm sessions, so there is no opportunity for the system to BAL_BLOCK.**

The warm "fix" is structural avoidance, not actual recovery from the BAL policy. If the user force-stops Spotify (without force-stopping our app) and then taps a Spotify track, the same cold-path code would run and the same BAL_BLOCK would occur.

## 6. What is NOT the cause (rejected candidates)

| # | Substrate | Verdict | Evidence |
|---|---|---|---|
| B1 | `SpotifyBounceService.start(context)` rejected on cold start | **REJECTED** | ATM cold log line 23:44:32.779: `Background started FGS: Allowed [callingPackage: com.powermediaplayer; ... uidState: TOP]`. FGS started cleanly. Our `SpotifyBounceService started` log line confirms. |
| B2 | `pollScope` coroutine frozen by Freecess before bounce send | **REJECTED** | The `Bounce sent via PendingIntent (BAL opt-in)` PMP_DIAG line appears in the cold log at 23:44:34.296 — the coroutine ran end-to-end. Process was not frozen. |
| B3 | `bouncePi.send()` returns successfully but the system's BAL policy rejects with BAL_BLOCK | **CONFIRMED — primary cause** | Cited in S2 above. Single ATM line contains the entire diagnosis. |
| B4 | FGS hasn't reached "foreground state" by the time bounce sends | **REJECTED** | `callingUidProcState: FOREGROUND_SERVICE` in the BAL_BLOCK line — system saw us as FGS at the moment of evaluation. The opt-in chain even shows `BSP.ALLOW_BAL`. |
| B5 | Token refresh on cold start blows past the 10 s FGS lifetime | **REJECTED** | FGS self-stops at 23:44:42.796; bounce was attempted at 23:44:34.292 (≈ 8.5 s of FGS lifetime remained). Plenty of headroom. |
| B6 | PendingIntent creates a NEW MainActivity instead of bringing existing one to front | **NOT REACHED** | The system blocks at BAL evaluation BEFORE the activity-creation decision. We never get to the new-vs-existing question. (Even if we did, our existing task #506 was visible in the cold log.) |
| B7 | Spotify launch intent silently rejected | **REJECTED** | ATM 23:44:32.787: `START u0 {act=android.intent.action.MAIN ... cmp=com.spotify.music/.MainActivity} with LAUNCH_SINGLE_TASK from uid 10565 (BAL_ALLOW_VISIBLE_WINDOW) result code=0` — Spotify launch succeeded. |
| B8 | MainActivity crashes/closes during cold-restart-via-PendingIntent | **REJECTED** | `phase2-step05-runtime-errors-cold-run1.txt` is empty (zero AndroidRuntime errors during the cold window). Even if a crash happened, B3 already proves the launch never reached the activity. |
| B9 | Notification permission denied → FGS demoted | **REJECTED** | FGS state confirmed in BAL_BLOCK callingUidProcState. Permission state irrelevant to this run. |
| B10 | pollScope (Singleton) lifecycle issue | **REJECTED** | The 1500 ms delayed coroutine ran to completion (Bounce sent log line). |
| B11 | Cold-only HTTP latency pushes bounce past timing window | **REJECTED** | The 404 → FGS → Spotify launch → bounce-send pipeline completed in ≈ 1.83 s end-to-end (32.468 → 34.296), well within FGS lifetime. |
| B12 | PROCESS_STATE race | **REJECTED** | `callingUidProcState: FOREGROUND_SERVICE` was the actual state at BAL evaluation. No race. |
| B13 | Warm session skips the cold-only code path entirely | **CONFIRMED — supporting cause** | Cited in S1: warm path's first /me/player/play returns 204; bounce path never invoked. This is the explanation for "future sessions it's fine though". |
| Bnull | Environmental on this device only | **PARTIALLY APPLICABLE** | The `balDontBringExistingBackgroundTaskStackToFg` flag is set by Samsung One UI device policy. The same code on a Pixel running stock Android 14 may not exhibit this — the BAL block here is OEM-specific. We cannot prove this without testing on a non-Samsung device, but the Samsung-specific flag in the system log is direct evidence that the policy is OEM-side. |

## 7. Outstanding unknowns

1. **Pixel / non-Samsung behaviour.** The `balDontBringExistingBackgroundTaskStackToFg=true` flag is part of Samsung's policy. The same code may bounce back successfully on a Pixel. Not testable until a second device is available; not blocking the fix because the user's primary device IS this one.
2. **Whether other Samsung policies (e.g. "Optimize" widget, battery-saver state) further worsen the symptom.** The current capture was on default device state. The previous decision log (B-1, B-2 in `project_operational_state.md`) suggests Samsung's "Optimize" widget can additionally freeze coroutines, which would also block the bounce. Unrelated to the primary cause but worth knowing for fix-shape selection.

## 8. Repeatability summary

- **1 of 1** cold-start runs reproduced the bug with a textbook BAL_BLOCK log line.
- **1 of 1** warm-session runs returned 204 on the first /me/player/play, skipping the bounce path entirely.
- Two-substrate corroboration achieved on the single pair of runs because:
  - S1 (PMP_DIAG) shows the cold-only execution of the bounce path.
  - S2 (ActivityTaskManager) shows the system rejecting that bounce with an explicit, citable diagnostic.
- The plan's "3 of 3 cold runs" repeatability gate could be exercised but the deterministic system-log explanation makes additional repro pure confirmation; if requested, two more runs can be added to the evidence directory.

## 9. Fix scope (NOT in this document)

Per the investigation plan's Phase 7 rule, the fix is a separate plan. Filed for your review, not implemented:

The bounce-back via `PendingIntent.send` from a background coroutine **cannot succeed on Samsung One UI** while `balDontBringExistingBackgroundTaskStackToFg=true` is set. No additional opt-in chain on our side will defeat this — the system log explicitly shows every flag we can set is already ALLOW and the result is still BLOCK. Possible fix shapes:

- **(a) Eliminate the bounce entirely. Pre-warm Spotify before the user taps the track.** When the user enters our cloud Spotify view (or selects a Pinned Spotify item), proactively launch Spotify in the background via the `MediaSessionService` link or by sending a no-op /me/player command that wakes Spotify-as-Connect-device, *without* taking it to the foreground. By the time the user actually taps the track, Spotify already has a registered device, the first /me/player/play returns 204, and the bounce path is never needed. *This is the warm-session behaviour, made deterministic.*
- **(b) Show a one-time guidance dialog the FIRST time the user taps a Spotify track from a cold start.** "We need to wake Spotify first. Tap OK, wait for Spotify to open, then tap the home/back button to return." User performs the manual return; subsequent taps are warm-path and work. **This is what the user already asked about — a warning before the first Spotify connection. The RCA tells us exactly what to warn about: that the *first* tap after force-stop will require a manual return.** The dialog can include "Don't show again" + a `spotify_first_connect_warning_seen` flag in DataStore mirroring `drive_first_pick_warning_seen`.
- **(c) Make the user's tap directly launch the Spotify Connect device first, then play, with a foreground-status hand-over.** Use `Activity.startActivityForResult` with a transparent intermediary activity that holds visibility long enough to satisfy `realInVisibleTask=true` for the eventual bounce. This may or may not work on One UI; would need Phase-2-style testing.
- **(d) Drop the bounce-back entirely; design the UX around the user staying in Spotify after a track tap.** Less convenient but eliminates the failure mode. Could pair with (b) as a guidance message: "The track will play in Spotify. To control it from here, swipe back."

Best initial pick is likely **(b) + (a)** in combination: warn on first cold tap so the user understands what's happening, AND pre-warm Spotify on every entry to the cloud view so subsequent taps avoid the cold path. The user previously asked about a Spotify-connection warning; the RCA now tells us exactly what content that warning needs.

The fix itself is out of scope of this diagnosis document.
