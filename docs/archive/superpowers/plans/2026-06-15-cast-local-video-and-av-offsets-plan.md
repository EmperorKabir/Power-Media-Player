# Plan — local video while casting audio + Cast/Bluetooth A/V offset sliders (T295)

Date 2026-06-15. User-approved feature. Evidence-gathered from the live code.

## Goal (verbatim intent)
1. When casting to an audio-only device, KEEP the video playing on the phone (local, muted, best-effort synced to the cast audio) instead of stopping it.
2. A **Cast audio offset** slider — in Settings AND in the Cast button popup.
3. A **Bluetooth audio offset** slider — in the Bluetooth button popup AND in Settings.
4. Cast offset and BT offset are INDEPENDENT values.
5. Each offset's two locations bind to ONE stored value → Settings ↔ popup two-way sync.
6. All sliders accent-robust (recolour with the theme accent).
7. Update the info boxes (in-app info sheet + slider helper text) sensibly.

## Evidence (current code)
- **Why video stops on cast:** `PlaybackService.switchPlayer` → `current.stop()` (`:1971`) + `exoPlayerRef = null` (`:1996`) when target is CastPlayer. The on-phone `VideoSurface` binds via `getExoPlayer()` (WeakReference) → nothing to render. CastPlayer streams to the receiver (audio-only device plays audio, drops video).
- **BT offset already exists:** `SettingsDataStore.btVideoAudioOffsetMs` (`:35,945-953`, ±1000 ms, default 0). Settings slider `BtVideoAudioOffsetRow` (`SettingsScreen.kt:1665`, section "bt-av-offset" used at `:344-349`). Wired: `PlaybackService:597-600` sums `audioDelayMs + btVideoAudioOffsetMs` → `audioDelayFlag` → AudioDelayProcessor ring buffer. → BT work = ADD TO POPUP ONLY (reuse pref).
- **Cast offset:** does not exist → new pref + new UI + applied in the new sync loop.
- **Accent:** `TealAccent` is a Compose `mutableStateOf` holder (`Color.kt:13-19`) → reading it auto-recomposes on accent change. Sliders that colour with TealAccent/Teal* are accent-robust for free.
- **Reusable sliders:** `SliderRow` + `ResetSliderRow` (`SettingsScreen.kt:985,1615`). Popups use `TealAccent`/`SurfaceElevated`/`OledBlack` throughout (CastSwitcher.kt, BluetoothButton.kt).
- **Info boxes:** `InfoContent.kt` — Bluetooth entry (`:55`), Cast entry (`:56`). Hue sync-offset helper text references "BT video audio offset" (`SettingsScreen.kt:2276`).
- **Cast device target rule:** only Living Area TV / Kabir Stereo / Kabir's Kitchen Stereo; the Kitchen Stereo is the audio-only test target.

## Implementation steps (dependency order)
1. **Data** — `SettingsDataStore`: add `CAST_VIDEO_AUDIO_OFFSET_MS` key + `castVideoAudioOffsetMs: Flow<Int>` (±1000 ms, default 0, coerced) + `setCastVideoAudioOffsetMs`. Expose `castVideoAudioOffsetMs` + setter in `SettingsViewModel` (state) and `PlayerViewModel` (so popups can bind). BT offset already exposed in SettingsViewModel; ALSO expose it in PlayerViewModel for the BT popup.
2. **Feature** — `PlaybackService`: when switching to CastPlayer AND the current item is VIDEO, do NOT stop the local player; instead keep it decoding video, MUTE it (volume 0), keep `exoPlayerRef` bound to it, and start a sync coroutine: every ~1 s read `castPlayer.currentPosition`, target `castPos + castOffsetMs`; if local drift > ~400 ms, seek local to match; mirror playWhenReady. Stop/clear the sync + restore on switch-back-to-local / stop-cast / audio item. KEEP all existing cast relay + metadata logic. Audio-only items keep the current stop behaviour (no video to show). Gate so Hue/FFT remains off during cast (unchanged).
3. **Bluetooth popup** — `BluetoothButton.BluetoothSheetContent`: add an "A/V sync offset" slider bound to `btVideoAudioOffsetMs` (PlayerViewModel). Accent-coloured. Helper text.
4. **Cast popup** — `CastSwitcherButton` sheet: add a "Video/audio sync offset" slider bound to `castVideoAudioOffsetMs`. Shown always (or when a video is active); accent-coloured; helper text.
5. **Settings** — add a Cast offset slider mirroring `BtVideoAudioOffsetRow` (new `CastVideoAudioOffsetRow`), in a sensible section near the BT one. Wire to SettingsViewModel.
6. **Info boxes** — update `InfoContent.kt` Cast entry (mention local-video-while-casting + the offset) and Bluetooth entry (mention the offset in the popup); update the BT slider description if needed; update the Hue note to mention both offsets.
7. **Build + device-verify** (Z Fold6 → Kabir's Kitchen Stereo): video keeps playing on phone while audio casts; cast offset slider visibly shifts sync; BT offset reachable + working from the BT popup; moving a slider in Settings moves it in the popup and vice-versa; accent change recolours all sliders. Regression: normal cast (audio) still works; stop-cast restores local; non-video cast unaffected; Hue/BT/Spotify paths untouched.

## Caveat (accepted)
Auto-sync of local video to remote cast audio is best-effort (cast latency drifts; Cast APIs don't expose frame-accurate timing). The cast offset slider is the manual fine-tune — this is the intended UX, not a workaround.

## Anti-skip
- All 7 steps implemented + both offset values independent + all 4 slider locations present + two-way sync + accent-robust + info boxes updated, before reporting done.
- Do NOT touch the working cast relay/metadata/Spotify/Hue/BT-resume paths beyond the minimal feature change.
- Device-verify the feature before claiming it works (cast path is delicate — broke before).
