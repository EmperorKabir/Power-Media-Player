# Master Plan v2 — Pre-Play-Store consolidation (deeper-dive)

Supersedes `2026-05-06-master-plan-pre-play-store.md`. Adds deeper investigation findings on Drive + Spotify casting and the existing grey-out pattern.

**Investigation evidence:** Context7 (Media3 cast docs), parallel agent on Cast options + grey-out pattern, code-grep with citations. NO guesswork.

---

## Item 1 — DB migration option (b)
**Confirmed.** Implementation: remove `.fallbackToDestructiveMigration(true)` from `di/AppModule.kt:41`; add `docs/MIGRATION_INSTRUCTIONS.md` documenting the rule "every Room schema change ships with a Migration object". 5 minutes. Zero runtime risk.

---

## Item 2 — AGP downgrade 9.1.0 → 8.7.3 + Gradle 9.3.1 → 8.10.2
**Confirmed.** Triple-checked:
- AGP only affects build pipeline, not runtime app behaviour.
- The codebase already has a comment acknowledging AGP 9 issues (`app/build.gradle.kts`: "FFmpegMediaMetadataRetriever has namespace conflicts with AGP 9").
- No AGP-9-only API used in our build scripts.
- `collectReleaseDependencies` task that fails on AGP 9.1 simply doesn't exist on AGP 8.x.
- All dependencies (Kotlin 2.2.21, KSP 2.1.20, Hilt 2.59.2, Room 2.7.1, Media3 1.6.0, Compose BOM 2025.04.00) work on AGP 8.7.3.

Implementation: edit two lines (root `build.gradle.kts` AGP version, `gradle/wrapper/gradle-wrapper.properties` Gradle distribution). Verify both `assembleDebug` AND `bundleRelease` succeed locally. ~15 minutes.

---

## Item 3 — Cast (deeper-dive findings + revised plan)

### What I previously said
"Cast can't work with our content — local files aren't HTTPS, Drive needs auth, Spotify uses its own protocol."

### What's actually true (after deeper dive)

**Drive: CAN work via on-device HTTP relay (option D-1).**
- DriveOAuthProvider already supports byte-range downloads with Bearer-token injection (`DriveOAuthProvider.kt:312-365`).
- Google Drive responds correctly to `Range: bytes=A-B` headers.
- We can add a small embedded HTTP server (e.g. NanoHTTPD, ~50 KB) bound to `127.0.0.1:<ephemeral-port>` that:
  - Accepts GET requests like `http://<phone-LAN-IP>:<port>/<token>`.
  - For Drive items: forwards each incoming Range request to the corresponding `googleapis.com/drive/v3/files/{id}?alt=media` URL with `Authorization: Bearer <fresh-token>`.
  - For local / SAF items: opens an `InputStream` via ContentResolver, seeks per Range, streams.
- CastPlayer receives `http://<LAN-IP>:<port>/<token>` as the URL — receiver fetches it from the local network without auth. The relay handles auth on the phone side, refreshes the Drive token as needed (it can; `currentAccessToken()` is suspend-callable with refresh logic already present).
- Token expiry during long playback (1 h Drive token lifetime): the relay caches the latest refreshed token; each forward request grabs `currentAccessToken()` (which auto-refreshes if expired). No bytes leaked because each receiver request is mapped through our process.

**Spotify: cannot use Google Cast at all (no relayable audio bytes), BUT…**
- Spotify Connect already works at the code level — `SpotifyProvider.listDevices(token)` (`SpotifyProvider.kt:511`) returns every Connect-capable device the user has, **including Google Home / Nest speakers** when the user has linked their Google account to Spotify in the Google Home app.
- The current code only auto-selects the first device from the list (`SpotifyProvider.kt:536`); there is **no UI device-picker** for the user to choose a different Connect device.
- Adding a Connect device picker UI surfaces the existing capability.

### Effective options for casting in v1.0

| Source | Mechanism | Effort | What's needed |
|---|---|---|---|
| Local files (file://) | Cast button → relay → receiver | Medium (3-5 h) | Add NanoHTTPD + relay server + LAN IP discovery |
| Drive SAF (content://) | Cast button → relay → receiver | Medium (1-2 h on top of local) | Reuse relay; ContentResolver byte streaming |
| Drive OAuth (googleapis.com) | Cast button → relay (with token forwarding) → receiver | Medium (1-2 h on top of local) | Reuse relay; forward Bearer header per request |
| Spotify | Spotify Connect device picker (NOT Cast button) | Small (1-2 h) | New UI in cloud Spotify section + bottom sheet |

Total effort ≈ 7-11 hours of careful implementation + testing. Reasonable for a v1.0 cast story that's actually functional.

### UI placement
- **Cast button** stays in player UI (top-right, where it already is). Currently visible in compact-audio + compact-video, missing in expanded — would also add to expanded.
- **Cast button is HIDDEN when Spotify is the active source** (the Cast button is for Google Cast; Spotify Connect is its own thing). Replaced by a **"Spotify Connect" button** that opens a bottom sheet listing Connect devices.
- **Cast button moves** from top-right to bottom transport-control row, **to the right of the Bluetooth icon** (per your earlier instruction in the original prompt). The expanded layout gets the same row.

### Grey-out logic during Cast
Reuses the existing `ControlsEnabledState` pattern from `PlayerUiState.kt:122-151` + `PlaybackControls.kt:263-282` (`tint = if (enabled) TealAccent else DisabledGrey`).

Specifically, when `isCasting == true`:
- **Greyed out** (per the matrix; these are silent no-ops on Cast because audio is on the receiver):
  - Speed (0.5×–2.0×) — CastPlayer 1.6.0 doesn't expose speed
  - Pitch — same
  - Volume boost (LoudnessEnhancer)
  - Audio delay
  - Crossfade
  - ReplayGain toggle
  - Reverb preset
  - Stereo flip
  - Mono mix
  - EQ 10-band (entire EQ tab disabled with banner: "EQ applies to phone playback only — disabled while casting")
  - Reverse audio (local)
- **Hidden during Cast** (don't apply at all):
  - Frame-step ± (no remote frame-step)
  - Video effects (mirror, flip, B&W, sepia, invert, rotation) — they're local UI; while casting, the cast device shows the actual stream not the local TextureView
- **Stay enabled** (work cleanly via `MediaController` → `session.player` which is `CastPlayer`):
  - Play/pause, scrub, full slider, skip-back/forward (any size), prev/next chapter or track or file, volume slider (routes to receiver volume), bookmarks, chapter picker, sleep timer
  - BT remap actions (route through `applyAction` on `session.player` which IS CastPlayer)
- **Always works regardless** (local-only):
  - Brightness override, Cover-art Fit/Fill setting
  - Sleep timer

### Implementation skeleton

1. **`util/LanIpDiscovery.kt`** — small helper returning the phone's non-loopback IPv4 address on the connected Wi-Fi interface.
2. **`service/CastRelayServer.kt`** — singleton starting/stopping with the cast session lifecycle. NanoHTTPD subclass. Maps tokens to (source, uri, optional bearer-supplier).
3. **`service/PlaybackService.kt`** — modify `switchPlayer(target)` to:
   - On switch-to-CastPlayer: start `CastRelayServer`, register every queue item, rebuild `MediaItem` list with relay URLs, pass to CastPlayer.
   - On switch-to-LocalPlayer: pass original items back, stop the relay.
4. **`ui/player/PlayerViewModel.kt`** — expose `isCasting: StateFlow<Boolean>` derived from `playerFlow.value is CastPlayer`. Thread into `PlayerUiState` (new field `isCasting`).
5. **`ui/player/PlayerUiState.kt` + `PlayerViewModel.mapToUiState`** — extend `ControlsEnabledState` with the new "isCastingDisabled-aware" flags, OR introduce a parallel `isCasting` boolean and apply per-control grey-out at the Composable level. The latter is less code change.
6. **`ui/player/components/PlaybackControls.kt` + AudioEffectsButton + EQ tab + Settings audio sections** — apply grey-out where appropriate using the existing `enabled = !isCasting` + `tint = DisabledGrey` pattern.
7. **`ui/cloud/CloudBrowserScreen.kt`** — new "Spotify Connect" section with a bottom-sheet picker that calls `SpotifyProvider.listDevices()` and lets the user pick a target.
8. **`ui/player/PlayerScreen.kt`** — move Cast button from top-right to the bottom transport row, right of Bluetooth. Hide when `isSpotifyActive`. Add same to expanded layout.

### Risks / unknowns I want to flag
- **NanoHTTPD vs hand-rolled `ServerSocket`**: NanoHTTPD is Apache-licensed, ~50 KB; hand-rolled is zero deps but needs Range header handling. NanoHTTPD recommended.
- **Receiver compatibility:** The default `CC1AD845` Cast Media Receiver supports HTTP MP3/AAC/FLAC/MP4-AAC/WebM via Cast SDK. `.m4b` (audiobook) is just MP4-AAC and should play. `.flac` works on most receivers but not all old Chromecast Audio devices. `.opus` — fragmented support.
- **Local network discovery:** the phone must be on the same Wi-Fi as the cast device, OR cast device is on a Cast Group. mDNS handles this for the Cast button itself; the LAN IP we serve from must be reachable. We should document "Wi-Fi only" in the UI.
- **MediaItemConverter:** We may need a custom one to put the relay URL in `MediaInfo.contentUrl` rather than the original content://. Default `MediaItemConverter` uses `MediaItem.localConfiguration.uri` — if we rebuild MediaItems with the relay URI, default converter works.

---

## Item 4 — Spotify-not-installed message polish

User-supplied wording (verbatim):
> "Spotify isn't installed or didn't start. Open/install Spotify on this phone or another device, then try again."

Implementation: replace the existing string at `cloud/SpotifyProvider.kt:539-541` with the user's wording. One-line edit. No CTA / Play Store button — user explicitly chose the simpler sharpened message.

---

## Item 5 — Cast-controls matrix doc + grey-out enforcement
**Confirmed.** File the 36-row matrix at `docs/superpowers/audits/2026-05-06-cast-controls-matrix.md` so future contributors understand the silent-no-op vs works distinction. The grey-out implementation is part of Item 3.

---

## Implementation order (after your approval)

1. **DB migration**: 5 min.
2. **AGP downgrade**: 15 min. Verify `bundleRelease` succeeds.
3. **Spotify message polish**: 2 min.
4. **Cast core (D-1):** ~5 hours
   - Add NanoHTTPD dep
   - LanIpDiscovery util
   - CastRelayServer singleton
   - Wire into switchPlayer
   - Test cold-start: tap local file → cast button → connect to Google Home → press play → audio on speaker
   - Test Drive OAuth: tap Drive .m4b → cast → audio on speaker (verify token forwarding)
   - Test SAF: tap SAF file → cast → audio on speaker
5. **Cast UI repositioning**: 1 hour
   - Move Cast button to bottom row
   - Add to expanded layout
   - Hide-when-Spotify gating
6. **Grey-out enforcement**: 2 hours
   - Add `isCasting` to PlayerUiState
   - Apply `enabled = !isCasting` + DisabledGrey in:
     - `PlaybackControls` speed slider
     - `AudioEffectsButton` popup
     - `SettingsScreen` Audio extras + Audio effects sections
     - EQ tab top-banner
   - Hide video effects + frame-step during cast
7. **Spotify Connect picker (S-1)**: 1.5 hours
   - Bottom sheet in cloud Spotify section listing devices
   - "Casting to <device-name>" indicator in Spotify mirror header
8. **Cast matrix doc**: 5 min — file as-is.

Total: ~10-11 hours of focused work + manual testing.

---

## Layman's terms — what's changing for you

1. **Database stays safe across app updates** (item 1). Done.
2. **Build to upload to Play Store actually works** (item 2). Done.
3. **Cast button does what people expect:** plays your local music + audiobooks + Drive files on a Google Home / TV / cast device. Spotify content uses its own "Spotify Connect" picker because Cast and Spotify Connect are different things — but you'll have UI for both.
4. **Spotify error message** says exactly what you wrote.
5. **Audio effects (EQ, reverb, etc) grey out** when casting, with a small "phone playback only" hint, so users aren't confused.

## Six confirms before I start

1. **DB migration (option b):** confirmed by you. Proceed?
2. **AGP downgrade (8.7.3 + Gradle 8.10.2):** confirmed by you. Proceed?
3. **Cast (D-1 HTTP relay for local + Drive, S-1 Spotify Connect picker UI):** new plan based on deeper dive. Proceed with all sub-items? Or pick a subset?
4. **Cast button position:** bottom transport row, right of Bluetooth, in BOTH compact and expanded layouts. Hidden when Spotify is active. **Confirm?**
5. **Spotify message polish:** use your exact wording. Proceed?
6. **Anything to skip?** (e.g. "skip the Spotify Connect picker for v1.0", "ship without grey-out enforcement", etc.)

I'll wait for your green light before any code change.
