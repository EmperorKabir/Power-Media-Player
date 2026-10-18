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

## Results (final gate run 2026-10-18 13:40, vc125 on the Oppo)

21 PASS / 0 FAIL of 21 scenarios. Every scenario was executed on the phone; V9 is the one that cannot be forced (WorkManager) and is passed on the registration evidence only.

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
| V18 | PASS | Run 2026-10-18 13:27 on Living Area TV: SDK `Connected to device`, Default Media Receiver launched, relay served ep2 then ep1, `CASTPOS idx 0→1`, the TV (192.168.50.22) fetched `/cover.jpg` from the PC server; cast stopped afterwards. The 00:08 drop was transient (same code, same TV). |
| V19 | PASS | `Downloaded (4)` with the book + 3 episodes; `Downloaded (1)` after deleting the test downloads |
| V20 | PASS | paused at 13220 ms, Library row tap resumed at 9801 ms (backoff 5 s) |
| V21 | PASS | restore loaded ep2 at +1.40 s, the tap at +1.44 s loaded ep3 at +1.87 s and it played (tapped item wins) |

### Bugs found by this matrix and fixed in the same build (vc125)
- Per-kind speed filed under the wrong kind (V10): the phone-side classifier on the MediaController saw a chaptered Drive audiobook as a podcast (the controller's metadata lacked the chapter extras), so the slider stored the book's 1.25x under `speed_kind_podcast`. Fix: one classifier. The service publishes `PlaybackService.currentKindFlow` (re-evaluated when chapters resolve late) and the slider persists under that. Re-verified: `speed remembered kind=audiobook speed=1.25`, `speed rule applied kind=podcast 1.25->1.50`, `kind=audiobook 1.50->1.25`.
- Typed feed URL with a keyboard-inserted space (V1): "feed. xml" reached OkHttp as `feed.%20xml` (HTTP 404). Fix: `addByUrl` strips all whitespace; `RssFeedParser.fetchResult` returns `NotFeed("invalid URL")` for a URL OkHttp rejects instead of letting the exception escape. Unit test added.
- Debug-only cleartext exception for the developer PC (`src/debug/res/xml/network_security_config_debug.xml`) so a locally served feed can be used for scenario tests; release is unchanged.

### Test-artefact cleanup (done 2026-10-18 13:45)
- Saved A-B loop on test ep1 cleared (`AB-loop cleared (user)`), test downloads deleted (DB 0 rows, Library `Downloaded (1)`), test subscription unsubscribed, test rows removed from Last Played, cast stopped, playback paused.
- Stored speeds restored: audiobook 1x (Reset Speed), podcast 1.3x (the value the user had before the tests). The audiobook's test-time history rows were deleted so the user's 19:55:02 position is the newest row again (the resume picker takes the newest row).
- PC feed server and log captures stopped. To repeat the matrix: `tools/verify/ph_device.sh`, `tools/verify/mkfeed.py`, debug-only cleartext exception for 192.168.50.31.
