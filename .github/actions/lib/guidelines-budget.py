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

    python3 guidelines-budget.py --dir _guidelines --trim 30

enforces `guidelines-max-previews` where it is trusted. The render phase already staged at most
that many, best first; but the handoff comes from the PR's own build, so the job holding the key
cuts it again: it walks the modules in `--order`, keeps the first N previews each `previews.json`
lists, and adds the rest to `_over_limit.json` (which the comment lists as not checked). A module
left with none loses its `previews.json`, so the check skips it. 0 cuts nothing.

    python3 guidelines-budget.py --dir _guidelines --seed-cache .

copies the catalog publish's guideline results — restored read-only from its Actions cache
(`**/build/compose-previews/guidelines/<shard>/<key>.json`, see `design-artifacts-reusable.yml`)
— into every staged module's `guidelines/`, which is where a handoff run's engine looks. A result
is filed under the hash of everything its verdict depends on (render bytes, source, nodes, rules,
model), never under a module, so one pool serves every module: a PR preview whose inputs are the
same as on the default branch is answered from it at no cost. Prints how many files it copied.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
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


OVER_LIMIT_FILE = "_over_limit.json"
# What `_over_limit.json` may list: enough for any real PR, bounded so a fork cannot bloat the comment.
MAX_OVER_LIMIT = 2000


def trim(dir_: Path, max_previews: int) -> int:
    """Keeps at most [max_previews] previews across the staged modules, in `order`; returns how
    many were cut. The cut ones join `_over_limit.json`'s list, under the trusted limit."""
    if max_previews <= 0 or not dir_.is_dir():
        return 0
    over_path = dir_ / OVER_LIMIT_FILE
    if over_path.is_symlink():
        over_path.unlink()
    listed: list[dict] = []
    try:
        data = json.loads(over_path.read_text(encoding="utf-8"))
        if isinstance(data, dict) and isinstance(data.get("previews"), list):
            listed = [p for p in data["previews"] if isinstance(p, dict)][:MAX_OVER_LIMIT]
    except (OSError, ValueError):
        pass
    left = max_previews
    cut = 0
    for module in order(dir_):
        manifest_path = module / "previews.json"
        if manifest_path.is_symlink():
            manifest_path.unlink()
            continue
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        previews = manifest.get("previews") if isinstance(manifest, dict) else None
        if not isinstance(previews, list):
            continue
        kept, dropped = previews[:left], previews[left:]
        left -= len(kept)
        if not dropped:
            continue
        cut += len(dropped)
        for preview in dropped:
            if isinstance(preview, dict) and len(listed) < MAX_OVER_LIMIT:
                listed.append(
                    {
                        "id": preview.get("id"),
                        "module": module.name,
                        "function": preview.get("functionName"),
                    }
                )
        if kept:
            manifest["previews"] = kept
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        else:
            manifest_path.unlink()
    if cut or over_path.exists():
        over_path.write_text(
            json.dumps({"limit": max_previews, "previews": listed}, indent=2) + "\n",
            encoding="utf-8",
        )
    return cut


RESULT_NAME = re.compile(r"^[0-9a-f]{64}\.json$")
SHARD_NAME = re.compile(r"^[0-9a-f]{2}$")
CACHE_SUFFIX = ("build", "compose-previews", "guidelines")
SKIP_DIRS = {"node_modules", ".git", ".gradle"}


def cached_results(root: Path, exclude: Path) -> list[Path]:
    """Every result file under `<root>/**/build/compose-previews/guidelines/<shard>/`, outside
    [exclude] (the staged handoff), skipping links."""
    found = []
    excluded = exclude.resolve()
    for cache in root.rglob("/".join(CACHE_SUFFIX)):
        relative = cache.relative_to(root)
        if any(part in SKIP_DIRS for part in relative.parts):
            continue
        # Every handoff entry is a top-level `_…` name (the action's restore allowlist), and the
        # handoff is the PR's own output: a result planted there must never pass for the publish's.
        if relative.parts[0].startswith("_"):
            continue
        # Nor reached through a link: the directory must be where its path says.
        if os.path.realpath(cache) != os.path.abspath(cache):
            continue
        if cache.resolve().is_relative_to(excluded):
            continue
        for shard in sorted(cache.iterdir()):
            if not SHARD_NAME.match(shard.name) or shard.is_symlink() or not shard.is_dir():
                continue
            for result in sorted(shard.iterdir()):
                if (
                    RESULT_NAME.match(result.name)
                    and result.name.startswith(shard.name)
                    and result.is_file()
                    and not result.is_symlink()
                ):
                    found.append(result)
    return found


def seed(dir_: Path, root: Path) -> int:
    """Copies the cached results under [root] into each staged module's `guidelines/`."""
    results = cached_results(root, dir_)
    copied = 0
    for module in order(dir_):
        if not (module / "previews.json").is_file():
            continue
        cache = module / "guidelines"
        # Only the restored cache may answer: never results the handoff carried in.
        if cache.is_symlink() or cache.is_file():
            cache.unlink()
        elif cache.is_dir():
            shutil.rmtree(cache)
        for result in results:
            dest = cache / result.parent.name / result.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(result, dest)
            copied += 1
    return copied


def remaining(dir_: Path, max_cost: float) -> float:
    """What is left of [max_cost] once this run's spend is taken off, never below zero."""
    return max(0.0, max_cost - spent(dir_))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--max-cost", type=float)
    parser.add_argument("--order", action="store_true", help="print the module directories in order")
    parser.add_argument("--trim", type=int, metavar="N", help="keep at most N previews in total")
    parser.add_argument("--seed-cache", type=Path, metavar="ROOT",
                        help="copy the restored catalog result cache under ROOT into each module")
    args = parser.parse_args(argv)
    if args.seed_cache is not None:
        print(seed(args.dir, args.seed_cache))
        return 0
    if args.trim is not None:
        print(trim(args.dir, args.trim))
        return 0
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
