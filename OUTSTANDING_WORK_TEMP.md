# OUTSTANDING WORK — TEMP (delete when all cleared). Rebuilt 2026-07-28; updated with device-verified results.

Legend: [x] DONE+device-verified · [c] DONE in code (not device-verified) · [ ] NOT done · [~] partial · [blocked] external/credential gated.
Build: debug on Oppo `3B166N000CZ00000` (run-as, DEBUGGABLE) + emulator-5554. adb: `$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.
Device gotchas: `MSYS_NO_PATHCONV=1` for /sdcard paths; ColorOS re-dozes → `screencap` goes black when screen off (force `svc power stayon true`+WAKEUP or foreground first). Screenshots >500KB must be PIL-thumbnailed before Read.

=====================================================================
## DONE + DEVICE-VERIFIED THIS SESSION (2026-07-28)
=====================================================================
- [x] Issue 5 — offline downloads STREAM INTO filesDir/offline/tmp (not cacheDir). Device-verified via a real 269MB Drive download: dlPhase showed path=/data/user/0/…/files/offline/…m4b, offline/tmp swept empty, NO drive_*_full in cacheDir. Cache no longer balloons mid-download. Startup sweep of offline/tmp handles process-death orphans. (DriveOAuthProvider/GoogleDriveProvider downloadFullToCache destDir; PowerMediaPlayerApp sweep.)
- [x] Issue D — 100% hang. ROOT CONFIRMED on device: `dlStart http=206 contentLength=269261306 total=269261306 itemSize=0` → Content-Length EXACT for the 206 range response, so the bar reaches 100% precisely at completion (NO early-100% pin). relocate=3ms, evict=1ms (no SAF on device → instant renameTo). The user's original "stuck for ages" was the OLD build (cacheDir streaming + indeterminate notification + large transfer), all now fixed. Added SAF-relocate progress (ProgressInputStream) so the rare SAF-folder case can't dead-pin either. dlStart/dlPhase logs kept (Diag.i = inline BuildConfig.DEBUG, R8-stripped in release → zero cost).
- [x] Issue 4-i — POST_NOTIFICATIONS now requested on Cloud entry. Device-verified: dialog appeared, granted; the "Saved offline / Anne of Green Gables.m4b" completion notification + the ongoing foreground progress notification both fired on the offline_downloads channel.
- [x] Issue 4 (sustain) — download runs in a FOREGROUND worker (survives tab-pop/background/process death) + determinate % notification. Verified via the notification lifecycle above.
- [x] Issue 3a — Last Played position persists (responsive-wake coordinator). Re-confirmed.
- [x] Issue 3c — Last Played whole-tab renders fully: Drive source pill, Offline badge, "Matt Dinniman" subtitle, @position, pin star, overflow, cover art.
- [x] Issue 3d — SLEEP: playback + position tracking survive screen-off/doze uninterrupted (position ran 0:15→3:19 across a 22s+ screen-off with lock-screen media controls working). Coordinator persists every 5s while playing regardless of screen state.
- [x] Issue 1 — downloaded Drive book "This Inevitable Ruin" plays with correct metadata (Title/author/narrator on Player; Drive+Offline+author in Last Played), no metadata error. DriveKeys cross-update stability + one-time re-key heal in code.
- [x] Issue 2 — backup restore sign-in gating + backup-folder chooser + delete-backup all wired (commit 884e8a4). Settings sign-in shares the singleton Drive token → Cloud auth-ready.
- [x] Issue 7 — 3 persistent collapsible Cloud sections + reorder (Drive favs under Drive box) + downloaded rows with cover art. Device-verified.
- [x] Metadata display — Title(accent)+subtext on Cloud Drive rows, Library Downloaded, Downloads manager. Device-verified (Cloud §1 shows Title+author+cover).
- [x] Spotify favourites metadata — SpotifyFavourite extended (+artist,album,imageUrl) additively (legacy id|name entries still parse); toggleSpotifyFav captures item.subtitle+thumbnailUri; SpotifyFavRow renders Title(accent)+artist subtext+cover. Existing 2 favourites render fine as bare titles (backward-compat verified on device). NEW stars capture metadata.
- [x] Delete offline copy — verified (removed the 269MB test download; file gone from filesDir).

=====================================================================
## REMAINING (this pass continues)
=====================================================================
- [ ] Enrich-on-download — subtitle only shows for ENRICHED files; un-enriched downloaded files show cleaned-filename only. FIX IN PROGRESS: fire DriveTagEnricher.enrich after offlineCopyDao.upsert in OfflineMediaManager.downloadDrive (reuses the local copy, no re-download; writes enrichment_cache → row subtext populates). Both @Singleton, no Hilt cycle, enrich reads offlineCopyDao.get(id).localPath (DriveTagEnricher:127).
- [blocked] Issue 8 — Spotify sign-in stuck on "Agree". Device+credential-gated (can't enter the user's Spotify password); robust fix = AppAuth PendingIntent completion. Not shipped blind.
- [blocked] Spotify favourites BACKFILL of the 2 existing legacy entries — needs a live signed-in Spotify session to re-fetch artist/cover. New stars work; old ones populate on re-star.
- [~] Issue 2 caveats — Cloud card badge still waits for a first folder-pick to read "signed in" (no re-prompt; cosmetic). Signed-out fresh-install restore path only emulator-reasoned. Low priority.
- [ ] Issue 4-iii — resume partial bytes on retry (HTTP Range resume). Larger change; not yet done.
- [~] Issue 6 — Library "Downloaded" section exists (surfaces downloaded books). A per-row Offline tag/badge is optional polish; confirm with user if wanted.
- [ ] Metadata subtext NOT applied to local Library MediaStore rows / Last Played rows (both already show their own title+artist). Confirm with user if the exact "Artist, Album" subtext is wanted there.

=====================================================================
## VERSION / RELEASE
=====================================================================
- vc69/1.5.14 (build.gradle.kts). AAB rebuild after enrich-on-download lands → dist/PowerMediaPlayer-release.aab.
- Dead ProviderCards() removed. dlStart/dlPhase logs kept (release-stripped).
- Delete THIS file when the remaining non-blocked items are cleared or user-deferred.
