#!/usr/bin/env python3
"""Generate preview baselines or compare renders against them.

Works with ``compose-preview show --json`` output, so it's portable to any
project that uses the ee.schimke.composeai.preview Gradle plugin + CLI.

Modes
-----
generate
    Read CLI JSON output, hash rendered PNGs, and emit ``baselines.json``
    plus a browsable ``README.md`` with inline images.

compare
    Read CLI JSON output and a previously-generated ``baselines.json``,
    then emit a Markdown PR comment body to stdout.

copy-changed
    Copy only new/changed PNGs to an output directory (for the PR renders
    branch).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import shutil
from pathlib import Path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_cli_output(cli_json_path: Path) -> dict[str, dict]:
    """Parse ``compose-preview show --json`` output into a keyed dict.

    The CLI emits a JSON array of PreviewResult objects.  We key them as
    ``<module>/<id>`` for stable cross-run comparison.
    """
    entries = json.loads(cli_json_path.read_text())
    result: dict[str, dict] = {}
    for entry in entries:
        key = f"{entry['module']}/{entry['id']}"
        result[key] = {
            "sha256": entry.get("sha256") or "",
            "functionName": entry["functionName"],
            "sourceFile": entry.get("sourceFile", ""),
            "module": entry["module"],
            "previewId": entry["id"],
            "pngPath": entry.get("pngPath", ""),
        }
    return result


# ---------------------------------------------------------------------------
# generate mode
# ---------------------------------------------------------------------------

def cmd_generate(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    out_dir = Path(args.output_dir)
    repo = args.repo
    branch = args.branch

    previews = load_cli_output(cli_json)
    if not previews:
        print("No previews in CLI output.", file=sys.stderr)
        return 1

    # --- baselines.json ---
    baselines = {
        key: {
            "sha256": info["sha256"],
            "functionName": info["functionName"],
            "sourceFile": info["sourceFile"],
        }
        for key, info in previews.items()
        if info["sha256"]  # skip entries without a rendered PNG
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "baselines.json").write_text(
        json.dumps(baselines, indent=2, sort_keys=True) + "\n")

    # --- copy PNGs into renders/<module>/<id>.png ---
    renders_out = out_dir / "renders"
    if renders_out.exists():
        shutil.rmtree(renders_out)
    for info in previews.values():
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        dest = renders_out / info["module"] / f"{info['previewId']}.png"
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(png, dest)

    # --- README.md (browsable gallery) ---
    lines = [
        "# Preview Baselines",
        "",
        "Auto-generated from `main`. Browse inline or compare against PR branches.",
        "",
    ]
    by_module: dict[str, list[tuple[str, dict]]] = {}
    for key, info in sorted(previews.items()):
        if not info["sha256"]:
            continue
        by_module.setdefault(info["module"], []).append((key, info))

    for module, entries in sorted(by_module.items()):
        lines.append(f"## {module}")
        lines.append("")
        lines.append("| Preview | Image |")
        lines.append("|---------|-------|")
        for _, info in entries:
            fn = info["functionName"]
            img_path = f"renders/{info['module']}/{info['previewId']}.png"
            raw_url = f"https://raw.githubusercontent.com/{repo}/{branch}/{img_path}"
            lines.append(
                f"| `{fn}` | <img src=\"{raw_url}\" width=\"150\" /> |"
            )
        lines.append("")

    (out_dir / "README.md").write_text("\n".join(lines) + "\n")

    print(f"Generated baselines for {len(baselines)} preview(s) in {out_dir}",
          file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# compare mode
# ---------------------------------------------------------------------------

def _variant_label(preview_id: str) -> str:
    """Extract the variant label from a preview ID (suffix after the last ``_``)."""
    # e.g. "com.example.PreviewsKt.ConfigProbePreview_German" -> "German"
    parts = preview_id.rsplit("_", 1)
    return parts[1] if len(parts) == 2 else ""


def _is_default_variant(preview_id: str) -> bool:
    label = _variant_label(preview_id).lower()
    return label in ("", "default")


def _render_url(repo: str, branch: str, module: str, preview_id: str) -> str:
    return (
        f"https://raw.githubusercontent.com/{repo}/{branch}"
        f"/renders/{module}/{preview_id}.png"
    )


def _pick_hero(entries: list) -> tuple[int, object]:
    """Pick the best variant to show inline (prefer Default, else first)."""
    for i, entry in enumerate(entries):
        pid = entry[0].split("/", 1)[1]  # key -> preview_id
        if _is_default_variant(pid):
            return i, entry
    return 0, entries[0]


def cmd_compare(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    repo = args.repo
    base_branch = args.base_branch
    head_branch = args.head_branch

    current = load_cli_output(cli_json)
    baselines = json.loads(baselines_path.read_text()) if baselines_path.exists() else {}

    new: list[tuple[str, dict]] = []
    changed: list[tuple[str, dict, dict]] = []
    removed: list[tuple[str, dict]] = []
    unchanged: list[tuple[str, dict]] = []

    for key, info in sorted(current.items()):
        if not info["sha256"]:
            continue
        if key not in baselines:
            new.append((key, info))
        elif info["sha256"] != baselines[key]["sha256"]:
            changed.append((key, info, baselines[key]))
        else:
            unchanged.append((key, info))

    for key, bl_info in sorted(baselines.items()):
        if key not in current:
            removed.append((key, bl_info))

    # --- generate markdown ---
    marker = "<!-- preview-diff -->"
    lines = [marker, "## Preview Changes", ""]

    if not new and not changed and not removed:
        lines.append("No visual changes detected.")
        lines.append("")
        if unchanged:
            lines.append(f"_{len(unchanged)} preview(s) unchanged._")
        print("\n".join(lines))
        return 0

    if changed:
        # Group changed variants by (module, functionName).
        groups: dict[tuple[str, str], list[tuple[str, dict, dict]]] = {}
        for key, cur, bl in changed:
            gk = (cur["module"], cur["functionName"])
            groups.setdefault(gk, []).append((key, cur, bl))

        lines.append(f"### Changed ({len(changed)} variant(s) across {len(groups)} function(s))")
        lines.append("")

        for (module, fn), entries in sorted(groups.items()):
            hero_idx, (hero_key, hero_cur, hero_bl) = _pick_hero(entries)
            hero_pid = hero_key.split("/", 1)[1]
            before = _render_url(repo, base_branch, module, hero_pid)
            after = _render_url(repo, head_branch, module, hero_pid)

            lines.append(f"**`{fn}`** ({module})")
            lines.append("")
            lines.append("| Before | After |")
            lines.append("|--------|-------|")
            lines.append(
                f"| <img src=\"{before}\" width=\"200\" /> "
                f"| <img src=\"{after}\" width=\"200\" /> |"
            )

            # Link remaining variants
            others = [e for i, e in enumerate(entries) if i != hero_idx]
            if others:
                variant_links = []
                for okey, ocur, obl in others:
                    opid = okey.split("/", 1)[1]
                    label = _variant_label(opid) or opid
                    link = _render_url(repo, head_branch, module, opid)
                    variant_links.append(f"[{label}]({link})")
                lines.append("")
                lines.append(f"Other variants: {', '.join(variant_links)}")
            lines.append("")

    if new:
        # Group new previews similarly.
        groups_new: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in new:
            gk = (info["module"], info["functionName"])
            groups_new.setdefault(gk, []).append((key, info))

        lines.append(f"### New ({len(new)} variant(s) across {len(groups_new)} function(s))")
        lines.append("")

        for (module, fn), entries in sorted(groups_new.items()):
            hero_idx, (hero_key, hero_info) = _pick_hero(entries)
            hero_pid = hero_key.split("/", 1)[1]
            after = _render_url(repo, head_branch, module, hero_pid)

            lines.append(
                f"**`{fn}`** ({module}) "
                f"<img src=\"{after}\" width=\"200\" />"
            )

            others = [e for i, e in enumerate(entries) if i != hero_idx]
            if others:
                variant_links = []
                for okey, oinfo in others:
                    opid = okey.split("/", 1)[1]
                    label = _variant_label(opid) or opid
                    link = _render_url(repo, head_branch, module, opid)
                    variant_links.append(f"[{label}]({link})")
                lines.append(f"Variants: {', '.join(variant_links)}")
            lines.append("")

    if removed:
        fn_set = {bl_info.get("functionName", "?") for _, bl_info in removed}
        lines.append(f"### Removed ({len(removed)} variant(s))")
        lines.append("")
        for fn in sorted(fn_set):
            lines.append(f"- ~`{fn}`~")
        lines.append("")

    if unchanged:
        fn_set = {info["functionName"] for _, info in unchanged}
        lines.append(f"<details><summary>Unchanged ({len(fn_set)} function(s), {len(unchanged)} variant(s))</summary>")
        lines.append("")
        for fn in sorted(fn_set):
            lines.append(f"- `{fn}`")
        lines.append("")
        lines.append("</details>")

    print("\n".join(lines))
    return 0


# ---------------------------------------------------------------------------
# copy-changed mode
# ---------------------------------------------------------------------------

def cmd_copy_changed(args: argparse.Namespace) -> int:
    """Copy new/changed PNGs to an output directory for the PR renders branch."""
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    out_dir = Path(args.output_dir)

    current = load_cli_output(cli_json)
    baselines = json.loads(baselines_path.read_text()) if baselines_path.exists() else {}

    copied = 0
    for key, info in current.items():
        if not info["sha256"]:
            continue
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        is_new = key not in baselines
        is_changed = not is_new and info["sha256"] != baselines[key]["sha256"]
        if is_new or is_changed:
            dest = out_dir / "renders" / info["module"] / f"{info['previewId']}.png"
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(png, dest)
            copied += 1

    print(f"Copied {copied} changed/new preview(s) to {out_dir}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = ap.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("generate", help="Generate baselines from CLI output")
    gen.add_argument("cli_json", help="Path to compose-preview show --json output")
    gen.add_argument("--output-dir", required=True)
    gen.add_argument("--repo", required=True, help="owner/repo")
    gen.add_argument("--branch", default="preview_main")

    cmp = sub.add_parser("compare", help="Compare CLI output against baselines")
    cmp.add_argument("cli_json", help="Path to compose-preview show --json output")
    cmp.add_argument("--baselines", required=True, help="Path to baselines.json")
    cmp.add_argument("--repo", required=True)
    cmp.add_argument("--pr", required=True)
    cmp.add_argument("--base-branch", default="preview_main")
    cmp.add_argument("--head-branch", required=True, help="e.g. preview_pr/42")

    cp = sub.add_parser("copy-changed", help="Copy new/changed PNGs to output dir")
    cp.add_argument("cli_json", help="Path to compose-preview show --json output")
    cp.add_argument("--baselines", required=True)
    cp.add_argument("--output-dir", required=True)

    args = ap.parse_args()
    handlers = {
        "generate": cmd_generate,
        "compare": cmd_compare,
        "copy-changed": cmd_copy_changed,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
