# Power Media Player — project notes

## Deep logger runbook (android-deep-logger skill)

- A debug-only forensic logger writes NDJSON to
  `getExternalFilesDir(null)/deeplog/session-<timestamp>.ndjson` — a FRESH file
  per launch. Release builds link a no-op (src/release) and write nothing.
- It auto-installs at process start via App Startup (src/debug manifest); no
  edit to `PowerMediaPlayerApp`. Captures (zero feature edits): activity/
  fragment lifecycle, touch input (x/y/target), frame jank, memory/GC (5 s
  sampler), uncaught exceptions w/ full cause chain, connectivity, session
  header (device/build/vc).
- After exercising the app: `bash tools/deeplog/pull_logs.sh` pulls the newest
  session into `deeplogs/`. Flags: `--all`, `--clear`, `--package`.
- Analyse the pulled NDJSON ON DISK with `python tools/deeplog/parse_logs.py`
  (or `jq`). NEVER read a whole raw session file into context. Query by
  `--summary`, `--trace`, `--since/--until`, `--category`, `--errors`,
  `--around-errors N`, `--slow-frames MS`, `--digest --budget N`.
- Newest session file = current run; do not assume continuity with older files.

## Two parallel logging systems (correlate by wall-clock timestamp)

1. **DeepLogger** (this skill) → `deeplog/session-*.ndjson` — automatic system
   forensics: frame jank, memory, lifecycle, touch, crashes.
2. **DiagLog** → `diag/log-current.txt` — targeted app-logic timing already
   hand-instrumented (resume-path phases, Hue collector/disconnect, BT, route,
   player state). Enable via Settings → Diagnostic logging.

For the resume-hang investigation: DeepLogger's `render.frame` + `input.touch`
shows whether the UI is starved and how many taps fired; DiagLog's `RESUME` /
`PERF` lines show which parse phase ate the time. Line them up by timestamp.
