#!/usr/bin/env python3
"""Render the sticky ``<!-- guidelines-report -->`` PR comment from ``compose-preview guidelines``.

Reads every ``<dir>/<module>/guidelines.json`` (the CLI's handoff-mode output) beside that module's
``ui-builder.guidelines.json`` (for each rule's severity and source guide) and writes Markdown: per
checked preview, its findings with the rule, reason, confidence, the nodes it names and a link to
the guide, then who checked it and what it cost.

Images are embedded only from a GitHub-hosted, commit-pinned location (``--image-repo`` and
``--image-ref``, with annotated renders pushed under ``--image-prefix/<module>/``), as the a11y
comment does: other hosts are stripped from PR bodies.

    guidelines-report.py --dir _guidelines --out _guidelines_comment.md
                         [--image-repo org/name --image-ref <sha> --image-prefix guidelines]
Writes nothing (and exits 0) when no module produced results.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

MARKER = "<!-- guidelines-report -->"
MIN_CONFIDENCE = 0.5


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


def image_url(args: argparse.Namespace, module: str, preview_id: str, render_names: dict) -> str | None:
    if not (args.image_repo and args.image_ref):
        return None
    name = render_names.get(preview_id)
    if not name:
        return None
    annotated = name[: -len(".png")] + ".guidelines.png" if name.endswith(".png") else None
    if not annotated:
        return None
    prefix = (args.image_prefix or "guidelines").strip("/")
    return (
        f"https://raw.githubusercontent.com/{args.image_repo}/{args.image_ref}/"
        f"{prefix}/{module}/{annotated}"
    )


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
    sections: list[str] = []
    total_checked = total_findings = 0
    total_cost = 0.0
    models: set[str] = set()
    for module_dir in modules:
        loaded = load(module_dir)
        if loaded is None:
            continue
        report, rules = loaded
        names = render_names(module_dir)
        results = report.get("results", [])
        total_checked += len(results)
        for result in results:
            record = result.get("record", {})
            total_cost += float(record.get("costUsd") or 0.0)
            if record.get("servedModel"):
                models.add(record["servedModel"])
            found = failures(record)
            unchecked = result.get("unchecked", [])
            if not found and not unchecked:
                continue
            total_findings += len(found)
            preview_id = result.get("previewId", "?")
            lines = [f"#### `{preview_id.rsplit('.', 1)[-1]}`", f"<sub>`{preview_id}`</sub>", ""]
            url = image_url(args, module_dir.name, preview_id, names)
            if url:
                lines += [f'<img src="{url}" width="240" />', ""]
            for verdict in found:
                rule = rules.get(verdict.get("ruleId"), {})
                severity = rule.get("severity", "warning")
                icon = "⚠️" if severity == "warning" else "ℹ️"
                nodes = verdict.get("nodeIds") or []
                node_text = f" on {', '.join(f'`{n}`' for n in nodes)}" if nodes else ""
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
            sections.append("\n".join(lines))
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
    args = ap.parse_args()
    body = build(Path(args.dir), args)
    if body is None:
        print("guidelines-report: no results; no comment.", file=sys.stderr)
        return 0
    Path(args.out).write_text(body, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
