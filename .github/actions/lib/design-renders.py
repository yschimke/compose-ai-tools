#!/usr/bin/env python3
"""The design-render lane's manifest and its sticky pull-request comment.

`compose-preview-server design render --local` draws a **UI-builder design document** — the thing a
person assembled in the builder, saved as JSON and committed — rather than a `@Preview` composable.
That is why this script exists beside `compare-previews.py` instead of inside it: the compose-preview
comparator is coupled to `compose-preview show --json`, a preview id and a Gradle module, and a
design has none of those. What the two share is the *shape* of the answer — a manifest pushed to a
branch, and a comment whose images are `raw.githubusercontent` URLs on it — so that shape is
reproduced here deliberately and the schemas are kept apart.

Two commands:

  manifest  <dir> --out _designs.json [--refusal id=reason ...]
      Hash every PNG/SVG a render lane produced and write the manifest the branch carries. A design
      the export REFUSED is a row too, carrying its reason and no file: a refusal is the lane's most
      useful output (a design that cannot be exported cannot be reviewed as pixels either), and one
      that vanished from the manifest would read as a design nobody drew.

  comment   --head _designs.json [--baseline _designs.json] --repo owner/name
            --head-branch <branch> [--baseline-branch <branch>] [--out comment.md]
      The sticky comment: what changed, what appeared, what went away, what the export refuses, and
      a count of what stayed put. Unchanged designs are counted rather than shown — a comment that
      renders nineteen identical pictures on every pull request teaches a reviewer to scroll past it.

Pure stdlib, like every script in this directory, and tested by `test_design_renders.py`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

SCHEMA = "compose-ui-builder-design-renders/v1"

#: What a design render may be. SVG is `design render --format svg`, which the CLI offers and which
#: GitHub renders inline in a comment, so both are first class here.
RENDER_SUFFIXES = (".png", ".svg")

#: The marker that makes the comment sticky. A caller finds its previous comment by this exact
#: string and edits it, so it must never carry a run id, a sha or a timestamp.
MARKER = "<!-- ui-builder-design-renders -->"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def _design_id(path: Path) -> str:
    """The design id a render file is named after.

    `design render -o <id>.png` is the CLI's own default, so the stem IS the id and nothing has to
    be threaded through the render loop to recover it.
    """
    return path.stem


def build_manifest(directory: Path, refusals: dict[str, str] | None = None) -> dict:
    """The manifest for a directory of renders, plus the refusals that produced no file."""
    refusals = dict(refusals or {})
    rows: list[dict] = []
    for path in sorted(directory.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in RENDER_SUFFIXES:
            continue
        design = _design_id(path)
        # A refusal that nevertheless wrote a file is a contradiction the lane should not paper
        # over: the file is real, so it wins, and the refusal is dropped rather than recorded
        # against a design that plainly drew.
        refusals.pop(design, None)
        rows.append(
            {
                "id": design,
                "file": path.relative_to(directory).as_posix(),
                "sha256": _sha256(path),
                "bytes": path.stat().st_size,
            }
        )
    for design, reason in sorted(refusals.items()):
        rows.append({"id": design, "refused": reason})
    return {"schema": SCHEMA, "designs": rows}


def _rows(manifest: dict) -> dict[str, dict]:
    return {row["id"]: row for row in manifest.get("designs", [])}


def _raw_url(repo: str, branch: str, path: str) -> str:
    return f"https://raw.githubusercontent.com/{repo}/{branch}/{path}"


def _image(alt: str, url: str) -> str:
    return f"![{alt}]({url})"


def build_comment(
    head: dict,
    baseline: dict | None,
    repo: str,
    head_branch: str,
    baseline_branch: str | None,
    title: str = "UI-builder design renders",
) -> str:
    """The sticky comment body for a head manifest against a baseline.

    With no baseline — the first run on a repository, or a lane whose branch has not been seeded —
    every design reads as added, which is the truthful answer rather than an empty comment.
    """
    head_rows = _rows(head)
    base_rows = _rows(baseline) if baseline else {}

    changed: list[tuple[dict, dict]] = []
    added: list[dict] = []
    refused: list[dict] = []
    unchanged = 0

    for design, row in sorted(head_rows.items()):
        if "refused" in row:
            refused.append(row)
            continue
        before = base_rows.get(design)
        if before is None or "refused" in before:
            added.append(row)
        elif before.get("sha256") != row.get("sha256"):
            changed.append((before, row))
        else:
            unchanged += 1

    removed = sorted(design for design in base_rows if design not in head_rows)

    lines = [MARKER, f"## {title}", ""]

    if not head_rows:
        lines += [
            "No design changed in this pull request, so nothing was rendered.",
            "",
            "_Rendered by `compose-preview-server design render --local`._",
        ]
        return "\n".join(lines) + "\n"

    if changed:
        lines += ["### Changed", "", "| Design | Before | After |", "| --- | --- | --- |"]
        for before, after in changed:
            before_url = _raw_url(repo, baseline_branch or head_branch, before["file"])
            after_url = _raw_url(repo, head_branch, after["file"])
            lines.append(
                f"| `{after['id']}` "
                f"| {_image(before['id'] + ' before', before_url)} "
                f"| {_image(after['id'] + ' after', after_url)} |"
            )
        lines.append("")

    if added:
        lines += ["### Added", "", "| Design | Render |", "| --- | --- |"]
        for row in added:
            url = _raw_url(repo, head_branch, row["file"])
            lines.append(f"| `{row['id']}` | {_image(row['id'], url)} |")
        lines.append("")

    if refused:
        # Named, with the reason, because this is the case a picture cannot show. A design the
        # export refuses is one no reviewer can look at, and the reason is the actionable half.
        lines += [
            "### Refused by the export",
            "",
            "| Design | Why |",
            "| --- | --- |",
        ]
        for row in refused:
            reason = str(row["refused"]).replace("\n", " ").replace("|", "\\|")
            lines.append(f"| `{row['id']}` | {reason} |")
        lines.append("")

    if removed:
        lines += ["### No longer rendered", "", *(f"- `{design}`" for design in removed), ""]

    if unchanged:
        lines += [f"{unchanged} design(s) unchanged.", ""]

    lines.append("_Rendered by `compose-preview-server design render --local`._")
    return "\n".join(lines) + "\n"


def cmd_manifest(args: argparse.Namespace) -> int:
    directory = Path(args.directory)
    if not directory.is_dir():
        print(f"design-renders: not a directory: {directory}", file=sys.stderr)
        return 1
    refusals: dict[str, str] = {}
    for entry in args.refusal or []:
        design, _, reason = entry.partition("=")
        if not design or not reason:
            print(f"design-renders: --refusal wants id=reason, got {entry!r}", file=sys.stderr)
            return 1
        refusals[design] = reason
    manifest = build_manifest(directory, refusals)
    Path(args.out).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    drawn = sum(1 for row in manifest["designs"] if "file" in row)
    print(f"design-renders: {drawn} rendered, {len(refusals)} refused -> {args.out}")
    return 0


def cmd_comment(args: argparse.Namespace) -> int:
    head = json.loads(Path(args.head).read_text(encoding="utf-8"))
    baseline = None
    if args.baseline:
        path = Path(args.baseline)
        # A missing baseline is the normal first run, not an error: the branch has not been seeded
        # yet, and a lane that failed here would fail on the very run that would create it.
        if path.is_file():
            baseline = json.loads(path.read_text(encoding="utf-8"))
        else:
            print(f"design-renders: no baseline at {path}, treating every design as added")
    body = build_comment(
        head=head,
        baseline=baseline,
        repo=args.repo,
        head_branch=args.head_branch,
        baseline_branch=args.baseline_branch,
        title=args.title,
    )
    if args.out:
        Path(args.out).write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    manifest = sub.add_parser("manifest", help="Hash a directory of renders into a manifest.")
    manifest.add_argument("directory")
    manifest.add_argument("--out", required=True)
    manifest.add_argument(
        "--refusal",
        action="append",
        metavar="ID=REASON",
        help="A design the export refused, and why. Repeatable.",
    )
    manifest.set_defaults(func=cmd_manifest)

    comment = sub.add_parser("comment", help="Build the sticky pull-request comment.")
    comment.add_argument("--head", required=True)
    comment.add_argument("--baseline")
    comment.add_argument("--repo", required=True, metavar="OWNER/NAME")
    comment.add_argument("--head-branch", required=True)
    comment.add_argument("--baseline-branch")
    comment.add_argument("--title", default="UI-builder design renders")
    comment.add_argument("--out")
    comment.set_defaults(func=cmd_comment)

    args = parser.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
