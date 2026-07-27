# OUTSTANDING WORK — TEMP (delete when all cleared). Written 2026-07-27, near context limit.

Honest granular ledger of everything from the last ~20 prompts: what's DONE+device-verified,
DONE-but-untested/code-only, NOT done, and what needs investigation. Read this FIRST after compaction.

Build coordinates: debug installed on Oppo `3B166N000CZ00000` (run-as works). Emulator NOT to be
used (user: device-only testing now). AAB staged `dist/PowerMediaPlayer-release.aab` vc68/1.5.13.
adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (PowerShell). Screenshot: `screencap -p
/sdcard/x.png` + `adb pull` (PowerShell mangles binary; Bash exec-out ok). DB pull unreliable (WAL);
sqlite3 not execable in run-as. Diag.i → logcat tag PMP_DIAG (debug only).

---

## A. METADATA / SUBTITLE FEATURE (Title + "Artist, Album, Filename" subtext)
Shared formatter `util/MediaRowText` (JVM-tested, MediaRowTextTest 6/6). Song=Artist,Album,Filename;
Audiobook(.m4b)=Author,Filename; title in accent colour (TealAccent). enrichedMeta flow in
CloudViewModel from EnrichmentCacheDao.observeEnriched() keyed by driveFileId.

- [x] DONE+VERIFIED — Cloud Drive rows (FavouriteTrackRow, CloudItemRow) show Title+subtext (Drive only; Spotify path untouched).
- [x] DONE+VERIFIED — Library "Downloaded" section: Title+subtext + cover art.
- [x] DONE (code, not re-verified) — Downloads manager DownloadRowView: Title(accent)+subtext+size.
- [ ] **NOT DONE — Spotify favourites show NO artist/album + a music-note icon + NO cover.** ROOT: `SpotifyFavourite` (SettingsDataStore.kt:1660) stores only `idname` in a stringSet. FIX NEEDED (multi-layer, DELICATE Spotify path — user warned): (1) extend SpotifyFavourite → id,name,artist,album,imageUrl; (2) extend the packed-string format + parsing in `spotifyFavSet` (SettingsDataStore.kt:1480-1490) + `toggleSetEntry`, with backward-compat for old 2-field entries; (3) change callers `toggleSpotifyFavouriteTrack(uri,name)` etc. (SettingsDataStore.kt:1473-1478) + the star handlers to pass artist(subtitle)+imageUrl(thumbnailUri) from the CloudMediaItem being favourited; (4) SpotifyFavRow (CloudBrowserScreen ~1970) render artist subtext + AsyncImage(cover). Screenshot shows "I Wish I Could Go Back to College" / "Stealing Society" as bare titles + music notes.
- [ ] **NOT DONE — subtitle "nowhere to be seen anywhere" (user).** REALITY: it DOES show for ENRICHED Drive files (verified "This Inevitable Ruin" / "Matt Dinniman, ..."), but enrichment cache is SPARSE — only files played / browse-peeked / favourite-enriched get title/artist/album. Un-enriched Drive files show cleaned-filename-only, no subtext. DECISION NEEDED: enrich-on-browse or enrich-on-download so metadata populates for all downloaded/browsed files (heavier), OR accept sparse + document. User clearly expects it broadly.
- [ ] NOT DONE — format NOT applied to: local Library MediaStore rows (MediaFileItem — shows title+artist from MediaStore), Last Played rows (title+source pill+subtitle), Player TrackInfoSection (already full metadata). Confirm with user whether they want the exact "Artist,Album,Filename" subtext on these too.

## B. COLLAPSIBLE CLOUD SECTIONS (P7)
- [x] DONE+VERIFIED — §1 Drive favourites & downloads, §2 Spotify favourites, §3 Downloads; DataStore-persisted expand state (survives cold relaunch).
- [x] DONE+VERIFIED — REORDER: §1 now directly under the "Cloud / external folders" (Google Drive) box; Spotify box then §2 below (user spec). Provider cards split into drive_card + spotify_card items.
- [x] DONE+VERIFIED — §1 shows downloaded Drive files (not just favourites) via CloudViewModel.downloadedDriveItems + cover art (was a DownloadDone tick).
- [ ] MINOR — ProviderCards() private composable now unused (inlined) → dead code, remove.

## C. LAST PLAYED POSITION (#2 — "no timestamps updating")
- [x] FIXED+VERIFIED — coordinator position-persist tick used a fixed delay(30_000) while idle; resuming mid-sleep left position un-persisted up to 30s (short items never persisted). Now waits on playerState.first{isPlaying} with 30s ceiling → first tick ~5s after play. Device-verified: Last Played tracked 24→29→34s (@0:34), was frozen.

## D. DOWNLOAD "STUCK AT 100%" (#3) — NOT FIXED
- [ ] **NOT ROOT-CAUSED / NOT FIXED.** User adamant: stuck at 100% "for ages", had to close+reopen. FACTS: file completed (1.64 GB in filesDir, durable rename = instant relocate, NOT SAF copy, no cache leftover). So hang was DURING download: % hit 100% long before bytes done ⇒ reported size (Content-Length) < actual 1.64 GB ⇒ ProgressInputStream fraction caps at 100% while the real download grinds on. `total = resp.body.contentLength() ?: item.size(0L)` in DriveOAuthProvider.downloadRangeToCache:782. Added debug `dlStart` log (contentLength/total/itemSize) at :783 — NOT yet reproduced/read. NEXT: trigger a real Drive download, read `PMP_DIAG dlStart`, confirm total<actual, then FIX (fetch + pass real file size so total is right, or handle chunked/gzip/redirect Content-Length). Note: downloadDrive builds item with size=0L (OfflineMediaManager.downloadDrive) so the fallback is 0 → contentLength is the only source.
- [ ] NOT DONE — download does NOT resume partial bytes (re-downloads from scratch on retry). User implied resume expectation.
- [ ] NOT DONE — "what it had downloaded to" (storage location) not surfaced to the user.

## E. DOWNLOAD PROGRESS NOTIFICATION (part of #3)
- [x] DONE (CODE, NOT tested live) — worker notification now determinate "title · N%" (was indeterminate spinner), mirrors DownloadProgressBus. OfflineDownloadWorker.doWork observes progressFor(id) + setForeground per whole-%. NEEDS a real download to visually confirm the % shows + is accurate (blocked by the same total-mismatch as D — if total is wrong the % is wrong).

## F. SPOTIFY SIGN-IN STUCK (P8, original 8-issue) — STILL OPEN
- [ ] NOT DONE — device+credential-gated. `_isLoggedIn` is reactive off tokenStore.observe (so an onResume re-check adds nothing). Real cause: AppAuth ActivityResult dropped on ColorOS process death → handleAuthResponse never runs. Robust fix = AppAuth PendingIntent completion (survives process death) — delicate rewrite of WORKING auth, needs the Oppo + a live Spotify OAuth (can't enter user's password) to verify. Not shipped blind.

## G. AUDIT / EARLIER 8-ISSUE BATCH (context)
- P1-P7 (vc65) done+emu-verified earlier. 3-lens efficiency audit applied (WorkManager stale-terminal + offline-hang + evict Mutex + P6 broken-URI guard). All committed. P8 = F above.

## H. TESTING DEBT (user: "you have not tested any of the new implementations properly")
- Device-verified this session: sections+reorder, download cover art, Library Downloaded metadata, position persist.
- CODE-ONLY (untested on device): Downloads manager subtitle/cover; download % notification; the audit's downloadForeground rewrite end-to-end on device; P3.2 Spotify artist heal (needs Spotify playback).
- Always screenshot-verify UI changes on the Oppo before claiming done (user repeatedly burned by icon/layout regressions).

## I. VERSION / RELEASE
- Current vc68/1.5.13 (build.gradle.kts:36-37). AAB staged. Bump to vc69 when the D/A-Spotify fixes land; rebuild AAB + overwrite dist/PowerMediaPlayer-release.aab.
- Delete THIS file + any stray debug logs (dlStart) once D is fixed + all cleared.

## PRIORITY ORDER (suggested)
1. D — reproduce + fix the 100%/Content-Length bug (functional, user-adamant) + verify the % notification.
2. A — Spotify favourites artist/album/cover (visible, user-flagged) — CAREFUL, delicate Spotify.
3. A — decide + implement broad enrichment so subtitles appear on more files.
4. F — Spotify sign-in PendingIntent (device+credential-gated).
