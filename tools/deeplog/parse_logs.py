#!/usr/bin/env python3
"""parse_logs.py — digest/query tool for DeepLogger NDJSON session files.

Loads a session file, builds an index, and answers queries WITHOUT requiring the
caller to read the raw file. Designed so an agent can pull only relevant slices
into context.

Examples:
  parse_logs.py FILE --summary
  parse_logs.py FILE --since 2026-05-02T10:00:00Z --until 2026-05-02T10:00:05Z
  parse_logs.py FILE --trace checkout-42
  parse_logs.py FILE --category network.response --category network.error
  parse_logs.py FILE --errors
  parse_logs.py FILE --transitions
  parse_logs.py FILE --slow-frames 32 --slow-net 1000 --slow-queries 10
  parse_logs.py FILE --around-errors 25
  parse_logs.py FILE --digest --budget 1500
  add --json to any query for machine-readable output.

Reads the file streaming (one line at a time); never holds more than the matched
slice in memory beyond a lightweight index.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone


def parse_iso_or_epoch(s):
    """Accept ISO-8601 (…Z) or epoch milliseconds; return epoch ms (int)."""
    if s is None:
        return None
    s = str(s).strip()
    if s.isdigit():
        return int(s)
    iso = s.replace("Z", "+00:00")
    dt = datetime.fromisoformat(iso)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def iter_entries(path):
    """Yield (lineno, obj) for each valid JSON line; skip malformed lines."""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh):
            line = line.strip()
            if not line:
                continue
            try:
                yield i, json.loads(line)
            except json.JSONDecodeError:
                continue


def load_all(path):
    return [obj for _, obj in iter_entries(path)]


def find_header(entries):
    for e in entries:
        if e.get("category") == "session" and isinstance(e.get("payload"), dict) \
                and e["payload"].get("kind") == "session-header":
            return e["payload"]
    return None


def detect_dropped(entries):
    """Dropped entries = (max seq - min seq + 1) - count, plus explicit gaps."""
    seqs = [e.get("seq") for e in entries if isinstance(e.get("seq"), int)]
    if not seqs:
        return {"reported": None, "gaps": 0, "min_seq": None, "max_seq": None}
    seqs_sorted = sorted(seqs)
    gaps = 0
    for a, b in zip(seqs_sorted, seqs_sorted[1:]):
        if b - a > 1:
            gaps += (b - a - 1)
    return {
        "min_seq": seqs_sorted[0],
        "max_seq": seqs_sorted[-1],
        "count": len(seqs),
        "gaps": gaps,
    }


def cmd_summary(entries, args):
    header = find_header(entries)
    cats = {}
    for e in entries:
        cats[e.get("category", "?")] = cats.get(e.get("category", "?"), 0) + 1
    dropped = detect_dropped(entries)
    t_first = entries[0].get("wall") if entries else None
    t_last = entries[-1].get("wall") if entries else None
    out = {
        "file_entries": len(entries),
        "wall_first": t_first,
        "wall_last": t_last,
        "duration_ms": (t_last - t_first) if (t_first and t_last) else None,
        "dropped_seq_gaps": dropped["gaps"],
        "seq_range": [dropped.get("min_seq"), dropped.get("max_seq")],
        "categories": dict(sorted(cats.items(), key=lambda kv: -kv[1])),
        "session": header,
    }
    if args.json:
        print(json.dumps(out, indent=2))
        return
    print("== DeepLogger session summary ==")
    if header:
        print(f"device   : {header.get('model')} (API {header.get('api')}, {header.get('release')})")
        print(f"screen   : {header.get('widthPx')}x{header.get('heightPx')} @ {header.get('densityDpi')}dpi")
        print(f"locale   : {header.get('locale')}   pkg: {header.get('pkg')}")
    print(f"entries  : {out['file_entries']}   duration: {out['duration_ms']} ms")
    print(f"seq range: {out['seq_range']}   dropped(seq gaps): {out['dropped_seq_gaps']}")
    print("categories:")
    for c, n in out["categories"].items():
        print(f"  {n:>7}  {c}")


def _in_window(e, lo, hi):
    w = e.get("wall")
    if w is None:
        return False
    if lo is not None and w < lo:
        return False
    if hi is not None and w > hi:
        return False
    return True


def cmd_filter(entries, args):
    lo = parse_iso_or_epoch(args.since)
    hi = parse_iso_or_epoch(args.until)
    cats = set(args.category or [])
    out = []
    for e in entries:
        if (lo is not None or hi is not None) and not _in_window(e, lo, hi):
            continue
        if cats and e.get("category") not in cats:
            continue
        if args.trace and e.get("trace") != args.trace:
            continue
        out.append(e)
    emit_lines(out, args)


def cmd_errors(entries, args):
    errs = [e for e in entries if e.get("category") == "exception"]
    if args.json:
        print(json.dumps(errs, indent=2))
        return
    if not errs:
        print("No exceptions in this session.")
        return
    for e in errs:
        p = e.get("payload", {})
        print(f"[{e.get('iso')}] {'FATAL ' if p.get('fatal') else ''}{p.get('type')}: {p.get('message')}")
        for c in p.get("causes", []):
            print(f"    caused by {c.get('type')}: {c.get('message')}")
        if args.full:
            print(p.get("stack", ""))
        print(f"    seq={e.get('seq')} trace={e.get('trace')} thread={e.get('thread')}")


def cmd_around_errors(entries, args):
    n = args.around_errors
    idx = [i for i, e in enumerate(entries) if e.get("category") == "exception"]
    if not idx:
        print("No exceptions in this session." if not args.json else "[]")
        return
    seen = set()
    slices = []
    for i in idx:
        lo = max(0, i - n)
        hi = min(len(entries), i + n + 1)
        for j in range(lo, hi):
            if j not in seen:
                seen.add(j)
                slices.append(entries[j])
    slices.sort(key=lambda e: e.get("seq", 0))
    emit_lines(slices, args)


def cmd_transitions(entries, args):
    trs = [e for e in entries if e.get("category") in ("state.transition", "state.programmatic")]
    if args.json:
        print(json.dumps(trs, indent=2))
        return
    if not trs:
        print("No state transitions recorded.")
        return
    for e in trs:
        p = e.get("payload", {})
        if e["category"] == "state.transition":
            print(f"[{e.get('iso')}] {p.get('machine')}: {p.get('from')} -> {p.get('to')} "
                  f"on '{p.get('trigger')}' guard={p.get('guard')}")
        else:
            print(f"[{e.get('iso')}] {p.get('name')}: {p.get('from')} -> {p.get('to')} "
                  f"trigger={p.get('trigger')} (programmatic)")


def cmd_slow(entries, args):
    out = []
    if args.slow_frames is not None:
        thr_ns = args.slow_frames * 1_000_000
        for e in entries:
            if e.get("category") == "render.frame" and (e.get("payload", {}).get("deltaNs", 0) >= thr_ns):
                out.append(e)
    if args.slow_net is not None:
        thr_ns = args.slow_net * 1_000_000
        for e in entries:
            if e.get("category") == "network.response" and (e.get("payload", {}).get("latencyNs", 0) >= thr_ns):
                out.append(e)
    if args.slow_queries is not None:
        thr_ns = args.slow_queries * 1_000_000
        for e in entries:
            if e.get("category") == "db.query":
                d = e.get("payload", {}).get("durationNs")
                if d is not None and d >= thr_ns:
                    out.append(e)
    out.sort(key=lambda e: e.get("seq", 0))
    emit_lines(out, args)


def cmd_digest(entries, args):
    """Compact, token-bounded overview safe to read into context."""
    header = find_header(entries)
    cats = {}
    for e in entries:
        cats[e.get("category", "?")] = cats.get(e.get("category", "?"), 0) + 1
    dropped = detect_dropped(entries)
    errs = [e for e in entries if e.get("category") == "exception"]
    trs = [e for e in entries if e.get("category") == "state.transition"]
    slow_net = sorted(
        [e for e in entries if e.get("category") == "network.response"],
        key=lambda e: e.get("payload", {}).get("latencyNs", 0), reverse=True
    )[:5]

    lines = []
    if header:
        lines.append(f"device={header.get('model')} api={header.get('api')} pkg={header.get('pkg')}")
    t0 = entries[0].get("wall") if entries else None
    t1 = entries[-1].get("wall") if entries else None
    lines.append(f"entries={len(entries)} dur_ms={(t1-t0) if (t0 and t1) else '?'} "
                 f"dropped_gaps={dropped['gaps']}")
    top = sorted(cats.items(), key=lambda kv: -kv[1])[:12]
    lines.append("top_categories: " + ", ".join(f"{c}={n}" for c, n in top))
    if errs:
        lines.append(f"exceptions={len(errs)}:")
        for e in errs[:5]:
            p = e.get("payload", {})
            lines.append(f"  - {p.get('type')}: {str(p.get('message'))[:120]} (trace={e.get('trace')})")
    if trs:
        lines.append(f"transitions={len(trs)} (first 5):")
        for e in trs[:5]:
            p = e.get("payload", {})
            lines.append(f"  - {p.get('machine')}: {p.get('from')}->{p.get('to')} on {p.get('trigger')}")
    if slow_net:
        lines.append("slowest_network:")
        for e in slow_net:
            p = e.get("payload", {})
            ms = (p.get("latencyNs", 0)) / 1e6
            lines.append(f"  - {ms:.0f}ms {p.get('status')} {str(p.get('url'))[:80]}")

    text = "\n".join(lines)
    # Token budget ~= 4 chars/token heuristic.
    max_chars = args.budget * 4
    if len(text) > max_chars:
        text = text[:max_chars] + "\n…(truncated to budget)"
    if args.json:
        print(json.dumps({"digest": text, "approx_tokens": len(text) // 4}, indent=2))
    else:
        print(text)


def emit_lines(entries, args):
    limit = args.limit
    if limit and limit > 0:
        entries = entries[:limit]
    if args.json:
        print(json.dumps(entries, indent=2))
    else:
        for e in entries:
            cat = e.get("category", "?")
            print(f"[{e.get('iso')}] seq={e.get('seq')} {cat} trace={e.get('trace')} "
                  f":: {json.dumps(e.get('payload'), ensure_ascii=False)[:400]}")
    if not args.json:
        print(f"-- {len(entries)} entr{'y' if len(entries)==1 else 'ies'} --", file=sys.stderr)


def build_parser():
    p = argparse.ArgumentParser(description="Query DeepLogger NDJSON session files.")
    p.add_argument("file", help="session-*.ndjson path")
    p.add_argument("--json", action="store_true", help="machine-readable JSON output")
    p.add_argument("--limit", type=int, default=0, help="cap emitted lines (0 = no cap)")

    p.add_argument("--summary", action="store_true", help="metadata + per-category counts + dropped")
    p.add_argument("--since", help="window start (ISO-8601 Z or epoch ms)")
    p.add_argument("--until", help="window end (ISO-8601 Z or epoch ms)")
    p.add_argument("--trace", help="filter by trace id")
    p.add_argument("--category", action="append", help="filter by category (repeatable)")
    p.add_argument("--errors", action="store_true", help="exceptions with cause chains")
    p.add_argument("--full", action="store_true", help="with --errors, print full stack traces")
    p.add_argument("--around-errors", type=int, metavar="N", help="N entries either side of each exception")
    p.add_argument("--transitions", action="store_true", help="state-machine + programmatic transitions")
    p.add_argument("--slow-frames", type=float, metavar="MS", help="frames with delta >= MS")
    p.add_argument("--slow-net", type=float, metavar="MS", help="network responses with latency >= MS")
    p.add_argument("--slow-queries", type=float, metavar="MS", help="db queries with duration >= MS")
    p.add_argument("--digest", action="store_true", help="compact, token-bounded overview")
    p.add_argument("--budget", type=int, default=1500, help="digest token budget (default 1500)")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    entries = load_all(args.file)
    if not entries:
        print("No valid entries found (empty or malformed file).", file=sys.stderr)
        return 1

    if args.summary:
        cmd_summary(entries, args)
    elif args.digest:
        cmd_digest(entries, args)
    elif args.errors:
        cmd_errors(entries, args)
    elif args.around_errors is not None:
        cmd_around_errors(entries, args)
    elif args.transitions:
        cmd_transitions(entries, args)
    elif args.slow_frames is not None or args.slow_net is not None or args.slow_queries is not None:
        cmd_slow(entries, args)
    elif args.trace or args.category or args.since or args.until:
        cmd_filter(entries, args)
    else:
        # default: summary
        cmd_summary(entries, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
