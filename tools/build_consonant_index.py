#!/usr/bin/env python3
"""Build a compact-pinyin lookup index from generated Rime dictionary shards.

Output rows match RimeDictionary.fromShardsWithPrecomputed():
compact_pinyin<TAB>word<TAB>spaced_pinyin<TAB>weight
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("shards_dir", type=Path)
    parser.add_argument("output_file", type=Path)
    args = parser.parse_args()

    if not args.shards_dir.is_dir():
        raise FileNotFoundError(f"Dictionary shards directory not found: {args.shards_dir}")

    # Keep the highest-weight row for each (compact key, word), matching the
    # runtime dictionary normalization and avoiding duplicate index entries.
    entries: dict[tuple[str, str], tuple[str, int]] = {}
    for path in sorted(args.shards_dir.glob("**/*.dict.yaml")):
        in_data = False
        with path.open("r", encoding="utf-8-sig") as source:
            for raw in source:
                line = raw.strip()
                if line == "...":
                    in_data = True
                    continue
                if not in_data or not line or line.startswith("#"):
                    continue
                fields = re.split(r"\s+", line)
                if len(fields) < 2:
                    continue
                word = fields[0]
                pinyin = re.sub(r"[^a-zA-Z ]", "", fields[1]).lower().strip()
                if not word or not pinyin:
                    continue
                compact = pinyin.replace(" ", "")
                if not compact:
                    continue
                try:
                    weight = int(fields[2]) if len(fields) > 2 else 0
                except ValueError:
                    weight = 0
                key = (compact, word)
                previous = entries.get(key)
                if previous is None or weight > previous[1]:
                    entries[key] = (pinyin, weight)

    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    with args.output_file.open("w", encoding="utf-8", newline="\n") as output:
        output.write("# compact_pinyin\\tword\\tpinyin\\tweight\n")
        for (compact, word), (pinyin, weight) in sorted(entries.items()):
            output.write(f"{compact}\t{word}\t{pinyin}\t{weight}\n")
    print(f"indexed_entries={len(entries)}")
    print(f"output={args.output_file}")


if __name__ == "__main__":
    main()
