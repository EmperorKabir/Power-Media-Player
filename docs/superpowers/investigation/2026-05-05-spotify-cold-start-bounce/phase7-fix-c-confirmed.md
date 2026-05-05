# Fix-shape (c) — Confirmed Working on Samsung One UI 6

**Date:** 2026-05-06.
**HEAD where confirmed:** `ccd892b` (bridge v2 with `noHistory` removed).

## Summary

The translucent in-task `SpotifyBounceBridgeActivity` approach successfully bounces back from cold start. Two-substrate evidence captured.

## What changed from the failing PendingIntent approach

The original `launchSpotifyAndReturn` did `pollScope.launch { delay(1500); bouncePi.send(...) }` — a PendingIntent.send fired from a background coroutine 1.5 s after Spotify was launched. Samsung One UI 6 BAL_BLOCK'd this with `balDontBringExistingBackgroundTaskStackToFg: true` because:
- The caller (the coroutine) was a non-Activity context.
- The PendingIntent's "real caller task" had no visible activity at the moment of send (Spotify was foreground, our task was buried).

The replacement: `SpotifyProvider.launchSpotifyAndReturn` now `startActivity`s a translucent, no-UI activity (`SpotifyBounceBridgeActivity`) that:
1. Lives in MainActivity's task (no `taskAffinity` override).
2. Starts the FGS and launches Spotify in `onCreate`.
3. Schedules a `Handler.postDelayed(1500)` that calls `startActivity(MainActivity)` then `finish()`.

When the deferred bounce fires, the system attributes the launch to the bridge Activity, which was visible ≤ 1.5 s prior. That qualifies under Android's BAL grace period — independent of Samsung's `balDontBringExistingBackgroundTaskStackToFg` flag.

## Evidence (two substrates)

**S1 — `PMP_DIAG`** (`phase5-bridgev2-pmp-cold-utf8.txt`):
```
00:02:40.739  Spotify.playTrackOnConnectDevice spotify:track:6Gf… context=null
00:02:41.094  Spotify.playRequest http=404
00:02:41.094  Spotify.play no active device — listing
00:02:41.222  Spotify auto-launch dispatched via BounceBridge
00:02:41.230  BounceBridge.onCreate
00:02:41.232  SpotifyBounceService started
00:02:41.236  BounceBridge: Spotify auto-launch fired
00:02:42.740  BounceBridge: bounce startActivity dispatched   ← +1504 ms after onCreate
00:02:42.741  BounceBridge.onDestroy
00:02:44.291  Spotify.play device appeared after 2500ms
00:02:45.339  Spotify.playRequest http=204
```

No "bounce startActivity failed" log. The `dispatched` log fires at exactly the expected timing.

**S2 — `ActivityTaskManager`** (`phase5-bridgev2-atm-cold-utf8.txt`):
```
00:02:41.222  START u0 {…cmp=BounceBridge} from uid 10565
              (BAL_ALLOW_VISIBLE_WINDOW) result code=0     ← bridge launch allowed (we tapped)
00:02:41.236  START u0 {…cmp=spotify.music/.MainActivity} from uid 10565 (sr=10414907)
              (BAL_ALLOW_VISIBLE_WINDOW) result code=0     ← Spotify launch allowed
00:02:42.740  START u0 {…cmp=powermediaplayer/.MainActivity} from uid 10565 (sr=10414907)
              (BAL_ALLOW_GRACE_PERIOD) result code=2       ← THE BOUNCE: ALLOWED
00:02:42.741  Duplicate finish request for r=…BounceBridge  ← bridge cleanly self-finishes
00:02:42.748  scheduleTopResumedActivityChanged onTop=true r=…powermediaplayer/.MainActivity
```

`BAL_ALLOW_GRACE_PERIOD` is the system's explicit approval. `result code=2` = `START_TASK_TO_FRONT` (existing task brought forward — ideal). The previous run's `BAL_BLOCK` line is conspicuously absent.

## Why fix-shape (b) is no longer needed

Fix-shape (b) (one-time warning dialog) was the fallback. With (c) confirmed working, the user never sees the failure mode that (b) was meant to communicate. Per the user's earlier instruction *"investigate c as a temporary measure and if it fails, we do b only where the clarification is made for first time use and also after restarts and force closes"*, (c) succeeded so (b) is shelved. If a future device or OS version regresses (c), (b) is still the documented fallback.

## What this leaves in code

- `app/src/main/java/com/powermediaplayer/service/SpotifyBounceBridgeActivity.kt` — new file, ~70 lines.
- `app/src/main/AndroidManifest.xml` — one new `<activity>` declaration with `Theme.Translucent.NoTitleBar`, `excludeFromRecents=true`, `exported=false`. **Crucially no `noHistory=true`** (an earlier prototype with that attribute was destroyed at 386 ms before its 1.5 s timer fired — see `phase5-bridge-pmp-cold-utf8.txt`).
- `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` `launchSpotifyAndReturn` — reduced from ~85 lines (PendingIntent + creator/sender BAL opt-ins + pollScope coroutine) to ~15 lines (startActivity the bridge). The PendingIntent `BOUNCE_PI_REQUEST_CODE` constant is no longer referenced; can be removed in a future cleanup pass.

## Falsified candidate

- B3 (BAL_BLOCK on PendingIntent): **CONFIRMED** as the original cause of the failure (RCA section 3 still stands).
- B3 (BAL_BLOCK applies equally to all background activity launches): **REJECTED**. The grace-period exemption defeats the Samsung policy when the caller is an Activity rather than a PendingIntent. We didn't predict this in the original Phase 3; this is genuinely new evidence.

## Outstanding items

- The `SpotifyBounceService` foreground service still runs for 10 seconds; it's no longer strictly required since the bridge's grace period handles BAL on its own. Could be removed in a future cleanup. Leaving it in place for safety until proved redundant on more devices.
- No second-device verification yet; this is currently confirmed only on the Z Fold 6.
