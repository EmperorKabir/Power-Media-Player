# Metadata subsystem — holistic assessment (2026-06-25)

User directive: "assess metadata **speed of loading**, **visibility on Player tab**, and **on Last Played tab**, for **all file types** — over many sessions various different fixes were applied; all of this needs to be assessed **as a whole**." Method: triangulated evidence (screenshot + DB/logcat + code), inline, no subagents. Device: Galaxy Z Fold6 RFCY70BARDJ, debug vc38, folded.

## 1. Architecture map (as built today)

**Metadata sources**
- Local audio/video: ExoPlayer extracts embedded tags in-process (fast, ~tens of ms); MediaStore as backup. Title often = filename when no tag.
- Drive audio/audiobook (.m4b, moov-at-END): tags/chapters unreadable over an auth'd HTTPS stream → `DriveTagEnricher` downloads the whole file to cache → `MediaMetadataRetriever` extracts title/artist/album/art + `M4bChapterParser` chapters. SLOW first play (hundreds of MB); cached after.
- Podcast: RSS feed → podcast tables.
- Spotify: Connect `/me/player` poll (~1 Hz) → mirror state.

**Stores**
- `PlaybackService.senderMetadataByMediaId` — IN-MEMORY map, keyed by mediaId. **Volatile: lost on process death** (so cold-start can't rely on it).
- `playback_history` rows — **MULTIPLE rows per mediaUri** (every play = a new `@Insert`). Each row's `title` is whatever the MediaItem carried at play time → can be the RAW FILENAME.
- `enrichment_cache` — DURABLE (uri-keyed, title/artist/album/artworkUrl) BUT written **only** when `writeSearchCache=true` (the #16 favourite path). Not written by a normal tap/heal → **not a general source of truth today**.
- `ArtworkCache` (filesDir) — durable cover bytes, uri-keyed. `ChapterCache` (cacheDir) — chapters, uri-keyed.

**Display surfaces**
- Player tab: `PlaybackConnection.updatePlayerState` (:1109) `title = overTitle ?: cached(senderMetadata)?.title ?: controllerMeta.title.ifBlank{itemMeta.title}`.
- Last Played tab: renders the `playback_history` row's `title`/`subtitle`/`artworkUri` **directly** (screenshot rows match DB rows 1:1).

## 2. Evidence matrix (file type × surface × {speed, visibility})

| File type | Player tab — visibility | Last Played — visibility | Speed | Evidence |
|-----------|------------------------|--------------------------|-------|----------|
| Local audio | OK (embedded tags) | OK but DUPLICATE rows (3× "Hey cuddle monkey") | Fast (~34–140 ms, prior T260) | lp.png; T260 |
| Local video | title often filename (no tag) — by nature | same dup risk | Fast | code (ExoPlayer) |
| **Drive audiobook (.m4b)** | **FAIL — raw filename shown** (`…[B0F14RFHS6].m4b`) on cold-start resume | **FAIL — 4 recent rows: 1 raw filename + 3 clean; 3 pinned (mixed author)** | SLOW first play (full download); cold-start shows filename until heal (often never, see root) | p2_small.png(15:07); DB rows id=11–14; lp.png |
| Drive video | same class as audiobook | same | same | code |
| Podcast | OK (RSS) | rows OK; dup risk | Fast | lp.png ("This Inevitable Ruin" ×2) |
| Spotify | OK (Connect poll) | row OK | ~1 Hz poll | media_session |

**Visibility defects (both tabs):** raw filename as title (Player + a Last Played row), and **duplicate rows per item with inconsistent title/author** (Last Played). **Speed defect:** Drive cold-start shows the filename during/forever-after the heal window.

## 3. Root cause — SINGLE, architectural (not per-symptom)

Every play path inserts a NEW `playback_history` row whose title is the MediaItem title at that instant. For Drive items opened/resumed **before** enrichment completes, that title is the RAW FILENAME. `DriveTagEnricher.parseAndApply` heals rows by URI (`updateDisplayByUri` updates ALL rows for the uri) — but a **later** raw `@Insert` creates a new newest row that escapes the previous heal. Then:
- Player cold-start `mostRecent()` picks the raw newest row → `setTitle(filename)` → `senderMetadata[uri].title = filename` → filename shown (logcat `cacheHit=true`, title truncation in the diag line hid it).
- Cold-start heal is gated `looksLikeRawMediaFilename(title) && !coverAlreadyDurable`; the cover IS durably cached → **heal permanently skipped** → filename persists.
- Last Played shows every row → duplicates with inconsistent metadata.

Contributing: `senderMetadata` volatile (cold-start can't lean on it); `enrichment_cache` not a general SoT; no de-dup anywhere. This is why 6+ prior point-fixes (T300/304/305/306/307/354) didn't hold — they patched symptoms around an insert-duplicates-then-heal-by-uri model that keeps re-creating the raw newest row.

## 4. Unified fix (one model, addresses the whole)

**One durable row per item; title/art only ever IMPROVE; heal actually fires.**

1. **`recordPlay` → upsert by `mediaUri`** (LastPlayedRepository, @Transaction):
   - If a row exists for the uri: UPDATE it in place (stable id) — refresh `lastPlayedAt` + position; set title/subtitle/artwork **only if the incoming value is better** (incoming not-raw, or existing blank/raw). NEVER overwrite a clean title with a raw filename.
   - Re-point bookmarks of any duplicate rows to the surviving id, then delete the duplicates (new DAO `repointBookmarks(fromIds,toId)` + `deleteMany`). Bookmarks preserved.
   - Else INSERT. Return the stable id (adoptSession/bookmarks unaffected).
   - Net: Recents shows ONE row per item (clutter gone), and it carries the best-known title.
2. **De-dup pins** (`history_favourites`): one pin per uri (collapse the 3 HP pins; pin = upsert by uri).
3. **Cold-start heal-gate fix** (PlaybackSessionCoordinator:728): fire the heal whenever `looksLikeRawMediaFilename(title)` — drop the `&& !coverAlreadyDurable` hole. Loop-guard for a genuinely-untitled file via a DURABLE "enrich attempted" marker (write `enrichment_cache` on EVERY enrich, not just favourites) so it never re-downloads a file that has no embedded title.
4. **Cold-start immediate clean title:** when building the resume MediaItem, if `recent.title` looks raw, resolve a clean title from `enrichment_cache`/a clean sibling row first → clean display instantly, no wait for download.
5. **Local fast path unchanged.** Mojibake already handled (TextNormalizer) — no change.

**Risk mitigation (user: no feature breakage):** stable row id preserves adoptSession + bookmarks (re-pointed, not lost); pins keyed by uri; resume position carried on the surviving row; TDD with Robolectric in-memory DB for the upsert + improve-only + bookmark-repoint; device-verify resume/bookmarks/pins after.

## 5. Verification plan (device, after fix)
Triangulated, inline, folded: for each file type, OPEN then COLD-START, capture Player title (screenshot) + DB row(s) (one per uri, clean title) + Last Played (no duplicates, clean). Confirm bookmarks + pins + resume position survive. Then the broader 19-item folded re-verification, then prompt unfold.

## Status
Phase 1 (root cause) COMPLETE + evidence-locked. Next: implement §4 with TDD, then device-verify §5.
