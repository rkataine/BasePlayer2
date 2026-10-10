#!/usr/bin/env python3
"""Filter a BED(.gz) file, keeping only feature names (column 4) that appear
at least N times anywhere in the file. All names are treated the same — no
family prefix filter (e.g. L1).

Writes the filtered BED to stdout. Progress goes to stderr.

Example:

  python3 scripts/filter_bed_min_name_count.py \\
      Repeat_masker_GRCh38_parsed.bed.gz \\
      > Repeat_masker_GRCh38_min1000.bed

  # or gzip the stream:
  python3 scripts/filter_bed_min_name_count.py \\
      Repeat_masker_GRCh38_parsed.bed.gz \\
      | gzip -c > Repeat_masker_GRCh38_min1000.bed.gz

Two passes: (1) count every column-4 name, (2) write kept rows to stdout.
Track/browser/# header lines are always kept.
"""

from __future__ import annotations

import argparse
import gzip
import sys
from collections import Counter
from pathlib import Path


def open_text(path: Path, mode: str = "rt"):
    if str(path).endswith(".gz"):
        return gzip.open(path, mode, encoding="utf-8", errors="replace")
    return open(path, mode, encoding="utf-8", errors="replace")


def is_header(line: str) -> bool:
    if not line or line[0] == "#":
        return True
    return line.startswith("track") or line.startswith("browser")


def count_names(path: Path) -> Counter[str]:
    counts: Counter[str] = Counter()
    data_rows = 0
    with open_text(path) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if is_header(line):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            data_rows += 1
            name = parts[3].strip() if len(parts) > 3 else ""
            counts[name] += 1
    print(f"Counted {data_rows:,} data rows, {len(counts):,} unique names", file=sys.stderr)
    return counts


def filter_to_stdout(in_path: Path, min_count: int, counts: Counter[str]) -> None:
    keep = {name for name, n in counts.items() if n >= min_count}
    drop = len(counts) - len(keep)
    print(
        f"Keeping {len(keep):,} names (≥ {min_count}); dropping {drop:,} rare names",
        file=sys.stderr,
    )

    kept_rows = 0
    dropped_rows = 0
    with open_text(in_path) as fin:
        for line in fin:
            raw = line.rstrip("\n")
            if is_header(raw):
                print(raw)
                continue
            parts = raw.split("\t")
            if len(parts) < 3:
                continue
            name = parts[3].strip() if len(parts) > 3 else ""
            if name in keep:
                print(raw)
                kept_rows += 1
            else:
                dropped_rows += 1

    print(
        f"Wrote {kept_rows:,} rows to stdout (dropped {dropped_rows:,})",
        file=sys.stderr,
    )


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("input", type=Path, help="Input BED or BED.gz")
    p.add_argument(
        "--min-count",
        type=int,
        default=1000,
        help="Minimum occurrences of column-4 name to keep (default: 1000)",
    )
    p.add_argument(
        "--list-dropped",
        action="store_true",
        help="Print dropped names with counts to stderr",
    )
    args = p.parse_args()

    if not args.input.is_file():
        print(f"Input not found: {args.input}", file=sys.stderr)
        return 1
    if args.min_count < 1:
        print("--min-count must be ≥ 1", file=sys.stderr)
        return 1

    counts = count_names(args.input)
    if args.list_dropped:
        dropped = sorted(
            ((n, c) for n, c in counts.items() if c < args.min_count),
            key=lambda t: (t[1], t[0]),
        )
        for name, c in dropped:
            label = name if name else "(empty)"
            print(f"{c:8d}\t{label}", file=sys.stderr)

    filter_to_stdout(args.input, args.min_count, counts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
