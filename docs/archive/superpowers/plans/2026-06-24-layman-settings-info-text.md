# Plan — Layman-friendly Settings + Info text (items #10 + #11)

> Source spec: `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`
> (findings #10 Webhooks; #11 full layman pass + factual errors).
> Scope: **string-only** rewrites (plus ONE non-string code change — the About
> version must read `BuildConfig.VERSION_NAME`, not a hardcode). No behaviour,
> no layout-structure, no wiring changes. British spelling; plain English.

## Goal

Make every user-facing word in Settings + the Info sheets understandable to a
non-technical person. Strip developer jargon and raw code/class names. Fix the
three factual/stale defects: hardcoded "Version 1.0.0" (actual 1.3.4), the
stale "on the roadmap" Smart-home placeholder shown next to live features, and
the American spelling "colors".

## File structure (the only files this plan touches)

All paths under
`app/src/main/java/com/powermediaplayer/`:

| File | What changes |
|------|--------------|
| `ui/settings/SettingsScreen.kt` | Webhooks rewrite (#10); SmartHomePlaceholder rewrite (#10); About version → `BuildConfig.VERSION_NAME` (#11 factual); "colors"→"colours" (#11); de-jargon: passthrough, subtitle formats, Deep Scan, ReplayGain-auto, Hue white-only block, Hue scene Kelvin pass-through label; misc group/helper text. |
| `ui/settings/ReplayGainModeRow.kt` | "-14 LUFS" / "track-gain/album-gain" de-jargon. |
| `ui/settings/ReplayGainScanRow.kt` | "per-file gain" helper de-jargon (minor). |
| `ui/settings/OfflineStorageLimitRow.kt` | "evicted" de-jargon. |
| `ui/settings/EnrichmentSubToggles.kt` | "embedded tags" wording (minor). |
| `ui/settings/OpenSubtitlesSection.kt` | "dev API key (…→Profile→Consumers)" flow de-jargon. |
| `ui/settings/HeadphoneEqSection.kt` | "global EQ" wording (minor). |
| `ui/settings/ThemeSection.kt` | already clean — VERIFY only, no edit expected. |
| `ui/settings/StorageFoldersRow.kt` | "DRM; Connect-only" wording (minor). |
| `ui/info/InfoContent.kt` | Remove raw code/class names (`WRITE_SETTINGS`, `EnvironmentalReverb`, `SpotifyProvider.skipNext…`, `ExoPlayer/AudioProcessor/Visualizer`); de-jargon Webhooks bullet, Hue Kelvin, LUFS, LRU/eviction, A2DP/AVRCP. |
| `hue/HueProvider.kt` | ScenePreset enum `description` strings (render at SettingsScreen.kt:2442) — strip "2700K/5000K". |

> `ui/info/InfoSheet.kt` (the renderer + `InfoSection`/`InfoSheetData` models)
> is read-only context; not edited.

### Constant verification (do FIRST, Task 0)

- `app/build.gradle.kts`: `buildFeatures { buildConfig = true }` (line 115,
  confirmed) + `defaultConfig { versionName = "1.3.4" }` (line 32, confirmed)
  ⇒ AGP auto-generates `com.powermediaplayer.BuildConfig.VERSION_NAME`. NO
  `buildConfigField` needed. `BuildConfig` already imported/used elsewhere
  (`SpotifyProvider.kt:9` etc.), so the symbol resolves.
- Namespace = `com.powermediaplayer` (line 24), so the fully-qualified
  reference is `com.powermediaplayer.BuildConfig.VERSION_NAME`. SettingsScreen
  is in that package already → can write `BuildConfig.VERSION_NAME` after an
  `import com.powermediaplayer.BuildConfig`.

---

## Design decisions (CONFIRM BEFORE EXECUTION)

1. **Tone / reading-level target.** Aim ~ Year-9 / age-13 reading level
   (plain, short sentences, no acronym unless defined inline once). Concrete
   over abstract: "speaker", "TV", "your home automation app" not "endpoint",
   "sink", "service". PROPOSED — confirm.
2. **Keep a short "advanced" parenthetical for power-users?** PROPOSED: YES,
   but only as a trailing parenthetical the layman can ignore, e.g. "sends a
   small web message (a 'webhook')". The plain meaning leads; the technical
   term is the aside, never the headline. This preserves searchability (the
   keyword lists already contain "webhook", "ifttt", "lufs", etc. — those are
   NOT shown to the user, so search still works regardless). Confirm.
3. **Delete vs rewrite `SmartHomePlaceholder`?** Two live options:
   - (3a) **REWRITE** into a true "what's here" signpost that points at the
     two working features (Hue is in this same Lighting group; Webhooks is in
     Automation). Keeps the catalogue item id `smart-home` + its keywords
     ("matter", "automation") searchable, no inventory change.
   - (3b) **DELETE** the `smart-home` SettingsItem entirely (also drop it from
     `SETTINGS_ITEM_IDS` 1149 + the Lighting group 628-633) → the
     20-item inventory guard becomes 19; `SettingsSearchTest` may assert the
     count.
   PROPOSED: **3a (rewrite)** — lowest risk, keeps the inventory guard +
   tests green, and still removes the false "on the roadmap" claim. Confirm
   3a vs 3b. (If 3b chosen, add a sub-task to update `SETTINGS_ITEM_IDS` and
   any count assertion in `SettingsSearchTest.kt`.)
4. **Service-name lists.** Keep a SHORT, current example list ("Home
   Assistant, IFTTT, Tasker") rather than the long salad; drop dead/paid
   specifics from user-visible copy. The keyword search list keeps the
   broader set. Confirm.

> Execution does NOT begin until decisions 1-4 are confirmed. The NEW wordings
> below are written assuming 1=yes, 2=yes, 3=3a, 4=yes; if a decision flips,
> only the affected task's NEW string changes (noted inline).

---

## Per-task protocol (string-only — TDD impractical)

Each task lists, for its section, EVERY old→new pair (exact OLD string +
file:line, exact COMPLETE NEW string). Then, per task:

1. Apply the edits (exact-match `Edit`; strings are unique enough).
2. Build: `.\gradlew.bat :app:assembleDebug -q` — must exit 0.
3. Device-verify: install, open the named Settings section / Info sheet, read
   the new copy on the phone (screenshot or read-aloud), confirm no jargon
   remains and nothing overflows/clips.
4. Commit (one commit per task / section).

Auto-push + auto-install after each commit per MEMORY (feedback_auto_push).

---

# TASK 0 — About version fix + "colours" + verify BuildConfig (#11 factual)

**File:** `SettingsScreen.kt`.

### 0a. About version (SettingsScreen.kt:761-765) — FACTUAL ERROR

- ADD import near top (after line 31 `import com.powermediaplayer.ui.theme.*`):
  `import com.powermediaplayer.BuildConfig`
- OLD (762):
  ```kotlin
      text = "Version 1.0.0",
  ```
- NEW:
  ```kotlin
      text = "Version " + BuildConfig.VERSION_NAME,
  ```
  (Renders "Version 1.3.4" now, and tracks every future `versionName` bump.)

### 0b. About build-credits line (SettingsScreen.kt:768) — de-jargon (#11)

- OLD (768): `text = "Built with Media3 ExoPlayer, FFmpeg, Jetpack Compose",`
- NEW: `text = "Built with Media3, FFmpeg and Jetpack Compose",`
  (Drops the developer-only "ExoPlayer" internal name; keeps a tasteful credit.)

### 0c. "colors" → "colours" (SettingsScreen.kt:527) — BRITISH SPELLING

- OLD (527):
  `"Web-standard subtitles. Supports basic styling like bold and colors. " +`
- NEW (this whole VTT description is also rewritten in Task 6; if Task 6 runs,
  the line is replaced there. If Task 0 runs first, apply the spelling fix
  now and Task 6's replacement supersedes it):
  `"Web-standard subtitles. Supports basic styling like bold and colours. " +`

**Build → device-verify (open Settings → Appearance & system → About & reset:
reads "Version 1.3.4"; the subtitle-format VTT row reads "colours") → commit:**
`fix(settings): About uses BuildConfig.VERSION_NAME; British 'colours'`

---

# TASK 1 — Webhooks section rewrite (#10)

**File:** `SettingsScreen.kt`, `WebhooksSection` (1833-1927).

Removes jargon: webhook/endpoint/JSON/POST/8-char hash/"Fire on…"/"BT remote"/
"not when the system pauses". Adds a one-line "what is this" + brief setup
guidance, and surfaces the payload fields here (not only in the Player Info
sheet).

### 1a. Header (1844)
- OLD: `SettingsSectionHeader("Webhooks")`
- NEW: `SettingsSectionHeader("Webhooks (send events to other apps)")`

### 1b. Intro text (1845-1853)
- OLD:
  ```
  "Send an HTTP POST to your own endpoint when playback events " +
      "happen. Works with IFTTT, n8n, Home Assistant, Pushover, Google " +
      "Apps Script — anything that accepts a JSON body. Privacy: only an " +
      "8-char track hash is sent, no titles or paths."
  ```
- NEW:
  ```
  "A webhook lets this player tell another app when your music starts, " +
      "stops or changes — so that app can react (for example, dim the " +
      "lights when a track begins). You paste in a web address from a home-" +
      "automation app (such as Home Assistant, IFTTT or Tasker — its app " +
      "gives you the address), choose which events to send below, then tap " +
      "Save. Your privacy is protected: the player never sends song names " +
      "or file locations — only a short code for the track, the time, and " +
      "how far through it is."
  ```

### 1c. URL field label (1857)
- OLD: `label = { Text("Webhook URL (https://...)") }`
- NEW: `label = { Text("Web address from your automation app") }`

> NOTE: keep the value/placeholder format hint implicit; the field still
> accepts the same `https://…` string. No behaviour change.

### 1d. Test / Save buttons (1869-1875) + add a one-line "what Test does"
- OLD Test button text (1870): `Text("Test", color = TealAccent)`
- NEW: `Text("Send a test", color = TealAccent)`
- OLD Save button text (1874): `Text("Save URL", color = TealAccent)`
- NEW: `Text("Save", color = TealAccent)`
- ADD a one-line helper UNDER the button Row (insert immediately after the
  Row that closes at 1876, before the `if (testStatus…)` block at 1877). New
  `Text`, same style/padding as the intro helper:
  ```kotlin
  Text(
      text = "‘Send a test’ checks the address works before you rely on it. " +
          "‘Save’ keeps the address for future events.",
      style = MaterialTheme.typography.bodySmall,
      color = TextTertiary,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
  )
  ```

### 1e. Toggle titles + descriptions (1885-1926) — kill "Fire on…", "BT remote"

| Line | OLD title | NEW title | OLD desc | NEW desc |
|------|-----------|-----------|----------|----------|
| 1886/1887 | "Fire on track-play start" | "When a track starts" | "Sent when a new track begins playing." | "Send an event each time a new track begins playing." |
| 1892/1894 | "Fire on pause" | "When you pause" | "Sent when the user pauses (not when the system pauses)." | "Send an event when YOU pause. Automatic pauses — a phone call, another app — don't count." |
| 1899/1901 | "Fire on resume" | "When you resume" | "Sent when the user resumes after a pause." | "Send an event when you start playing again after a pause." |
| 1906/1908 | "Fire on skip-next" | "When you skip to the next track" | "Sent when next-track is pressed (in-app or BT remote)." | "Send an event when you press Next — in the app, or on a Bluetooth remote or car stereo." |
| 1913/1915 | "Fire on skip-previous" | "When you skip to the previous track" | "Sent when previous-track is pressed." | "Send an event when you press Previous." |
| 1920/1922 | "Fire on track-end" | "When a track finishes" | "Sent once when a track plays through to completion." | "Send an event once when a track plays all the way to the end." |

### 1f. Surface the payload fields HERE (new helper at end of section)
- ADD immediately BEFORE the closing `}` of `WebhooksSection` (after the last
  `SettingsToggleItem` at 1926):
  ```kotlin
  Text(
      text = "What each event contains: which event it was, the time it " +
          "happened, a short anonymous code for the track, how far through " +
          "the track you are, and the track's length. No names, no file " +
          "locations.",
      style = MaterialTheme.typography.bodySmall,
      color = TextTertiary,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
  )
  ```

**Build → device-verify (Settings → Automation → Webhooks: every line is plain
English, no "POST/endpoint/JSON/hash/Fire/BT"; payload explained inline) →
commit:** `feat(settings): rewrite Webhooks copy into plain English (#10)`

---

# TASK 2 — SmartHomePlaceholder rewrite (#10 stale/contradictory)

**File:** `SettingsScreen.kt`, `SmartHomePlaceholder` (1810-1829) + its KDoc
(1799-1809). Decision 3a (rewrite). Removes "on the roadmap" (both features
ship live) + the contradictory different service set.

### 2a. KDoc (1799-1809)
- OLD KDoc claims "Placeholder for now"/"details surface as the features land".
- NEW KDoc:
  ```kotlin
  /**
   * Smart-home signpost. Hue and Webhooks are both LIVE features; this
   * short note points the user at them (Hue is in this same Lighting
   * group; Webhooks is under Automation). Plain-English, no roadmap claim.
   */
  ```

### 2b. Body title (1814)
- OLD: `text = "Smart home",`
- NEW: `text = "Smart home",` (unchanged — already plain)

### 2c. Body text (1819-1824) — the stale "on the roadmap" claim
- OLD:
  ```
  "Philips Hue and Webhooks integrations are on the roadmap. " +
      "Both are zero-cost. Hue will discover bridges on your local " +
      "Wi-Fi; Webhooks lets you connect to any service that accepts " +
      "incoming HTTP — Home Assistant, Pipedream, n8n, Make.com, " +
      "IFTTT Pro, Tasker, etc."
  ```
- NEW:
  ```
  "This player can work with your smart home in two ways, both free " +
      "and set up elsewhere in Settings:\n\n" +
      "• Philips Hue — make your lights pulse in time with the music. " +
      "Set it up just above, under ‘Philips Hue’.\n" +
      "• Webhooks — tell another app (such as Home Assistant or IFTTT) " +
      "when your music starts or stops, so it can react. Set it up under " +
      "Settings → Automation → Webhooks."
  ```

**Build → device-verify (Settings → Lighting → Smart home: no "roadmap"; both
features described as live + where to find them) → commit:**
`fix(settings): de-stale Smart-home placeholder — point at live Hue+Webhooks (#10)`

---

# TASK 3 — Audio group de-jargon (#11)

**File:** `SettingsScreen.kt`, Audio group "audio-effects" item (411-469).

### 3a. Multi-channel passthrough (452-454) — WORST jargon cluster
- OLD title (452): `"Multi-channel passthrough"`
- NEW title: `"Surround sound passthrough"`
- OLD desc (453):
  ```
  "When on, 5.1/7.1/Dolby/DTS audio bitstream is sent to a connected receiver / HDMI sink unchanged so it can decode itself. Off forces software downmix to stereo."
  ```
- NEW desc:
  ```
  "If you're plugged into a home-cinema receiver or soundbar (over HDMI or USB), turn this on to pass surround sound (Dolby / DTS) through untouched, so the receiver decodes it. Leave it off for headphones or your phone speaker — the player then mixes everything down to normal stereo."
  ```

### 3b. Reverb header label (419) — keep; plain already. No change.

### 3c. Volume boost (455) — keep (dB is understood + shown as "+N dB"). No change.

### 3d. Independent pitch (458) — keep. No change.

### 3e. Stereo flip (446-447), Mono mix (449-450), Reverse audio (461-467) —
already plain; VERIFY only, no edit.

**Build → device-verify (Settings → Audio → Audio effects → Surround sound
passthrough reads plainly) → commit:**
`fix(settings): plain-English surround passthrough (#11)`

---

# TASK 4 — Library & cloud group de-jargon (#11)

**File:** `SettingsScreen.kt` Library item (267-343) + sub-component files
`ReplayGainModeRow.kt`, `ReplayGainScanRow.kt`, `OfflineStorageLimitRow.kt`,
`EnrichmentSubToggles.kt`, `StorageFoldersRow.kt`.

### 4a. Deep Scan (274-280) — exposes raw class names MediaMetadataRetriever / MediaStore
- OLD desc:
  ```
  "Reads the full file header (via MediaMetadataRetriever) " +
          "when you tap 'Refresh metadata' on individual tracks — finds " +
          "tags like artist / album / artwork that MediaStore's faster " +
          "index missed. The main library scan always uses the fast " +
          "MediaStore path regardless of this setting, so toggling " +
          "here doesn't change scan time."
  ```
- NEW desc:
  ```
  "When you tap ‘Refresh metadata’ on a single track, read deeper inside " +
          "the file to recover details — artist, album, cover art — that the " +
          "quick library scan can miss. The normal library scan always uses " +
          "the fast method, so this setting doesn't slow scanning down."
  ```

### 4b. Online metadata enrichment (320-323) — "MusicBrainz / Discogs" + "network requests"
- OLD desc:
  ```
  "When a track has missing info (artist, album, year, genre, " +
      "cover art), look it up on MusicBrainz / Discogs and fill in the blanks. " +
      "Off by default to avoid network requests on poorly-tagged libraries."
  ```
- NEW desc:
  ```
  "When a track is missing details (artist, album, year, genre or cover " +
      "art), look them up in the free online music databases MusicBrainz " +
      "and Discogs and fill in the blanks. Off by default, so the player " +
      "doesn't use the internet unless you ask it to."
  ```

### 4c. Auto-scan ReplayGain (332-335) — "loudness"/"ReplayGain" jargon
- OLD desc:
  ```
  "Calculate loudness for every newly-discovered audio file so " +
      "tracks at different volumes play at consistent loudness. Off by default " +
      "(scan can be slow on first import)."
  ```
- NEW desc:
  ```
  "Measure the loudness of each new audio file as it's found, so tracks " +
      "recorded at different volumes all play at a similar level. Off by " +
      "default — the first measurement of a big library can take a while."
  ```

### 4d. ReplayGain normalisation (340-341) — keyword "ReplayGain" + "REPLAYGAIN tags"
- OLD title (340): `"ReplayGain normalisation"`
- NEW title: `"Even out track volumes"`
- OLD desc (341): `"Even out loudness across tracks using their REPLAYGAIN tags."`
- NEW desc:
  ```
  "Play every track at a similar volume so you're not reaching for the " +
      "volume between songs. Uses each file's own loudness measurement " +
      "(known as ReplayGain)."
  ```

### 4e. `ReplayGainModeRow.kt` (33-52) — "-14 LUFS" / "track-gain/album-gain"
- OLD (33-38):
  ```kotlin
  Text(
      "ReplayGain mode (locked target -14 LUFS):",
      color = TextSecondary,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
  )
  ```
- NEW:
  ```kotlin
  Text(
      "How to even out volume:",
      color = TextSecondary,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
  )
  ```
- Chip labels (46/50): keep "Track gain" / "Album gain" but ADD a one-line
  helper below the chip Row (insert after the closing `}` of the `Row` at 53,
  before the function close):
  ```kotlin
  Text(
      "Per track: every song gets the same loudness. Per album: keeps the " +
          "quiet-to-loud feel within an album, while still matching other albums.",
      color = TextSecondary,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
  )
  ```

### 4f. `ReplayGainScanRow.kt` (96-98) — "per-file gain" wording
- OLD (97-98):
  ```
  else "Walks every audio file once and saves a per-file gain. " +
      "Used at playback when ReplayGain normalisation is on."
  ```
- NEW:
  ```
  else "Measures every audio file once and remembers its loudness. " +
      "Used while playing when ‘Even out track volumes’ is on."
  ```

### 4g. `OfflineStorageLimitRow.kt` (56-57) — "evicted" jargon
- OLD (56-57):
  ```
  "When a Save Offline takes you over the limit, oldest " +
      "unstarred copies are evicted first."
  ```
- NEW:
  ```
  "When saving a new offline copy would go over this limit, the player " +
      "deletes your oldest saved copies first (protected ones are kept)."
  ```

### 4h. `EnrichmentSubToggles.kt` (90) — "embedded tags" wording
- OLD (90): `label = { Text("Files with no embedded tags") }`
- NEW: `label = { Text("Files with no details of their own") }`

### 4i. `StorageFoldersRow.kt` (98) — "DRM; Connect-only" jargon
- OLD (98):
  `"Spotify content can't be downloaded (DRM; Connect-only), so it has no storage location."`
- NEW:
  `"Spotify tracks can't be saved to your phone — Spotify only allows them to be streamed — so there's no folder to choose for them."`

**Build → device-verify (Settings → Library & cloud: Deep Scan, enrichment,
both ReplayGain rows, offline-limit, storage-folder note all read plainly) →
commit:** `fix(settings): plain-English Library & cloud (Deep Scan, ReplayGain, storage) (#11)`

---

# TASK 5 — Subtitles / OpenSubtitles de-jargon (#11)

**File:** `SettingsScreen.kt` Subtitles item (509-558) + `OpenSubtitlesSection.kt`.

### 5a. `OpenSubtitlesSection.kt` (87-92) — most dev-hostile flow
- OLD (88-90):
  ```
  "Free dev API key required (opensubtitles.com → Profile " +
      "→ Consumers). Plays a video without an SRT? We " +
      "look up + cache one for you.",
  ```
- NEW:
  ```
  "Get free subtitles automatically. Make a free account at " +
      "opensubtitles.com, then paste your personal key below (on their " +
      "site it's under Profile → Consumers → ‘New consumer’). When you " +
      "play a video that has no subtitle file, the player finds and saves " +
      "matching subtitles for you.",
  ```
- OLD sign-up button (143): `Text("Sign up at opensubtitles.com", color = TealAccent)`
- NEW: `Text("Create a free account", color = TealAccent)` (still opens the
  same sign-up URL — no behaviour change).

### 5b. Subtitle format options (517-536) — SRT/VTT/ASS internals + "colors"
- OLD SRT name (520): `name = "SRT — SubRip Text"`
- NEW: `name = "SRT — simple subtitles"`
- OLD SRT desc (521-522): keep — already plain. No change.
- OLD VTT name (526): `name = "VTT — Web Video Text Tracks"`
- NEW: `name = "VTT — web subtitles"`
- OLD VTT desc (527-528):
  ```
  "Web-standard subtitles. Supports basic styling like bold and colors. " +
          "Used by most streaming services including YouTube and Netflix."
  ```
- NEW (also fixes Task 0c spelling):
  ```
  "The kind used by most streaming sites such as YouTube and Netflix. " +
          "Can show simple styling like bold text and colours."
  ```
- OLD ASS name (532): `name = "ASS/SSA — Advanced SubStation Alpha"`
- NEW: `name = "ASS — fancy subtitles"`
- OLD ASS desc (533-535):
  ```
  "Advanced subtitles with full typographic control — custom fonts, " +
          "positioned text, karaoke effects, and animated styling. " +
          "Common in anime fansubs and professional subtitle work."
  ```
- NEW:
  ```
  "Subtitles that can use custom fonts, place text anywhere on screen, " +
          "and add effects such as sing-along (karaoke) highlighting. Often " +
          "seen on anime and professionally subtitled films."
  ```

### 5c. Section headers (514, 516) — keep; already plain. No change.

**Build → device-verify (Settings → Video & subtitles → Subtitles: account
help + the 3 format rows read plainly; "colours") → commit:**
`fix(settings): plain-English subtitles + OpenSubtitles setup (#11)`

---

# TASK 6 — Hue Lighting de-jargon (densest block) (#11)

**File:** `SettingsScreen.kt` HueSection (1929-2455) + `HueProvider.kt`
ScenePreset descriptions.

> Only the FLAGGED dense strings are touched; the audio-reactive explanatory
> copy (2167-2204, 2327-2341) already reads reasonably and is kept. The
> targets are the "white-only (dimmable)" block, the "buckets" wording, the
> sync-offset internals, and the scene Kelvin descriptions.

### 6a. "Drive white-only (dimmable) lights" block (2239-2258) — DENSEST in app
"2Hz/1Hz/Zigbee queues/5xx/429/IKEA" all leak.
- OLD title (2239): `"Drive white-only (dimmable) lights"`
- NEW title: `"Pulse plain white bulbs too"`
- OLD desc (2244-2255):
  ```
  "Pulse brightness of white-only bulbs (no colour) " +
      "in time with the music. For rooms and zones the " +
      "engine sends one group brightness command per " +
      "cycle. The rate is set automatically by the slowest " +
      "bulb in the group: all-Hue groups run at 2 Hz, any-" +
      "IKEA at 1 Hz (their Zigbee firmware queues commands " +
      "instead of aborting), other brands 1.5 Hz. Commands " +
      "are sent as instant snaps (no transition smoothing) " +
      "so they can't pile up. If the bridge ever returns " +
      "5xx/429 we pause for 2.5 s, and we stop pulsing the " +
      "moment the colour stream drops. Smart plugs are " +
      "never touched."
  ```
- NEW desc:
  ```
  "Make plain white bulbs (ones that can't change colour) brighten and " +
      "dim with the beat as well. The player flashes them at a steady rate " +
      "that it picks automatically to suit the bulbs in the room, and eases " +
      "off if the Hue bridge gets busy. Smart plugs are left alone."
  ```

### 6b. Area "buckets" helper (2057-2062) — "colour/white (warm/cool)/white-only" OK, but tighten
- OLD (2058-2062): keeps "RGB" implicitly via "full RGB".
  ```
  "Pick a room or zone (or an existing Entertainment area). " +
      "Only the lights inside your pick are addressed. The counts " +
      "below split each bulb into one bucket only: 'colour' = full " +
      "RGB; 'white (warm/cool)' = adjustable colour temperature; " +
      "'white-only' = brightness only; smart plugs are ignored."
  ```
- NEW:
  ```
  "Pick a room or zone (or an Entertainment area you've already set up " +
      "in the Hue app). Only the lights you pick are used. The counts below " +
      "sort each bulb into one group: ‘colour’ = can show any colour; " +
      "‘white (warm/cool)’ = white only, but you can make it warmer or " +
      "cooler; ‘white-only’ = brightness only. Smart plugs are ignored."
  ```

### 6c. Light/sound sync offset helper (2371-2382) — "A2DP"/"DAC" leak
- OLD (2372-2378):
  ```
  "Tune so light flashes line up with what you hear. " +
      "Phone speaker / wired ~150-250 ms; Bluetooth A2DP " +
      "~300-500 ms; USB-C DAC near 0 ms. The app auto-adds " +
      "your 'Audio delay' and 'BT video audio offset' values " +
      "to this. White-only bulbs subtract a brand-specific " +
      "delay on top automatically — use the 'Dimmable lag " +
      "offset' slider above to nudge only those if needed."
  ```
- NEW:
  ```
  "Adjust so the light flashes line up with what you hear. Roughly: " +
      "phone speaker or a cable, 150-250 ms; Bluetooth, 300-500 ms; a " +
      "USB-C headphone adapter, near 0 ms. The player also adds your " +
      "‘Audio delay’ and Bluetooth video offset automatically, so changing " +
      "those won't throw the lights off."
  ```

### 6d. Sensitivity helper (2327-2341) — keep mostly; "IKEA / Tradfri / GU10" hardware aside is fine to soften
- OLD (2334-2337 tail):
  ```
  "transitions), colours change ~twice per second. IKEA / " +
      "Tradfri / GU10 bulbs have a hardware response limit and " +
      "can lag the colour stream slightly at high values; native " +
      "Hue bulbs keep up cleanly."
  ```
- NEW tail:
  ```
  "transitions), colours change about twice a second. Some non-Hue " +
      "bulbs react a little slower at the highest settings; genuine Hue " +
      "colour bulbs keep up best."
  ```

### 6e. Dimmable lag offset helper (2289-2301) — "Entertainment-area (no group route)" internals
- OLD (2290-2297):
  ```
  "Fine-tune only if white bulbs still feel off-beat after " +
      "the automatic brand detection. Negative values fire " +
      "commands earlier (compensates for very slow bulbs), " +
      "positive values fire them later. Default 'Auto' = 0 ms; " +
      "this slider is added on top of each bulb's auto-detected " +
      "delay. For rooms and zones the offset is applied to the " +
      "group's average bulb delay; for Entertainment-area picks " +
      "(no group route) it's applied per bulb individually."
  ```
- NEW:
  ```
  "Only needed if plain white bulbs still feel slightly off the beat. " +
      "Slide left to flash them a touch earlier, right to flash a touch " +
      "later. ‘Auto’ (0 ms) suits most setups — this is a small nudge on " +
      "top of the timing the player already works out for you."
  ```

### 6f. `HueProvider.kt` ScenePreset descriptions (998-1001) — Kelvin "2700K/5000K"
Renders at SettingsScreen.kt:2442 via `preset.description`.
- OLD (998-1001):
  ```kotlin
  PARTY("Party", "Cycling saturated colours, full brightness"),
  AMBIENT("Ambient", "Warm dim, ~2700K, 30% brightness"),
  CINEMA("Cinema", "Dim deep-red, 15% brightness"),
  READING("Reading", "Cool white, ~5000K, 80% brightness")
  ```
- NEW:
  ```kotlin
  PARTY("Party", "Bright, ever-changing colours"),
  AMBIENT("Ambient", "Warm, dim glow"),
  CINEMA("Cinema", "Very dim deep red"),
  READING("Reading", "Bright, cool white")
  ```

### 6g. Pair instructions (1965-1975), tuning intro (2167-2204), Basic-controls
intro (2396-2403) — already plain; VERIFY, no edit.

**Build → device-verify (Settings → Lighting → Philips Hue, expand each
sub-section: white-only block, buckets, sync-offset, scenes all plain; no
Hz/A2DP/Kelvin/5xx) → commit:**
`fix(settings,hue): plain-English Hue lighting copy, drop Hz/A2DP/Kelvin (#11)`

---

# TASK 7 — Misc Settings group/helper de-jargon (#11)

**File:** `SettingsScreen.kt`. Smaller residual items flagged in the catalogue's
"recurring undefined terms" + wall-of-text list.

### 7a. Low-latency audio buffer (187-194) — "latency"/"buffer"/"CPU load"
- OLD desc (188-190):
  ```
  "Snappier pitch / speed / effect changes (~50-100 ms quicker " +
      "to take effect). May cause brief glitches under CPU load. Apply by " +
      "starting a track after toggling."
  ```
- NEW desc:
  ```
  "Makes pitch, speed and effect changes take hold a touch faster. On a " +
      "busy phone it can cause occasional brief glitches. Start a track " +
      "after switching this for it to apply."
  ```
- OLD title (187): `"Low-latency audio buffer"` → NEW: `"Faster effect response"`

### 7b. Crossfade master helper (257-262) — "per-curve and per-trigger sub-toggles"
- OLD (258):
  ```
  "Master crossfade duration. Per-curve and per-trigger sub-toggles live on the Player tab's Crossfade panel."
  ```
- NEW:
  ```
  "How long one track fades into the next. The finer settings (fade " +
      "shape and when it kicks in) are on the Player tab's Crossfade panel."
  ```

### 7c. External app control (687-690) — keep "Tasker/Macrodroid" (those ARE
the user-facing app names) but soften "Android intents"
- OLD desc (688-690):
  ```
  "Let other apps trigger play, pause, skip, and seek via Android " +
      "intents. Useful for automation workflows. Off by default — turn on only " +
      "if you trust the apps you're going to wire it up to."
  ```
- NEW desc:
  ```
  "Let automation apps like Tasker or MacroDroid control playback — " +
      "play, pause, skip and jump. Off by default; only turn it on for apps " +
      "you trust."
  ```

### 7d. Diagnostic logging description (1622-1626) — already plain; VERIFY, no edit.

### 7e. Bluetooth A/V offset intro (1741-1744) and Cast offset intro (1780-1783)
— already reasonably plain ("Bluetooth adds AUDIO latency" → soften "latency").
- OLD (1741-1744):
  ```
  "If watching video over Bluetooth speakers / headphones, " +
      "lip-sync may drift because Bluetooth adds AUDIO latency. The " +
      "fix is to delay the video to match — slide right. Range 0–1 " +
      "second (the picture can be held back but not advanced)."
  ```
- NEW:
  ```
  "Watching video through Bluetooth speakers or headphones can make the " +
      "sound arrive slightly after the picture (Bluetooth adds a short " +
      "delay). Slide right to hold the picture back so it matches the " +
      "sound. Range 0-1 second."
  ```

**Build → device-verify (Settings → Playback ‘Faster effect response’,
Crossfade helper, Automation ‘External app control’, Connectivity BT A/V
offset all plain) → commit:**
`fix(settings): plain-English residual jargon (latency/intents/crossfade) (#11)`

---

# TASK 8 — InfoContent.kt: remove RAW CODE / CLASS NAMES (#11 most severe)

**File:** `ui/info/InfoContent.kt`. These leak internal symbols into user help.

### 8a. `WRITE_SETTINGS` (line 23) — Player → Display & sliders, Volume+Brightness
- OLD:
  ```
  "Volume + Brightness sliders — Volume is on top. Brightness (below) sets the SYSTEM screen brightness, which needs the one-time \"Modify system settings\" permission (Android WRITE_SETTINGS). Until you grant it the brightness slider is greyed out and can't be moved — tap \"Tap to grant brightness permission\" beneath it to enable. Brightness commits when you release the slider."
  ```
- NEW:
  ```
  "Volume + Brightness sliders — Volume is on top. Brightness (below) changes your phone's screen brightness, which needs a one-time ‘Modify system settings’ permission. Until you grant it, the brightness slider is greyed out — tap ‘Tap to grant brightness permission’ beneath it to switch it on. Brightness is applied when you let go of the slider."
  ```

### 8b. `EnvironmentalReverb` (line 52) — Player → Effects, Reverb on Bluetooth
- OLD:
  ```
  "Reverb on Bluetooth — EnvironmentalReverb attaches to our local audio chain. A2DP-routed audio is re-encoded by the BT codec downstream, so reverb can be inaudible over BT. Use wired output if you can't hear it."
  ```
- NEW:
  ```
  "Reverb over Bluetooth — Reverb is added on your phone before the sound is sent out. Bluetooth re-processes the audio on its way to the speaker, which can wash the reverb out, so you may not hear it over Bluetooth. Use a wired connection if it's not coming through."
  ```

### 8c. `SpotifyProvider.skipNext/skipPrevious/seekTo/pause/resume` (line 62) — Player → Output
- OLD:
  ```
  "Spotify BT-remote on car HU — car steering-wheel prev/next/skip/play/pause route to SpotifyProvider.skipNext/skipPrevious/seekTo/pause/resume when the Connect mirror is active, so the car remote actually moves the Spotify song instead of silently re-driving a muted local Player."
  ```
- NEW:
  ```
  "Spotify controls from your car — When Spotify is playing through this app, your car's steering-wheel and dashboard buttons (previous, next, skip, play, pause) control the actual Spotify track, instead of quietly doing nothing."
  ```

### 8d. `ExoPlayer/AudioProcessor/Visualizer` (line 74) — Player → Webhooks + Hue, "How the audio gets there"
- OLD:
  ```
  "How the audio gets there (no mic permission) — We tap the PCM stream inside ExoPlayer's own AudioProcessor chain, not Android's Visualizer. So no microphone permission, lower latency, and audio data the user owns stays in-app."
  ```
- NEW:
  ```
  "How the lights ‘hear’ the music (no microphone needed) — The player reads the music directly from inside its own audio engine, rather than listening through the phone's mic. So it needs no microphone permission, reacts quickly, and your audio never leaves the app."
  ```

**Build → device-verify (Player tab → ‘i’ icon → Display & sliders, Effects,
Output, Webhooks+Hue groups: no raw symbol/class names) → commit:**
`fix(info): strip raw code/class names from Player help (#11)`

---

# TASK 9 — InfoContent.kt: remaining jargon in Info bullets (#11)

**File:** `ui/info/InfoContent.kt`. The remaining flagged jargon in the info
sheets (webhook payload, Hue Kelvin/Entertainment, LUFS-adjacent, LRU/eviction,
A2DP). Most info copy is acceptable; only the genuinely dense bullets change.

### 9a. Webhooks info bullet (line 71) — "JSON POST/8-char SHA-256 hash/ms"
- OLD:
  ```
  "Webhooks (Settings → Webhooks) — Single URL + six per-event toggles (play / pause / resume / skip-next / skip-prev / track-end). On each event we fire a JSON POST: event name, timestamp ms, 8-char SHA-256 track hash, position ms, duration ms. Privacy: no titles or paths leave the device. 'Test' button sends a synthetic payload so you can verify your endpoint accepts our shape. Works with IFTTT, n8n, Home Assistant, Pushover, Google Apps Script, etc."
  ```
- NEW:
  ```
  "Webhooks (Settings → Webhooks) — Paste in a web address from a home-automation app, then choose which events to send: when a track starts, when you pause, resume, skip to the next or previous track, or when a track finishes. Each event carries which event it was, the time, a short anonymous code for the track, how far through you are, and the track's length — never the song's name or location. ‘Send a test’ lets you check the address works. Works with apps like Home Assistant, IFTTT and Tasker."
  ```

### 9b. Hue audio-reactive info bullet (line 73) — "BPM"/"Entertainment area" — soften, keep "Entertainment area" (it's the literal Hue-app term the user must find)
- OLD:
  ```
  "Audio-reactive lighting (Hue, primary feature) — Lights pulse with the music: bass kicks flash bright, treble runs cool blues, mids track melody. The BPM is tracked from audio onsets and drives the colour-cycle rate so transitions feel musical, not metronomic. Single 'Intensity' slider (0-100); 0 turns the reactive engine off. Needs an Entertainment area in your Hue mobile app first (Settings → Entertainment areas → drag your lights in)."
  ```
- NEW:
  ```
  "Music-reactive lighting (Hue, the main feature) — Your lights pulse with the music: heavy beats flash bright, high notes run cool blue, and the colours shift in time with the tempo so it feels musical. One ‘Intensity’ slider (0-100); 0 switches it off. Set up an ‘Entertainment area’ in the Philips Hue app first (Settings → Entertainment areas → add your lights)."
  ```

### 9c. Hue sync-offset info bullet (line 75) — "PCM tap/AudioTrack output buffer/A2DP/DAC"
- OLD:
  ```
  "Light/sound sync offset — Lights would otherwise lead the sound because the PCM tap runs BEFORE the AudioTrack output buffer. The slider compensates: phone speaker / wired ~150-250 ms; Bluetooth A2DP ~300-500 ms; USB-C DAC near 0 ms. The app auto-adds your Audio delay + BT video audio offset sliders to this number, so changing those doesn't break Hue alignment."
  ```
- NEW:
  ```
  "Light/sound sync — The lights can run slightly ahead of the sound, because the player reads the music just before it reaches the speaker. The slider lines them back up: phone speaker or cable, about 150-250 ms; Bluetooth, about 300-500 ms; a USB-C headphone adapter, near 0 ms. The player also folds in your Audio delay and Bluetooth video offset automatically, so those won't knock the lights out of time."
  ```

### 9d. Speed/Pitch info bullet (line 50) — "~250 ms inherent buffer latency/BT A2DP codec"
- OLD:
  ```
  "Speed / Pitch — Independent. Sonic time-stretch processor adds ~250 ms of inherent buffer latency between dragging the slider and hearing the change; BT A2DP codec adds another ~100-300 ms. Turn on Settings → Low-latency audio buffer for snappier response (some dropout risk under CPU load)."
  ```
- NEW:
  ```
  "Speed / Pitch — Adjusted independently. There's a short delay (about a quarter-second) between moving the slider and hearing the change, and Bluetooth adds a little more. Turn on Settings → ‘Faster effect response’ for a quicker reaction (with a small risk of brief glitches on a busy phone)."
  ```

### 9e. Cast info bullet (line 60) — "embedded LAN HTTP relay" jargon
- OLD fragment:
  ```
  "The app runs an embedded LAN HTTP relay so the receiver can fetch your phone-local content (works for MP4, M4A, M4B audiobooks, FLAC, MP3, MKV, etc — receiver compatibility decides what plays)."
  ```
- NEW fragment (replace just that sentence within the bullet):
  ```
  "The app quietly shares your phone's files over your home Wi-Fi so the TV or speaker can play them (MP4, M4A, M4B audiobooks, FLAC, MP3, MKV and more — what actually plays depends on the device receiving it)."
  ```

### 9f. Offline copy info bullet (line 211) — "LRU eviction"
- OLD fragment: `"Settings → Cloud → Offline storage limit caps total size; LRU eviction clears the oldest copy first."`
- NEW fragment:
  `"Settings → Cloud → Offline storage limit caps the total size; when it's reached, the player removes your oldest saved copies first."`

### 9g. Output Bluetooth info bullet (line 59) — "AVRCP key-event cars/MediaController-style/two intercept paths"
- OLD fragment:
  ```
  "Remap a car's prev/next buttons under Settings → Bluetooth Car Controls — applies to both AVRCP key-event cars (older BMWs etc.) and MediaController-style ones (Android Auto), via two intercept paths."
  ```
- NEW fragment:
  ```
  "You can change what your car's previous/next buttons do under Settings → Bluetooth car controls — this works with both older cars and newer Android Auto systems."
  ```

> Bullets NOT listed here (Library/Last Played/Cloud/Equalizer that are already
> plain) are left as-is; VERIFY they contain no raw symbol after Task 8.

**Build → device-verify (Player ‘i’ sheet Webhooks+Hue + Effects bullets;
Cloud ‘i’ sheet Cast + Offline bullets; all plain) → commit:**
`fix(info): plain-English Webhooks/Hue/Cast/offline info bullets (#11)`

---

# TASK 10 — Final sweep + gate

1. **Grep gate** — confirm the catalogue's flagged tokens are gone from
   user-visible string literals (NOT keyword lists / code identifiers):
   - `Grep` for each of: `Version 1.0.0`, `colors`, `on the roadmap`,
     `HTTP POST`, `endpoint`, `JSON body`, `8-char`, `Fire on`, `BT remote`,
     `passthrough` (title only), `LUFS`, `MediaMetadataRetriever` (in a Text),
     `MediaStore` (in a Text), `WRITE_SETTINGS`, `EnvironmentalReverb`,
     `SpotifyProvider.skip`, `ExoPlayer's own AudioProcessor`, `Visualizer`,
     `2700K`, `5000K`, `A2DP` (info), `LRU eviction`, `AVRCP`.
   - Expected: zero hits in `SettingsScreen.kt`, the 8 sub-components,
     `InfoContent.kt`, `HueProvider.kt` ScenePreset descriptions. Hits that
     remain ONLY in `keywords` lists (e.g. "passthrough", "lufs") or code
     identifiers are acceptable (not user-visible) — note each explicitly.
2. **Build gate**: `.\gradlew.bat :app:assembleDebug -q` exit 0.
3. **Unit-test gate**: `.\gradlew.bat :app:testDebugUnitTest -q` — confirm
   `SettingsSearchTest` still green (search keywords untouched; if Decision 3b
   was taken, the inventory count assertion was updated in Task 2).
4. **Device sweep**: walk all 8 Settings groups (expand each) + all 5 Info
   sheets (Player/Library/Last Played/Cloud/Equalizer) on the phone; read for
   any residual jargon or clipped/overflowing text at the default font scale.
5. Commit any final touch-ups: `chore(text): final layman-copy sweep (#10/#11)`.

---

## Coverage checklist (every catalogue-flagged string mapped to a task)

| Catalogue item (#10/#11) | Task |
|--------------------------|------|
| Webhooks header/intro/field/test-save/toggles/payload (1844-1926) | 1 |
| SmartHomePlaceholder "on the roadmap" (1799-1829) | 2 |
| About "Version 1.0.0" (762) → BuildConfig.VERSION_NAME | 0 |
| "colors" (527) → "colours" | 0 (+5) |
| Multi-channel passthrough (452-454) | 3 |
| ReplayGain "-14 LUFS"/track-album (ReplayGainModeRow 33-52) | 4 |
| ReplayGain normalisation + auto-scan (340-341, 332-335) | 4 |
| Deep Scan MediaMetadataRetriever/MediaStore (275-280) | 4 |
| Enrichment MusicBrainz/Discogs (320-323) + sub-toggle "embedded tags" | 4 |
| Offline "evicted" (OfflineStorageLimitRow 56-57) | 4 |
| Storage "DRM; Connect-only" (StorageFoldersRow 98) | 4 |
| OpenSubtitles dev-API-key flow (87-90) | 5 |
| Subtitle formats SRT/VTT/ASS internals (517-536) | 5 |
| Hue white-only/dimmable block 2Hz/Zigbee/5xx/429/IKEA (2239-2258) | 6 |
| Hue buckets "RGB/colour temperature" (2057-2062) | 6 |
| Hue sync-offset A2DP/DAC (2371-2382) | 6 |
| Hue sensitivity IKEA/Tradfri/GU10 (2334-2337) | 6 |
| Hue dimmable-lag Entertainment-area internals (2290-2297) | 6 |
| Hue scene Kelvin 2700K/5000K (HueProvider 998-1001) | 6 |
| Low-latency buffer / crossfade / external-control / BT-offset intros | 7 |
| InfoContent WRITE_SETTINGS (23) | 8 |
| InfoContent EnvironmentalReverb (52) | 8 |
| InfoContent SpotifyProvider.skip… (62) | 8 |
| InfoContent ExoPlayer/AudioProcessor/Visualizer (74) | 8 |
| InfoContent Webhooks JSON/hash (71) | 9 |
| InfoContent Hue BPM/Entertainment (73), sync PCM/AudioTrack/A2DP/DAC (75) | 9 |
| InfoContent Speed/Pitch latency/A2DP (50) | 9 |
| InfoContent Cast LAN HTTP relay (60), Offline LRU (211), BT AVRCP (59) | 9 |

## Notes / non-goals

- No DataStore keys, no token strings, no `keywords` lists change — search is
  untouched, so all current searches keep working.
- No layout/structure/hitbox changes (those are items #1/#14/#15, separate).
- British spelling throughout the NEW strings (colour, normalise, etc.).
- TASKS.md: add rows T347 (#10) + T348 (#11) at PLAN phase on execution start;
  flip to M then V with evidence per the protocol.
