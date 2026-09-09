#!/usr/bin/env python3
"""Remove evidence directories that nothing references any more.

Evidence is captured for one pull request and embedded in its body. Once that PR merges the
directory has done its job: the images stay reachable at the commit they were pinned to, which is
what `AGENTS.md` requires an embed to cite, so deleting them from HEAD costs a merged PR nothing.
Left alone they only accumulate — 81 directories and 21.3 MB of PNGs when this was written, against
a family of repositories on a hard per-checkout size budget.

A directory is KEPT when any of these holds:

* **Something references it.** Any tracked file outside the evidence tree containing the directory's
  name counts, whether as a markdown link, an image embed or a comment. The match is a plain
  substring, deliberately: over-matching keeps a directory, and that is the safe direction.
* **It is younger than the age floor.** Evidence for a PR that is still open must survive, and the
  PR that adds a directory adds no reference to it from anywhere else. The floor is measured from
  the last commit that touched the directory, so a long-running branch keeps its evidence as long as
  it is being worked on.
* **It carries a `KEEP` file.** The escape hatch for evidence worth keeping that nothing links to —
  a baseline, a reference capture. Put the reason in the file.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path

# Every tree that holds per-PR capture. They were added at different times under different names
# and are the same thing: one directory per pull request, images embedded in that PR's body.
DEFAULT_ROOTS = (
    Path("docs/design/evidence"),
    Path("docs/evidence"),
    Path("renders"),
)
DEFAULT_MIN_AGE_DAYS = 90


def tracked_files(repo: Path) -> list[Path]:
    out = subprocess.run(
        ["git", "-C", str(repo), "ls-files"], capture_output=True, text=True, check=True
    ).stdout
    return [Path(p) for p in out.splitlines() if p]


def last_commit_epoch(repo: Path, path: Path) -> int | None:
    """Unix time of the last commit touching `path`, or None when git knows nothing about it."""
    out = subprocess.run(
        ["git", "-C", str(repo), "log", "-1", "--format=%ct", "--", str(path)],
        capture_output=True,
        text=True,
    ).stdout.strip()
    return int(out) if out else None


def referenced_names(repo: Path, roots: tuple[Path, ...]) -> str:
    """Every tracked file outside the evidence trees, concatenated, for substring matching.

    Outside *all* of them, not just the one being walked: two spent directories in different trees
    citing each other would otherwise keep each other alive.
    """
    chunks = []
    for rel in tracked_files(repo):
        if any(rel.parts[: len(r.parts)] == r.parts for r in roots):
            continue
        try:
            chunks.append((repo / rel).read_bytes().decode("utf-8", "ignore"))
        except OSError:
            continue
    return "\n".join(chunks)


def classify(
    repo: Path,
    min_age_days: int,
    now: float | None = None,
    roots: tuple[Path, ...] = DEFAULT_ROOTS,
) -> tuple[list[str], dict[str, str]]:
    """`(prunable, kept)` — both keyed by the directory's repo-relative path.

    Keyed by path rather than by name because the trees can hold the same name twice; the reference
    check still matches on the bare name, which is how a link, an embed or a comment spells it.
    """
    now = time.time() if now is None else now
    floor = now - min_age_days * 86400
    present = tuple(r for r in roots if (repo / r).is_dir())
    if not present:
        return [], {}
    haystack = referenced_names(repo, roots)

    prunable: list[str] = []
    kept: dict[str, str] = {}
    for root in present:
        for entry in sorted(p for p in (repo / root).iterdir() if p.is_dir()):
            rel = str(root / entry.name)
            if (entry / "KEEP").is_file():
                kept[rel] = "KEEP file"
            elif entry.name in haystack:
                kept[rel] = "referenced"
            else:
                touched = last_commit_epoch(repo, root / entry.name)
                if touched is not None and touched > floor:
                    age = int((now - touched) / 86400)
                    kept[rel] = f"only {age}d old (floor {min_age_days}d)"
                else:
                    prunable.append(rel)
    return prunable, kept


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=".", type=Path)
    ap.add_argument(
        "--min-age-days",
        type=int,
        default=DEFAULT_MIN_AGE_DAYS,
        help="never prune a directory touched more recently than this (default: %(default)s)",
    )
    ap.add_argument(
        "--roots",
        default=",".join(str(r) for r in DEFAULT_ROOTS),
        help="comma-separated evidence trees to walk (default: %(default)s)",
    )
    ap.add_argument("--prune", action="store_true", help="delete them; otherwise only report")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)

    repo = args.repo.resolve()
    roots = tuple(Path(r.strip()) for r in args.roots.split(",") if r.strip())
    prunable, kept = classify(repo, args.min_age_days, roots=roots)

    if not args.quiet:
        print(f"{len(kept)} kept, {len(prunable)} prunable")
        for rel in prunable:
            print(f"  prune {rel}")

    if args.prune:
        for rel in prunable:
            shutil.rmtree(repo / rel)
        if not args.quiet and prunable:
            print(f"removed {len(prunable)} directories")
        return 0

    # Report mode is a gate: non-zero when there is something to prune.
    return 1 if prunable else 0


if __name__ == "__main__":
    sys.exit(main())
