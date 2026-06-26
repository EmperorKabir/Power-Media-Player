# #17 — background activity when the app is "closed"

## Question
Android reported the app operating in the background even when closed. Why?

## Vector inventory (all bounded; no leaked loop)
1. **Foreground media service** — `PlaybackService` honours `onTaskRemoved` /
   `stopOnTaskRemoved` (default true): swiping the app away stops playback +
   the FGS. Lives only while actually playing.
2. **`AlarmManager.setAlarmClock`** (`alarm/AlarmScheduler.kt`) — the strongest
   and **legitimate** "running when closed" attribution: a user-set clock alarm
   is scheduled with the OS and re-armed on boot. This is the alarm WAITING to
   ring, not continuous playback — the expected behaviour of any alarm app.
3. **`PodcastSyncWorker`** — periodic 6 h, **Wi-Fi-only**, WorkManager-bounded.
4. **Spotify 1 Hz poll** — bounded; a 30 s background-stop tears it down when
   the app backgrounds (`SpotifyProvider.stopPlaybackPolling` / the bg-stop job).
5. **Hue DTLS** — gated on playback; disconnects when playback ends.

## Conclusion
No leaked, unbounded loop. The OS "background" attribution the user saw is the
expected behaviour of a scheduled **alarm clock** they set (vector 2). No
resume-logic / leak fix is warranted on current evidence.

## What shipped (no-downgrade directive — code, not doc-only)
- **Unconditional transparency note** in the in-app info text (`InfoContent.kt`)
  explaining the alarm-background attribution + that playback/sync stop on close.
- **Uniform `DiagLog.bg(...)` shutdown instrumentation** at every vector's
  teardown so a swipe-away/close trace proves each terminates:
  - FGS — `PlaybackService.onTaskRemoved` (`"FGS stopped reason=onTaskRemoved"` /
    `…-idle` for the opted-out idle path).
  - Spotify 1 Hz poll — `SpotifyProvider.stopPlaybackPolling`
    (`"spotify-poll stopped reason=stop gen=N"`).
  - Hue DTLS — `HueEntertainment.stop` (`"hue stopped reason=stop"`).
  - Podcast sync — `PodcastSyncWorker.doWork` end (`"podcast-sync finished …"`).
  - Alarm — `AlarmScheduler.cancel` (`"alarm cancelled reason=cancel id=N"`); a
    LIVE `setAlarmClock` legitimately persists (it is the alarm waiting to ring).
- **Hard stop-on-close safeguard (17.3): already-enforced.** Each vector's
  teardown is invoked by existing lifecycle code (FGS `onTaskRemoved`; Spotify
  30 s background-stop + `stopPlaybackPolling`; Hue disconnect on playback end;
  worker is WorkManager-bounded). No new teardown code was required — the trace
  (below) is the proof, per the no-guesswork rule (do not pre-empt a leak the
  trace doesn't show).

## Device-trace verification (`[DEVICE]`, deferred to the consolidated pass)
Swipe the app away / close while a clock alarm is set; pull the log and confirm
every vector emits its `BG` stop line and none keeps logging afterward (only the
set `setAlarmClock` remains scheduled, by design).
