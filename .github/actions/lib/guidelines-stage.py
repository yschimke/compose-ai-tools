#!/usr/bin/env python3
"""Stage what the design-guidelines check needs into the fork-safe handoff.

The publish job holds the OpenRouter key but checks out the BASE branch, so it can neither render
nor read the PR's source. This runs in the render phase and copies, for every changed or new
preview (``_changed_previews.json`` from ``compare-previews.py copy-changed``), everything
``compose-preview guidelines`` needs in handoff mode:

    <out>/<module-key>/previews.json             the module manifest, narrowed to those previews,
                                                 with each capture's render rewritten into renders/
    <out>/<module-key>/renders/<name>.png        their renders
    <out>/<module-key>/src/<sourceFile>          their source files (the CLI extracts each function)
    <out>/<module-key>/accessibility.json        their accessibility nodes, when the a11y run made them
    <out>/<module-key>/ui-builder.guidelines.json the catalog's guidelines

A module without a guidelines file (``--guidelines-file`` overrides) stages nothing: there are no
rules to ask. Source is data the model reads, never executed, so staging it from a fork is safe.

The visual diff alone misses what the check now reads beyond pixels: an edit that adds a content
description or replaces a hard-coded colour can leave the render identical. ``--changed-files``
(the PR's changed paths, one per line, relative to the repository root) also selects every
preview whose ``sourceFile`` changed, and every preview of a module whose guidelines file changed
(the rules being judged changed, so every verdict may).

    guidelines-stage.py --changed _changed_previews.json --out _guidelines [--root .]
                        [--guidelines-file path] [--changed-files _pr_changed_files.txt]
Prints the number of previews staged.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

GUIDELINES_FILE = "ui-builder.guidelines.json"
SKIP_DIRS = {"node_modules", ".git", ".gradle"}


def find_manifests(root: Path) -> list[Path]:
    found = []
    for path in root.rglob("build/compose-previews/previews.json"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        found.append(path)
    return sorted(found)


def module_key(module_dir: Path, root: Path) -> str:
    rel = module_dir.resolve().relative_to(root.resolve()).as_posix()
    if rel in ("", "."):
        return "root"
    return re.sub(r"[^A-Za-z0-9._-]+", "_", rel)


def safe_relative(path: str) -> bool:
    candidate = Path(path)
    return not candidate.is_absolute() and ".." not in candidate.parts


def repo_relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def rules_changed(
    module_dir: Path, root: Path, guidelines_file: Path | None, changed_files: set[str]
) -> bool:
    """Whether the guidelines this module is judged against changed in the PR."""
    if guidelines_file is not None:
        return repo_relative(guidelines_file, root) in changed_files
    module = repo_relative(module_dir, root)
    authored = {GUIDELINES_FILE, f"{module}/{GUIDELINES_FILE}" if module != "." else GUIDELINES_FILE}
    return any(path in authored for path in changed_files)


def select(
    previews: list[dict],
    changed: set[str],
    changed_files: set[str],
    module_dir: Path,
    root: Path,
    all_of_module: bool,
) -> list[dict]:
    """The previews to check: changed renders, changed source files, or every one when the rules
    changed."""
    if all_of_module:
        return list(previews)
    module = repo_relative(module_dir, root)
    picked = []
    for preview in previews:
        source = preview.get("sourceFile")
        source_changed = (
            bool(source)
            and safe_relative(source)
            and (source if module == "." else f"{module}/{source}") in changed_files
        )
        if preview.get("id") in changed or source_changed:
            picked.append(preview)
    return picked


def stage(
    root: Path,
    changed: set[str],
    out: Path,
    guidelines_file: Path | None,
    changed_files: set[str] | None = None,
) -> int:
    changed_files = changed_files or set()
    staged = 0
    for manifest_path in find_manifests(root):
        previews_dir = manifest_path.parent
        module_dir = previews_dir.parent.parent
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        selected = select(
            manifest.get("previews", []),
            changed,
            changed_files,
            module_dir,
            root,
            rules_changed(module_dir, root, guidelines_file, changed_files),
        )
        if not selected:
            continue
        rules = guidelines_file or previews_dir / GUIDELINES_FILE
        if not rules.is_file():
            continue
        target = out / module_key(module_dir, root)
        (target / "renders").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(rules, target / GUIDELINES_FILE)

        kept = []
        for preview in selected:
            captures = []
            for capture in preview.get("captures", []):
                output = capture.get("renderOutput")
                if not output or not safe_relative(output):
                    continue
                source_png = previews_dir / output
                if not source_png.is_file():
                    continue
                name = Path(output).name
                shutil.copyfile(source_png, target / "renders" / name)
                captures.append({**capture, "renderOutput": f"renders/{name}"})
            if not captures:
                continue
            source_file = preview.get("sourceFile")
            if source_file and safe_relative(source_file) and (module_dir / source_file).is_file():
                dest = target / "src" / source_file
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(module_dir / source_file, dest)
            kept.append({**preview, "captures": captures})
            staged += 1
        (target / "previews.json").write_text(
            json.dumps({**manifest, "previews": kept}, indent=2) + "\n", encoding="utf-8"
        )

        a11y = previews_dir / "accessibility.json"
        if a11y.is_file():
            try:
                report = json.loads(a11y.read_text(encoding="utf-8"))
                ids = {p["id"] for p in kept}
                report["entries"] = [
                    e for e in report.get("entries", []) if e.get("previewId") in ids
                ]
                (target / "accessibility.json").write_text(
                    json.dumps(report, indent=2) + "\n", encoding="utf-8"
                )
            except (OSError, json.JSONDecodeError, KeyError, TypeError):
                pass
    return staged


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--changed", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--root", default=".")
    ap.add_argument("--guidelines-file")
    ap.add_argument("--changed-files")
    args = ap.parse_args()
    changed: set[str] = set()
    changed_path = Path(args.changed)
    if changed_path.is_file() and changed_path.stat().st_size > 0:
        try:
            changed = {
                e["previewId"] for e in json.loads(changed_path.read_text(encoding="utf-8"))
            }
        except (json.JSONDecodeError, KeyError, TypeError):
            print("guidelines-stage: unreadable changed list", file=sys.stderr)
    changed_files: set[str] = set()
    if args.changed_files and Path(args.changed_files).is_file():
        changed_files = {
            line.strip()
            for line in Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        }
    if not changed and not changed_files:
        print(0)
        return 0
    rules = Path(args.guidelines_file) if args.guidelines_file else None
    print(stage(Path(args.root), changed, Path(args.out), rules, changed_files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
