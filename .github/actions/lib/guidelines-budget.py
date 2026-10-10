#!/usr/bin/env python3
"""What is left of the design-guidelines budget, across every staged module.

The apply action runs `compose-preview guidelines` once per `_guidelines/<module>/` directory, and
those directories come from the handoff a PR's own build wrote. `guidelines-max-cost` has to be
ONE budget for the whole check, not one per directory a fork chooses to stage, so before each
invocation the action asks this script how much is left:

    python3 guidelines-budget.py --dir _guidelines --max-cost 0.25

It prints the remaining dollars (never below zero) and sums the `costUsd` of every result in each
module's `guidelines.json`. The action deletes those files before the first invocation, so every
one it reads was written by this run's CLI; a negative or unreadable cost counts as zero, so no file
can raise the budget.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


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
        for result in results:
            record = result.get("record") if isinstance(result, dict) else None
            if isinstance(record, dict):
                total += _cost(record.get("costUsd"))
    return total


def remaining(dir_: Path, max_cost: float) -> float:
    """What is left of [max_cost] once this run's spend is taken off, never below zero."""
    return max(0.0, max_cost - spent(dir_))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--max-cost", type=float, required=True)
    args = parser.parse_args(argv)
    print(f"{remaining(args.dir, args.max_cost):.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
