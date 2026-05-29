#!/usr/bin/env bash
# pull_logs.sh — adb pull the newest DeepLogger session file (or all) into
# ./deeplogs/, with an optional device-side clear.
#
# Usage:
#   bash pull_logs.sh [--package PKG] [--serial SERIAL] [--all] [--clear] [--dest DIR]
#
# Defaults:
#   - PKG is auto-detected from the foreground app if not given.
#   - DEST is ./deeplogs
#
# The logger writes to getExternalFilesDir(null) ->
#   /sdcard/Android/data/<pkg>/files/deeplog/session-*.ndjson

set -euo pipefail

# Git Bash / MSYS on Windows rewrites POSIX-looking args (e.g. /sdcard/...) into
# Windows paths before adb sees them. Disable that so remote device paths pass
# through verbatim. No-op on Linux/macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

PKG=""
SERIAL=""
DEST="deeplogs"
PULL_ALL=0
CLEAR=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PKG="$2"; shift 2 ;;
    --serial)  SERIAL="$2"; shift 2 ;;
    --dest)    DEST="$2"; shift 2 ;;
    --all)     PULL_ALL=1; shift ;;
    --clear)   CLEAR=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

ADB="adb"
if [[ -n "$SERIAL" ]]; then ADB="adb -s $SERIAL"; fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH. Install platform-tools or pass its dir." >&2
  exit 1
fi

# Detect package from the foreground/resumed activity if not supplied.
if [[ -z "$PKG" ]]; then
  PKG="$($ADB shell dumpsys activity activities 2>/dev/null \
    | grep -m1 -Eo 'mResumedActivity.* [a-zA-Z0-9_.]+/' \
    | grep -Eo '[a-zA-Z0-9_.]+/' | tr -d '/' || true)"
fi
if [[ -z "$PKG" ]]; then
  echo "Could not auto-detect package. Pass --package <id>." >&2
  exit 1
fi

REMOTE_DIR="/sdcard/Android/data/$PKG/files/deeplog"
echo "Package : $PKG"
echo "Remote  : $REMOTE_DIR"
mkdir -p "$DEST"

# List remote session files, newest last (sorted by name = timestamp).
mapfile -t FILES < <($ADB shell "ls -1 $REMOTE_DIR/session-*.ndjson 2>/dev/null" | tr -d '\r' | sort)
if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "No session files found in $REMOTE_DIR" >&2
  echo "(Is this a DEBUG build? Has the app launched at least once?)" >&2
  exit 1
fi

if [[ "$PULL_ALL" -eq 1 ]]; then
  for f in "${FILES[@]}"; do
    echo "Pulling $f"
    $ADB pull "$f" "$DEST/" >/dev/null
  done
  echo "Pulled ${#FILES[@]} file(s) into $DEST/"
else
  NEWEST="${FILES[-1]}"
  echo "Newest  : $NEWEST"
  $ADB pull "$NEWEST" "$DEST/" >/dev/null
  echo "Pulled $(basename "$NEWEST") into $DEST/"
fi

if [[ "$CLEAR" -eq 1 ]]; then
  echo "Clearing device directory $REMOTE_DIR ..."
  # Prefer run-as (works on debuggable apps without root).
  $ADB shell "run-as $PKG sh -c 'rm -f files/deeplog/session-*.ndjson'" 2>/dev/null \
    || $ADB shell "rm -f $REMOTE_DIR/session-*.ndjson" 2>/dev/null \
    || echo "Could not clear (permission). Clear manually if needed." >&2
  echo "Cleared."
fi
