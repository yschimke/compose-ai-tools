#!/usr/bin/env python3
"""The previews a pull request's a11y run checks: the ones the PR changed.

Running the Accessibility Test Framework over a whole catalog on every PR is a misconfiguration,
not a cost to tune: m3-catalog PR #544 changed one dialog (13 previews) and spent 3,779s checking
all 4,139 of its previews, one at a time. So on a PR the a11y pipeline checks exactly the previews
the PR changed, by the same two rules the design-guidelines check selects them with:

* every preview the compose pipeline's visual diff found new or changed
  (``_changed_previews.json``, written by ``compare-previews.py copy-changed``), and
* every preview whose ``sourceFile`` the PR changed (``--changed-files``, the PR's changed paths
  relative to the repository root), since an edit to a content description or a role can leave
  the render identical. Read off each module's ``build/compose-previews/previews.json``, whose
  ``sourceFile`` is module-relative.

Writes the ids to ``--out``, one per line, for ``compose-preview a11y --id-file``, and prints how
many. Prints ``full`` and writes nothing when the selection cannot be trusted to be complete:
``_changed_previews.json`` is missing (the compose pipeline did not run in this job, or failed
before its diff), so there is no visual diff to scope by. The caller then checks every preview of
the affected modules, as before.

    a11y-scope.py --changed _changed_previews.json --out _a11y_preview_ids.txt
                  [--changed-files _pr_changed_files.txt] [--root .]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SKIP_DIRS = {"node_modules", ".git", ".gradle"}


def find_manifests(root: Path) -> list[Path]:
    found = []
    for path in root.rglob("build/compose-previews/previews.json"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        found.append(path)
    return sorted(found)


def safe_relative(path: str) -> bool:
    candidate = Path(path)
    return not candidate.is_absolute() and ".." not in candidate.parts


def module_relative_dir(module_dir: Path, root: Path) -> str:
    try:
        rel = module_dir.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return module_dir.as_posix()
    return "." if rel in ("", ".") else rel


def source_changed_ids(root: Path, changed_files: set[str]) -> list[str]:
    """Ids of previews whose module-relative ``sourceFile`` is among [changed_files]."""
    if not changed_files:
        return []
    ids: list[str] = []
    for manifest_path in find_manifests(root):
        module_dir = manifest_path.parent.parent.parent
        module = module_relative_dir(module_dir, root)
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        for preview in manifest.get("previews", []):
            source = preview.get("sourceFile")
            preview_id = preview.get("id")
            if not source or not preview_id or not safe_relative(source):
                continue
            path = source if module == "." else f"{module}/{source}"
            if path in changed_files:
                ids.append(preview_id)
    return ids


def select(changed_path: Path, changed_files: set[str], root: Path) -> list[str] | None:
    """The ids to check, in a stable order, or None when the visual diff is missing."""
    if not changed_path.is_file():
        return None
    try:
        changed = json.loads(changed_path.read_text(encoding="utf-8") or "[]")
        visual = [e["previewId"] for e in changed if isinstance(e, dict) and e.get("previewId")]
    except (OSError, json.JSONDecodeError, KeyError, TypeError):
        return None
    return list(dict.fromkeys(visual + source_changed_ids(root, changed_files)))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--changed", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--changed-files")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    changed_files: set[str] = set()
    if args.changed_files and Path(args.changed_files).is_file():
        changed_files = {
            line.strip()
            for line in Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        }
    ids = select(Path(args.changed), changed_files, Path(args.root))
    out = Path(args.out)
    if ids is None:
        out.unlink(missing_ok=True)
        print("full")
        return 0
    out.write_text("".join(f"{i}\n" for i in ids), encoding="utf-8")
    print(len(ids))
    return 0


if __name__ == "__main__":
    sys.exit(main())
