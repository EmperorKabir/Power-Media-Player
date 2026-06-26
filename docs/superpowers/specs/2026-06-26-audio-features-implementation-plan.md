# Audio/Podcast Features — Implementation Plan (feeds /android-build-and-device-test)

Date: 2026-06-26. Phases 1–2 (audit + Context7/Superpowers investigation) complete; this is Phase 3 (the plan). Execution = the `android-build-and-device-test` 4-stage cycle.

## Cross-cutting principles (apply to every feature)
- **Effect chain** (PlaybackService custom `DefaultAudioProcessorChain`): `stereoTransform → reverb → EQ → audioDelay → gain → hueTap`. New audio processors slot in here.
- **Per-file override system**: skip-silence + voice-boost become MediaOverrideEntity axes (like EQ/reverb), resolved via `activeOverride` (episode→show→global merge already exists) keyed by `currentOverrideKeyFlow`. Adding columns = **Room migration 21→22** (current @Database version 21; do NOT renumber existing migrations).
- **Route/source gating**: audio effects only apply to the LOCAL ExoPlayer pipeline → work on Local/Drive + BT/wired; **N/A on Spotify (Connect) and Cast**. Grey/disable the relevant UI when `isSpotifyActive` or `isCasting`, with a one-line "not available on Spotify/Cast" note.
- **File-type defaults**: spoken-word (podcast/audiobook) defaults differ from music (e.g. voice-boost/skip-silence default-on for spoken via the per-show/per-file override, off for music).

## Feature 1 — Auto-play next podcast episode + toggle
- **Change**: `PodcastsViewModel.playEpisode` builds a QUEUE (`setMediaItems(List, …)`) = the show's episodes from the tapped one forward (ordered by publishedAt desc, capped ~50, option: downloaded-only). New DataStore `podcastAutoplayNext` (default ON) + a per-show toggle on the subscription row. When off → single item (today's behaviour).
- **API (Context7-verified)**: `Player.setMediaItems(List<MediaItem>, resetPosition)` + auto-advance via `MEDIA_ITEM_TRANSITION_REASON_AUTO`. Each item carries its `pmpOverrideKey` extra so per-episode/show overrides + position-resume work per item.
- **Route/type**: queue works Local/Drive/BT/wired/Cast; Spotify podcasts ride Spotify's queue (unchanged).
- **Tests**: unit — queue builder (ordering/cap/downloaded-filter). Device — play episode N → it advances to N+1 at end; toggle off → stops; each episode shows its own metadata + override.
- **Files**: PodcastsSection.kt (VM playEpisode + per-show toggle UI), SettingsDataStore (key), PodcastDao (episodesFromForFeed query), ReorderableShowList (toggle).

## Feature 2 — Skip silence (surface + per-file)
- **Change**: keep `player.skipSilenceEnabled`; add `skipSilence: Boolean?` override axis (MediaOverrideEntity, migration) + a first-class Settings toggle (move out of the Crossfade-only popup; keep a mirror there). Drive `skipSilenceEnabled` from `activeOverride.skipSilence ?: globalSkipSilence`. Grey on Spotify/Cast.
- **Optional**: replace the built-in with a tunable `SilenceSkippingAudioProcessor` in our chain for a min-silence threshold slider (Context7: SilenceSkippingAudioProcessor(minimumSilenceDurationUs, paddingSilenceUs, silenceThresholdLevel)).
- **Tests**: unit — override resolution incl. skipSilence in mergeEpisodeOverShow. Device — toggle on a podcast → silence trimmed (position advances faster across a silent gap; verify via logcat skipSilence flag + audible/position check).
- **Files**: MediaOverrideEntity (+migration), MediaOverrideRepository.mergeEpisodeOverShow, PlaybackService (drive from override), MediaOverridesPopup (axis), SettingsScreen (first-class toggle).

## Feature 3 — Voice boost / speech clarity (+ existing volume normalisation)
- **Change**: NEW `VoiceBoostAudioProcessor` (presence-band ~1.5–4 kHz lift + gentle dynamic compression for intelligibility) inserted **after EQ, before gain** in the chain. `voiceBoost: Int?` (0=off / level) override axis (migration) + global toggle. Default-on for spoken via per-show/file override.
- **API**: custom `androidx.media3.common.audio.BaseAudioProcessor` (same pattern as EqualizerAudioProcessor/ReverbAudioProcessor — Context7-verify BaseAudioProcessor.queueInput/processBuffer signatures for the installed media3). Coeffs/compressor maths unit-tested.
- **Volume normalisation**: ReplayGain already complete — no change; document it's the "normalisation" answer + local-route-only.
- **Route/type**: Local/Drive/BT/wired; grey on Spotify/Cast; spoken-word default.
- **Tests**: unit — VoiceBoostAudioProcessor (THD/gain at presence band, pass-through when off) like EqualizerThdTest. Device — enable on a podcast → speech louder/clearer (verify processor active via a Diag log + A/B).
- **Files**: audio/VoiceBoostAudioProcessor.kt (+test), PlaybackService (chain insert + flag), MediaOverrideEntity (+migration), MediaOverridesPopup (axis), PlayerViewModel.applyDirectAxes or service flow.

## Feature 4 — Cloud settings/cache backup & restore (Drive)
- **Change**: NEW backup module. Serialize selectable SETS to a JSON manifest (+ artwork blobs optional) → upload to app's Drive (Drive OAuth present). Restore = download + apply. Sets (independent or together): (a) Settings (DataStore prefs), (b) Overrides + EQ presets (Room media_overrides, equalizer_presets), (c) Library data (podcast subs, favourites, history, pinned albums), (d) Caches (enrichment_cache; artwork optional — large/regenerable).
- **API (Context7-verify)**: Drive REST files.create/update (multipart) + files.list (appDataFolder scope vs drive.file); DataStore export (read all prefs), Room @Query dumps / Gson.
- **Route/effects**: agnostic (data feature). No effect-chain interaction.
- **Tests**: unit — serialize→deserialize round-trips each set (no data loss); merge-vs-replace policy. Device — back up, change a setting, restore, confirm reverted; restore on a clean install path (manual).
- **Files**: backup/BackupManager.kt, backup/BackupModels.kt, DriveOAuthProvider (upload/download helpers), SettingsScreen (Backup & restore section with per-set checkboxes + Back up now / Restore).

## Feature 5 — Sleep timer (refinements only; core exists + device-verified)
- **Change**: (a) fade-out on Spotify/Cast — ramp the REMOTE volume (SpotifyProvider.setVolume / cast volume) instead of the local mixer factor, so fade is audible on those routes; (b) end-of-queue becomes meaningful once Feature 1 lands (no code change). 
- **Tests**: device — set a short timer on Spotify → volume ramps down then pauses.
- **Files**: PlayerViewModel sleep-timer fade branch (route-aware).

## Suggested order (low-risk → high)
1. Feature 1 (podcast auto-advance) — high value, queue-level, no migration.
2. Feature 2 (skip-silence surface + axis) — small, migration starts.
3. Feature 3 (voice-boost) — new processor + axis (same migration batch as #2).
4. Feature 4 (cloud backup) — independent, largest.
5. Feature 5 (sleep-timer remote fade) — small refinement.

## Build-cycle (skill) mapping
- Stage 1 Research: per feature above (done in Phases 1–2).
- Stage 2 Plan: this doc → `.build_cycle_checklist.md` multi-level checkboxes; Context7 re-verify each media3/Drive/Room API at the installed versions before coding.
- Stage 3 Implement: TDD the pure units (queue builder, VoiceBoost maths, backup round-trips, override merge) first.
- Stage 4 Device-test: adb on RFCY70BARDJ (`-s`), predefined signals (logcat flags, media_session state/speed, UIAutomator bounds, screencap). Spotify/Cast route checks = physical-only where a live Connect device/cast target is needed → pause + precise instruction.

## Migrations / risk
- ONE Room migration 21→22 adds: media_overrides.skipSilence (INTEGER nullable), media_overrides.voiceBoost (INTEGER nullable). Update AppDatabase version + MIGRATION_21_22 + entity + DAO + mergeEpisodeOverShow + MediaOverrideMergeTest.
- Effect-chain order change (insert VoiceBoost) — verify EQ/reverb unaffected (existing THD tests must still pass).
