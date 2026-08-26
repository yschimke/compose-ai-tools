#!/usr/bin/env python3
"""Measure how tightly `compose-preview serve` is still bolted to this repo.

Issue #3824 defines a four-condition gate that says when the preview server is
ready to move to its own repository, and #3856 asks the same question three
ways (core / serve / VS Code extension). Both numbers lived only in issue
bodies, which makes them folklore: nobody can re-run them, and the next
measurement is not comparable with the last. This script is that measurement,
committed.

    python3 scripts/measure-serve-coupling.py                  # trailing 300 PRs
    python3 scripts/measure-serve-coupling.py --prs 500
    python3 scripts/measure-serve-coupling.py --since <rev>
    python3 scripts/measure-serve-coupling.py --json

One commit on `main` is one PR (the repo squash-merges), minus release-please
bots. A PR is "crossing" a boundary if it touches at least one *counted* file
on each side of it.

Counted files (issue #3824's classifier rules, kept verbatim so the script and
the issue cannot disagree):

  * extensions kt|kts|ts|tsx|js|mjs|css|html|svg|json|yml|yaml|sh|properties|toml
  * excluding renders/**, docs/**, site/**, .github/**, *.md and images

Docs and CI are excluded deliberately: they are cheap to duplicate across two
repos and would flatter the result.

Component sides are matched against BOTH the pre- and post-extraction layout,
so the series stays continuous across the moves issue #3824 asks for (the
`:cli:serve` module extraction, the `preview-server` build, and the relocation
of the serve Playwright harness out of `vscode-extension/`).

Exit status is 0 unless `--gate` is passed, in which case a red gate exits 1.
Conditions 1 (structural) and 4 (protocol) are reported as MANUAL: the first
is `checkServeSeam` / `checkServeModuleBoundary` in the build, the second needs
a human to say whether a `protocolVersion` bump was serve-motivated. The script
prints the candidate commits for condition 4 rather than guessing.

Pure stdlib; unit-tested by scripts/test_measure_serve_coupling.py.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------------
# Classifier
# ---------------------------------------------------------------------------

COUNTED_SUFFIXES = (
    ".kt",
    ".kts",
    ".ts",
    ".tsx",
    ".js",
    ".mjs",
    ".css",
    ".html",
    ".svg",
    ".json",
    ".yml",
    ".yaml",
    ".sh",
    ".properties",
    ".toml",
)

UNCOUNTED_PREFIXES = ("renders/", "docs/", "site/", ".github/")

# Server side. Both layouts: today's `cli/src/*/kotlin/.../cli/serve/**` package
# and the post-extraction `preview-server/**` / `cli/serve/**` module paths.
SERVE_PATTERNS = (
    r"^preview-server/",
    r"^cli/serve/",
    r"^cli/serve-web/",
    r"^cli/src/[^/]+/kotlin/ee/schimke/composeai/cli/serve/",
    r"^cli/src/[^/]+/resources/ee/schimke/composeai/cli/serve/",
    r"^cli/src/[^/]+/kotlin/ee/schimke/composeai/cli/ServeCommand",
    r"^deploy/",
    r"^docs/serve/",
    r"^docs/public-preview-server\.md$",
    # The serve viewer's Playwright harness, in both its current (misfiled
    # under the extension) and relocated homes.
    r"^vscode-extension/preview-harness/serve-",
    r"^vscode-extension/preview-harness/pages-snapshot\.",
    r"^vscode-extension/preview-harness/playground[-.]",
    r"^vscode-extension/preview-harness/format-compare-scorer\.",
    r"^vscode-extension/preview-harness/fixtures/pages/serve-",
)

# VS Code extension side — everything under vscode-extension/ that the serve
# patterns above did not already claim.
EXTENSION_PATTERNS = (r"^vscode-extension/",)

# "Deep" crossings: the tier gate condition 3 counts. These are the paths that
# say the protocol / render engine is still growing on the server's behalf.
DEEP_PATTERNS = (
    r"^daemon/",
    r"^renderers/",
    r"^renderer-",
    r"^gradle-plugin/",
    r"^data/[^/]+/connector/",
    r"^render-session/",
)

SERVE_RE = tuple(re.compile(p) for p in SERVE_PATTERNS)
EXTENSION_RE = tuple(re.compile(p) for p in EXTENSION_PATTERNS)
DEEP_RE = tuple(re.compile(p) for p in DEEP_PATTERNS)

SERVE = "serve"
EXTENSION = "extension"
CORE = "core"

RELEASE_SUBJECT = re.compile(r"^chore\(main\): release ")


def is_counted(path: str) -> bool:
    """True if `path` counts toward coupling at all."""
    if any(path.startswith(prefix) for prefix in UNCOUNTED_PREFIXES):
        return False
    return path.endswith(COUNTED_SUFFIXES)


def side_of(path: str) -> str:
    """Which component a counted file belongs to.

    Serve wins over extension on purpose: the serve viewer's harness sits
    inside `vscode-extension/` today, and counting it as extension source is
    exactly the misfiling issue #3824 measured at 28% of apparent crossings.
    """
    if any(r.search(path) for r in SERVE_RE):
        return SERVE
    if any(r.search(path) for r in EXTENSION_RE):
        return EXTENSION
    return CORE


def is_deep(path: str) -> bool:
    return any(r.search(path) for r in DEEP_RE)


# ---------------------------------------------------------------------------
# Per-PR classification
# ---------------------------------------------------------------------------


def parse_instant(value: str) -> datetime | None:
    """`%cI` -> an aware datetime, or None if git gave us something unexpected.

    Committer dates carry the committer's UTC offset, and this history has three of them
    (`+00:00`, `+01:00`, `+03:00`). Sorting the strings therefore does NOT order the commits:
    `2026-08-01T09:00+03:00` sorts after `2026-08-01T08:00+00:00` but happened before it. Both
    window endpoints and `deep_per_week` are derived from that ordering, so a mixed-offset window
    could move a gate condition across its threshold. Compare instants, never text.
    """
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


class Pr:
    __slots__ = ("sha", "subject", "date", "instant", "sides", "deep_files")

    def __init__(self, sha: str, subject: str, date: str, files: list[str]):
        self.sha = sha
        self.subject = subject
        self.date = date
        self.instant = parse_instant(date)
        counted = [f for f in files if is_counted(f)]
        self.sides = {side_of(f) for f in counted}
        self.deep_files = [f for f in counted if is_deep(f)]

    def touches(self, side: str) -> bool:
        return side in self.sides

    def crosses(self, side: str) -> bool:
        """Touches counted files on `side` and on at least one other side."""
        return side in self.sides and len(self.sides) > 1

    def crosses_deep(self, side: str) -> bool:
        return self.crosses(side) and bool(self.deep_files)


def measure(prs: list[Pr], side: str) -> dict:
    total = len(prs)
    touching = [p for p in prs if p.touches(side)]
    crossing = [p for p in prs if p.crosses(side)]
    deep = [p for p in crossing if p.deep_files]
    weeks = span_weeks(prs)
    return {
        "side": side,
        "prs": total,
        "touching": len(touching),
        "crossing": len(crossing),
        "deep": len(deep),
        "weeks": weeks,
        "pct_of_all": pct(len(crossing), total),
        "pct_of_touching": pct(len(crossing), len(touching)),
        "deep_per_week": (len(deep) / weeks) if weeks else 0.0,
        "deep_paths": Counter(
            top_dir(f) for p in deep for f in p.deep_files
        ).most_common(10),
        "deep_examples": [
            {"sha": p.sha, "subject": p.subject} for p in deep[:5]
        ],
    }


def pct(n: int, d: int) -> float:
    return (100.0 * n / d) if d else 0.0


def top_dir(path: str) -> str:
    parts = path.split("/")
    return "/".join(parts[:2]) if len(parts) > 1 else path


def window_bounds(prs: list[Pr]) -> tuple[datetime, datetime] | None:
    """The oldest and newest commit in the window, ordered as instants."""
    instants = sorted(p.instant for p in prs if p.instant is not None)
    if len(instants) < 2:
        return None
    return instants[0], instants[-1]


def span_weeks(prs: list[Pr]) -> float:
    bounds = window_bounds(prs)
    if not bounds:
        return 0.0
    first, last = bounds
    return (last - first).total_seconds() / 86400.0 / 7.0


# ---------------------------------------------------------------------------
# The gate (issue #3824, "Ready-to-split test")
# ---------------------------------------------------------------------------

GATE_VOLUME_ALL = 5.0  # % of all PRs
GATE_VOLUME_TOUCHING = 15.0  # % of component-touching PRs
GATE_DEEP_PER_WEEK = 2.0


def gate(stats: dict) -> list[dict]:
    """Conditions 2 and 3. 1 and 4 are reported as MANUAL by the caller."""
    return [
        {
            "id": 2,
            "name": "Volume — crossing PRs as a share of all PRs",
            "value": f"{stats['pct_of_all']:.1f}%",
            "target": f"<= {GATE_VOLUME_ALL:.0f}%",
            "pass": stats["pct_of_all"] <= GATE_VOLUME_ALL,
        },
        {
            "id": 2,
            "name": "Volume — crossing PRs as a share of component-touching PRs",
            "value": f"{stats['pct_of_touching']:.1f}%",
            "target": f"<= {GATE_VOLUME_TOUCHING:.0f}%",
            "pass": stats["pct_of_touching"] <= GATE_VOLUME_TOUCHING,
            # #3856: this limb misreads a component in maintenance, where the
            # denominator is tiny. Kept as-is on purpose — editing a threshold
            # so a different component passes is how a gate stops meaning
            # anything — but flagged so a reader knows to check the absolute
            # number beside it.
            "caveat_low_volume": stats["touching"] < 80,
        },
        {
            "id": 3,
            "name": "Depth — deep crossings per week (load-bearing)",
            "value": f"{stats['deep_per_week']:.1f}/wk",
            "target": f"<= {GATE_DEEP_PER_WEEK:.0f}/wk",
            "pass": stats["deep_per_week"] <= GATE_DEEP_PER_WEEK,
        },
    ]


def gate_is_green(stats: dict) -> bool:
    """Whether every automatable condition passes for one component.

    Conditions 1 and 4 are not decidable here (see the module docstring), so a
    green return means "nothing the script can measure is red" — never "ship it".
    """
    return all(cond["pass"] for cond in gate(stats))


PROTOCOL_HINT = re.compile(r"protocolVersion|protocol version", re.IGNORECASE)


def protocol_candidates(prs: list[Pr]) -> list[Pr]:
    """PRs whose subject smells like a protocol bump — condition 4's shortlist."""
    return [p for p in prs if PROTOCOL_HINT.search(p.subject)]


# ---------------------------------------------------------------------------
# Git plumbing
# ---------------------------------------------------------------------------


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def load_prs(rev: str, limit: int | None, since: str | None) -> list[Pr]:
    sep = "\x1e"
    args = ["log", "--no-merges", f"--format={sep}%H%x1f%cI%x1f%s", "--name-only"]
    if since:
        args.append(f"{since}..{rev}")
    else:
        args.append(rev)
    out = git(*args)
    prs: list[Pr] = []
    for chunk in out.split(sep):
        chunk = chunk.strip("\n")
        if not chunk:
            continue
        header, _, body = chunk.partition("\n")
        sha, _, rest = header.partition("\x1f")
        date, _, subject = rest.partition("\x1f")
        if RELEASE_SUBJECT.match(subject):
            continue
        files = [line for line in body.split("\n") if line.strip()]
        prs.append(Pr(sha[:9], subject, date, files))
        if limit and len(prs) >= limit:
            break
    return prs


def warn_if_shallow() -> None:
    try:
        shallow = git("rev-parse", "--is-shallow-repository").strip()
    except subprocess.CalledProcessError:
        return
    if shallow == "true":
        print(
            "measure-serve-coupling: WARNING — shallow clone. The window is "
            "truncated to the commits present; run "
            "`git fetch --unshallow origin main` for a real measurement.",
            file=sys.stderr,
        )


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------


def render(prs: list[Pr], sides: list[str], want_gate: bool) -> bool:
    weeks = span_weeks(prs)
    bounds = window_bounds(prs)
    span = (
        f"{bounds[0].date().isoformat()} … {bounds[1].date().isoformat()}" if bounds else "?"
    )
    print(f"Window: {len(prs)} human PRs over {weeks:.1f} weeks ({span})")
    print()
    header = f"{'component':<12}{'touching':>10}{'crossing':>10}{'% all':>9}{'% touch':>10}{'deep/wk':>10}"
    print(header)
    print("-" * len(header))
    all_stats = {}
    for side in sides:
        s = measure(prs, side)
        all_stats[side] = s
        print(
            f"{side:<12}{s['touching']:>10}{s['crossing']:>10}"
            f"{s['pct_of_all']:>8.1f}%{s['pct_of_touching']:>9.1f}%"
            f"{s['deep_per_week']:>10.1f}"
        )
    print()

    for side in sides:
        s = all_stats[side]
        print(f"Gate — {side}")
        for cond in gate(s):
            mark = "PASS" if cond["pass"] else "FAIL"
            note = ""
            if cond.get("caveat_low_volume"):
                note = (
                    f"  (only {s['touching']} touching PRs — ratio is noisy "
                    "for a component in maintenance; see #3856)"
                )
            print(f"  [{mark}] {cond['id']}. {cond['name']}: "
                  f"{cond['value']} (target {cond['target']}){note}")
        if s["deep_paths"]:
            top = ", ".join(f"{p} x{n}" for p, n in s["deep_paths"][:5])
            print(f"  deep traffic: {top}")
        for ex in s["deep_examples"]:
            print(f"    e.g. {ex['sha']} {ex['subject'][:88]}")
        print("  [MANUAL] 1. Structural — `./gradlew checkServeSeam` green for "
              "the whole window, with no allowlist additions")
        cands = protocol_candidates(prs)
        if cands:
            print(f"  [MANUAL] 4. Protocol — {len(cands)} candidate commit(s) to "
                  "judge for a serve-motivated protocolVersion bump:")
            for c in cands[:5]:
                print(f"    {c.sha} {c.subject[:88]}")
        else:
            print("  [MANUAL] 4. Protocol — no commit subject in the window "
                  "mentions protocolVersion")
        print()

    green = SERVE not in all_stats or gate_is_green(all_stats[SERVE])
    if want_gate:
        print("GATE: " + ("GREEN" if green else "RED"))
    return green


def gate_green(components: dict, want_gate: bool) -> bool:
    """`--gate` judges the serve component; without it, nothing gates."""
    if not want_gate or SERVE not in components:
        return True
    return gate_is_green(components[SERVE])


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rev", default="main", help="branch or revision to walk (default: main)")
    ap.add_argument("--prs", type=int, default=300, help="window size in human PRs (default: 300)")
    ap.add_argument("--since", help="measure <since>..<rev> instead of a PR count")
    ap.add_argument(
        "--component",
        action="append",
        choices=[SERVE, EXTENSION, CORE],
        help="component to measure (repeatable; default: serve + extension)",
    )
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--gate", action="store_true", help="exit 1 if the serve gate is red")
    args = ap.parse_args(argv)

    warn_if_shallow()
    sides = args.component or [SERVE, EXTENSION]
    prs = load_prs(args.rev, None if args.since else args.prs, args.since)
    if not prs:
        print("measure-serve-coupling: no commits in window", file=sys.stderr)
        return 2

    if args.json:
        bounds = window_bounds(prs)
        payload = {
            "window": {
                "prs": len(prs),
                "weeks": round(span_weeks(prs), 2),
                "newest": bounds[1].isoformat() if bounds else None,
                "oldest": bounds[0].isoformat() if bounds else None,
            },
            "components": {s: measure(prs, s) for s in sides},
        }
        payload["gate"] = {s: gate(payload["components"][s]) for s in sides}
        print(json.dumps(payload, indent=2))
        # `--gate` is an exit-status contract, not an output format, so it holds
        # here too: a CI consumer piping `--json --gate` must not be told a red
        # window succeeded.
        return 0 if gate_green(payload["components"], args.gate) else 1

    green = render(prs, sides, args.gate)
    return 0 if (green or not args.gate) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
