# Investigation CHANGELOG

## Stop-the-world rule
This investigation MUST NOT be paused mid-phase to ship unrelated fixes.
If user requests an unrelated fix, finish the current step, capture
its artefact, and only then context-switch.

## Phase 0 — set-up

### 2026-05-05 — investigation opens

- HEAD at start: `bae040f2720f23503a16dee84fd0bb5955a38c64`
- Device fingerprint: samsung/q7qxeea/q7q:16/BP2A.250605.031.A3/F966BXXS9AZC8_OXM9AZC8:user/release-keys
- Window: Physical size: 1968x2184 @ Physical density: 420 Override density: 311
- App (com.powermediaplayer): versionCode=2 versionName=1.0.0-friends targetSdk=35
- Spotify (com.spotify.music): versionCode=141048312 versionName=9.1.44.2120 targetSdk=36
- Pre-conditions:
  - Spotify installed: YES (`adb shell pm list packages --user 0 com.spotify.music` returned `package:com.spotify.music`)
  - Our app installed: YES
  - PKCE token present: deferred — DataStore directory exists per
    `run-as ls files/datastore/`: settings.preferences_pb, spotify_auth.preferences_pb
  - Pinned Spotify favourite exists: TRUSTED FROM USER REPORT (the
    sqlite3 binary is not available in the device shell to confirm
    via run-as; the ROM has no /system/bin/sqlite3). User has stated
    they reproduce by tapping a pinned Spotify track, so this is the
    operative precondition.

## Pre-committed candidates B1..B13 + Bnull
See `docs/superpowers/plans/2026-05-05-spotify-cold-start-bounce-investigation.md`
for the full table. None ranked or ruled in/out at this stage.
