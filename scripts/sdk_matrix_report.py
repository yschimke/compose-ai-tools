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
    sdk_version: str | None
    robolectric: str | None
    expected: str
    outcome: str
    exit_code: int
    reason: str | None

    @classmethod
    def from_json(cls, payload: dict) -> "Cell":
        return cls(
            label=payload["label"],
            jdk=payload["jdk"],
            compile_sdk=payload["compileSdk"],
            target_sdk=payload["targetSdk"],
            min_sdk=payload["minSdk"],
            sdk_version=payload.get("sdkVersion"),
            robolectric=payload.get("robolectric"),
            expected=payload["expected"],
            outcome=payload["outcome"],
            exit_code=payload["exitCode"],
            reason=payload.get("reason"),
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


def _short_reason(reason: str | None, char_limit: int = 140) -> str:
    """Trim a captured failure line for the inline table column. The full text shows up below
    the table for each cell that failed; this trim just keeps the table readable."""
    if not reason:
        return ""
    cleaned = reason.strip()
    if len(cleaned) <= char_limit:
        return cleaned
    return cleaned[: char_limit - 1].rstrip() + "…"


def _escape_table_cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").strip()


def render(cells: list[Cell]) -> str:
    cells_sorted = sorted(
        cells, key=lambda c: (c.jdk, c.compile_sdk, c.target_sdk, c.min_sdk)
    )
    lines: list[str] = [
        "| JDK | compileSdk | targetSdk | minSdk | sdkVersion | Robolectric | Expected | Outcome | Reason |",
        "|---:|---:|---:|---:|---|---|---|---|---|",
    ]
    for c in cells_sorted:
        sdk_override = c.sdk_version or "auto"
        robolectric = c.robolectric or "stable"
        # Inline reason only when the cell actually failed — passes don't need a reason column
        # entry. Keep it short; the full text lands under the table.
        reason_cell = ""
        if c.outcome == "fail":
            reason_cell = _escape_table_cell(_short_reason(c.reason))
        lines.append(
            f"| {c.jdk} | {c.compile_sdk} | {c.target_sdk} | {c.min_sdk} | "
            f"{sdk_override} | {robolectric} | {c.expected} | {status_glyph(c)} | "
            f"{reason_cell} |"
        )
    unexpected = [c for c in cells if not c.matches_expectation]
    failed = [c for c in cells if c.outcome == "fail"]
    lines.append("")
    if unexpected:
        lines.append(f"**{len(unexpected)} cell(s) drifted from expectations:**")
        for c in unexpected:
            lines.append(f"- `{c.label}` — expected {c.expected}, got {c.outcome}")
    else:
        lines.append("All cells matched their documented expectations.")
    # Always print full per-cell failure reasons under the table — the most actionable info for
    # someone reading the workflow summary. Includes expected fails so the doc keeps "why" right
    # next to each ❌ row; drift cells get an extra bold marker.
    if failed:
        lines.append("")
        lines.append("### Failure reasons")
        lines.append("")
        for c in sorted(failed, key=lambda x: (x.jdk, x.compile_sdk, x.target_sdk, x.min_sdk)):
            tag = " **(unexpected)**" if not c.matches_expectation else ""
            reason = c.reason or "(no message captured — see build.log artifact)"
            lines.append(f"- `{c.label}`{tag}: {reason}")
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
