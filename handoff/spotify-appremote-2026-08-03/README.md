# Handoff — Spotify precise stop-at-end via App Remote SDK (2026-08-03)

## Goal (user requirement, no compromises)
When "Autoplay next" is OFF, a Spotify track must play to its **exact end (0 remaining)** and
**stop without auto-advancing** — no early cut, no next-track flash, no loop, no artifact.
User rejected every partial: stop-early (9:01, "ugly"), advance-then-pause, repeat-loop.

## Why the Web API can't do it (evidence-proven, this session)
The app controls Spotify only via the **Web API**: polls `GET /v1/me/player` at ~1 Hz
(`SpotifyProvider.fetchCurrentState`), which is "eventually consistent" (~1 s stale), and
`pause()` is a ~300-500 ms cloud round-trip. So a timed pause can't hit the boundary:
- lead=700 → stops ~9:01 (early); lead=400 → overshoots + advances.
- Device log `[AUTOPLAY] timed pause fired ok=true rem=742ms` while the track was actually
  at its end → the mirror is ~1 s behind Spotify's real position. Fixed lead can't win.

## The solution (researched w/ Context7 + agents): Spotify **App Remote SDK**
`subscribeToPlayerState()` PUSHES real-time state over a **local IPC** (fresh, ms-latency) and
`pause()` is a local IPC call (tens of ms) — precise enough to land at ~0 remaining. SCOPE
LIMIT (honest, unavoidable): this only works for playback **ON THE PHONE**. For a remote/cast
Connect device the App Remote learns state via the SAME cloud sync as the Web API, so there's
no gain — cast stays Web-API best-effort. No Spotify API offers "stop after current track".

Dependency: NOT on Maven Central (Context7's snippet was wrong; JitPack serves a broken
artifact). Official AAR vendored at `app/libs/spotify-app-remote-release-0.8.0.aar` (130 KB,
from github.com/spotify/android-sdk release v0.8.0-appremote_v2.1.0-auth). Only transitive
need is gson (already present).

## What's DONE this session (vc92/1.5.37 — compiles, installed, WIP committed)
- `app/build.gradle.kts`: `implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))`.
- `SpotifyAppRemoteController.kt` (NEW, @Singleton): connect/disconnect, `subscribeToPlayerState`
  → `StateFlow<RemoteState>` {trackUri,durationMs,positionMs,playbackSpeed,isPaused,atElapsedRealtimeMs},
  local-IPC `pause()`. `connect(ctx: Context = context)`.
- `SpotifyProvider`: added `deviceType` to `SpotifyPlaybackState` + parse `device.type` (for the
  on-phone "Smartphone" vs cast gate).
- `PlaybackSessionCoordinator`:
  - `startSpotifyAppRemoteLifecycle()` — connect App Remote when autoplay-off + playing + local
    (deviceType=="Smartphone"), disconnect otherwise.
  - `startSpotifyAppRemotePreciseStop()` — drives off `spotifyAppRemote.state`; extrapolates
    curPos = position + elapsed×speed; schedules `pause()` `AUTOPLAY_APPREMOTE_LEAD_MS` (=120, UNTUNED)
    before the end. Re-derives each event. Re-verifies before pausing.
  - Existing `startSpotifyAutoplayGuard()` (Web-API timed pause) now GATED to `!handledByAppRemote`
    (= fires only for remote/cast, or on-phone before App Remote connects) — the best-effort fallback.

## THE BLOCKER (where it stopped) — App Remote auth needs a FOREGROUND ACTIVITY
Device evidence (`pmp_diag_session.log`): `[APPREMOTE] connect failed: UserNotAuthorizedException`
×3, and the user got NO consent prompt. Root cause: `connect()` runs from the background app
context (coordinator = @Singleton, app context). Spotify's `showAuthView(true)` consent dialog
for the `app-remote-control` scope can ONLY launch from a **foreground Activity**. So the scope
is never granted → every connect fails → precise-stop never engages → it falls back to the
Web-API timed pause (the ~9:01 early stop the user rejected).

## NEXT SESSION — the fix (2 options)
1. **PREFERRED (no re-sign-in): connect with the foreground Activity.** `MainActivity.onResume`
   already calls `MainActivityHolder.set(this)` (MainActivity.kt:126). Verify `MainActivityHolder`
   exposes the current Activity (add a `get()`/WeakReference if not), then in the coordinator's
   `startSpotifyAppRemoteLifecycle` call `spotifyAppRemote.connect(MainActivityHolder.get() ?: appCtx)`
   — a foreground Activity → the consent shows once; after granting, background reconnects work
   with the app context. (Or wire the connect from MainActivity directly via a
   `repeatOnLifecycle(STARTED)` collector of (spotifyState, musicAutoplayNext).)
2. Alternative: add `app-remote-control` to the AppAuth OAuth scopes (`SpotifyProvider` auth
   request) + `connect(showAuthView=false)` — works from any context (background too) BUT needs
   the user to RE-SIGN-IN to Spotify to grant the new scope.

Then: build, install, FORCE-STOP (install -r doesn't restart the process — new code won't load
otherwise; proven this session), reopen, play Spotify on the phone with autoplay OFF, approve the
one-time consent, let a track end → expect stop at 0 remaining. Then **tune AUTOPLAY_APPREMOTE_LEAD_MS**
on device (local IPC is ~tens of ms, so ~50-150 ms should land at 9:02/0-remaining; measure the
final paused position from `dumpsys media_session` vs duration).

## Also noted
- The lyric-tap "starts mid-line" was PROVEN not a code bug (track-specific bad LRC timing on the
  "City Hall" medley; git shows the seek/parse code unchanged). See [[reference_lrclib_ua_block]].
- Lyrics fix (LRCLib UA → HTTP 520) shipped + verified earlier (vc87/88, commit 3edc438).
- I had left the media stream volume low (21/160) → the "plays silently" reports; raised to 141/160
  this session. Not an app bug.
- Diag `Spotify.seekTo requested=Xms` + `[AUTOPLAY] …` + `[APPREMOTE] …` lines are the debug hooks.

## Files in this handoff
- `pmp_diag_session.log` — the App-Remote-fail + autoplay + Spotify-control evidence.
- `session-20260803-*.ndjson` — deep forensic log (parse w/ tools/deeplog/parse_logs.py).
- `logcat_tail4000.log` — raw logcat snapshot (extras).
- `device_state.txt` — vc / permission / volume snapshot.
