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

## What shipped this turn
- An **unconditional transparency note** in the in-app info text explaining the
  alarm-background attribution + that playback/sync stop on close.

## Device-trace hardening (optional verification, `[DEVICE]`)
Each vector can be proven to terminate on close by adding a uniform
`DiagLog.bg("<vector> stopped …")` line at its teardown and confirming on a
swipe-away trace that every vector emits its stop line and none keeps logging.
This is verification (the vectors are already bounded by design); the
transparency note is the user-facing deliverable.
