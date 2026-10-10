#!/usr/bin/env python3
"""Render the sticky ``<!-- guidelines-report -->`` PR comment from ``compose-preview guidelines``.

Reads every ``<dir>/<module>/guidelines.json`` (the CLI's handoff-mode output) beside that module's
``ui-builder.guidelines.json`` (for each rule's severity and source guide) and writes Markdown: per
checked preview, its findings with the rule, reason, confidence, the nodes it names and a link to
the guide, then who checked it and what it cost.

Each preview with findings shows one picture: the render with its findings marked
(``<render>.guidelines.png``, which ``compose-preview guidelines --annotate`` writes) when a finding
names a node or a region on it, otherwise the render itself, captioned as having nothing marked.
A preview two module directories both hold is reported once.

Images are embedded only from a GitHub-hosted, commit-pinned location (``--image-repo`` and
``--image-ref``, with the pictures pushed under ``--image-prefix/<module>/``), as the a11y comment
does: other hosts are stripped from PR bodies. ``--stage-images <dir>`` copies exactly the pictures
the comment embeds into ``<dir>/<prefix>/<module>/`` for that push, so the action runs this twice:
once to stage, once with the pushed commit's SHA.

    guidelines-report.py --dir _guidelines --out _guidelines_comment.md [--stage-images <dir>]
                         [--image-repo org/name --image-ref <sha> --image-prefix guidelines]
Writes nothing (and exits 0) when no module produced results.
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
IMAGE_WIDTH = 200
# The pictures come from the PR's render job; bound what one comment pushes and embeds.
MAX_IMAGES = 40
MAX_IMAGE_BYTES = 4 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


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


def marks(found: list[dict], preview_id: str, nodes: set[str]) -> int:
    """How many things the annotator draws for these findings: each node it names that the render
    has, and each region on this preview's picture."""
    count = 0
    for verdict in found:
        count += sum(1 for n in verdict.get("nodeIds") or [] if n in nodes)
        for region in verdict.get("regions") or []:
            # A region on another picture (the long screenshot) is not drawn on the render.
            if (
                isinstance(region, dict)
                and region.get("subjectId") in (None, preview_id)
                and region.get("pictureKind") in (None, "device")
            ):
                count += 1
    return count


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


def build(dir_: Path, args: argparse.Namespace) -> str | None:
    modules = sorted(p for p in dir_.iterdir() if p.is_dir()) if dir_.is_dir() else []
    stage_dir = Path(args.stage_images) if getattr(args, "stage_images", None) else None
    sections: list[str] = []
    total_checked = total_findings = images = 0
    total_cost = 0.0
    models: set[str] = set()
    seen: set[str] = set()
    for module_dir in modules:
        if not SAFE_NAME.match(module_dir.name):
            continue
        loaded = load(module_dir)
        if loaded is None:
            continue
        report, rules = loaded
        names = render_names(module_dir)
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
            total_checked += 1
            if record.get("servedModel"):
                models.add(record["servedModel"])
            found = failures(record)
            unchecked = result.get("unchecked", [])
            if not found and not unchecked:
                continue
            total_findings += len(found)
            lines = [f"#### `{preview_id.rsplit('.', 1)[-1]}`", f"<sub>`{preview_id}`</sub>", ""]
            marked = marks(found, preview_id, nodes.get(preview_id, set()))
            shown = picture(module_dir, names.get(preview_id), marked) if found else None
            if shown and images < MAX_IMAGES:
                images += 1
                if stage_dir is not None:
                    prefix = (args.image_prefix or "").strip("/")
                    dest = stage_dir / prefix / module_dir.name if prefix else stage_dir / module_dir.name
                    dest.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(module_dir / "renders" / shown, dest / shown)
                url = image_url(args, module_dir.name, shown)
                if url:
                    caption = (
                        f"{marked} finding location(s) marked on the render."
                        if shown.endswith(".guidelines.png")
                        else "Nothing marked: no finding names a node or region on this render."
                    )
                    lines += [f'<img src="{url}" width="{IMAGE_WIDTH}" />', "", f"<sub>{caption}</sub>", ""]
            for verdict in found:
                rule = rules.get(verdict.get("ruleId"), {})
                severity = rule.get("severity", "warning")
                icon = "⚠️" if severity == "warning" else "ℹ️"
                nodes_named = verdict.get("nodeIds") or []
                node_text = f" on {', '.join(f'`{n}`' for n in nodes_named)}" if nodes_named else ""
                guide = rule.get("source")
                guide_text = f" ([guide]({guide}))" if guide and guide.startswith("https://") else ""
                confidence = int(float(verdict.get("confidence", 0)) * 100)
                lines.append(
                    f"- {icon} **{verdict.get('ruleId')}**{node_text} ({confidence}%): "
                    f"{verdict.get('reason', '').strip()}{guide_text}"
                )
            if unchecked:
                lines.append(f"- ❔ Unchecked (needs evidence this run could not get): "
                             f"{', '.join(f'`{u}`' for u in unchecked)}")
            sections.append("\n".join(lines) + "\n")
    if total_checked == 0:
        return None
    model_text = ", ".join(sorted(models)) or "the configured model"
    header = [
        MARKER,
        "### Design guidelines",
        "",
        f"{total_checked} changed preview(s) checked against their catalog's design guidelines; "
        f"**{total_findings} finding(s)**. Checked by {model_text} · ${total_cost:.4f}.",
        "",
        "<sub>Findings are advice from a model judging the render, its source and its "
        "accessibility nodes; each links the guide it comes from.</sub>",
        "",
    ]
    if not sections:
        header.append("No findings. ✅")
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
