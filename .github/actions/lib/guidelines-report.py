#!/usr/bin/env python3
"""Render the sticky ``<!-- guidelines-report -->`` PR comment from ``compose-preview guidelines``.

Reads every ``<dir>/<module>/guidelines.json`` (the CLI's handoff-mode output) beside that module's
``ui-builder.guidelines.json`` (for each rule's severity and source guide) and writes Markdown: per
checked preview, its findings with the rule, reason, confidence, the nodes it names and a link to
the guide, then who checked it and what it cost.

Each preview with findings shows one picture: the render with its findings marked
(``<render>.guidelines.png``, which ``compose-preview guidelines --annotate`` writes) when a finding
names a node or a region on it, otherwise the render itself, captioned as having nothing marked.
The annotator outlines each mark and puts a numbered badge beside it, numbering the findings that
have a mark in the order they are listed here; the list carries the same numbers, so the picture
needs no text of its own. The picture links to its full-size file. A preview two module directories
both hold is reported once.

A run that judged nothing is never reported as a pass: previews whose request failed (or hit the
cost cap), previews no rule applies to (``noRules``) and modules the check wrote no results for are
listed as NOT checked, with the engine's problem lines quoted.

Images are embedded only from a GitHub-hosted, commit-pinned location (``--image-repo`` and
``--image-ref``, with the pictures pushed under ``--image-prefix/<module>/``), as the a11y comment
does: other hosts are stripped from PR bodies. ``--stage-images <dir>`` copies exactly the pictures
the comment embeds into ``<dir>/<prefix>/<module>/`` for that push, so the action runs this twice:
once to stage, once with the pushed commit's SHA.

    guidelines-report.py --dir _guidelines --out _guidelines_comment.md [--stage-images <dir>]
                         [--image-repo org/name --image-ref <sha> --image-prefix guidelines]
Writes nothing (and exits 0) when no module was staged.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

MARKER = "<!-- guidelines-report -->"
MIN_CONFIDENCE = 0.5
# Wide enough that the annotator's badges (7.5% of the picture's width) read at ~24px.
IMAGE_WIDTH = 320
# The pictures come from the PR's render job; bound what one comment pushes and embeds.
MAX_IMAGES = 40
MAX_IMAGE_BYTES = 4 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# The PNG tEXt entry `GuidelineAnnotator` writes on an overlay with numbered badges. An overlay from
# an older CLI (the action can install one) has rule-id labels instead, so its findings get no
# numbers rather than numbers the picture does not show.
OVERLAY_FORMAT = (b"compose-preview-guidelines-overlay", b"numbered-v1")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
# The engine's own problem lines, quoted so a failed or empty run says why.
MAX_PROBLEMS = 10
MAX_PROBLEM_CHARS = 300


def load(module_dir: Path) -> tuple[dict, dict] | None:
    result = module_dir / "guidelines.json"
    rules_file = module_dir / "ui-builder.guidelines.json"
    if not result.is_file():
        return None
    try:
        report = json.loads(result.read_text(encoding="utf-8"))
        rules = json.loads(rules_file.read_text(encoding="utf-8")) if rules_file.is_file() else {}
    except (OSError, json.JSONDecodeError):
        return None
    by_id = {r.get("id"): r for r in rules.get("rules", []) if isinstance(r, dict)}
    return report, by_id


def failures(record: dict) -> list[dict]:
    found = [
        v for v in record.get("verdicts", [])
        if v.get("verdict") == "fail" and float(v.get("confidence", 0)) >= MIN_CONFIDENCE
    ]
    return sorted(found, key=lambda v: -float(v.get("confidence", 0)))


def image_url(args: argparse.Namespace, module: str, name: str) -> str | None:
    if not (args.image_repo and args.image_ref):
        return None
    prefix = (args.image_prefix or "").strip("/")
    path = f"{prefix}/{module}/{name}" if prefix else f"{module}/{name}"
    return f"https://raw.githubusercontent.com/{args.image_repo}/{args.image_ref}/{path}"


def node_ids(module_dir: Path) -> dict[str, set[str]]:
    """Per preview, the ids its accessibility nodes go by — what a verdict's `nodeIds` can name and
    the annotator can outline (`ref`, else the node's position, as the CLI numbers them)."""
    try:
        report = json.loads((module_dir / "accessibility.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    ids: dict[str, set[str]] = {}
    entries = report.get("entries") if isinstance(report, dict) else None
    for entry in entries if isinstance(entries, list) else []:
        if not isinstance(entry, dict) or not isinstance(entry.get("nodes"), list):
            continue
        found = ids.setdefault(str(entry.get("previewId")), set())
        for index, node in enumerate(entry["nodes"]):
            if not isinstance(node, dict):
                continue
            bounds = str(node.get("boundsInScreen") or "").split(",")
            if len(bounds) == 4 and all(b.strip().lstrip("-").isdigit() for b in bounds):
                found.add(str(node.get("ref") or f"n{index}"))
    return ids


def finding_marks(verdict: dict, preview_id: str, nodes: set[str]) -> int:
    """How many things the annotator draws for one finding: each node it names that the render
    has, and each region on this preview's picture."""
    count = sum(1 for n in verdict.get("nodeIds") or [] if n in nodes)
    for region in verdict.get("regions") or []:
        # A region on another picture (the long screenshot) is not drawn on the render.
        if (
            isinstance(region, dict)
            and region.get("subjectId") in (None, preview_id)
            and region.get("pictureKind") in (None, "device")
        ):
            count += 1
    return count


def marks(found: list[dict], preview_id: str, nodes: set[str]) -> int:
    """How many things the annotator draws for these findings."""
    return sum(finding_marks(v, preview_id, nodes) for v in found)


def badge_numbers(found: list[dict], preview_id: str, nodes: set[str]) -> list[int | None]:
    """The number on each finding's badges, as `GuidelineAnnotator` assigns them: findings in the
    order listed, counting only those with something drawn; None for a finding with no mark."""
    numbers: list[int | None] = []
    next_number = 1
    for verdict in found:
        if finding_marks(verdict, preview_id, nodes):
            numbers.append(next_number)
            next_number += 1
        else:
            numbers.append(None)
    return numbers


def picture(module_dir: Path, render: str | None, marked: int) -> str | None:
    """The file to show for a preview: the annotated render when something is marked on it, else
    the render. None when neither is a plausible PNG of a reasonable size."""
    if not render or not render.endswith(".png") or not SAFE_NAME.match(render):
        return None
    annotated = render[: -len(".png")] + ".guidelines.png"
    for name in ([annotated] if marked else []) + [render]:
        path = module_dir / "renders" / name
        try:
            if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_IMAGE_BYTES:
                continue
            with path.open("rb") as f:
                if f.read(len(PNG_SIGNATURE)) != PNG_SIGNATURE:
                    continue
        except OSError:
            continue
        return name
    return None


def numbered_overlay(path: Path) -> bool:
    """Whether the PNG at ``path`` declares the numbered-badge overlay format: a ``tEXt`` chunk
    before the image data, as the annotator writes it."""
    try:
        with path.open("rb") as f:
            if f.read(len(PNG_SIGNATURE)) != PNG_SIGNATURE:
                return False
            for _ in range(64):
                header = f.read(8)
                if len(header) < 8:
                    return False
                length = int.from_bytes(header[:4], "big")
                kind = header[4:]
                if kind in (b"IDAT", b"IEND") or length > 1 << 16:
                    return False
                data = f.read(length)
                f.read(4)  # CRC
                if kind == b"tEXt" and tuple(data.split(b"\0", 1)) == OVERLAY_FORMAT:
                    return True
    except OSError:
        return False
    return False


def render_names(module_dir: Path) -> dict:
    manifest = module_dir / "previews.json"
    names = {}
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return names
    for preview in data.get("previews", []):
        for capture in preview.get("captures", []):
            output = capture.get("renderOutput")
            if output:
                names[preview.get("id")] = Path(output).name
                break
    return names


def staged_ids(module_dir: Path) -> list[str]:
    """The preview ids a module's staged `previews.json` lists: what the check was handed."""
    try:
        data = json.loads((module_dir / "previews.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    previews = data.get("previews") if isinstance(data, dict) else None
    return [
        str(p["id"]) for p in previews if isinstance(p, dict) and p.get("id")
    ] if isinstance(previews, list) else []


def short(preview_id: str) -> str:
    return f"`{preview_id.rsplit('.', 1)[-1]}`"


def names(ids: list[str], limit: int = 8) -> str:
    shown = ", ".join(short(i) for i in ids[:limit])
    return shown + (f" and {len(ids) - limit} more" if len(ids) > limit else "")


def quoted(problem: str) -> str:
    """One engine problem line, safe inside a fenced block: it can carry a model's reply."""
    text = " ".join(str(problem).split()).replace("`", "'")
    return text[:MAX_PROBLEM_CHARS] + ("…" if len(text) > MAX_PROBLEM_CHARS else "")


def build(dir_: Path, args: argparse.Namespace) -> str | None:
    modules = sorted(p for p in dir_.iterdir() if p.is_dir()) if dir_.is_dir() else []
    stage_dir = Path(args.stage_images) if getattr(args, "stage_images", None) else None
    sections: list[str] = []
    total_checked = total_findings = images = 0
    total_cost = 0.0
    models: set[str] = set()
    seen: set[str] = set()
    # What was handed to the check and never judged, by why: a request that failed or the cost cap
    # (`pending`), no rule applying (`noRules`, by reason), or a module the check wrote nothing for.
    pending: list[str] = []
    no_rules: dict[str, list[str]] = {}
    no_results: list[str] = []
    failed_requests = 0
    problems: list[str] = []
    for module_dir in modules:
        if not SAFE_NAME.match(module_dir.name):
            continue
        loaded = load(module_dir)
        if loaded is None:
            # Staged but no readable results: the check failed, or never reached this module.
            for preview_id in staged_ids(module_dir):
                if preview_id not in seen:
                    seen.add(preview_id)
                    no_results.append(preview_id)
            continue
        report, rules = loaded
        failed_requests += int(report.get("failedRequests") or 0)
        for problem in report.get("problems") or []:
            if quoted(problem) not in problems:
                problems.append(quoted(problem))
        names_by_id = render_names(module_dir)
        nodes = node_ids(module_dir)
        results = report.get("results", [])
        for result in results:
            record = result.get("record", {})
            total_cost += float(record.get("costUsd") or 0.0)
            preview_id = result.get("previewId", "?")
            # A preview staged under two module directories is the same composable; report it once.
            if preview_id in seen:
                continue
            seen.add(preview_id)
            if result.get("noRules"):
                no_rules.setdefault(str(result["noRules"]), []).append(preview_id)
                continue
            if result.get("pending"):
                pending.append(preview_id)
                continue
            total_checked += 1
            if record.get("servedModel"):
                models.add(record["servedModel"])
            found = failures(record)
            unchecked = result.get("unchecked", [])
            if not found and not unchecked:
                continue
            total_findings += len(found)
            lines = [f"#### `{preview_id.rsplit('.', 1)[-1]}`", f"<sub>`{preview_id}`</sub>", ""]
            preview_nodes = nodes.get(preview_id, set())
            marked = marks(found, preview_id, preview_nodes)
            shown = picture(module_dir, names_by_id.get(preview_id), marked) if found else None
            numbered = False
            if shown and images < MAX_IMAGES:
                images += 1
                if stage_dir is not None:
                    prefix = (args.image_prefix or "").strip("/")
                    dest = stage_dir / prefix / module_dir.name if prefix else stage_dir / module_dir.name
                    dest.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(module_dir / "renders" / shown, dest / shown)
                url = image_url(args, module_dir.name, shown)
                if url:
                    annotated = shown.endswith(".guidelines.png")
                    numbered = annotated and numbered_overlay(module_dir / "renders" / shown)
                    caption = (
                        f"{marked} finding location(s) marked on the render."
                        if annotated
                        else "Nothing marked: no finding names a node or region on this render."
                    )
                    lines += [
                        f'<a href="{url}"><img src="{url}" width="{IMAGE_WIDTH}" /></a>',
                        "",
                        f"<sub>{caption}</sub>",
                        "",
                    ]
            # Numbers only when the numbered picture is the one shown; otherwise they point nowhere.
            numbers = (
                badge_numbers(found, preview_id, preview_nodes) if numbered else [None] * len(found)
            )
            for verdict, number in zip(found, numbers):
                rule = rules.get(verdict.get("ruleId"), {})
                severity = rule.get("severity", "warning")
                icon = "⚠️" if severity == "warning" else "ℹ️"
                nodes_named = verdict.get("nodeIds") or []
                node_text = f" on {', '.join(f'`{n}`' for n in nodes_named)}" if nodes_named else ""
                guide = rule.get("source")
                guide_text = f" ([guide]({guide}))" if guide and guide.startswith("https://") else ""
                confidence = int(float(verdict.get("confidence", 0)) * 100)
                badge = f"**{number}** " if number is not None else ""
                lines.append(
                    f"- {badge}{icon} **{verdict.get('ruleId')}**{node_text} ({confidence}%): "
                    f"{verdict.get('reason', '').strip()}{guide_text}"
                )
            if unchecked:
                lines.append(f"- ❔ Unchecked (needs evidence this run could not get): "
                             f"{', '.join(f'`{u}`' for u in unchecked)}")
            sections.append("\n".join(lines) + "\n")
    not_checked = len(pending) + sum(len(v) for v in no_rules.values()) + len(no_results)
    if total_checked == 0 and not_checked == 0:
        return None
    model_text = ", ".join(sorted(models)) or "the configured model"
    header = [MARKER, "### Design guidelines", ""]
    if total_checked:
        header += [
            f"{total_checked} changed preview(s) checked against their catalog's design guidelines; "
            f"**{total_findings} finding(s)**. Checked by {model_text} · ${total_cost:.4f}.",
            "",
        ]
    else:
        # Nothing was judged: say so, never a pass.
        header += [
            f"❌ **Not checked.** None of the {not_checked} changed preview(s) was judged against "
            f"its catalog's design guidelines, so this is not a pass. ${total_cost:.4f} spent.",
            "",
        ]
    if not_checked:
        header.append(f"**{not_checked} preview(s) were NOT checked:**")
        if pending:
            header.append(
                f"- {len(pending)} because their model request failed or the cost cap was reached"
                + (f" ({failed_requests} request(s) failed)" if failed_requests else "")
                + f": {names(pending)}"
            )
        for reason, ids in no_rules.items():
            header.append(f"- {len(ids)} because {quoted(reason)}: {names(ids)}")
        if no_results:
            header.append(
                f"- {len(no_results)} because the check wrote no results for them (it failed "
                f"before answering, or the budget ran out first): {names(no_results)}"
            )
        header.append("")
    if problems:
        header += ["Problems the check reported:", "", "```text"]
        header += problems[:MAX_PROBLEMS]
        if len(problems) > MAX_PROBLEMS:
            header.append(f"… and {len(problems) - MAX_PROBLEMS} more")
        header += ["```", ""]
    header += [
        "<sub>Findings are advice from a model judging the render, its source and its "
        "accessibility nodes; each links the guide it comes from.</sub>",
        "",
    ]
    if not sections and total_checked:
        header.append(
            "No findings. ✅" if not not_checked
            else f"No findings in the {total_checked} preview(s) that were checked."
        )
    return "\n".join(header + sections).rstrip() + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--image-repo")
    ap.add_argument("--image-ref")
    ap.add_argument("--image-prefix", default="guidelines")
    ap.add_argument("--stage-images", help="copy the pictures the comment embeds under this dir")
    args = ap.parse_args()
    body = build(Path(args.dir), args)
    if body is None:
        print("guidelines-report: no results; no comment.", file=sys.stderr)
        return 0
    Path(args.out).write_text(body, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
