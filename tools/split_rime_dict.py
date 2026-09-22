#!/usr/bin/env python3
"""Merge Rime dictionaries and split them into two-letter pinyin shards."""
from __future__ import annotations
import argparse, json, re
from collections import defaultdict
from pathlib import Path

DEFAULT_FILES = (
    "base.dict.yaml",
    "ext.dict.yaml",
    "tencent.dict.yaml",
    "8105.dict.yaml",
    "41448.dict.yaml",
)

def data_start(lines):
    for i, line in enumerate(lines):
        if line.strip() == "...":
            return i + 1
    return 0

def parse_entry(line):
    s = line.strip()
    if not s or s.startswith("#"):
        return None
    p = re.split(r"\s+", s)
    if len(p) < 2:
        return None
    py = re.sub(r"[^a-zA-Z ]", "", p[1]).lower().strip()
    first = py.split()[0] if py else ""
    if not first or not first[0].isalpha():
        return None
    key = (first + first)[:2]
    return key, line.rstrip()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input_dir", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--files", nargs="+", default=DEFAULT_FILES)
    args = ap.parse_args()
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    groups = defaultdict(dict)
    total = 0
    for name in args.files:
        src = args.input_dir / name
        if not src.exists():
            raise FileNotFoundError(f"Missing dictionary: {src}")
        text = src.read_text(encoding="utf-8-sig")
        lines = text.splitlines()
        for line in lines[data_start(lines):]:
            item = parse_entry(line)
            if not item:
                continue
            key, entry = item
            groups[key][entry] = True
            total += 1
    manifest = {"format": 1, "shardPrefixLength": 2, "shards": {}}
    for key in sorted(groups):
        d = out / key[0]
        d.mkdir(parents=True, exist_ok=True)
        path = d / f"{key}.dict.yaml"
        entries = sorted(groups[key])
        with path.open("w", encoding="utf-8", newline="\n") as f:
            f.write("---\n")
            f.write(f"name: merged.{key}\n")
            f.write("version: \"1.0\"\n")
            f.write("sort: by_weight\n")
            f.write("...\n")
            f.write("\n".join(entries))
            f.write("\n")
        manifest["shards"][key] = {"path": f"{key[0]}/{key}.dict.yaml", "entries": len(entries)}
    (out / "index.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"input_entries={total}")
    print(f"unique_entries={sum(len(v) for v in groups.values())}")
    print(f"shards={len(groups)}")
    print(f"output={out}")

if __name__ == "__main__":
    main()
