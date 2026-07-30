# Efficiency audit — results (2026-07-31)

3-lens audit (Reasoning / Context7 / Superpowers) of today's vc74–vc78 diff (`be480d9..HEAD`, 19 files).
All three lenses converged: **disciplined diff, zero deprecated/misused APIs, zero High-severity defects.**
Net wins already present (preserved): I4d selective chapter-parse, I6 PodcastPlayback dedup, I8 isCasting
correctness fix, sound IO/Main dispatcher hygiene, reference-shared cachedQueue (no per-tick churn).

## Applied (2 — both low-risk, compile + unit-tests green)
- **F4** `PlaybackConnection.pollPositionOnce` — hoisted the duplicate `cachedPlaylistPosition(c)` in the
  I9 CASTPOS diag branch to a single local; logged value now matches emitted state. Diag-only, behaviour-identical.
- **F3** `PlaybackService.switchPlayer` — reset `castActiveFlow = false` on the relay-unavailable early-return
  (~:2781). Since I8 made that flag authoritative for the whole cast UI, leaving it stuck-true after a failed
  cast attempt (no Wi-Fi LAN) rendered "casting" while actually local. One line, error-path, definitionally correct.

## FLAGGED — design-level, deliberately NOT changed (delicate paths; owner decision)
- **F1 (IMPORTANT) — podcast autoplay entangled with music autoplay via a GLOBAL flag.**
  I4a's collector does `player.pauseAtEndOfMediaItems = !musicAutoplayNext`. That flag is a single global
  ExoPlayer property. Podcast queues rely on natural advance (flag=false). So turning **Music-autoplay OFF**
  sets it true globally → a **podcast** multi-episode queue pauses after episode 1 even though Podcast-autoplay
  is ON. Silent; both settings entangle. Correct fix = set `pauseAtEndOfMediaItems` per-play at `setMediaItems`
  time by content kind (music honours the pref; podcast queues force false), replacing the global live collector.
  A design change to the delicate player path — not applied. (Also, C7 note: the flag is local-only, so a CAST
  queue ignores autoplay-OFF entirely — accepted gap.)
- **F2 (IMPORTANT) — `oauthInFlight` can strand true; window widened 60s→5min.**
  I2's 5-min safety timer lives in `viewModelScope`. If CloudViewModel is cleared (leave Cloud screen) before the
  Spotify result callback fires AND before 5 min, the timer is cancelled → the `false` write never happens →
  `oauthInFlight` stays true → `handleAudioFocusChange` suppresses auto-resume for the rest of the process.
  Pre-existing (the old 60s timer had the same shape) but I2 makes the window 5× wider. Correct fix = host the
  timeout on a scope that outlives the screen (application/service), or belt-and-braces reset `oauthInFlight`
  on the next genuine AUDIOFOCUS_GAIN long after the launch. Design change to the delicate Spotify+focus path — not applied.

## KEEP notes (no action needed; recorded for future passes)
- primeCastStart `Player.Listener` isn't removed if READY never arrives — **dormant by default** (CAST_START_DELAY_MS=0),
  self-corrects, bounded per-session. If the lead-in feature is exercised, mitigate with a single reusable listener field (no teardown edit).
- `castcover` keyed by 32-bit `String.hashCode()` (reader I5a matches the pre-existing writer) — latent wrong-cover on collision.
  If ever revisited, apply the T312 SHA-256 keying to BOTH `writeCastCover` and `castCoverFileUri` atomically.
- `DiagLog.event` is not truly free when disabled (formats + rings the line); new cast/Spotify sites are all well-gated so cost is negligible. "Cheap when disabled" comment overstates it (doc-only).
- transientToast comment overstates pre-subscription buffering (replay=0 drops pre-subscriber emits); functionally fine (collector subscribes before any toggle). Comment-only.
- Out-of-diff hygiene (AppAuth): confirm `AuthorizationService.dispose()` on teardown to avoid Custom-Tab connection leaks — future pass.
