# Power Media Player — project notes

## Fresh-context primer — read FIRST, every session

- **`PROJECT_RULES.md` (repo root) is the canonical primer.** Read it in full at
  session start: app purpose + feature map, current build coordinates, architecture
  spine, the delicate do-NOT-break contingencies, and the binding build/app
  preferences. It is auto-loaded as binding rules via the user's global `CLAUDE.md`.
- This file (`CLAUDE.md`) holds only the task-ledger mandate + a pointer to the
  logging runbooks. Historical detail lives under `archive/` (index: `archive/INDEX.md`).

## MANDATORY task ledger — read FIRST, every turn

- **`TASKS.md` (repo root) is the binding task ledger.** Read it at the start
  of EVERY turn and immediately after any context compaction. Its PROTOCOL
  block governs all work: evidence-gated check-offs, legal statuses only,
  phase lock (investigate → plan → implement), no skip/defer ever.
- End-of-turn reports must reflect that file's live statuses (the table, not
  prose claims). New in-scope work gets added there the same turn it appears.

## Logging runbooks

- DeepLogger (NDJSON forensics) + DiagLog (targeted app-logic timing) usage and
  correlation live in the project skill `deeplog-runbook`
  (`.claude/skills/deeplog-runbook/SKILL.md`) — invoke it before pulling or
  analysing any on-device logs.
