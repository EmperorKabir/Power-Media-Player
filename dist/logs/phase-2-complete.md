# Phase 2 — Auto-hide controls — COMPLETE

**Completed:** 2026-05-07.
**Tasks:** 5. All committed + pushed.

## Tasks recap

| # | Subject | Outcome |
|---|---|---|
| 2.1 | Add 3 auto-hide DataStore keys + getters/setters | `VIDEO_CONTROLS_HIDE_SEC` (default 4), `AUDIO_EFFECTS_POPUP_HIDE_SEC` (default 3), `VIDEO_EFFECTS_POPUP_HIDE_SEC` (default 3). Each with reactive Flow getter + suspend setter. |
| 2.2 | Add Auto-hide controls section to SettingsScreen | New "Auto-hide controls" section between Display and Bluetooth Car Controls with 3 `AutoHideRow` dropdowns. Options: Never / 1 / 2 / 3 / 4 / 6 / 8 s. |
| 2.3 | Wire video-controls auto-hide in PlayerScreen | `viewModel.videoControlsHideSec` collected; `LaunchedEffect` reads value; 0 = Never (no auto-hide). Replaces hardcoded `delay(4_000)`. |
| 2.4 | Wire audio-effects + video-effects popup auto-hide | Both `AudioEffectsButton.kt` + `VideoEffectsButton.kt` now read their respective `audioEffectsPopupHideSec` / `videoEffectsPopupHideSec` from `SettingsViewModel.uiState`. 0 = popup stays until manual dismiss. |
| 2.5 | Phase 2 regression sweep | Build + install + monkey on both devices. 0 FATAL. |

## Single commit

`1e82ac6 — feat(settings): Phase 2 — Auto-hide controls + 3 user-configurable timers`

## Deferred items (per plan §D2 — will land in later phases)

- **Audio-mode controls auto-hide** (§D2 audio-folded / audio-unfolded). Current behaviour: audio controls never hide. Adding a hide path requires new state machinery similar to `controlsVisible` for video. Scheduled for Phase 3.
- **Crossfade panel sub-popup auto-hide** (§D2). Crossfade panel doesn't exist yet — Phase 4 builds it.
- **Full Settings tab section reorder** per §D (15 sections, current order is the existing 8). Deferred — requires a major SettingsScreen refactor that risks breaking working settings. Will be addressed as part of later phases as new sections are added.

## Regression results

- Build: clean (`assembleDebug` exit 0).
- Z Fold 6: monkey 100 events seed=2, 0 FATAL.
- Emulator (Pixel_6_API_34): monkey 100 events seed=2, 0 FATAL.
- Cold-start branch from Phase 1 still fires.

## Next phase

**Phase 3 — Library improvements** (UI-only, no audio risk).
- C16: refresh-on-tab-switch.
- C25: long-press track menu (`TrackContextSheet`).
- C26: multi-select.
- C27: hidden files.

~14 tasks. See plan §J Phase 3.
