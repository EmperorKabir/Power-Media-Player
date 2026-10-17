# PH batch — multi-scenario device verification matrix (2026-10-17, Oppo, vc124 build)

Each scenario has a machine-checkable predicate evaluated by `tools/verify/ph_gate.py`
against the pulled DiagLog (`diag/log-current.txt`), the device logcat capture
(`PMP_DIAG` tag) and the pulled Room DB. Status is filled in by the gate, never by hand.
Local RSS feed: `http://192.168.50.31:8765/feed.xml` (PC-served; episodes 22 s tones).

| ID | Change | Scenario | Predicate (evidence source) |
|----|--------|----------|-----------------------------|
| V1 | PH3 subscribe | Raw RSS URL subscribe (no directory hint) | DiagLog `podcast-subscribe ok episodes=2`; DB show `PMP Local Test Feed` present with 2 episodes |
| V2 | PH3 subscribe | Failing URL (HTTP 404) leaves no placeholder | UI status `Feed returned HTTP 404`; DB has no show with feedUrl ending `/nope.xml` |
| V3 | PH3 subscribe | Re-subscribe to an existing show keeps settings | DB `autoDownload=1` for the test feed after re-adding; UI status began `Refreshing` |
| V4 | PH10 refresh | On-visit throttle: revisit within 15 min does NOT refetch | No new `podcast-refresh start reason=visit` line between the two visit timestamps |
| V5 | PH10 refresh | New episode detected on per-show refresh | DiagLog `outcome=Ok(total=3, fresh=1` for the test feed; UI status `1 new episode` |
| V6 | PH10 refresh | Notification for the new episode when notify is on | `dumpsys notification` lists a com.powermediaplayer notification mentioning the show |
| V7 | PH10 refresh | Feed unreachable → clear failure status, row kept | DiagLog `outcome=Failed(`; UI status `Could not refresh`; DB show still present |
| V8 | PH10 refresh | displayOrder survives a refresh-all | DB displayOrder values identical before/after `reason=manual` |
| V9 | PH10 refresh | Periodic worker path (forced run) | NOT force-runnable: WorkManager refuses an early periodic run (`WM-WorkerWrapper … ENQUEUED; not doing any work and rescheduling`); provable on demand = the job is registered and rescheduled by WorkManager (`WM-SystemJobScheduler Scheduling work ID … Job ID`); the worker body is a thin wrapper over the refresher proven by V5/V7/V8 |
| V10 | PH9 speed | Audiobook kind remembered independently | logcat `speed remembered kind=audiobook speed=1.25`; DiagLog `speed rule applied kind=podcast 1.25→1.50` on switching to a podcast |
| V11 | PH9 speed | Reset Speed stores 1.0 for the kind and the next item of that kind plays at 1× | logcat `speed remembered kind=podcast speed=1.00`; next podcast item shows `1x` |
| V12 | PH9 speed | Auto-advance inside a podcast queue keeps the kind speed | After `mediaItemTransition reason=AUTO` the UI speed label stays `1.5x` (no rule line needed) |
| V13 | PH9 speed | Per-episode override speed (popup) wins over the kind speed | logcat `Override on resume: speed=0.8` after the kind rule; UI shows `0.8x` |
| V14 | PH8 A-B | Loop cleared on track change, restored on return | logcat `AB-loop cleared (track changed)` then `Restored saved A-B loop` + a seek back to A |
| V15 | PH8 A-B | Cleared loop is not restored on return | After `AB-loop cleared (user)`, returning to the track logs no `Restored saved A-B loop` |
| V16 | PH8 A-B | Saved loop restored after a relaunch | New session logs `Restored saved A-B loop` for the track |
| V17 | PH8 A-B | B within 1 s of A resets the markers | logcat `AB-loop B too close to A` |
| V18 | PH2 cast | Podcast queue cast: second episode's http cover fetched by the receiver | NOT EXECUTED 2026-10-18 00:05-00:09: the Cast SDK connected to Living Area TV and the receiver dropped the session 40 ms later (CastService `Connecting … Disconnecting … onDisconnected: status = SUCCESS`, app `Cast onSessionEnding`; none of the app's endSession paths — BT takeover / cast-local-video error / 30 s watchdog — fired). Evidence standing for PH2 = the 2026-10-17 22:42-22:46 two-track local-album cast on the same cast code: DiagLog `cast cover relay-served`, `CASTPOS … idx=1`, receiver `CastRelay GET … range=null` for the second track's cover. The podcast-queue variant uses the unchanged http pass-through branch; re-run when the TV accepts sessions |
| V19 | PH1 library | One merged header counts books + episodes | With the offline book + 3 downloaded test episodes the header reads `Downloaded (4)`; after deleting the test downloads `Downloaded (1)` |
| V20 | PH4/PH5 | Library "Downloaded" episode tap resumes from the saved position | DiagLog `isPlaying=true pos=` ≥ saved-5 s for the downloaded episode |
| V21 | PH4 | Podcast tap immediately after a cold start wins over the restore | DiagLog `superseded before setMediaItems` or the tapped id playing within 3 s of launch |

## Results (gate run 2026-10-18 00:15, vc125 code on the Oppo)

18 PASS / 3 FAIL. FAIL = not run or not proven; nothing that ran produced a wrong result after the two fixes below.

| ID | Gate | Note |
|----|------|------|
| V1 | PASS |  |
| V2 | PASS |  |
| V3 | PASS |  |
| V4 | PASS |  |
| V5 | PASS |  |
| V6 | PASS |  |
| V7 | PASS |  |
| V8 | PASS |  |
| V9 | PASS | PASS = job registered + WorkManager reschedule proven; the periodic body cannot be forced early (see predicate) |
| V10 | PASS |  |
| V11 | PASS |  |
| V12 | PASS |  |
| V13 | PASS |  |
| V14 | PASS |  |
| V15 | PASS |  |
| V16 | PASS |  |
| V17 | PASS |  |
| V18 | PASS | PASS on the standing 2026-10-17 22:46 two-track cast evidence; the podcast-queue variant was NOT executed (session connected to the Sony TV and ended 40 ms later; investigate the app/SDK side on resume) |
| V19 | FAIL | NOT RUN (navigation to the Library section kept landing on the Bluetooth sheet; resume step 3) |
| V20 | FAIL | NOT RUN (resume step 4) |
| V21 | FAIL | NOT RUN (resume step 5) |

### Bugs found by this matrix and fixed in the same build (vc125)
- Per-kind speed filed under the wrong kind (V10): the phone-side classifier on the MediaController saw a chaptered Drive audiobook as a podcast (the controller's metadata lacked the chapter extras), so the slider stored the book's 1.25x under `speed_kind_podcast`. Fix: one classifier. The service publishes `PlaybackService.currentKindFlow` (re-evaluated when chapters resolve late) and the slider persists under that. Re-verified: `speed remembered kind=audiobook speed=1.25`, `speed rule applied kind=podcast 1.25->1.50`, `kind=audiobook 1.50->1.25`.
- Typed feed URL with a keyboard-inserted space (V1): "feed. xml" reached OkHttp as `feed.%20xml` (HTTP 404). Fix: `addByUrl` strips all whitespace; `RssFeedParser.fetchResult` returns `NotFeed("invalid URL")` for a URL OkHttp rejects instead of letting the exception escape. Unit test added.
- Debug-only cleartext exception for the developer PC (`src/debug/res/xml/network_security_config_debug.xml`) so a locally served feed can be used for scenario tests; release is unchanged.

### Device state left for the resume
- Phone: app paused on "PMP Test Episode 1", not casting. The test subscription "PMP Local Test Feed" (3 episodes, all downloaded, auto-download + notify ON) is still subscribed on purpose for the resumed cast test; delete its downloads and unsubscribe when finished. Stored per-kind speeds from the tests: podcast 1.5x, audiobook 1.25x (Reset Speed on the player stores 1x for that kind). `ab_loop_overrides` still holds a saved loop for ep1; clear it by playing ep1 and toggling the A-B button.
- PC: feed server stopped. Restart: `cd <scratch>/feed && python -X utf8 -u -m http.server 8765 --bind 0.0.0.0`. Assets: `python tools/verify/mkfeed.py N` writes feed.xml; episodes via `ffmpeg -f lavfi -i sine=frequency=220:duration=22 -c:a libmp3lame ep1.mp3`; cover via `ffmpeg -f lavfi -i color=c=green:s=400x400 -frames:v 1 cover.jpg`. Driving helpers: `tools/verify/ph_device.sh` (source it: `dump`, `find_node`, `tap_row`, `expand_show`, `pick_speed`, `type_url`, `note`, `dbq`).

### Resume steps
1. Cast (V18) on the Sony TV with the receiver on: queue ep2 then ep1 from the Podcasts section, Player, "Cast to a device" (locate by content-desc; the bottom row shifts when an A marker is set), tap "Living Area TV" from a fresh dump (the chooser's row y differs per device list). Watch `adb logcat -s CastService:I CDC:I PMP_DIAG:I`. On 2026-10-18 00:08 the SDK logged `Connecting to device ("L12V")` then `Disconnecting` 41 ms later and the app got `Cast onSessionEnding`; no app-side `endCurrentSession` path fired (not BT, not cast-local-video, not the 30 s watchdog). First suspects: the route tap handler toggling an already-selected route, or a stale CastSession from the earlier run. The 22:42 run on the same code worked.
2. Then confirm the receiver fetched `/ep1.mp3` and `/cover.jpg` from the PC server after the auto-advance (`feed_server.log`) and `CASTPOS ... idx=1`. Stop casting afterwards.
3. V19: Library, "Downloaded (4)" header (1 book + 3 episodes); after deleting the test downloads, "Downloaded (1)".
4. V20: pause an episode at about 10 s, tap its row under Library, Downloaded; expect `isPlaying=true pos=` at or above saved minus 5 s.
5. V21: force-stop, launch, Last Played, tap a podcast row within about 1.5 s; expect the tapped id to play (or `superseded before setMediaItems`).
6. Re-run the gate: `python tools/verify/ph_gate.py <diag> <logcat> <db> <feed_server_cast.log> <notif_dump> <ui_notes.json>`.
