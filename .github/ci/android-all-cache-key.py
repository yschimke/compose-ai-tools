#!/usr/bin/env python3
"""Bound the Robolectric `android-all` cache payload and print its cache key.

Used by integration.yml's two "Resolve android-all cache key" steps (the
external-repo matrix and the daemon-harness leg). It runs AFTER the build, over
whatever `~/.m2/repository/org/robolectric/android-all-instrumented` holds, and
prints one `coordinate=<key>` line for `$GITHUB_OUTPUT`; the caller feeds that
into the split restore/save cache key.

Two failure modes bound the retained set from either side, and both have been
hit in this repo:

  - Keep only ONE (the newest). Wrong: a cell can legitimately need several.
    `wear-os-samples` fetched both `15-robolectric-13954326-i7` and
    `16-robolectric-13921718-i7` in one run, because the Gradle render path and
    the daemon path resolve different SDKs. Dropping one left the entry
    permanently incomplete — re-downloaded (~200 MB) every run, with the save
    no-op'ing because the key never changed.
  - Keep ALL. Also wrong: the restore hands back the previous set, so each
    `robolectric` bump folds another dead coordinate into the next entry.
    Evicting older entries doesn't shrink the newest one, so the payload grows
    ~200 MB per bump forever.

There is no way to ask which coordinates this run actually USED: a restored jar
that gets used is not rewritten, so it looks identical to one that sat
untouched. Usage can't be inferred after the fact, so the bound is a deliberate
policy instead — keep the [MAX_COORDINATES] highest Android versions and delete
the rest, loudly. That covers a multi-SDK cell (2 today) with headroom, and a
bump evicts the oldest rather than accumulating. A drop is logged as a workflow
warning, so if a cell ever genuinely needs more than the cap the re-download
shows up as a warning rather than as an unexplained slow leg.

Env:
    ANDROID_ALL_ROOT   override the coordinate directory (default
                       ~/.m2/repository/org/robolectric/android-all-instrumented)

Pure stdlib; unit-tested by test_android_all_cache_key.py. Lives here rather
than inline in the workflow so the retention policy can be exercised without
running a 40-minute matrix leg — it deletes ~200 MB directories, so "only drops
what it says it drops" has to be a test, not a comment.
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import sys
from pathlib import Path

# Headroom over the 2 a render+daemon cell needs today. Raise it if a cell
# starts warning that it dropped a coordinate it wanted.
MAX_COORDINATES = 3

DEFAULT_ROOT = Path.home() / ".m2/repository/org/robolectric/android-all-instrumented"


def materialised_coordinates(root: Path) -> list[Path]:
    """Coordinate directories under [root] that actually carry a jar.

    A directory with no jar is a half-finished download (or a stale marker
    dir); caching or keying on it would pin an entry that can't serve a build.
    """
    if not root.is_dir():
        return []
    return [d for d in root.glob("*") if d.is_dir() and any(d.glob("*.jar"))]


def _rank(name: str) -> tuple[int, str]:
    """Sort key: Android version (leading int of `16-robolectric-13921718-i7`),
    then the full name so two builds of one version order stably."""
    match = re.match(r"(\d+)", name)
    return (int(match.group(1)) if match else -1, name)


def partition(names: list[str], cap: int = MAX_COORDINATES) -> tuple[list[str], list[str]]:
    """Split [names] into (keep, drop): the [cap] highest-ranked, then the rest."""
    ranked = sorted(names, key=_rank, reverse=True)
    return ranked[:cap], ranked[cap:]


def cache_key(names: list[str]) -> str:
    """The cache-key fragment for a retained coordinate set.

    One coordinate reads plainly in the key; several would blow past a sensible
    key length, so collapse those to a stable digest. Either way the key changes
    if and only if the retained SET changes — which is what makes the save a
    no-op on an unchanged cell and a fresh entry after a bump.
    """
    joined = "_".join(sorted(names))
    if len(names) == 1:
        return joined
    return f"{len(names)}x-{hashlib.sha256(joined.encode()).hexdigest()[:16]}"


def resolve(root: Path, cap: int = MAX_COORDINATES) -> str:
    """Prune [root] to the cap and return the resulting key ("" when empty).

    Deletes the dropped coordinate directories — the caller is about to save the
    whole tree, so pruning has to happen on disk, not just in the key.
    """
    present = [d.name for d in materialised_coordinates(root)]
    if not present:
        print("no android-all coordinate on disk — nothing to cache", file=sys.stderr)
        return ""

    keep, drop = partition(present, cap)
    for name in drop:
        print(
            f"::warning::dropping android-all coordinate {name} — over the "
            f"{cap}-coordinate cache cap. If this cell needs it, it will be "
            "re-downloaded every run; raise MAX_COORDINATES.",
            file=sys.stderr,
        )
        shutil.rmtree(root / name)

    print(
        f"caching {len(keep)} android-all coordinate(s): {'_'.join(sorted(keep))}",
        file=sys.stderr,
    )
    return cache_key(keep)


def main() -> int:
    root = Path(os.environ.get("ANDROID_ALL_ROOT") or DEFAULT_ROOT)
    print(f"coordinate={resolve(root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
