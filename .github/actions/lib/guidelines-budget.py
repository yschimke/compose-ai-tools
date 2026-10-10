#!/usr/bin/env python3
"""What is left of the design-guidelines budget, across every staged module.

The apply action runs `compose-preview guidelines` once per `_guidelines/<module>/` directory, and
those directories come from the handoff a PR's own build wrote. `guidelines-max-cost` has to be
ONE budget for the whole check, not one per directory a fork chooses to stage, so before each
invocation the action asks this script how much is left:

    python3 guidelines-budget.py --dir _guidelines --max-cost 0.25

It prints the remaining dollars (never below zero). A module's spend is its `guidelines.json`'s
run-level `costUsd` (every request, replies that could not be used included), or, from a CLI that
does not record one, the sum of its results' `costUsd`; whichever is larger. The action deletes
those files before the first invocation, so every one it reads was written by this run's CLI; a
negative or unreadable cost counts as zero, so no file can raise the budget.

    python3 guidelines-budget.py --dir _guidelines --order

prints the staged module directories in the order the budget is spent on them: those holding the
PR's changed previews first, then those staged only because the rules changed
(`"guidelinesSelection": "rules-changed"` in their `previews.json`), each group by name.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

RULES_TIER = "rules-changed"
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def _cost(value: object) -> float:
    try:
        cost = float(value or 0.0)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0.0
    return cost if math.isfinite(cost) and cost > 0.0 else 0.0


def spent(dir_: Path) -> float:
    """The dollars recorded across every `<dir_>/*/guidelines.json`."""
    total = 0.0
    for report in sorted(dir_.glob("*/guidelines.json")):
        try:
            results = json.loads(report.read_text(encoding="utf-8")).get("results", [])
        except (OSError, ValueError, AttributeError):
            continue
        if not isinstance(results, list):
            continue
        records = 0.0
        for result in results:
            record = result.get("record") if isinstance(result, dict) else None
            if isinstance(record, dict):
                records += _cost(record.get("costUsd"))
        run = _cost(report_cost(report))
        total += max(run, records)
    return total


def report_cost(report: Path) -> object:
    """The run-level `costUsd` of a module's `guidelines.json`, or None."""
    try:
        data = json.loads(report.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data.get("costUsd") if isinstance(data, dict) else None


def selection(module_dir: Path) -> str | None:
    """The tier a staged module directory holds: `rules-changed`, or None for the PR's own."""
    try:
        data = json.loads((module_dir / "previews.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    value = data.get("guidelinesSelection") if isinstance(data, dict) else None
    return value if value == RULES_TIER else None


def order(dir_: Path) -> list[Path]:
    """The staged module directories, the PR's changed previews before the rules-changed tier."""
    modules = sorted(
        p for p in (dir_.iterdir() if dir_.is_dir() else [])
        if p.is_dir() and not p.is_symlink() and SAFE_NAME.match(p.name)
    )
    return sorted(modules, key=lambda p: (selection(p) == RULES_TIER, p.name))


def remaining(dir_: Path, max_cost: float) -> float:
    """What is left of [max_cost] once this run's spend is taken off, never below zero."""
    return max(0.0, max_cost - spent(dir_))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--max-cost", type=float)
    parser.add_argument("--order", action="store_true", help="print the module directories in order")
    args = parser.parse_args(argv)
    if args.order:
        for module in order(args.dir):
            print(f"{args.dir.as_posix()}/{module.name}")
        return 0
    if args.max_cost is None:
        parser.error("--max-cost is required")
    print(f"{remaining(args.dir, args.max_cost):.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
