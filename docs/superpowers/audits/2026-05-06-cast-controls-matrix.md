# Cast-controls matrix — what each control does WHILE casting is active

**Reference doc.** Compiled by parallel-agent investigation 2026-05-06 (see `docs/superpowers/plans/2026-05-06-master-plan-pre-play-store-v2.md`). When `MediaSession.player` is `CastPlayer` (i.e. `uiState.isCasting == true`), every control routes through MediaController to whichever Player is active. Most operate cleanly on the receiver; some attach to the local ExoPlayer's audio session and silently no-op on the receiver. The grey-out enforcement at commit `<this-commit>` ensures no-op controls are not actionable while casting.

| # | Control / Setting | Verdict | Reason / citation |
|---|---|---|---|
| 1 | Play / pause | WORKS | `Player.play()/pause()` are uniform across CastPlayer + ExoPlayer (`PlaybackService.kt:554` `ms.player = target`). |
| 2 | Track-slider scrub | WORKS | `controller?.seekTo()` routes via MediaController to active player. |
| 3 | Full / playlist slider | WORKS | `seekToAbsolutePlaylistPosition()` uses `seekTo(i, positionMs)`. |
| 4 | Skip-back-N (5/10/15/20/30) | WORKS | `cumulativeSkip(session.player, deltaMs)` (PlaybackService) → `player.seekTo(target)` works on CastPlayer. |
| 5 | Skip-forward-N | WORKS | Mirror of above. |
| 6 | Previous / next chapter-or-track | WORKS | `seekToPreviousMediaItem()` / `seekToNextMediaItem()` are standard Player commands. |
| 7 | Previous / next file | WORKS | Same as #6. |
| 8 | **Speed (0.5×–2.0×)** | **GREYED OUT** while casting. | CastPlayer (Media3 1.6.0) doesn't expose `setPlaybackSpeed`. `PlayerScreen.kt` PreparedSpeedComponent `enabled = ... && !uiState.isCasting`. |
| 9 | Volume slider | WORKS | Routes to receiver volume via Cast SDK. |
| 10 | **Frame-step ±** | **HIDDEN** while casting. | No remote frame-step on receiver. `PlayerScreen.kt` step buttons gated by `if (!uiState.isCasting)`. |
| 11 | A/B loop | WORKS | Position polling + seekTo work uniformly. |
| 12 | Bookmarks (add / seek-to) | WORKS | Position read from active player; seek via standard `seekTo`. |
| 13 | Chapter picker | WORKS | `seekToChapterIndex` calls `seekTo` on active player. |
| 14 | Sleep timer | WORKS | Affects local timer; pauses via `Player.pause()` on whichever player is active. |
| 15 | Brightness override | WORKS | Device-side only, independent of player. |
| 16 | Cover-art Fit/Fill | WORKS | UI-only, no player interaction. |
| 17 | Reverse audio | N/A (no-op anyway) | Local-only feature; not implemented for cast. |
| 18 | **Independent pitch** | **SILENTLY NO-OP** (greyed via PreparedSpeedComponent gating on speed). | CastPlayer ignores pitch. |
| 19 | **Volume boost (LoudnessEnhancer)** | **GREYED OUT in AudioEffectsButton** while casting. | LoudnessEnhancer attaches to local `audioSessionId`; receiver decodes audio independently. |
| 20 | **Audio delay** | **GREYED OUT** (Settings → Audio extras section also gated on `isCasting`). | `AudioDelayProcessor` is an injected ExoPlayer audio-sink stage; bypassed when CastPlayer is the source. |
| 21 | **Crossfade** | **GREYED OUT**. | `setCrossfadeFactor()` writes to local `ExoPlayer.volume` only; CastPlayer not affected. |
| 22 | **ReplayGain toggle** | **GREYED OUT**. | Same — ExoPlayer.volume + LoudnessEnhancer combo, both local. |
| 23 | **Gapless playback** | **GREYED OUT** (no audible effect; receiver handles its own gap behaviour). | ExoPlayer-decoder-level setting. |
| 24 | Resume on Bluetooth | WORKS (logical pause routes to active player). | `setHandleAudioBecomingNoisy(true)` on ExoPlayer; pause command still routes via MediaSession to active player. |
| 25 | **Reverb preset** | **GREYED OUT in AudioEffectsButton**. | `EnvironmentalReverb` attaches to local `audioSessionId`. |
| 26 | **Stereo flip / Mono mix** | **GREYED OUT in AudioEffectsButton**. | `StereoTransformProcessor` is in local ExoPlayer audio sink. |
| 27 | Multi-channel passthrough | WORKS | Receiver-side decision; CastPlayer forwards bitstream. |
| 28 | **EQ 10-band** | **GREYED OUT** via banner at top of EqualizerScreen + EqualizerViewModel.isCasting. | `Equalizer(0, sessionId)` uses local `audioSessionId`. |
| 29 | Mirror H, Flip V (video effects) | HIDDEN while casting | Local TextureView only; receiver renders the actual stream. |
| 30 | B&W, Sepia, Invert | HIDDEN while casting | Same. |
| 31 | Rotation 0/90/180/270 | HIDDEN while casting | Same. |
| 32 | BT prev/next remap | WORKS | `applyAction` routes through `session.player` which is CastPlayer when casting. |
| 33 | Tap local file (`file://`, `content://`) when casting | WORKS via relay | `PlaybackService.switchPlayer` rebuilds MediaItems with `http://<lan-ip>:<port>/<token>` URLs served by `CastRelayServer`. |
| 34 | Tap Drive (drive.file OAuth) when casting | WORKS via relay (with token forwarding) | Same — `RelayItem.DriveOAuth` forwards Range requests with fresh `Authorization: Bearer <token>` per request. |
| 35 | Tap Drive (SAF) when casting | WORKS via relay | `RelayItem.Local` path serves `content://` via `ContentResolver.openInputStream`. |
| 36 | Spotify track | INDEPENDENT — uses Spotify Connect | CastPlayer is not involved; user picks a Spotify Connect device via the cloud Spotify section's "Spotify Connect device" picker. |

## Summary

- **15 controls work normally during cast.**
- **12 controls are greyed out** (audio effects + speed/pitch + frame-step + EQ banner). Greying is enforced at commit `<this-commit>` via `uiState.isCasting` from `PlayerViewModel.uiState` combine + `EqualizerViewModel.isCasting` flow.
- **6 video-effect controls are hidden** during cast (mirror, flip, B&W, sepia, invert, rotation).
- **Cloud sources work via the new `CastRelayServer`** (local + Drive + SAF).
- **Spotify is its own world** — handled by the new "Spotify Connect device" picker in the cloud Spotify section, independent of the Cast button.
