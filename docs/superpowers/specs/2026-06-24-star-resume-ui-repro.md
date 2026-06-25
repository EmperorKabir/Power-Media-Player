# #18 — "no auto-resume after star" repro + verdict

## Investigation conclusion (evidence-locked)
- Starring is **causally inert** to resume: `LastPlayedRepository.pinSession`
  writes ONLY `history_favourites` (+ snapshot bookmarks) — never
  `adoptSession`/`recordPlay`/`currentSessionId`/`playback_history`.
- The process had **not** died, so cold-start correctly hit
  `PlaybackSessionCoordinator` `session-already-adopted → skip` (it was still
  playing). No resume-logic bug on current evidence.
- Therefore the candidate is a **UI-surfacing** gap (the Player tab/mini-bar may
  have looked un-resumed while the engine kept playing), NOT a resume-logic bug.

## Why no blind code change (no-guesswork rule)
- The Player UI is driven by `playbackConnection.playerState` (a live flow that
  already reflects the service session). Changing the player-state/adoption path
  without a reproduced, located gap risks breaking the **working** resume path
  (the exact failure mode the no-guesswork policy exists to prevent).
- So the surfacing change is **evidence-gated on the device repro below**; the
  apparatus ships now, the targeted change lands when the repro pinpoints it.

## Device repro protocol  `[DEVICE] AWAITING-USER`
1. Settings → enable Diagnostic logging (DiagLog → `diag/log-current.txt`).
2. Play a Drive audiobook; mid-play, tap the star (pin it).
3. Send the app to background (Home) — do NOT swipe-kill; reopen.
4. Record per reopen: (a) does audio keep playing? (b) does the **Player tab**
   show the item (title/cover/transport), or "nothing's playing"? (c) does the
   mini-bar show it? Screenshot each.
5. Pull + correlate by wall-clock timestamp:
   `python tools/deeplog/parse_logs.py … --category DEC,RESUME,PLAYER` — look for
   the `session-already-adopted → skip` DEC line co-occurring with an empty
   Player tab.

## Verdict matrix (fill in after the repro)
- **Engine playing + UI showed it** → no bug; close. (Most likely.)
- **Engine playing + UI showed empty** → real surfacing gap → push the live
  service session into the Player UI state on reopen (additive; NO change to
  `pinSession`/cold-start adoption/`session-already-adopted`).
- **Engine STOPPED** → a genuine resume gap on real process death → fix the
  cold-start/process-death resume path (evidence-locked, minimal).

Status: apparatus shipped; CODE change device-gated. The #19 follow-live feature
(shipped this turn) independently improves the resume UX the user asked about.
