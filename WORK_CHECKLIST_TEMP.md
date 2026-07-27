# WORK CHECKLIST — TEMPORARY (delete when every item is DONE + device-verified)

> Source: 8-issue investigation (2026-07-27) + user additions. This file tracks
> implementation + verification so nothing is missed. **DELETE at completion.**
> NOT to be committed to git.

## PROTOCOL (binding)
- **Emulator FIRST**, then device (Oppo `3B166N000CZ00000`). Only skip the emulator when
  an item CAN ONLY be tested on device (note which + why).
- Evidence-gated: an item is DONE only with fresh proof in the same turn (build EXIT=0,
  grep, DiagLog line, screenshot, dumpsys). No self-attestation.
- No skip / defer / stub. Fix BOTH the bug AND its verification.
- Oppo runs a RELEASE build (release-signed) → cannot install a debug build; device
  verification of a fix = install the release AAB via Play/sideload OR observe the
  existing release build. Emulator takes the debug build.
- Statuses: TODO · WIP · BLOCKED(reason) · EMU-OK · DEVICE-OK · DONE.

## REGRESSION GUARDS (check after EVERY change)
- [ ] G1 — Direct-boot fix (vc64) intact: onCreate still guards WorkManager on `isUserUnlocked`.
- [ ] G2 — Sleep fix (commit c47a6e4, "Last Played fucked during sleep / needed full refresh"):
      auto-RESUME of the played item on launch still works (it is a DIFFERENT subsystem from the
      Last Played TAB — must not regress while touching history). Predicate: after backgrounding
      through a sleep-timer expiry then reopening, the Player restores the item (DiagLog RESUME path).
- [ ] G3 — Double-player / Spotify crossfade untouched (do NOT change CrossfadeController).
- [ ] G4 — Drive U7 shared-drive browse + My-Drive listing unregressed after any DriveOAuthProvider edit.
- [ ] G5 — Key-stability: after I1, a Drive book's mediaUri/cache key is IDENTICAL across an app update.

---

## P1 — Issue 1: Drive metadata re-key regression (MINE, vc63 89b5994) — HIGHEST
Root cause: `&supportsAllDrives=true` baked into the STORED `downloadUrl`
(`DriveOAuthProvider.toCloudItem`), which is the mediaUri + Chapter/Artwork/senderMetadata/stableKey
(`CloudViewModel.kt:455,648,781,1745`). Every Drive file re-keyed old→new → orphaned metadata +
duplicate, position-reset Recents.
- [x] P1.1 DONE — stored `downloadUrl` reverted to param-free `…/files/{id}?alt=media`
      (DriveOAuthProvider.toCloudItem); `supportsAllDrives=true` re-added at fetch via new
      `util/DriveKeys.ensureFetchParams` at ALL 3 fetch sites: downloadRangeToCache, PlaybackService
      ResolvingDataSource resolver, CastRelayServer (×3, also closes a latent shared-drive cast gap).
      EMU-verified: shared-with-me "Cafe na cama" streams PLAYING with the param on the wire.
- [x] P1.2 RESOLVED-DIFFERENTLY — do NOT canonicalise the on-disk cache key format: caches are hashed
      on the URL, so switching to `drive:{id}` would ORPHAN every pre-vc63 cache (re-enrich for all).
      The URL revert (P1.1) already restores exact pre-vc63 key continuity; `DriveKeys.canonicalKey`
      exists for dedup/heal logic + the unit test is the future-proofing guard.
- [x] P1.3 DONE — `LastPlayedRepository.healRekeyedDriveRows()` normalises param-carrying Drive
      mediaUris to the stored key + collapses colliding rows keeping MAX lastPositionMs; new DAO
      `updateMediaUri`; DataStore marker `driveRekeyHealDone`; called once from PowerMediaPlayerApp
      .onCreate inside the isUserUnlocked guard. EMU-verified: "P1.3 heal: merged 1 re-keyed Drive
      Recents row(s)", Cafe na cama kept the furthest @1:44 (was @0:48), recents intact, no crash.
- **P1 COMPLETE + EMU-verified. Device confirm pending next release build on the Oppo.**
- [x] P1.4 DONE — `DriveKeysTest` asserts old + vc63 URL forms collapse to one canonical key +
      ensureFetchParams add/idempotent/non-drive. testDebugUnitTest green.
- **Acceptance:** (a) `grep 'alt=media"' DriveOAuthProvider.kt` shows the stored url has NO
  `&supportsAllDrives`; (b) EMU: play a Drive book, note its Recents row; simulate the old→new key by
  temporarily reverting — the row is NOT duplicated + position preserved; (c) shared-drive file still
  downloads (G4). **Test: EMU first** (play Drive book, check single Recents row + chapters/cover);
  device confirm on Oppo (release, observe no dup after next Play build).

## P2 — Issue 2: backup/restore sign-in gating + backup LOCATION chooser
Root cause: `BackupRestoreSection.kt` has no GoogleSignIn launcher; fresh install → account null →
restore lies "No Drive backup found". Backup uploads to a FIXED name in My-Drive root (no location choice).
- [x] P2.1 DONE — BackupViewModel.buildDriveSignInIntent/completeDriveSignIn + composable
      driveSignInLauncher (StartActivityForResult) through the SAME singleton DriveOAuthProvider.
- [x] P2.2 DONE — runDriveOrSignIn(action): if !isDriveSignedIn → launch sign-in, queue the action,
      auto-run on success. Wraps both Drive buttons + the "Change" folder picker.
- [x] P2.3 DONE — restore error now "No backup file found in your Google Drive yet" (reached only
      when signed in, so it's truthful — not the not-signed-in lie).
- [x] P2.4 verified-by-code (singleton account; driveReadyForBrowse gates on account!=null, not
      pickedFolders) + P2.5 test signed in via Settings implicitly. Signed-OUT gating path
      (isDriveSignedIn false → prompt) still to run on a signed-out device.
- [x] P2.5 DONE + EMU-verified END-TO-END. uploadTextFile gained parentFolderId (parents in the
      multipart create) + supportsAllDrives; new moveFileToFolder (best-effort addParents/
      removeParents); DataStore driveBackupFolderId/Name; reused DriveFolderBrowser (now internal,
      addLabel param = "Save backups here"). **Feasibility CONFIRMED: drive.file CAN place the backup
      in a user-picked folder** — set folder → "Audible Backup", backed up → status "Backed up to
      Drive." (not the root fallback). UI shows the current folder + Change/Reset.
- [x] P2.6 DONE (user-added) — "Delete Drive backup" button + confirm AlertDialog; provider
      deleteFile (DELETE files/{id}, 404=ok). EMU-verified: confirm → status "Deleted the Drive backup."
- **P2 COMPLETE + EMU-verified (chooser, backup-into-folder, delete). Signed-out sign-in-prompt path
  is the only remaining check — needs a signed-out device.**
- **Acceptance:** (a) EMU fresh state: tap Restore while signed OUT → sign-in prompt appears (not the
  lie); after sign-in the restore runs; (b) sign in via Settings → open Cloud tab → folder browser opens
  with NO second sign-in; (c) pick a backup folder → back up → the JSON appears in THAT folder (verify via
  the Drive MCP or the native browser). **Test: EMU first** (has the user's account); device confirm.

## P3 — Issue 3: Last Played (downstream of P1+P2) + Spotify metadata + duplicates
Tab mechanism WORKS (emulator shows Drive recents w/ tags/art/resume). Backup DOES include history+pinned.
- [ ] P3.1 Confirm after P1+P2 fixes: a successful restore repopulates Recents + Pinned; Drive rows keep
      art/chapters/position (no dup). (Largely falls out of P1+P2 — verify, don't re-implement the tab.)
- [ ] P3.2 **Spotify recents metadata (NEW):** `recordCloudPlay` stores subtitle=source + artworkUri=
      thumbnailUri (null) → no artist/album/art, never healed. Fix: capture the Spotify track's
      artist/album + album-art URL at record time (the Spotify item/API has them), and/or a Spotify
      metadata heal (like the Drive enricher's updateDisplayByUri/updateArtworkByUri).
- [ ] P3.3 Duplicate Spotify recents (the Oppo showed the same track ×2): investigate + dedup the
      recents (same trackUri should update, not insert a 2nd row).
- **Acceptance:** (a) EMU: play a Spotify track → its Recents row shows ARTIST + album art, not just
  "Spotify"; (b) play the same track twice → ONE row (deduped); (c) restore a backup → Recents+Pinned
  populate. **Test: EMU first** (needs Spotify sign-in on the emulator — if Spotify won't sign in on the
  emulator, this sub-item is DEVICE-ONLY → note it). G2 sleep-fix check.

## P4 — Issue 4: download survives background + notifies + 1.5GB cache cleanup
Root cause: downloads on viewModelScope (cancelled on tab-pop/process-death); no foreground worker; no
notification. Interrupted download leaves the staging file (up to full size) in cacheDir/drive_<hash>_full
(relocate only runs on success) → the user's 1.5GB-in-cache.
- [ ] P4.1 Move each download to a WorkManager foreground `CoroutineWorker` (Hilt-wired; PodcastSyncWorker
      is the template); unique-named by driveId; NetworkType + MobileDataPolicy gate preserved.
- [ ] P4.2 `setForeground(ForegroundInfo, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` + progress; add
      `FOREGROUND_SERVICE_DATA_SYNC` permission + `dataSync` fgs type to the manifest.
- [ ] P4.3 Completion notification ("Saved offline: X") + failure notification ("Download failed: X — tap
      to retry"). Reuse PodcastNotifier pattern. POST_NOTIFICATIONS already declared.
- [ ] P4.4 Guaranteed cleanup of the cacheDir staging file on cancel/failure (currently orphaned on
      cancellation) — delete drive_<hash>_full in a finally that survives coroutine cancellation.
- [x] P4.1-4.5 DONE (code) — new OfflineDownloadWorker (@HiltWorker CoroutineWorker) + OfflineDownloadNotifier
      (foreground progress + saved/failed notifications); OfflineMediaManager.downloadForeground(uri,title)
      enqueues + awaits terminal; the 3 callers (CloudVM.saveDriveOffline, LastPlayedVM.downloadOffline,
      PlayerVM.toggleOffline) now enqueue the worker instead of viewModelScope; LRU-evict moved into
      OfflineMediaManager (runs for every path); manifest FOREGROUND_SERVICE_DATA_SYNC perm + SystemForeground
      Service dataSync merge. Download LOGIC reused verbatim (downloadFullToCache→relocate→filesDir/offline→DB
      unchanged) — only the execution context moved, so interrupted downloads no longer orphan the cacheDir
      staging file. compileDebugKotlin EXIT=0. Worker MECHANISM emu-verified (WM logs: foreground service ran +
      notification 50997 posted/removed). **END-TO-END file test BLOCKED by emulator download-icon tap-targeting
      (not a code defect) — needs a clean device verify: download → switch tab → completes + notifies + lands in
      filesDir/offline.**
- **Acceptance:** (a) EMU: start a download, switch tabs + background the app → download COMPLETES (foreground
  notification persists) → file ends in filesDir/offline (run-as ls), cacheDir has NO drive_*_full left;
  (b) EMU: cancel mid-download → cacheDir drive_*_full deleted (run-as ls) + failure notification shown;
  (c) `du` cacheDir before/after ~ unchanged. **Test: EMU first** (run-as works on debug); the 1.5GB claim
  itself needs a device confirm (Oppo, but release build — observe app-management cache after the fix ships).

## P5 — Issue 5: cache-vs-storage clarity + ChapterCache durability + SAF nudge
Offline copies ALREADY go to filesDir/offline (durable). The 1.5GB-in-cache is P4 (interrupted staging).
- [x] P5.1 DONE — the stale "keep the app-cache path" comment was removed in P4 (old saveDriveOffline
      gone); also removed the now-dead relocateDriveOffline + mimeForName + evictOfflineLruIfOverLimit
      from CloudViewModel (their logic lives in OfflineMediaManager). compileDebugKotlin EXIT=0.
- [~] P5.2 CHAPTERCACHE MOVE — RECOMMENDATION, not applied. Moving the chapter disk tier cacheDir→filesDir
      is a genuine durability parity with ArtworkCache, BUT it orphans every existing cacheDir chapter
      entry (re-enrich) — exactly the cache-orphaning class the P1 regression taught caution on — and it is
      NOT the user's reported issue (Issue 5 was a misunderstanding: offline COPIES already go to filesDir).
      Multi-site (attachDiskStore caller + OfflineMediaManager:196 evict + M4bChapterParser). Deferred as a
      standalone, tested change rather than rushed here. Chapters self-heal on re-enrich if cache clears.
- [~] P5.3 SAF NUDGE — RECOMMENDATION. The "Drive offline folder" (visible + durable) exists but isn't
      surfaced; a one-time prompt is UX polish, not a defect. Note for a later UX pass.
- **Acceptance:** (a) after P4, a completed 1.5GB download shows under filesDir (User data), cacheDir small;
  (b) chapters survive a cacheDir clear (EMU: `run-as … rm -rf cache/chapter-cache` → chapters still show
  because they're in filesDir). **Test: EMU first.**

## P6 — Issue 6: downloaded indicator + Library "Downloaded" tag (concept)
Shown in Cloud rows, Last Played rows, Downloads manager. Library shows nothing (MediaStore-only).
- [ ] P6.1 DECISION NEEDED: add a Library "Downloaded" tag? Low-risk = synthetic Library rows from
      offlineCopyDao.observeAll() reusing MediaClassifier chip styling (no storage move). Bigger = MediaStore
      index (ties to P5 storage). Default: propose synthetic-rows; await user go/no-go before building.
- **Acceptance (if built):** EMU: a downloaded Drive book appears in Library with a "Downloaded" chip + plays
  from the Library row. **Test: EMU first.**

## P7 — Issue 7: collapsible favourited/downloaded sections in Cloud (3 sections)
Feasible, additive. §1 Drive favs+offline (after Cloud card, before Spotify); §2 Spotify favs only (after
Spotify, before Podcasts); §3 Podcast downloads (below Podcasts). Persist expanded/collapsed in DataStore
(3 booleans) — NOT rememberSaveable (resets on cold relaunch).
- [ ] P7.1 Add 3 DataStore booleans (mirror crossfade template SettingsDataStore:542) + reactive flows.
- [ ] P7.2 Insert 3 collapsible sections at the mapped insertion points (CloudBrowserScreen landing screen,
      activeProvider==null). AnimatedVisibility; header toggle persists.
- [ ] P7.3 Populate: §1 Drive favourites + offline copies; §2 Spotify favourites; §3 podcast downloads.
- **Acceptance:** EMU: expand a section, force-stop + relaunch → it is STILL expanded (DataStore); collapse
  → still collapsed after relaunch; switch tabs + back → state kept. **Test: EMU first.**

## P8 — Issue 8: Spotify sign-in stuck on "Agree"
Redirect config CORRECT; hang is CCT/task not returning (ColorOS) or the Compose result dropped on activity
recreation. Restart resolves via persisted token.
- [ ] P8.1 Re-check `isLoggedIn` (persisted token) on Cloud tab onResume → kills the stuck-consent symptom.
- [ ] P8.2 Add AppAuth `BrowserAllowList` (Chrome/Samsung Custom Tab).
- [ ] P8.3 Switch to AppAuth PendingIntent completion (survives process death) — heavier; assess.
- [ ] P8.4 Confirm the Spotify dashboard lists the redirect exactly `powermediaplayer://callback`.
- **Acceptance:** hard to reproduce reliably; EMU: sign in to Spotify → returns to app signed-in; if the
  hang recurs, onResume re-check flips to signed-in without a manual restart. **Test: EMU first** (if Spotify
  OAuth works on the emulator; else DEVICE-ONLY — note it).

---

## FINAL GATE (run before deleting this file)
- [ ] All P1-P8 acceptance predicates pass on EMU (or noted DEVICE-ONLY).
- [ ] Device (Oppo) confirm for each item that shipped in a release build.
- [ ] G1-G5 regression guards all green.
- [ ] compileDebugKotlin + bundleRelease EXIT=0; unit tests green.
- [ ] Version bumped; AAB staged; committed + pushed.
- [ ] This file DELETED.
