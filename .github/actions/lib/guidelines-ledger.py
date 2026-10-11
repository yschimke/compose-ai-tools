#!/usr/bin/env python3
"""The design-guidelines check's running total and re-run gate, kept in its sticky PR comment.

The `<!-- guidelines-report -->` comment is rewritten on every run, so on its own it shows only
the last run's spend: what earlier pushes cost disappears with the body they wrote. This script
keeps a ledger in a hidden marker at the end of that comment, carries it from one body to the
next, and adds a task-list box that decides whether the next push is checked at all:

    guidelines-ledger.py gate --previous prev.md
        Prints `run` or `skip`. Runs when there is no earlier comment, when it records no model
        check yet, when it predates the gate (no box), or when its box is ticked; otherwise skips. A PR's first
        check is free to run; every later one needs someone with write access to tick the box
        (a bot's comment is editable only by them), which clears once the check has run.

    guidelines-ledger.py finalize --body new.md --previous prev.md --sha <head> --run-usd 0.0981
        Appends the ledger section to `new.md` (a body this run wrote): the PR's total so far,
        this run's spend and an unticked box. `--not-a-check` for a body written without asking
        the model (the "nothing needed" resolution), which keeps the totals and counts no run.

    guidelines-ledger.py skipped --previous prev.md --sha <head> --out body.md
        Writes the earlier comment back with a banner saying this revision was NOT checked and
        its findings are from an earlier one, the skip counted and the box still unticked.

`--previous` is the earlier comment's body (empty or missing when there is none). Only the
github-actions bot's comment is read for it, so a comment someone else posts cannot tick the box
or rewrite the totals.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

MARKER = "<!-- guidelines-report -->"
SECTION_START = "<!-- guidelines-ledger-start -->"
SECTION_END = "<!-- guidelines-ledger-end -->"
BOX_MARKER = "<!-- guidelines-rerun -->"
BANNER_START = "<!-- guidelines-skipped-start -->"
BANNER_END = "<!-- guidelines-skipped-end -->"
LEDGER = re.compile(r"<!-- guidelines-ledger (\{[^\n]*?\}) -->")
BOX = re.compile(r"^\s*[-*] \[( |x|X)\] .*" + re.escape(BOX_MARKER), re.MULTILINE)
SHA = re.compile(r"^[0-9a-f]{7,64}$")


def read(path: Path | None) -> str:
    if path is None:
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def _usd(value: object) -> float:
    try:
        usd = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0.0
    return usd if math.isfinite(usd) and usd > 0.0 else 0.0


def _count(value: object) -> int:
    return value if isinstance(value, int) and value > 0 else 0


def ledger(body: str) -> dict:
    """The totals the comment carries: paid checks, dollars, skipped pushes, last checked SHA."""
    match = LEDGER.search(body)
    data: object = {}
    if match:
        try:
            data = json.loads(match.group(1))
        except ValueError:
            data = {}
    if not isinstance(data, dict):
        data = {}
    sha = data.get("sha")
    return {
        "runs": _count(data.get("runs")),
        "usd": _usd(data.get("usd")),
        "skipped": _count(data.get("skipped")),
        "sha": sha if isinstance(sha, str) and SHA.match(sha) else "",
    }


def gate(previous: str) -> str:
    """`run` or `skip` for this revision, from the earlier comment's box."""
    if not previous.startswith(MARKER):
        return "run"
    if ledger(previous)["runs"] == 0:
        # Nothing has been checked for this PR yet (an earlier revision needed no check).
        return "run"
    box = BOX.search(previous)
    if box is None:
        # A comment from before the gate existed: run once, and the new body carries the box.
        return "run"
    return "run" if box.group(1) in "xX" else "skip"


def strip(body: str) -> str:
    """[body] without the ledger section or a skipped banner an earlier run added."""
    body = re.sub(
        re.escape(SECTION_START) + r".*?" + re.escape(SECTION_END) + r"\n?", "", body, flags=re.S
    )
    body = re.sub(
        re.escape(BANNER_START) + r".*?" + re.escape(BANNER_END) + r"\n*", "", body, flags=re.S
    )
    return body.rstrip() + "\n"


def short(sha: str) -> str:
    return sha[:7] if sha else "?"


def heading_end(lines: list[str]) -> int:
    """The index just after the comment's heading (the first `#` line after the marker)."""
    at = 1
    while at < len(lines) and not lines[at].startswith("#"):
        at += 1
    return at + 1 if at < len(lines) else 1


def place(body: str, block: str) -> str:
    """[block] right under [body]'s heading (and any skipped banner there). Near the top, not at
    the end: `post-comment.sh` keeps only the head of an over-long body, and a ledger cut off
    there would reset the total and let the next push run unasked."""
    lines = body.rstrip("\n").split("\n")
    at = heading_end(lines)
    if at < len(lines) and BANNER_START in "\n".join(lines[at:at + 3]):
        end = next((i for i in range(at, len(lines)) if lines[i] == BANNER_END), at)
        at = end + 1
    lines[at:at] = ["", block.rstrip("\n"), ""]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).rstrip("\n") + "\n"


def section(totals: dict, this_run: str, max_cost: str) -> str:
    runs = totals["runs"]
    skipped = totals["skipped"]
    skipped_text = f"; {skipped} push(es) not re-checked" if skipped else ""
    cap = f"up to ${max_cost} " if max_cost else ""
    data = json.dumps(
        {"v": 1, "runs": runs, "usd": round(totals["usd"], 6), "skipped": skipped,
         "sha": totals["sha"]},
        separators=(",", ":"),
    )
    return "\n".join(
        [
            SECTION_START,
            f"💰 **This PR so far: ${totals['usd']:.4f}** over {runs} model check(s){skipped_text}. "
            f"{this_run}",
            "",
            f"- [ ] Re-check the design guidelines on the next push (or re-run of this workflow) "
            f"{BOX_MARKER}",
            "",
            f"<sub>Each check spends {cap}on the model. Later pushes are not re-checked until "
            "someone with write access ticks the box; it clears once the check has run.</sub>",
            f"<!-- guidelines-ledger {data} -->",
            "",
            "---",
            SECTION_END,
            "",
        ]
    )


def finalize(body: str, previous: str, sha: str, run_usd: float, checked: bool,
             max_cost: str) -> str:
    totals = ledger(previous) if previous.startswith(MARKER) else ledger("")
    if checked:
        totals["runs"] += 1
        totals["usd"] += run_usd
        totals["sha"] = sha
        this_run = f"This revision (`{short(sha)}`): ${run_usd:.4f}."
    else:
        this_run = f"This revision (`{short(sha)}`) asked the model nothing: $0."
    return place(strip(body), section(totals, this_run, max_cost))


def skipped(previous: str, sha: str, max_cost: str) -> str:
    totals = ledger(previous)
    totals["skipped"] += 1
    body = strip(previous)
    last = totals["sha"]
    from_text = f"from `{short(last)}`" if last else "from an earlier revision"
    banner = "\n".join(
        [
            BANNER_START,
            f"⏸️ **Not re-checked at `{short(sha)}`.** The findings below are {from_text} and may "
            "no longer match this revision. Tick the box below, then push "
            "again or re-run this workflow, to check it.",
            BANNER_END,
            "",
        ]
    )
    lines = body.split("\n")
    # After the marker and the heading, so the comment still opens on what it is.
    at = heading_end(lines)
    lines[at:at] = ["", banner]
    body = re.sub(r"\n{3,}", "\n\n", "\n".join(lines))
    this_run = f"This revision (`{short(sha)}`): not checked, $0."
    return place(body, section(totals, this_run, max_cost))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    g = sub.add_parser("gate")
    g.add_argument("--previous", type=Path)
    f = sub.add_parser("finalize")
    f.add_argument("--body", type=Path, required=True)
    f.add_argument("--previous", type=Path)
    f.add_argument("--sha", default="")
    f.add_argument("--run-usd", default="0")
    f.add_argument("--not-a-check", action="store_true")
    f.add_argument("--max-cost", default="")
    s = sub.add_parser("skipped")
    s.add_argument("--previous", type=Path, required=True)
    s.add_argument("--sha", default="")
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--max-cost", default="")
    args = parser.parse_args(argv)
    sha = getattr(args, "sha", "")
    sha = sha if SHA.match(sha or "") else ""
    max_cost = getattr(args, "max_cost", "")
    max_cost = max_cost if re.fullmatch(r"[0-9]+(\.[0-9]+)?", max_cost or "") else ""
    if args.command == "gate":
        print(gate(read(args.previous)))
        return 0
    if args.command == "finalize":
        body = read(args.body)
        if not body.startswith(MARKER):
            return 0
        args.body.write_text(
            finalize(body, read(args.previous), sha, _usd(args.run_usd), not args.not_a_check,
                     max_cost),
            encoding="utf-8",
        )
        return 0
    previous = read(args.previous)
    if not previous.startswith(MARKER):
        print("guidelines-ledger: no earlier comment to mark as skipped", file=sys.stderr)
        return 1
    args.out.write_text(skipped(previous, sha, max_cost), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
