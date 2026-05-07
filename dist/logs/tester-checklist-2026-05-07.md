# Tester checklist — what to try on the Z Fold 6

Use this to verify the new features actually work for you. Each row tells you exactly what to do and what to expect.

The latest debug APK is already installed on your phone (Z Fold 6, model SM_F966B). All builds passed monkey stress on-device.

---

## 1. Info icons (every tab)

- Open the app → look top-right of every tab.
- **Expect:** small blue rounded-square box with a white "i" letter.
- Tap it → bottom sheet opens with grouped sections (Player has 5 groups, Library 3, Last Played 3, Cloud 4, EQ 3).
- Tap a section header → it expands. Tap another → the previous collapses (accordion).
- On the **Player tab during video**: leave the screen alone for 4 s → controls AND info icon hide together. Tap screen → both reappear.

## 2. Bookmark mirror (the bug fix)

- Force-stop the app. Re-launch.
- **Expect logcat:** `Cold-start restored '<your last track>' @ <ms> (session N)` — already verified at session 16 in this session's tests.
- While the auto-resumed track is playing, tap the bookmark icon to add a bookmark.
- Open Last Played tab → find the same track in Recents → tap to expand its bookmarks dropdown.
- **Expect:** the bookmark you just added appears in that row's dropdown.
- This used to silently drop on cold-start auto-resume; now it works in every scenario.

## 3. Frame-step icons (video only)

- Open a video file (any MP4 / WebM in your Library).
- Pause it.
- Look at the player transport row.
- **Expect:** distinct frame-step icons (thick bar + single triangle) — NOT the same icons as prev/next-track. Tap them to nudge by ~one frame at a time.
- Switch to an audio file → frame-step icons disappear entirely.

## 4. Auto-hide controls timers

- Settings → Auto-hide controls.
- Three dropdowns: video controls (default 4s), audio effects popup (default 3s), video effects popup (default 3s).
- Change "Video controls" to "Never" → open a video → controls stay visible until you tap to dismiss.
- Change to 1s → controls vanish after 1 second.

## 5. Library long-press menu

- Library tab → long-press any track row.
- **Expect:** bottom-sheet menu with: Favourite (or Unfavourite if already starred), Hide, Share.
- Tap Hide → row vanishes from list immediately.

## 6. Hidden files Settings sub-sheet

- Settings → "Hidden files (N)" row (count shown in label).
- Tap → bottom sheet lists every hidden URI with per-row Unhide button + "Unhide all".
- Tap Unhide → file reappears in Library on next visit.

## 7. Multi-select mode

- Library tab → 3-dot menu → "Select multiple".
- **Expect:** action bar overlays with count + Cancel + Favourite-all + Hide-all icons.
- Each row gains a checkbox at left. Tap rows to tick.
- Tap Favourite-all → all ticked rows get a star.
- Tap Hide-all → all ticked rows vanish (find them in Settings → Hidden files).

## 8. Crossfade panel

- Player → tap the Crossfade icon (right of Audio Effects).
- **Expect:** bottom sheet with 9 controls — Master crossfade, duration slider, fade curve dropdown, Album mode, Skip silence, Pre-fade trigger, Manual fade-now, Fade-out on pause, Fade-in on resume.
- Toggle Master ON, set duration to 5s.
- Play 2 audio files in sequence → expect a brief volume ramp at the transition (single-player linear fade — true 2-player overlap is deferred).
- Switch to a video file → tap Crossfade icon → entire panel greyed out (compatibility matrix).

## 9. Quick Settings tiles

- Pull down quick settings → tap "edit / pencil" icon.
- **Expect:** three new tiles available: Power Media Player (play/pause), PMP −15 s, PMP +15 s.
- Drag them into your active QS row.
- Lock device → pull down QS → tap each tile → playback responds.

## 10. Plug-in resume

- Settings → "Auto-play on headphone connect" → toggle ON.
- Pause playback.
- Plug in headphones (wired or BT connect).
- **Expect:** playback resumes automatically.
- Toggle OFF → plug in headphones → no auto-resume.

## 11. Sleep timer fade-out

- Player → Sleep timer button.
- Toggle "Linear fade-out" ON.
- Set a 1-minute timer.
- Listen — the audio is at full volume for 30 s, then ramps down to silence over the final 30 s before pausing.

## 12. Listening stats dashboard

- Settings → "Listening stats" row.
- **Expect:** bottom sheet shows total plays, time listened (rough), longest track, top-5 titles, top-5 artists/sources.
- Numbers reflect your actual playback history (you've already accumulated some; should be non-zero).

## 13. Tasker / external-intent control

- Settings → "External app control (Tasker / Macrodroid)" → toggle ON.
- From your computer with Z Fold 6 connected via USB-debug:
  ```
  adb shell am broadcast -a com.powermediaplayer.action.PLAY_PAUSE -p com.powermediaplayer
  ```
- **Expect:** playback toggles play/pause. Logcat shows `TaskerReceiver handled '...PLAY_PAUSE'`.
- Toggle OFF → repeat command → logcat shows `TaskerReceiver ignored '...PLAY_PAUSE' — toggle is OFF`. Playback unaffected.
- Other documented actions: PLAY, PAUSE, SKIP_NEXT, SKIP_PREV, SKIP_BACK_30, SKIP_FORWARD_30, SEEK_TO (`--el position_ms <value>`).

## 14. Cast bug investigation diagnostics

- If you cast and the bug recurs ("connects but nothing plays"), capture:
  ```
  adb -s RFCY70BARDJ logcat -s PMP_DIAG:V > cast-bug-zfold-$(date +%H%M).log
  ```
- Logs now include verbose CastRelayServer responses (status, MIME, byte count, range, fileId for Drive). These let me bisect H1–H6 from plan §J Phase 11 evidence-locked.

---

## What's NOT yet user-verifiable on this build

- True 2-player crossfade overlap (Phase 4 audio-engine follow-up).
- Curve maths (Linear / Equal-power / Exponential / S-curve all currently fall back to linear).
- Album mode / Skip silence / Pre-fade trigger / Manual fade-now / Fade-on-pause / Fade-on-resume actual behaviour (settings persist; engine deferred).
- Per-file overrides (Phase 5 — Room migration).
- OpenSubtitles auto-fetch / Drive offline copy / Podcast subscriptions (Phase 6).
- Wake-up alarm with full-screen lock-screen wake (Phase 7).
- Home-screen widget (Phase 8).
- Headphone-aware EQ (Phase 9).
- Audio focus policy customisation (Phase 9).
- Discogs / MusicBrainz metadata enrichment (Phase 9).
- ReplayGain library scanner (Phase 9).
- Smart playlists (Phase 9).
