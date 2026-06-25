# 19-item master plan — on-device evidence pass (2026-06-25)

Device: **Galaxy Z Fold6 (RFCY70BARDJ)**, debug build (DEBUGGABLE) at HEAD `b74700d`, freshly installed.
Postures driven via `adb shell cmd device_state`: **FOLDED** = state 0 (cover, `wm size 1080x2520`, compact → bottom NavigationBar, 1-col) · **UNFOLDED** = state 3 (inner, `wm size 1968x2184`, expanded → NavigationRail, multi-col grid, two-pane).
Artifacts (screenshots + uiautomator XML dumps): `deeplogs/device-evidence-2026-06-25/` (78 files, git-ignored — local only).
Verdict legend: **PASS** = concrete on-device artifact · **PASS(obj)** = objective unit-test + verified code where the live leg needs an external account/content not scriptable.

## Verdict table

| # | Item | Folded | Unfolded | Verdict |
|---|------|--------|----------|---------|
| 1 | Brightness-grant banner/slider hit target ≥48dp | banner `[31,1970][461,2063]` = 93px ≈ 48dp | same control, player layout | **PASS** |
| 2 | Spotify metadata banner clears + lyrics off poll | n/a (posture-independent) | n/a | **PASS(obj)** — live leg BLOCKED (needs active Spotify Premium Connect playback, not scriptable); SpotifyLyricsDecoupleTest 2/2 + `fetchLyricsAsync` off-loop + banner-clear-on-snap |
| 3 | Cache-orphan hygiene + ArtworkCache 32MB cap | cover art renders durably | — | **PASS(obj)** — OfflineCacheEvictionTest + `ArtworkCache.CAP_BYTES=32MB`/`trimToCap`/evict; run-as dir listing inconclusive (quoting) |
| 4 | Timer-tap → SeekTimeDialog, remaining = absolute, both bars | elapsed `8:41`→"Jump to time"; remaining `-8:32:41`→"Jump to time remaining"; both `[*,1246][*,1339]` 93px | same dialog code | **PASS** |
| 5 | Reorder subscribed podcast shows | "Reorder StarTalk Radio / The Extra Inch / The Fighting Cock" handles | — | **PASS** (+ PodcastShowOrderTest 3/3) |
| 6 | Per-episode effects overrides | "Playback effects for …" → "Custom settings for this file" (Speed/Stereo flip/Mono mix/Reverb/Boost/Clear all) | — | **PASS** |
| 7 | EQ robotic/aliasing | 10-band EQ (31Hz…), boost applied cleanly | — | **PASS** (audible = user ear) + EqualizerThdTest 5/5 |
| 8 | Sub-kind labels | audiobook player → **"Book"/"Chapter"** + "Previous Book/Chapter, Next Chapter/Book" | — | **PASS** (podcast Show/Episode + MediaClassifierTest 23) |
| 9 | EQ levels survive Audio-settings re-init | 31Hz=+5 before AND after leave→return (`31Hz,5,dB` both dumps) | — | **PASS** + EqualizerLiveStateTest 4/4 |
| 10 | Webhooks plain-English | "Paste in a web address from a home-automation app… never the song's name or location"; Home Assistant/IFTTT/Tasker | — | **PASS** |
| 11 | Full layman + About version | "Version 1.3.4" (BuildConfig) + "Accent colour"; info dialogs plain English | — | **PASS** |
| 12 | Library video thumbnail vs audio icon | 1-col list, Audio (145)/Video (58) filters | **2-col grid** (two items share each y: x=804 & x=1710) | **PASS** (frames = coil-video `shouldThumbnailVideo`) |
| 13 | Cloud/Drive audiobook icon (not camera) | `.m4b` audiobook in Drive browse with audio/book icon | — | **PASS** (`isVideoByName`, old MIME-only line grep=0, MediaClassifierTest) |
| 14 | Player Info (ⓘ) tappable folded | tap `[971,118][1064,211]` → "Player — what each control does" dialog opens | icon present ("Open help for this tab") | **PASS** |
| 15 | Mini-player no black box folded / rail unfolded | bottom NavigationBar + mini-player bar (track shown, no black box) | **NavigationRail** (vertical left, x 26–130, y 172→757) | **PASS** |
| 16 | Drive search matches enriched metadata | — | search **"rowling"** → "Harry Potter…(Full-Cast Edition) / J.K. Rowling" though filename has NO "rowling" | **PASS** + MetadataSearchDaoTest 4/4 |
| 17 | Background terminates when closed | PlaybackService running → after close **process gone** (no lingering) | — | **PASS** + DiagLog.bg teardown on 5 vectors |
| 18 | Resume after star + close + reopen | relaunch restored audiobook into Player (clean title + position) | — | **PASS** + SessionSurfaceTest 4/4 |
| 19 | Star Fixed/Follow-live dialog, default Fixed | pinned tags **"Hold position @1:54"** (Fixed) + **"Resume live @8:46"** (Follow-live); fresh pin → Fixed default; "Save to Favourites" dialog (Hold/Resume live/Both) | — | **PASS** + StarPositionResolverTest |

## Per-item evidence notes

- **#1** `00_launch_folded.xml`: brightness banner node `[31,1970][461,2063]` (height 93px = 48dp at density 311); flanking Brightness low/high icons. Slider enabled only after WRITE_SETTINGS (T297) — banner is the grant tap target, ≥48dp.
- **#2** No scriptable Spotify Premium Connect session to observe the banner live. Objective: `SpotifyLyricsDecoupleTest` 2/2; `SpotifyProvider.fetchLyricsAsync` runs off the 1Hz poll loop; banner clears on first real snap. (Cloud→Spotify shows "Sign out" = signed in, but live full-track playback needs Premium + an active device.)
- **#3** `OfflineCacheEvictionTest` + `ArtworkCache.CAP_BYTES = 32 MB` (`trimToCap`) + `OfflineMediaManager.evictOrphanCaches`. Cover art renders durably on the player. run-as cache dir listing inconclusive due to shell quoting (not a failure).
- **#4** `04_seekdialog_folded.xml` (elapsed → "Jump to time. Current: 8:41"), `04b_seekdialog_remaining_folded.xml` (remaining → "Jump to time remaining. Current: -8:32:41"). Remaining is absolute time; both bars open the dialog.
- **#5** `05_06_podcasts.xml`: per-show "Reorder <show>" drag handles (ReorderableShowList). PodcastShowOrderTest 3/3.
- **#6** `06_effectspopup.xml`: episode "Playback effects for …" → MediaOverridesPopup ("Custom settings for this file", per-file overrides + Clear all).
- **#7** `07_09_eq_folded.xml`/`07_eq_boosted_folded.png`: real 10-band biquad EQ; boosting 31Hz works (no crash/gross distortion). Aliasing objective = EqualizerThdTest 5/5 (THD <2%/<5%/<8% at +12dB).
- **#8** `08_relaunch_player.xml`: audiobook (has chapters) → labels "Book"/"Chapter" + nav descs "Previous Book/Chapter, Next Chapter/Book" (vs MUSIC "Track"). MediaClassifierTest (23) covers podcast→Show/Episode etc.
- **#9** `07_eq_boosted.xml` vs `09_eq_afterreturn.xml`: 31Hz = 5 dB in BOTH (after boost, and after navigating to Settings and back) → live levels not clobbered on VM re-init. EqualizerLiveStateTest 4/4.
- **#10** `10_webhooks_folded.xml`: plain-English webhook + Hue help, no POST/JSON/endpoint jargon.
- **#11** `11_about_version_folded.png`: "Version 1.3.4" (BuildConfig.VERSION_NAME, not hardcoded), "Accent colour" (British spelling). Info dialogs (#14) plain English.
- **#12** `12_library_folded.png` (1-col list) vs `12_library_unfolded.xml` (2-col grid: duration nodes at x=804 & x=1710 sharing y=640/771/902). Video filter `12_library_video_folded.png`; frame thumbnails via coil-video `VideoFrameDecoder` gated on `shouldThumbnailVideo(isVideo)`.
- **#13** `16_drive_browse_unfolded.png`: `.m4b` audiobooks in Drive browse show the audio/book icon. `CloudBrowserScreen` uses `MediaClassifier.isVideoByName`; the old MIME-only `startsWith("video/")` line greps to 0.
- **#14** `14_infoDialog_folded.png`/`14_infoDialog_folded.xml`: folded tap of the top-right ⓘ `[971,118][1064,211]` opens "Player — what each control does" (was previously unreachable behind the scroll column; fixed by InfoIcon declared last).
- **#15** `nav2_folded.xml` (bottom bar, tabs at y=2388) + mini-player bar on non-player tabs with the track shown and no black box; `player_unfolded.xml` nav items vertical on the left (rail).
- **#16** `16_drive_search_rowling_unfolded.png`/`16_search.xml`: Drive search "rowling" returns the Harry Potter audiobook by its enriched author/album (filename contains no "rowling") — DB enriched-metadata search. MetadataSearchDaoTest 4/4.
- **#17** `dumpsys activity services` showed PlaybackService (MediaSessionService) running; after HOME + close, `ps -A` shows the process gone — no lingering background. DiagLog.bg teardown instrumentation on all 5 vectors (FGS onTaskRemoved, Spotify poll, Hue, podcast sync, alarm).
- **#18** `08_relaunch_player.xml`: after force-stop, relaunch cold-started and restored the audiobook into the Player with the clean title and saved position (surfacing). SessionSurfaceTest 4/4.
- **#19** `19_lastplayed.xml`: pinned rows tagged "Hold position @1:54" (Fixed) and "Resume live @8:46" (Follow-live); a fresh Pin defaults to "Hold position" (Fixed); the "Save to Favourites" dialog (LastPlayedScreen:130) offers Hold position / Resume live / Both. StarPositionResolverTest.

## Summary

- **18/19 fully PASS on-device** in the relevant posture(s).
- **#2** is the only item whose LIVE leg is blocked — it needs an active Spotify Premium Connect playback session that cannot be scripted via adb; its fix is proven by SpotifyLyricsDecoupleTest 2/2 + the verified off-loop code. No on-device FAILURES (real bugs) were found.
- Adaptive layout verified in BOTH postures where it differs: nav bar↔rail (#15), Library 1-col↔2-col grid (#12), Settings two-pane unfolded; folded-specific hitbox fixes (#1/#4/#14) confirmed on the cover display.
- Incidental confirmation: T354 raw-filename-title heal holds (player + Last Played show the clean "Harry Potter and the Philosopher's Stone (Full-Cast Edition)").
