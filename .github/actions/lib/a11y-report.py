#!/usr/bin/env python3
"""Generate accessibility reports from compose-preview output.

Reads the per-module ``previews.json`` (from the Gradle plugin) and its
sidecar ``accessibility.json`` (written by ``verifyAccessibility``), filters
each preview function down to a single canonical Wear variant, and emits
either a browsable ``README.md`` for the ``a11y_main`` baseline branch or a
Markdown PR comment body for the ``a11y_pr`` branch.

Subcommands
-----------
copy-annotated
    Read the two manifests, pick one variant per (module, function), and
    copy the rendered PNG plus the annotated ``<id>.a11y.png`` into the
    output directory. Also writes ``findings.json``, a flat per-preview
    summary the readme/comment subcommands consume.

readme
    Render ``findings.json`` to a browsable Markdown gallery with inline
    images (raw.githubusercontent SHA-pinned).

comment
    Render ``findings.json`` to a PR-comment Markdown body with the
    ``<!-- a11y-report -->`` marker the action upserts on.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Variant filter
# ---------------------------------------------------------------------------

# Wear meta-annotations (`@WearPreviewDevices`, `@WearPreviewLargeRound`, …)
# fan a single function out into multiple previews, each with a different
# `params.device`. The README would get noisy if we listed every variant,
# so for each function we keep just the variant whose device sits earliest
# in this global priority. Devices not listed here fall through to a
# stable id-sort tiebreaker, so non-Wear modules still produce output.
_DEVICE_PRIORITY: tuple[str, ...] = (
    "id:wearos_large_round",
    "id:wearos_small_round",
)


def device_priority(device: str | None) -> int:
    """Index of ``device`` in [_DEVICE_PRIORITY]; sentinel when absent/unknown."""
    if not device:
        return len(_DEVICE_PRIORITY) + 1
    try:
        return _DEVICE_PRIORITY.index(device)
    except ValueError:
        return len(_DEVICE_PRIORITY) + 1


def variant_label(device: str | None) -> str:
    """Short human-readable label for ``device``.

    Strips the ``id:`` prefix Compose uses for known device ids
    (``id:wearos_large_round`` → ``wearos_large_round``); returns the empty
    string for ``None`` so the README header drops the trailing dot when
    there's no device to show.
    """
    if not device:
        return ""
    return device.removeprefix("id:")


# ---------------------------------------------------------------------------
# Manifest loading
# ---------------------------------------------------------------------------

def load_previews(build_dir: Path) -> tuple[dict, dict]:
    """Return ``(manifest, a11y_by_id)`` for one module's build output.

    ``manifest`` is the raw ``previews.json`` dict. ``a11y_by_id`` maps each
    previewId to its accessibility entry (findings + relative annotatedPath),
    or ``None`` when ``accessibility.json`` is absent (a11y not enabled).
    """
    manifest_path = build_dir / "previews.json"
    if not manifest_path.exists():
        raise SystemExit(f"previews.json not found at {manifest_path}")
    manifest = json.loads(manifest_path.read_text())

    a11y_by_id: dict = {}
    a11y_path = build_dir / "accessibility.json"
    if a11y_path.exists():
        report = json.loads(a11y_path.read_text())
        for entry in report.get("entries", []):
            a11y_by_id[entry["previewId"]] = entry
    return manifest, a11y_by_id


def is_dynamic_preview(preview: dict) -> bool:
    """Returns True for `@ScrollingPreview` / `@AnimatedPreview` variants.

    Dynamic captures move during the render — `scroll != null` covers TOP,
    END, LONG, and GIF scroll modes, and a `.gif` extension catches
    `@AnimatedPreview`'s frame-strip output. Including them in the a11y
    report would mean overlaying the legend onto a tall stitched scroll or
    a single animation frame, neither of which is a useful "what TalkBack
    sees" picture. The static variant of the same function (when it
    exists) carries the a11y signal.
    """
    for capture in preview.get("captures", []):
        if capture.get("scroll") is not None:
            return True
        if (capture.get("renderOutput") or "").endswith(".gif"):
            return True
    return False


def select_variants(manifest: dict, a11y_by_id: dict) -> list[dict]:
    """Pick one variant per (functionName) and merge in a11y data.

    Returns a list of flat dicts with everything readme/comment need:
    module, functionName, sourceFile, previewId, variant, renderOutput
    (module-relative), annotatedPath (module-relative), findings.

    Filtered out:
    * Tile previews (``params.kind == "TILE"``) — ATF runs against the
      Robolectric View tree, but Wear Tiles render through
      `TilePreviewRenderer`, so listing them with empty findings would
      falsely imply they were checked.
    * Scroll / animation captures (``@ScrollingPreview`` /
      ``@AnimatedPreview``) — see [is_dynamic_preview]. Functions whose
      ONLY variants are dynamic drop out of the report entirely.
    """
    module = manifest["module"]
    by_fn: dict[str, list[dict]] = {}
    for preview in manifest.get("previews", []):
        kind = (preview.get("params") or {}).get("kind", "COMPOSE")
        if kind != "COMPOSE":
            continue
        if is_dynamic_preview(preview):
            continue
        by_fn.setdefault(preview["functionName"], []).append(preview)

    rows: list[dict] = []
    for fn, group in sorted(by_fn.items()):
        chosen = min(
            group,
            key=lambda p: (
                device_priority((p.get("params") or {}).get("device")),
                p["id"],
            ),
        )
        # `captures[0].renderOutput` is the canonical PNG for a static preview.
        # Multi-capture previews (scroll/time fan-outs) collapse to the first
        # capture for the report — a Wear a11y demo doesn't need the full
        # animation strip; the annotated PNG already pins one frame.
        captures = chosen.get("captures") or []
        render_rel = captures[0]["renderOutput"] if captures else ""
        a11y = a11y_by_id.get(chosen["id"])
        chosen_device = (chosen.get("params") or {}).get("device")
        rows.append({
            "module": module,
            "functionName": fn,
            "sourceFile": chosen.get("sourceFile"),
            "previewId": chosen["id"],
            "variant": variant_label(chosen_device),
            "renderOutput": render_rel,
            "annotatedPath": (a11y or {}).get("annotatedPath"),
            "findings": (a11y or {}).get("findings", []),
        })
    return rows


# ---------------------------------------------------------------------------
# copy-annotated
# ---------------------------------------------------------------------------

def cmd_copy_annotated(args: argparse.Namespace) -> int:
    build_dir = Path(args.build_dir)
    out_dir = Path(args.output_dir)
    manifest, a11y_by_id = load_previews(build_dir)
    rows = select_variants(manifest, a11y_by_id)

    module = manifest["module"]
    renders_out = out_dir / "renders" / module
    if renders_out.exists():
        shutil.rmtree(renders_out)
    renders_out.mkdir(parents=True, exist_ok=True)

    findings_summary: list[dict] = []
    for row in rows:
        # Copy the clean render (always — the README links it for previews
        # without findings so the gallery still shows what was checked).
        clean_basename = ""
        if row["renderOutput"]:
            src = build_dir / row["renderOutput"]
            if src.exists():
                clean_basename = src.name
                shutil.copy2(src, renders_out / clean_basename)
        # Copy the annotated PNG when present.
        annotated_basename = ""
        if row["annotatedPath"]:
            src = build_dir / row["annotatedPath"]
            if src.exists():
                annotated_basename = src.name
                shutil.copy2(src, renders_out / annotated_basename)
        findings_summary.append({
            "module": row["module"],
            "functionName": row["functionName"],
            "sourceFile": row["sourceFile"],
            "previewId": row["previewId"],
            "variant": row["variant"],
            "cleanBasename": clean_basename,
            "annotatedBasename": annotated_basename,
            "findings": row["findings"],
        })

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "findings.json").write_text(
        json.dumps({"entries": findings_summary}, indent=2, sort_keys=True) + "\n"
    )

    total_findings = sum(len(r["findings"]) for r in findings_summary)
    print(
        f"Copied {len(findings_summary)} preview(s) with "
        f"{total_findings} finding(s) to {out_dir}",
        file=sys.stderr,
    )
    return 0


# ---------------------------------------------------------------------------
# readme / comment shared rendering
# ---------------------------------------------------------------------------

def _level_counts(entries: list[dict]) -> tuple[int, int, int]:
    err = warn = info = 0
    for entry in entries:
        for f in entry["findings"]:
            level = f.get("level")
            if level == "ERROR":
                err += 1
            elif level == "WARNING":
                warn += 1
            elif level == "INFO":
                info += 1
    return err, warn, info


def _image_url(repo: str, ref: str, module: str, basename: str) -> str:
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{module}/{basename}"
    )


def _findings_table(findings: list[dict]) -> list[str]:
    """Markdown table of one preview's findings. Returns lines (no trailing blank)."""
    lines = [
        "| # | Level | Rule | Element | Message |",
        "|--:|---|---|---|---|",
    ]
    for idx, f in enumerate(findings, start=1):
        element = (f.get("viewDescription") or "").replace("|", "\\|")
        # Messages from ATF can contain backticks and newlines; collapse to
        # a single line so the table cell renders correctly.
        message = (f.get("message") or "").replace("\n", " ").replace("|", "\\|")
        lines.append(
            f"| {idx} | {f.get('level', '')} | {f.get('type', '')} "
            f"| {element} | {message} |"
        )
    return lines


def _entry_block(entry: dict, ref: str, repo: str, image_width: int) -> list[str]:
    fn = entry["functionName"]
    module = entry["module"]
    variant = entry["variant"]
    findings = entry["findings"]

    # Prefer the annotated PNG when there are findings (it visually flags
    # the issues); fall back to the clean render so the gallery still has
    # an image even for clean previews.
    image_basename = entry["annotatedBasename"] or entry["cleanBasename"]
    lines: list[str] = []
    header_suffix = f" · `{variant}`" if variant else ""
    lines.append(f"### `{fn}`{header_suffix}")
    lines.append("")
    if image_basename:
        url = _image_url(repo, ref, module, image_basename)
        lines.append(f'<img src="{url}" width="{image_width}" />')
        lines.append("")
    if findings:
        lines.extend(_findings_table(findings))
    else:
        lines.append("_No findings._")
    lines.append("")
    return lines


# ---------------------------------------------------------------------------
# readme
# ---------------------------------------------------------------------------

def cmd_readme(args: argparse.Namespace) -> int:
    findings_path = Path(args.findings)
    payload = json.loads(findings_path.read_text())
    entries: list[dict] = payload.get("entries", [])

    err, warn, info = _level_counts(entries)
    findings_count = err + warn + info

    by_module: dict[str, list[dict]] = {}
    for entry in entries:
        by_module.setdefault(entry["module"], []).append(entry)

    lines = [
        "# Accessibility Report",
        "",
        f"_Auto-generated from `{args.branch}`. "
        f"{len(entries)} preview(s) across {len(by_module)} module(s) · "
        f"{err} error(s) · {warn} warning(s) · {info} info._",
        "",
        "Browse inline; image URLs are pinned to the commit SHA on the "
        "baseline branch so links keep resolving after merge.",
        "",
    ]
    if findings_count == 0:
        lines.append("No accessibility findings.")
        lines.append("")

    for module, module_entries in sorted(by_module.items()):
        lines.append(f"## {module}")
        lines.append("")
        for entry in sorted(module_entries, key=lambda e: e["functionName"]):
            lines.extend(_entry_block(entry, args.branch, args.repo, image_width=400))

    out_path = Path(args.output) if args.output else None
    body = "\n".join(lines).rstrip() + "\n"
    if out_path:
        out_path.write_text(body)
    else:
        sys.stdout.write(body)
    return 0


# ---------------------------------------------------------------------------
# comment
# ---------------------------------------------------------------------------

def cmd_comment(args: argparse.Namespace) -> int:
    findings_path = Path(args.findings)
    payload = json.loads(findings_path.read_text())
    entries: list[dict] = payload.get("entries", [])

    err, warn, info = _level_counts(entries)
    findings_count = err + warn + info

    by_module: dict[str, list[dict]] = {}
    for entry in entries:
        by_module.setdefault(entry["module"], []).append(entry)

    marker = "<!-- a11y-report -->"
    lines = [
        marker,
        "## Accessibility Report",
        "",
        f"{err} error(s) · {warn} warning(s) · {info} info "
        f"across {len(entries)} preview(s).",
        "",
    ]

    if findings_count == 0:
        lines.append("No accessibility findings.")
        lines.append("")
        # Still link the gallery thumbnails so reviewers can spot-check
        # what was actually rendered — same data the readme would show,
        # without the table.
        for module, module_entries in sorted(by_module.items()):
            lines.append(f"### {module}")
            lines.append("")
            for entry in sorted(module_entries, key=lambda e: e["functionName"]):
                image = entry["annotatedBasename"] or entry["cleanBasename"]
                if not image:
                    continue
                url = _image_url(args.repo, args.head_ref, module, image)
                lines.append(
                    f"- `{entry['functionName']}` "
                    f'<img src="{url}" width="120" />'
                )
            lines.append("")
        sys.stdout.write("\n".join(lines).rstrip() + "\n")
        return 0

    for module, module_entries in sorted(by_module.items()):
        lines.append(f"### {module}")
        lines.append("")
        for entry in sorted(module_entries, key=lambda e: e["functionName"]):
            lines.extend(_entry_block(entry, args.head_ref, args.repo, image_width=240))

    sys.stdout.write("\n".join(lines).rstrip() + "\n")
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

    cp = sub.add_parser(
        "copy-annotated",
        help="Copy chosen-variant PNGs and emit findings.json",
    )
    cp.add_argument(
        "--build-dir", required=True,
        help="Path to the module's build/compose-previews directory",
    )
    cp.add_argument("--output-dir", required=True)

    rd = sub.add_parser("readme", help="Render findings.json to README.md")
    rd.add_argument("findings", help="Path to findings.json")
    rd.add_argument("--repo", required=True, help="owner/repo")
    rd.add_argument("--branch", required=True, help="Branch hosting the renders")
    rd.add_argument(
        "--output", default=None,
        help="Output README path (default: stdout)",
    )

    cm = sub.add_parser("comment", help="Render findings.json to PR comment body")
    cm.add_argument("findings", help="Path to findings.json")
    cm.add_argument("--repo", required=True)
    cm.add_argument(
        "--head-ref", required=True,
        help="a11y_pr commit SHA (or branch) for image URLs",
    )

    args = ap.parse_args()
    handlers = {
        "copy-annotated": cmd_copy_annotated,
        "readme": cmd_readme,
        "comment": cmd_comment,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
