#!/usr/bin/env python3
"""Stage what the design-guidelines check needs into the fork-safe handoff.

The publish job holds the OpenRouter key but checks out the BASE branch, so it can neither render
nor read the PR's source. This runs in the render phase and copies, for every changed or new
preview (``_changed_previews.json`` from ``compare-previews.py copy-changed``), everything
``compose-preview guidelines`` needs in handoff mode:

    <out>/<module-key>/previews.json             the module manifest, narrowed to those previews,
                                                 with each capture's render rewritten into renders/
    <out>/<module-key>/renders/<name>.png        their renders (and <name>_SCROLL_long.png, the
                                                 long screenshot of a scrolled capture, when the
                                                 renderer wrote one)
    <out>/<module-key>/src/<sourceFile>          their source files (the CLI extracts each function),
                                                 and the module files declaring the composables those
                                                 files call, so a wrapper defined elsewhere in the
                                                 module reaches the CLI's source index (bounded)
    <out>/<module-key>/accessibility.json        their accessibility nodes, when the a11y run made them
    <out>/<module-key>/ui-builder.guidelines.json the catalog's guidelines

A module without a guidelines file (``--guidelines-file`` overrides) stages nothing: there are no
rules to ask. A preview id two modules both discovered (a desktop module re-rendering a
multiplatform module's previews) is staged once, in the module holding its source (``owners``). Source is data the model reads, never executed, so staging it from a fork is safe.

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
LONG_SUFFIX = "_SCROLL_long.png"
MAX_LONG_BYTES = 2 * 1024 * 1024
SKIP_DIRS = {"node_modules", ".git", ".gradle"}
# Source scans also skip build outputs, which hold generated copies rather than the module's code.
SOURCE_SKIP_DIRS = SKIP_DIRS | {"build"}

# Calls that may name a module composable: a capitalised name followed by `(` or `{`.
CALL = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*[({]")
DECLARATION = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Z][A-Za-z0-9_]*)\s*\(")
# Bounds on the callee files staged per module, so a fork cannot make the handoff large.
MAX_CALLEE_FILES = 40
MAX_CALLEE_BYTES = 512 * 1024


def module_declarations(module_dir: Path) -> dict[str, list[Path]]:
    """Composable-looking `fun Name(` declarations in the module's Kotlin sources, by name, as the
    CLI's `SourceIndex` finds them."""
    found: dict[str, list[Path]] = {}
    src = module_dir / "src"
    if not src.is_dir():
        return found
    for path in sorted(src.rglob("*.kt")):
        if any(part in SOURCE_SKIP_DIRS for part in path.parts) or path.is_symlink():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for name in DECLARATION.findall(text):
            found.setdefault(name, []).append(path)
    return found


def stage_callees(module_dir: Path, sources: list[Path], target: Path) -> int:
    """Copies, under `target/src/`, the module files declaring composables that `sources` call
    (one level), within MAX_CALLEE_FILES and MAX_CALLEE_BYTES. Returns the number copied."""
    if not sources:
        return 0
    declarations = module_declarations(module_dir)
    wanted: list[Path] = []
    for source in sources:
        try:
            text = source.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for name in dict.fromkeys(CALL.findall(text)):
            for path in declarations.get(name, []):
                if path not in wanted and path not in sources:
                    wanted.append(path)
    copied = 0
    budget = MAX_CALLEE_BYTES
    for path in wanted[:MAX_CALLEE_FILES]:
        size = path.stat().st_size
        if size > budget:
            continue
        relative = path.relative_to(module_dir)
        dest = target / "src" / relative
        if dest.exists():
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, dest)
        budget -= size
        copied += 1
    return copied


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


def has_source(preview: dict, module_dir: Path) -> bool:
    """Whether the preview's source is inside its module, so it can be staged with it."""
    source = preview.get("sourceFile")
    return bool(source) and safe_relative(source) and (module_dir / source).is_file()


def owners(candidates: list[tuple[Path, Path, dict, list[dict]]]) -> dict[str, Path]:
    """The one module each preview id is checked in.

    The same composable can be discovered by more than one module: a desktop module re-rendering a
    multiplatform module's previews has the same ids, its own renders, and a `sourceFile` reaching
    into the other module (`../catalog/src/...`), which is never staged. Checking both asks the
    model twice and lists the preview twice, once judged without its source. The module that holds
    the source wins, then the one with accessibility nodes (findings can then name them), then the
    first in path order.
    """
    best: dict[str, tuple[tuple[int, int], Path]] = {}
    for previews_dir, module_dir, _manifest, selected in candidates:
        has_nodes = (previews_dir / "accessibility.json").is_file()
        for preview in selected:
            preview_id = preview.get("id")
            if not preview_id:
                continue
            score = (int(has_source(preview, module_dir)), int(has_nodes))
            if preview_id not in best or score > best[preview_id][0]:
                best[preview_id] = (score, module_dir)
    return {preview_id: module_dir for preview_id, (_score, module_dir) in best.items()}


def stage(
    root: Path,
    changed: set[str],
    out: Path,
    guidelines_file: Path | None,
    changed_files: set[str] | None = None,
) -> int:
    changed_files = changed_files or set()
    staged = 0
    candidates: list[tuple[Path, Path, dict, list[dict]]] = []
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
        candidates.append((previews_dir, module_dir, manifest, selected))

    owner = owners(candidates)
    for previews_dir, module_dir, manifest, selected in candidates:
        selected = [p for p in selected if owner.get(p.get("id")) == module_dir]
        if not selected:
            continue
        rules = guidelines_file or previews_dir / GUIDELINES_FILE
        target = out / module_key(module_dir, root)
        (target / "renders").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(rules, target / GUIDELINES_FILE)

        kept = []
        staged_sources: list[Path] = []
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
                # The whole scrolling content beside a scrolled capture: the check sends it, so
                # content scrolled out of view is not read as clipped.
                long = source_png.with_name(source_png.stem + LONG_SUFFIX)
                if long.is_file() and not long.is_symlink() and long.stat().st_size <= MAX_LONG_BYTES:
                    shutil.copyfile(long, target / "renders" / long.name)
                captures.append({**capture, "renderOutput": f"renders/{name}"})
            if not captures:
                continue
            if has_source(preview, module_dir):
                source_file = preview["sourceFile"]
                dest = target / "src" / source_file
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(module_dir / source_file, dest)
                if module_dir / source_file not in staged_sources:
                    staged_sources.append(module_dir / source_file)
            kept.append({**preview, "captures": captures})
            staged += 1
        stage_callees(module_dir, staged_sources, target)
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
        elif kept:
            # Without nodes a finding can name none; it can still point at a region of the render.
            print(
                f"guidelines-stage: no accessibility nodes for {module_key(module_dir, root)} "
                "(the a11y pipeline did not run); findings can be marked by region only.",
                file=sys.stderr,
            )
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
