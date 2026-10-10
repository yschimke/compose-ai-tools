#!/usr/bin/env python3
"""Stage what the design-guidelines check needs into the fork-safe handoff.

The publish job holds the OpenRouter key but checks out the BASE branch, so it can neither render
nor read the PR's source. This runs in the render phase and copies, for every changed or new
preview (``_changed_previews.json`` from ``compare-previews.py copy-changed``), everything
``compose-preview guidelines`` needs in handoff mode:

    <out>/<module-key>/previews.json             the module manifest, narrowed to those previews,
                                                 with each capture's render rewritten into renders/
    <out>/<module-key>/renders/<name>.png        their renders (and <name>_SCROLL_long.png, the
                                                 preview's `render/scroll/long` data product, when
                                                 the renderer wrote one; a LONG-only preview's long
                                                 screenshot is its capture)
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
preview whose ``sourceFile`` changed.

**The PR's own previews, bounded.** ``--max-previews N`` caps how many previews are staged in
total, across modules and tiers (0: no cap). Within the cap the previews are ranked: a render the
PR changed before one selected only because its source file changed, a new preview or a bigger
render diff (``diff`` in ``_changed_previews.json``) first. One representative of each preview
FUNCTION is taken before a second capture of any: a function fanned out over five screen sizes or
a dozen ``_VARIANT_`` cells is one design, and its first render says most of what its siblings
would, so a 30-preview cap reaches 30 functions rather than three. The rest follow in rank order
while the cap allows. What the cap leaves out is listed, never dropped:

    <out>/_over_limit.json   {"limit": n, "previews": [{"id", "module", "function"}, ...]}

**Rules changes.** A PR that changes a module's guidelines file changes what every verdict of that
module is judged against, but those previews are not the PR's, and the catalog publish on the
default branch re-checks every preview against the new rules from its result cache. So by default
the PR checks only its own previews and records that the rules changed:

    <out>/_rules_changed.json  {"modules": [<module-key>, ...], "sweep": false}

``--rules-sweep`` (the action's ``guidelines-rules-sweep``) restores the old second tier: other
previews of the module, staged apart, within MAX_RULES_ONLY and what the cap leaves:

    <out>/<module-key>/                  previews whose render or source the PR changed
    <out>/<module-key>.rules-changed/    up to MAX_RULES_ONLY previews (across all modules) selected
                                         only because the rules changed, with
                                         ``"guidelinesSelection": "rules-changed"`` in previews.json
    <out>/_rules_only.json               {"staged": n, "deferred": m}: how many such previews were
                                         staged, and how many were left for the catalog publish

The publish job checks every changed tier before any rules-changed one
(``guidelines-budget.py --order``), so a capped run spends the budget on the PR's own previews.

    guidelines-stage.py --changed _changed_previews.json --out _guidelines [--root .]
                        [--guidelines-file path] [--changed-files _pr_changed_files.txt]
                        [--max-previews N] [--rules-sweep]
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
LONG_KIND = "render/scroll/long"
MAX_LONG_BYTES = 2 * 1024 * 1024
SKIP_DIRS = {"node_modules", ".git", ".gradle"}
# Source scans also skip build outputs, which hold generated copies rather than the module's code.
SOURCE_SKIP_DIRS = SKIP_DIRS | {"build"}
# The second tier: previews selected only because the rules changed. Bounded across all modules;
# the rest are left to the catalog publish, whose cache re-checks every preview under new rules.
MAX_RULES_ONLY = 24
RULES_TIER_SUFFIX = ".rules-changed"
RULES_TIER = "rules-changed"
SELECTION_KEY = "guidelinesSelection"
RULES_ONLY_FILE = "_rules_only.json"
RULES_CHANGED_FILE = "_rules_changed.json"
OVER_LIMIT_FILE = "_over_limit.json"
# The default cap on previews one PR sends to the check; the action's `guidelines-max-previews`.
DEFAULT_MAX_PREVIEWS = 30

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
) -> tuple[list[dict], list[dict]]:
    """The previews to check, in two tiers: those whose render or source file the PR changed, and,
    when the rules changed, every other preview of the module."""
    module = repo_relative(module_dir, root)
    picked: list[dict] = []
    rules_only: list[dict] = []
    for preview in previews:
        source = preview.get("sourceFile")
        source_changed = (
            bool(source)
            and safe_relative(source)
            and (source if module == "." else f"{module}/{source}") in changed_files
        )
        if preview.get("id") in changed or source_changed:
            picked.append(preview)
        elif all_of_module:
            rules_only.append(preview)
    return picked, rules_only


def long_render(preview: dict, previews_dir: Path) -> Path | None:
    """The preview's long screenshot (the `render/scroll/long` data product, which the renderer
    writes under `data/render-scroll-long/`), or the legacy `<render>_SCROLL_long.png` beside its
    first capture. None when there is none, or it is a link or too large to send."""
    candidates: list[Path] = []
    for product in preview.get("dataProducts") or []:
        output = product.get("output") if isinstance(product, dict) else None
        if product.get("kind") == LONG_KIND and isinstance(output, str) and safe_relative(output):
            candidates.append(previews_dir / output)
    for capture in preview.get("captures", []):
        output = capture.get("renderOutput")
        if isinstance(output, str) and safe_relative(output):
            render = previews_dir / output
            candidates.append(render.with_name(render.stem + LONG_SUFFIX))
            break
    for path in candidates:
        if (
            path.name.endswith(".png")
            and path.is_file()
            and not path.is_symlink()
            and path.stat().st_size <= MAX_LONG_BYTES
        ):
            return path
    return None


def stageable(preview: dict, previews_dir: Path) -> bool:
    """Whether [stage_module] would keep [preview]: it has a render on disk (a capture, or a long
    screenshot standing in for one). A preview whose render failed takes no slot of the cap."""
    for capture in preview.get("captures", []):
        output = capture.get("renderOutput")
        if output and safe_relative(output) and (previews_dir / output).is_file():
            return True
    return long_render(preview, previews_dir) is not None


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


def stage_module(
    previews_dir: Path,
    module_dir: Path,
    manifest: dict,
    selected: list[dict],
    rules: Path,
    target: Path,
    selection: str | None,
    root: Path,
) -> int:
    """Stages [selected] (one module's previews of one tier) under [target]. Returns how many."""
    (target / "renders").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(rules, target / GUIDELINES_FILE)

    kept = []
    staged_sources: list[Path] = []
    for preview in selected:
        captures = []
        long = long_render(preview, previews_dir)
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
            # The whole scrolling content: a follow-up round can ask for it, so content
            # scrolled out of view is not read as clipped. Staged as `<render>_SCROLL_long.png`
            # beside the render, which is where the check looks for it.
            if long is not None and len(captures) == 1:
                shutil.copyfile(long, target / "renders" / (Path(name).stem + LONG_SUFFIX))
        if not captures and long is not None:
            # A LONG-only preview: the long screenshot is its only render, so it is the capture.
            shutil.copyfile(long, target / "renders" / long.name)
            captures.append({"renderOutput": f"renders/{long.name}", "scroll": {"mode": "LONG"}})
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
    stage_callees(module_dir, staged_sources, target)
    staged_manifest = {**manifest, "previews": kept}
    staged_manifest.pop(SELECTION_KEY, None)
    if selection:
        staged_manifest[SELECTION_KEY] = selection
    (target / "previews.json").write_text(
        json.dumps(staged_manifest, indent=2) + "\n", encoding="utf-8"
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
    return len(kept)


def function_key(module_dir: Path, preview: dict) -> tuple:
    """What a preview is a render OF: its module, class and function. A multipreview fanned out over
    screen sizes (`WearList_192dp` … `_240dp`) or `_VARIANT_` cells shares one key."""
    function = preview.get("functionName")
    if not function:
        function = re.sub(r"_VARIANT_.*$", "", str(preview.get("id") or ""))
    return (str(module_dir), preview.get("className") or "", function)


def rank(preview: dict, changed: dict[str, dict]) -> tuple:
    """Sort key within the PR's own previews: a changed render before a source-only selection, a
    new preview or a bigger render diff first. An unmeasured diff counts as a whole change."""
    info = changed.get(preview.get("id"))
    if info is None:
        return (1, 0.0)
    diff = info.get("diff")
    try:
        value = 1.0 if info.get("new") or diff is None else float(diff)
    except (TypeError, ValueError):
        value = 1.0
    return (0, -value)


def prioritise(
    entries: list[tuple[Path, Path, dict, dict]], changed: dict[str, dict]
) -> list[tuple[Path, Path, dict, dict]]:
    """[entries] (previews_dir, module_dir, manifest, preview) in the order the cap takes them: the
    best-ranked render of each function first, then every other render, each pass by rank."""
    ordered = sorted(
        enumerate(entries),
        key=lambda item: (rank(item[1][3], changed), str(item[1][1]), item[0]),
    )
    firsts: list[tuple[Path, Path, dict, dict]] = []
    rest: list[tuple[Path, Path, dict, dict]] = []
    seen: set[tuple] = set()
    for _index, entry in ordered:
        key = function_key(entry[1], entry[3])
        (rest if key in seen else firsts).append(entry)
        seen.add(key)
    return firsts + rest


def stage(
    root: Path,
    changed: set[str] | dict[str, dict],
    out: Path,
    guidelines_file: Path | None,
    changed_files: set[str] | None = None,
    max_rules_only: int = MAX_RULES_ONLY,
    max_previews: int = 0,
    rules_sweep: bool = True,
) -> int:
    """Stages the previews to check under [out]; returns how many. [changed] is the visually changed
    preview ids, or `_changed_previews.json`'s entries by id (with `new` and `diff`, for ranking).
    [max_previews] caps the total (0: no cap); [rules_sweep] stages the rules-changed tier."""
    changed_files = changed_files or set()
    changed_info: dict[str, dict] = (
        dict(changed) if isinstance(changed, dict) else {i: {} for i in changed}
    )
    staged = 0
    candidates: list[tuple[Path, Path, dict, list[dict]]] = []
    rules_only: dict[Path, list[dict]] = {}
    rules_modules: list[str] = []
    for manifest_path in find_manifests(root):
        previews_dir = manifest_path.parent
        module_dir = previews_dir.parent.parent
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        rules = guidelines_file or previews_dir / GUIDELINES_FILE
        if not rules.is_file():
            continue
        module_rules_changed = rules_changed(module_dir, root, guidelines_file, changed_files)
        if module_rules_changed:
            rules_modules.append(module_key(module_dir, root))
        selected, by_rules = select(
            manifest.get("previews", []),
            set(changed_info),
            changed_files,
            module_dir,
            root,
            module_rules_changed and rules_sweep,
        )
        if not selected and not by_rules:
            continue
        candidates.append((previews_dir, module_dir, manifest, selected + by_rules))
        rules_only[previews_dir] = by_rules

    owner = owners(candidates)
    # Every directory a module's own tier may use, so a rules tier never lands on one: a real module
    # can be keyed `catalog.rules-changed` as well.
    used = {module_key(module_dir, root) for _, module_dir, _, _ in candidates}

    # The PR's own previews, across every module, in the order the cap takes them.
    mine: list[tuple[Path, Path, dict, dict]] = []
    for previews_dir, module_dir, manifest, selected in candidates:
        by_rules_ids = {id(p) for p in rules_only[previews_dir]}
        mine += [
            (previews_dir, module_dir, manifest, p)
            for p in selected
            if id(p) not in by_rules_ids
            and owner.get(p.get("id")) == module_dir
            and stageable(p, previews_dir)
        ]
    ordered = prioritise(mine, changed_info)
    taken = ordered[:max_previews] if max_previews > 0 else ordered
    over = ordered[len(taken):]
    taken_ids = {id(entry[3]) for entry in taken}
    for previews_dir, module_dir, manifest, _selected in candidates:
        # In rank order, so a module's own previews.json lists its most important first too.
        tier_changed = [
            entry[3] for entry in ordered
            if entry[0] == previews_dir and id(entry[3]) in taken_ids
        ]
        if not tier_changed:
            continue
        rules = guidelines_file or previews_dir / GUIDELINES_FILE
        target = out / module_key(module_dir, root)
        staged += stage_module(
            previews_dir, module_dir, manifest, tier_changed, rules, target, None, root
        )

    # The rules tier (opt-in): what the cap has left, and never more than MAX_RULES_ONLY.
    rules_left = max(0, max_rules_only)
    if max_previews > 0:
        rules_left = min(rules_left, max(0, max_previews - len(taken)))
    rules_staged = rules_deferred = 0
    for previews_dir, module_dir, manifest, selected in candidates:
        by_rules_ids = {id(p) for p in rules_only[previews_dir]}
        tier_rules = [
            p for p in selected
            if id(p) in by_rules_ids
            and owner.get(p.get("id")) == module_dir
            and stageable(p, previews_dir)
        ]
        if not tier_rules:
            continue
        taken_rules, left_over = tier_rules[:rules_left], tier_rules[rules_left:]
        rules_left -= len(taken_rules)
        rules_deferred += len(left_over)
        if not taken_rules:
            continue
        rules = guidelines_file or previews_dir / GUIDELINES_FILE
        name = module_key(module_dir, root) + RULES_TIER_SUFFIX
        suffix = 2
        while name in used:
            name = f"{module_key(module_dir, root)}{RULES_TIER_SUFFIX}-{suffix}"
            suffix += 1
        used.add(name)
        target = out / name
        count = stage_module(
            previews_dir, module_dir, manifest, taken_rules, rules, target, RULES_TIER, root
        )
        rules_staged += count
        staged += count
    if rules_staged or rules_deferred:
        out.mkdir(parents=True, exist_ok=True)
        (out / RULES_ONLY_FILE).write_text(
            json.dumps({"staged": rules_staged, "deferred": rules_deferred}) + "\n",
            encoding="utf-8",
        )
    if rules_modules:
        out.mkdir(parents=True, exist_ok=True)
        (out / RULES_CHANGED_FILE).write_text(
            json.dumps({"modules": rules_modules, "sweep": rules_sweep}) + "\n",
            encoding="utf-8",
        )
    if over:
        out.mkdir(parents=True, exist_ok=True)
        (out / OVER_LIMIT_FILE).write_text(
            json.dumps(
                {
                    "limit": max_previews,
                    "previews": [
                        {
                            "id": entry[3].get("id"),
                            "module": module_key(entry[1], root),
                            "function": entry[3].get("functionName"),
                        }
                        for entry in over
                    ],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    return staged


def read_changed(path: Path) -> dict[str, dict]:
    """`_changed_previews.json` by preview id: `[{previewId, module, new?, diff?}]`."""
    if not path.is_file() or path.stat().st_size == 0:
        return {}
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
        return {str(e["previewId"]): e for e in entries if isinstance(e, dict)}
    except (json.JSONDecodeError, KeyError, TypeError):
        print("guidelines-stage: unreadable changed list", file=sys.stderr)
        return {}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--changed", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--root", default=".")
    ap.add_argument("--guidelines-file")
    ap.add_argument("--changed-files")
    ap.add_argument(
        "--max-previews", type=int, default=DEFAULT_MAX_PREVIEWS,
        help="cap on previews staged in total (0: no cap)",
    )
    ap.add_argument(
        "--rules-sweep", action="store_true",
        help="when the rules changed, also stage other previews of the module (bounded)",
    )
    args = ap.parse_args()
    changed = read_changed(Path(args.changed))
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
    print(
        stage(
            Path(args.root),
            changed,
            Path(args.out),
            rules,
            changed_files,
            max_previews=max(0, args.max_previews),
            rules_sweep=args.rules_sweep,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
