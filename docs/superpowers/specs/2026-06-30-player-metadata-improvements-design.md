# Spec: player and metadata improvements (2026-06-30)

User-approved design. Punctuation style throughout: no em-dashes or unnecessary
hyphens; use colons, commas, semicolons, full stops. Keep layman explanations.

## 1. Auto-resume on a missing source (the FILE_NOT_FOUND banner)
Root cause: `PlaybackSessionCoordinator` cold-start restore (~line 680) rebuilds a
MediaItem from the saved URI of the most recent LOCAL item without checking the
source still exists. A deleted file raises `ERROR_CODE_IO_FILE_NOT_FOUND`, and
`PlayerErrorMessage` only maps Drive-403 and network errors, so the raw banner shows.
- Guard: before loading a LOCAL source (and the resolved local copy of a DRIVE item),
  verify it exists. `file://` via `File(path).exists()`; `content://` via a
  `contentResolver.openAssetFileDescriptor`/query probe wrapped in runCatching. If
  missing: skip the restore (DiagLog.dec "source missing, skip") and clear that row
  from recents so it stops resurfacing.
- Safety net: add `ERROR_CODE_IO_FILE_NOT_FOUND` mapping in `PlayerErrorMessage`
  ("This file is no longer available. It may have been moved or deleted.");
  auto-dismiss and clear the dead item. Unit-test the new mapping.
- Files: `PlaybackSessionCoordinator.kt`, `PlayerErrorMessage.kt`,
  `PlaybackConnection.onPlayerError`, LastPlayed repo clear-by-uri.

## 2. Always show a subtle pill
Drop the `hasCoverArt` gate in `TrackInfoSection` (`frostEnabled`). With cover art:
the frosted blur backdrop (current `FrostedTextLine`). Without cover art: a plain
translucent scrim pill (dim only, no blur, no captured layer). Per-visual-line pills
retained. `FrostedTextLine` gains a no-cover branch: when `frost == null ||
!frost.captured`, still draw the per-line rounded scrim (Color.Black alpha ~0.40).

## 3 + 4. Player text colour: Default / Custom / Dynamic
New global setting `playerTextColourMode` (enum int: 0 DEFAULT, 1 CUSTOM, 2 DYNAMIC)
plus `playerCustomTextColour` (ARGB Int) in `SettingsDataStore`.
- DEFAULT: current black/white by backdrop luminance (global).
- CUSTOM: user-picked colour (global), applied to all pill lines. Keep the shadow.
- DYNAMIC: the app chooses per file with NO user input. From `CoverArtColors`
  (PaletteHelper: dominant/vibrant/muted + light variants), build candidate colours
  including the complementary/opposite of the dominant; score each by contrast ratio
  against the actual pill backdrop luminance; pick the highest-contrast candidate that
  clears a threshold (~4.5:1), else fall back to black/white. Per file by nature.
- Colour resolver replaces `rememberAdaptiveTextColor`: inputs = mode, customColour,
  CoverArtColors, backdrop luminance; output = Color. Pure scoring fn is unit-tested.
- Settings UI: new "Player text" group (mode selector; when CUSTOM, a swatch opens a
  compact custom HSV picker: hue slider, saturation/value square, preset swatches, hex
  field; no new dependency). Applies to all pill lines.
- Files: `SettingsDataStore.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, new
  `ColourPicker.kt`, `CoverFrost.kt` (resolver), `PlayerScreen.kt`/`PlayerViewModel`.

## 5. Grammar and punctuation sweep (all user-visible text)
Scope: every string the app shows (Text, dialogs, errors, toasts, chips, settings
labels, info boxes). Remove em-dashes and unnecessary hyphens; replace with colons,
commas, semicolons, full stops; fix grammar; keep layman explanations. Info boxes
(`InfoContent.kt`) rewritten for tone and completeness so every feature is covered.
Code comments are out of scope (not user-visible). Sweep file-by-file across `ui/`.

## 6. Drive and offline thumbnails
`coil-video` `VideoFrameDecoder` is already registered in the app ImageLoader.
Thumbnail model priority in `CloudItemRow` (and any downloaded/offline item render):
1. embedded artwork (audio and video; enricher path / MMR getEmbeddedPicture cached),
2. else for video: `ImageRequest(localVideoUri).videoFramePercent(0.1)` (Coil frame),
3. else: the current generic file icon.
Artwork has priority over the video frame. Files: `CloudBrowserScreen.kt`
(CloudItemRow), `DriveTagEnricher`/enricher art extraction, offline resolver.

## Order and method
Order: 1 bug fix, 6 thumbnails, 2+3+4 pill/colour/settings, 5 grammar (largest, lowest
risk). Method: TDD for pure logic (error mapping, colour scoring); build and
device-test each item via the android build-and-device-test skill; commit per item;
push and adb-install after commits. No background subagents. Context7 for coil3
(confirmed videoFramePercent) and Compose colour-picker specifics.
