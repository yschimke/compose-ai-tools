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


def _capture_label(capture: dict) -> str:
    """Human-readable summary of a capture's non-null dimensions.

    Mirrors the TS `captureLabels.captureLabel` in the VS Code extension so
    the two surfaces agree on wording. Static captures (no dimensions)
    return ``""``; time fan-outs read ``"500ms"``; scroll captures read
    ``"scroll top"`` / ``"scroll end"`` / ``"scroll long"``; the
    cross-product reads ``"500ms \u00B7 scroll end"``.
    """
    parts: list[str] = []
    ms = capture.get("advanceTimeMillis")
    if ms is not None:
        parts.append(f"{ms}ms")
    scroll = capture.get("scroll")
    if isinstance(scroll, dict):
        mode = str(scroll.get("mode") or "").lower()
        if mode:
            parts.append(f"scroll {mode}")
    return " \u00B7 ".join(parts)


def _render_basename(png_path: str, preview_id: str) -> str:
    """File basename the diff bot should use when copying/linking a capture.

    Prefer the basename the renderer actually wrote (it encodes dimension
    suffixes like ``_SCROLL_end`` / ``_TIME_500ms`` so two captures of the
    same preview never collide). Fall back to ``<previewId>.png`` when the
    CLI didn't surface a real path — that matches the legacy behaviour for
    missing / unrendered rows.
    """
    if png_path:
        name = Path(png_path).name
        if name:
            return name
    return f"{preview_id}.png"


def load_cli_output(cli_json_path: Path) -> dict[str, dict]:
    """Parse ``compose-preview show --json`` output into a keyed dict.

    The CLI emits a versioned envelope ``{schema, previews, counts}`` (schema
    ``compose-preview-show/v1``).  Pre-envelope CLIs (≤0.4.0) emitted a bare
    JSON array of PreviewResult objects — accepted as a fallback so this
    action keeps working against older CLI tarballs in CI matrices.

    Previews with multiple captures (``@RoboComposePreviewOptions`` time
    fan-out, ``@ScrollingPreview(modes = […])`` scroll fan-out) expand into
    one row per capture. The first capture keeps the bare ``<module>/<id>``
    key so existing baselines continue matching single-capture previews;
    subsequent captures are keyed ``<module>/<id>#<n>`` — same convention as
    the CLI's own per-capture state file.

    Rows carry the render PNG basename (``_SCROLL_end.png`` etc.) and a
    ``captureLabel`` for downstream markdown / filename handling.
    """
    raw = json.loads(cli_json_path.read_text())
    if isinstance(raw, dict) and "previews" in raw:
        entries = raw["previews"]
    elif isinstance(raw, list):
        entries = raw
    else:
        raise SystemExit(
            f"Unexpected CLI JSON shape in {cli_json_path}: "
            f"expected {{schema, previews, ...}} or a list, got {type(raw).__name__}"
        )

    result: dict[str, dict] = {}
    for entry in entries:
        module = entry["module"]
        preview_id = entry["id"]
        fn = entry["functionName"]
        source = entry.get("sourceFile", "")

        # Legacy / unrendered shape: no per-capture list, fall back to the
        # top-level sha/png. Produces one row as before.
        captures = entry.get("captures") or []
        if not captures:
            result[f"{module}/{preview_id}"] = {
                "sha256": entry.get("sha256") or "",
                "functionName": fn,
                "sourceFile": source,
                "module": module,
                "previewId": preview_id,
                "pngPath": entry.get("pngPath") or "",
                "captureIndex": 0,
                "captureLabel": "",
                "renderBasename": _render_basename(entry.get("pngPath") or "", preview_id),
            }
            continue

        for idx, capture in enumerate(captures):
            # Index 0 keeps the bare key so pre-fan-out baselines on `main`
            # keep matching single-capture previews. Additional captures
            # (#1, #2, …) appear as "new" entries on the first run after
            # a preview grows a fan-out, which is correct — those PNGs
            # didn't exist in the baseline.
            key = f"{module}/{preview_id}" if idx == 0 else f"{module}/{preview_id}#{idx}"
            png = capture.get("pngPath") or ""
            result[key] = {
                "sha256": capture.get("sha256") or "",
                "functionName": fn,
                "sourceFile": source,
                "module": module,
                "previewId": preview_id,
                "pngPath": png,
                "captureIndex": idx,
                "captureLabel": _capture_label(capture),
                "renderBasename": _render_basename(png, preview_id),
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
    # Persist the renderBasename alongside the sha so the compare run can
    # reconstruct raw-GitHub URLs for removed captures without needing the
    # CLI output for them.
    baselines = {
        key: {
            "sha256": info["sha256"],
            "functionName": info["functionName"],
            "sourceFile": info["sourceFile"],
            "renderBasename": info["renderBasename"],
            "captureLabel": info["captureLabel"],
        }
        for key, info in previews.items()
        if info["sha256"]  # skip entries without a rendered PNG
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "baselines.json").write_text(
        json.dumps(baselines, indent=2, sort_keys=True) + "\n")

    # --- copy PNGs into renders/<module>/<renderBasename> ---
    # Using the renderer's on-disk basename (e.g. `Foo_SCROLL_end.png`)
    # rather than `<previewId>.png` so captures in a multi-mode /
    # time-fan-out preview don't collide on the baseline branch.
    renders_out = out_dir / "renders"
    if renders_out.exists():
        shutil.rmtree(renders_out)
    for info in previews.values():
        if not info["pngPath"]:
            continue
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        dest = renders_out / info["module"] / info["renderBasename"]
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
            label_suffix = f" · {info['captureLabel']}" if info["captureLabel"] else ""
            fn = f"{info['functionName']}{label_suffix}"
            img_path = f"renders/{info['module']}/{info['renderBasename']}"
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


def _entry_label(info: dict) -> str:
    """Display label for one row inside a function group.

    Combines the variant suffix from the preview id (Light/Dark, device
    name, etc.) with the capture label (``scroll end``, ``500ms``, …).
    Either half may be empty; the label is used in link text / table
    headings, so empty strings are filtered out.
    """
    variant = _variant_label(info["previewId"])
    capture = info.get("captureLabel") or ""
    parts = [p for p in (variant, capture) if p]
    return " · ".join(parts) or info["previewId"]


def _render_url(repo: str, ref: str, module: str, basename: str) -> str:
    # ``ref`` is either a commit SHA (preferred: durable) or a branch name
    # (first-run fallback when no baseline/PR commit exists yet).
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{module}/{basename}"
    )


def cmd_compare(args: argparse.Namespace) -> int:
    cli_json = Path(args.cli_json)
    baselines_path = Path(args.baselines)
    repo = args.repo
    base_ref = args.base_ref
    head_ref = args.head_ref

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
        # Group changed variants by (module, functionName) — a function
        # fans out into (preview variants × captures), so one group can
        # contain many rows even for a single source function.
        groups: dict[tuple[str, str], list[tuple[str, dict, dict]]] = {}
        for key, cur, bl in changed:
            gk = (cur["module"], cur["functionName"])
            groups.setdefault(gk, []).append((key, cur, bl))

        lines.append(f"### Changed ({len(changed)} variant(s) across {len(groups)} function(s))")
        lines.append("")

        for (module, fn), entries in sorted(groups.items()):
            hero_key, hero_cur, hero_bl = entries[0]
            before = _render_url(repo, base_ref, module, hero_cur["renderBasename"])
            after = _render_url(repo, head_ref, module, hero_cur["renderBasename"])

            lines.append(f"**`{fn}`** ({module})")
            lines.append("")
            lines.append("| Before | After |")
            lines.append("|--------|-------|")
            lines.append(
                f"| <img src=\"{before}\" width=\"200\" /> "
                f"| <img src=\"{after}\" width=\"200\" /> |"
            )

            # Link remaining variants
            if len(entries) > 1:
                variant_links = []
                for _okey, ocur, _obl in entries[1:]:
                    label = _entry_label(ocur)
                    link = _render_url(repo, head_ref, module, ocur["renderBasename"])
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
            hero_key, hero_info = entries[0]
            after = _render_url(repo, head_ref, module, hero_info["renderBasename"])

            lines.append(
                f"**`{fn}`** ({module}) "
                f"<img src=\"{after}\" width=\"200\" />"
            )

            if len(entries) > 1:
                variant_links = []
                for _okey, oinfo in entries[1:]:
                    label = _entry_label(oinfo)
                    link = _render_url(repo, head_ref, module, oinfo["renderBasename"])
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
        if not info["pngPath"]:
            continue
        png = Path(info["pngPath"])
        if not png.exists():
            continue
        is_new = key not in baselines
        is_changed = not is_new and info["sha256"] != baselines[key]["sha256"]
        if is_new or is_changed:
            # Use the renderer's on-disk basename so multi-capture previews
            # don't collide — matches the generate path.
            dest = out_dir / "renders" / info["module"] / info["renderBasename"]
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
    # SHA-pin both sides so the PR comment's images keep resolving after
    # `preview_main` advances and after the PR merges. Branch names are
    # accepted as a first-run fallback when no commit exists yet.
    cmp.add_argument("--base-ref", default="preview_main",
                     help="preview_main commit SHA (or branch name) for Before URLs")
    cmp.add_argument("--head-ref", required=True,
                     help="preview_pr commit SHA (or branch name) for After URLs")

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
