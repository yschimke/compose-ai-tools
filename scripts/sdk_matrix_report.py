#!/usr/bin/env python3
"""Aggregate per-cell JSON results from `.github/workflows/sdk-matrix.yml` into a Markdown
table for the workflow job summary (and, eventually, `docs/SDK_COMPATIBILITY.md`).

Each cell JSON has the shape:

    {
      "label": "jdk17 / compileSdk=35 / target=35 / min=24",
      "jdk": "17",
      "compileSdk": 35,
      "targetSdk": 35,
      "minSdk": 24,
      "expected": "pass",
      "outcome": "pass",
      "exitCode": 0
    }

The table sorts by (JDK, compileSdk, targetSdk, minSdk) so the doc reads top-down from "newest
consumer on newest JDK" to "older consumer on older JDK". A trailing summary line counts
unexpected outcomes (where `outcome != expected`) — that's the value worth alerting on.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Cell:
    label: str
    jdk: str
    compile_sdk: int
    target_sdk: int
    min_sdk: int
    expected: str
    outcome: str
    exit_code: int

    @classmethod
    def from_json(cls, payload: dict) -> "Cell":
        return cls(
            label=payload["label"],
            jdk=payload["jdk"],
            compile_sdk=payload["compileSdk"],
            target_sdk=payload["targetSdk"],
            min_sdk=payload["minSdk"],
            expected=payload["expected"],
            outcome=payload["outcome"],
            exit_code=payload["exitCode"],
        )

    @property
    def matches_expectation(self) -> bool:
        return self.outcome == self.expected


def status_glyph(cell: Cell) -> str:
    if cell.outcome == "pass" and cell.expected == "pass":
        return "✅ pass"
    if cell.outcome == "fail" and cell.expected == "fail":
        return "❌ fail (expected)"
    if cell.outcome == "pass" and cell.expected == "fail":
        return "❓ pass (expected fail — investigate)"
    return "💥 fail (unexpected)"


def render(cells: list[Cell]) -> str:
    cells_sorted = sorted(
        cells, key=lambda c: (c.jdk, c.compile_sdk, c.target_sdk, c.min_sdk)
    )
    lines: list[str] = [
        "| JDK | compileSdk | targetSdk | minSdk | Expected | Outcome |",
        "|---:|---:|---:|---:|---|---|",
    ]
    for c in cells_sorted:
        lines.append(
            f"| {c.jdk} | {c.compile_sdk} | {c.target_sdk} | {c.min_sdk} | "
            f"{c.expected} | {status_glyph(c)} |"
        )
    unexpected = [c for c in cells if not c.matches_expectation]
    lines.append("")
    if unexpected:
        lines.append(f"**{len(unexpected)} cell(s) drifted from expectations:**")
        for c in unexpected:
            lines.append(f"- `{c.label}` — expected {c.expected}, got {c.outcome}")
    else:
        lines.append("All cells matched their documented expectations.")
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cells", required=True, type=Path,
                        help="Directory containing per-cell JSON files.")
    parser.add_argument("--out", required=True, type=Path,
                        help="Markdown table output path.")
    args = parser.parse_args(argv)

    cells: list[Cell] = []
    for path in sorted(args.cells.rglob("*.json")):
        with path.open() as f:
            cells.append(Cell.from_json(json.load(f)))
    if not cells:
        print(f"No cell JSON files found under {args.cells}", file=sys.stderr)
        return 1
    args.out.write_text(render(cells))
    print(f"Wrote {args.out} ({len(cells)} cells)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
