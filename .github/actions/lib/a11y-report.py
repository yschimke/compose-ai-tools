#!/usr/bin/env python3
"""Generate accessibility reports from compose-preview output.

Reads the per-module ``previews.json`` (from the Gradle plugin) and its
sidecar ``accessibility.json`` (written by ``verifyAccessibility``), filters
each preview function down to a single canonical Wear variant, and emits
either a browsable ``README.md`` for the ``compose-preview/a11y/main``
baseline branch or a Markdown PR comment body for the
``compose-preview/a11y/pr`` branch.

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
    ``<!-- a11y-report -->`` marker the action upserts on. Diffs against the
    baseline ``findings.json`` and surfaces only the previews whose findings
    changed (collapsing the rest into a roster); stays silent when nothing
    a reviewer cares about changed.
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

#: Value the CLI stamps into ``accessibility.json``'s ``status`` field when the
#: daemon was unable to return ATF data for any preview in the module. Mirrors
#: ``A11Y_REPORT_STATUS_ATF_UNAVAILABLE`` in preview-data-api's A11yWireFormat.kt
#: — change in lockstep with the Kotlin constant.
ATF_UNAVAILABLE: str = "atf-unavailable"


def load_previews(build_dir: Path) -> tuple[dict, dict, str | None]:
    """Return ``(manifest, a11y_by_id, status)`` for one module's build output.

    ``manifest`` is the raw ``previews.json`` dict. ``a11y_by_id`` maps each
    previewId to its accessibility entry (findings + relative annotatedPath),
    or ``None`` when ``accessibility.json`` is absent (a11y not enabled).
    ``status`` is the top-level ``status`` field from ``accessibility.json`` —
    typically ``None`` for a clean run, or ``"atf-unavailable"`` when the
    daemon couldn't return ATF data and the empty entries list should NOT be
    interpreted as "ran cleanly, found nothing." See issue #1453.
    """
    manifest_path = build_dir / "previews.json"
    if not manifest_path.exists():
        raise SystemExit(f"previews.json not found at {manifest_path}")
    manifest = json.loads(manifest_path.read_text())

    a11y_by_id: dict = {}
    status: str | None = None
    a11y_path = build_dir / "accessibility.json"
    if a11y_path.exists():
        report = json.loads(a11y_path.read_text())
        status = report.get("status")
        for entry in report.get("entries", []):
            a11y_by_id[entry["previewId"]] = entry
    return manifest, a11y_by_id, status


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
    # ``--build-dir`` is repeatable so a single `copy-annotated` invocation
    # can fold every module the a11y CLI ran across into one ``findings.json``
    # + ``renders/<module>/`` tree. argparse hands us a list; tests still pass
    # a bare string, so normalize.
    raw = args.build_dir
    build_dirs = [raw] if isinstance(raw, (str, Path)) else list(raw)
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    findings_summary: list[dict] = []
    # Status is "the worst across modules": if any module's run came back
    # ``atf-unavailable``, the combined report carries it so the comment
    # subcommand can still flag the warning even when other modules ran
    # cleanly.
    combined_status: str | None = None
    per_module_counts: list[tuple[str, int, int, bool]] = []

    for raw_build_dir in build_dirs:
        build_dir = Path(raw_build_dir)
        manifest, a11y_by_id, status = load_previews(build_dir)
        rows = select_variants(manifest, a11y_by_id)

        module = manifest["module"]
        renders_out = out_dir / "renders" / module
        if renders_out.exists():
            shutil.rmtree(renders_out)
        renders_out.mkdir(parents=True, exist_ok=True)

        module_findings = 0
        for row in rows:
            # Copy the clean render (always — the README links it for previews
            # without findings so the gallery still shows what was checked).
            clean_basename = ""
            if row["renderOutput"]:
                src = build_dir / row["renderOutput"]
                if src.exists():
                    clean_basename = src.name
                    shutil.copy2(src, renders_out / clean_basename)
            # Copy the annotated PNG when present. The daemon writes every
            # overlay to `data/<previewId>/a11y-overlay.png`, so the source
            # basename collides across previews — rename to `<clean>.a11y.png`
            # (or `<previewId>.a11y.png` when the clean render is missing) so
            # each preview's overlay survives in the flat per-module dir.
            annotated_basename = ""
            if row["annotatedPath"]:
                src = build_dir / row["annotatedPath"]
                if src.exists():
                    stem = (
                        Path(clean_basename).stem if clean_basename
                        else row["previewId"]
                    )
                    annotated_basename = f"{stem}.a11y.png"
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
            module_findings += len(row["findings"])

        per_module_counts.append(
            (module, len(rows), module_findings, status == ATF_UNAVAILABLE)
        )
        # ATF-unavailable wins: once any module flagged it, the combined
        # report keeps the flag regardless of how clean later modules are.
        if status == ATF_UNAVAILABLE:
            combined_status = ATF_UNAVAILABLE
        elif status and combined_status is None:
            combined_status = status

    # ``status`` propagates verbatim from accessibility.json so downstream
    # consumers (the readme / comment subcommands, future MCP integrations)
    # can tell "ran cleanly with zero findings" from "didn't run." Omitted
    # from the JSON entirely on a normal run to keep diffs against the
    # baseline trivially clean.
    summary_payload: dict = {"entries": findings_summary}
    if combined_status:
        summary_payload["status"] = combined_status
    (out_dir / "findings.json").write_text(
        json.dumps(summary_payload, indent=2, sort_keys=True) + "\n"
    )

    total_findings = sum(c[2] for c in per_module_counts)
    total_previews = sum(c[1] for c in per_module_counts)
    unavailable_modules = [c[0] for c in per_module_counts if c[3]]
    if unavailable_modules:
        print(
            "ATF data unavailable for "
            f"{', '.join(unavailable_modules)} — "
            f"emitted {total_previews} preview(s) across "
            f"{len(per_module_counts)} module(s).",
            file=sys.stderr,
        )
    else:
        print(
            f"Copied {total_previews} preview(s) with "
            f"{total_findings} finding(s) across "
            f"{len(per_module_counts)} module(s) to {out_dir}",
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
    status: str | None = payload.get("status")

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
    if status == ATF_UNAVAILABLE:
        lines.extend([
            "> [!WARNING]",
            "> ATF data unavailable for this run — the daemon did not return "
            "accessibility findings. The renders below are **not** "
            "accessibility-checked.",
            "",
        ])
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

def _finding_tuple(f: dict) -> tuple:
    """Hash-stable representation of a single finding for change detection.

    Filenames are deliberately excluded elsewhere; here we capture only the
    fields a reviewer reacts to (level, rule type, message, element, bounds).
    """
    return (
        f.get("level"),
        f.get("type"),
        f.get("message"),
        f.get("viewDescription"),
        f.get("boundsInScreen"),
    )


def _entry_findings_key(entry: dict) -> tuple:
    """Order-insensitive key for one preview's findings.

    Returns the empty tuple for a preview with no findings, so an
    absent-from-baseline preview (default ``()``) and a clean preview compare
    equal — the surface rule in [cmd_comment] treats "clean" and "not present"
    as the same neutral state.
    """
    return tuple(sorted(_finding_tuple(f) for f in entry.get("findings", [])))


def _findings_fingerprint(entries: list[dict], status: str | None = None) -> tuple:
    """Stable representation of (status, previews-with-findings) for change detection.

    Compares only the data a reviewer would care about — top-level status,
    plus per-entry module, previewId, and findings list. Filenames
    (`cleanBasename`, `annotatedBasename`) are excluded because identical
    findings can move around between files when the renderer renames variants,
    and we don't want to wake the PR comment for that.

    Previews with **no** findings are excluded entirely: adding or removing a
    clean preview is not an accessibility change, so it must neither break the
    comment's silence nor surface a near-empty "nothing to see" comment. Only
    previews that carry findings participate, which mirrors the per-preview
    surface rule in [cmd_comment] (an absent/clean preview has the empty
    findings key).

    ``status`` is included so the fingerprint diverges when the daemon goes
    from "ran cleanly" to "atf-unavailable" or vice versa — without this, a
    daemon crash would match a clean baseline and the workflow would stay
    silent on the very condition the comment needs to surface.
    """
    return (status,) + tuple(
        (e["module"], e["previewId"], _entry_findings_key(e))
        for e in sorted(
            (e for e in entries if e.get("findings")),
            key=lambda e: (e["module"], e["previewId"]),
        )
    )


def cmd_comment(args: argparse.Namespace) -> int:
    findings_path = Path(args.findings)
    payload = json.loads(findings_path.read_text())
    entries: list[dict] = payload.get("entries", [])
    status: str | None = payload.get("status")

    # When a baseline (the on-`compose-preview/a11y/main` findings.json) is provided, stay
    # silent if nothing has changed — the workflow runs on every PR and a
    # comment that says "no findings" on PRs that don't touch a11y was
    # noted as distracting in user feedback. Empty stdout signals the
    # action to skip both the push and the upsert.
    #
    # `status` participates in the fingerprint so an atf-unavailable run on
    # this side never matches a clean-run baseline — that flips on the
    # comment so reviewers see the daemon failure rather than the misleading
    # "no findings" the bug in #1453 produced.
    baseline_entries: list[dict] = []
    baseline_status: str | None = None
    if args.baseline:
        baseline_path = Path(args.baseline)
        if baseline_path.exists():
            try:
                baseline_payload = json.loads(baseline_path.read_text())
            except json.JSONDecodeError:
                baseline_payload = {"entries": []}
            baseline_entries = baseline_payload.get("entries", [])
            baseline_status = baseline_payload.get("status")
            if (
                _findings_fingerprint(entries, status)
                == _findings_fingerprint(baseline_entries, baseline_status)
            ):
                return 0  # silent

    # Per-preview diff against the baseline. The comment used to re-post every
    # preview's findings on every PR that tripped the fingerprint (#1585);
    # instead, surface only the previews whose findings actually changed and
    # collapse the rest. A preview's findings key defaults to `()` when it's
    # absent from the baseline, so a brand-new *clean* preview compares equal
    # to its (empty) baseline and is treated as unchanged — only new or
    # changed findings get a full block.
    baseline_by_key: dict[tuple[str, str], tuple] = {
        (e["module"], e["previewId"]): _entry_findings_key(e)
        for e in baseline_entries
    }
    current_keys = {(e["module"], e["previewId"]) for e in entries}

    changed: list[dict] = []
    unchanged: list[dict] = []
    for entry in entries:
        key = (entry["module"], entry["previewId"])
        if _entry_findings_key(entry) != baseline_by_key.get(key, ()):
            changed.append(entry)
        else:
            unchanged.append(entry)

    # Previews that carried findings on the baseline but are gone now — the
    # underlying issue is no longer reachable. A clean baseline preview that
    # disappears isn't an accessibility change, so those are ignored.
    resolved = [
        e
        for e in baseline_entries
        if (e["module"], e["previewId"]) not in current_keys
        and _entry_findings_key(e)
    ]

    err, warn, info = _level_counts(entries)
    findings_count = err + warn + info

    marker = "<!-- a11y-report -->"
    lines = [
        marker,
        "## Accessibility Report",
        "",
    ]
    if status == ATF_UNAVAILABLE:
        # Header replaces the usual finding-counts line when ATF didn't run
        # — the counts would be misleadingly zero. The link to the CI logs
        # is left abstract on purpose; the workflow's failure step already
        # surfaces a direct link, and we don't want to embed the job URL
        # here (the python helper doesn't know it).
        lines.extend([
            "> [!WARNING]",
            "> ATF data unavailable — the preview daemon did not return "
            "accessibility findings for this run. The renders below are "
            "**not** accessibility-checked; see the CI logs for the daemon "
            "failure.",
            "",
        ])
    lines.extend([
        f"{err} error(s) · {warn} warning(s) · {info} info "
        f"across {len(entries)} preview(s).",
        "",
    ])

    if status == ATF_UNAVAILABLE:
        # ATF returned no data, so every current entry has empty findings —
        # not because the issues were fixed but because nothing was checked.
        # Diffing those empties against a baseline would render prior findings
        # as "resolved" / "_No findings._", making a daemon failure look like
        # a clean run. Surface only the warning + counts; the per-preview diff
        # is meaningless until ATF data comes back.
        sys.stdout.write("\n".join(lines).rstrip() + "\n")
        return 0

    if findings_count == 0:
        lines.append("No accessibility findings.")
        lines.append("")

    # Changed / new previews, grouped by module — these get the full block.
    changed_by_module: dict[str, list[dict]] = {}
    for entry in changed:
        changed_by_module.setdefault(entry["module"], []).append(entry)
    for module, module_entries in sorted(changed_by_module.items()):
        lines.append(f"### {module}")
        lines.append("")
        for entry in sorted(module_entries, key=lambda e: e["functionName"]):
            lines.extend(_entry_block(entry, args.head_ref, args.repo, image_width=240))

    if resolved:
        lines.append(f"### Resolved ({len(resolved)} preview(s) no longer present)")
        lines.append("")
        for entry in sorted(resolved, key=lambda e: (e["module"], e["functionName"])):
            lines.append(f"- `{entry['functionName']}` ({entry['module']})")
        lines.append("")

    if unchanged:
        # Collapse everything that didn't change into a names-only roster so
        # reviewers can still confirm the tool covered the full preview set
        # without scrolling past 100+ unchanged tables.
        lines.append("<details>")
        lines.append(f"<summary>Unchanged ({len(unchanged)} preview(s))</summary>")
        lines.append("")
        for entry in sorted(unchanged, key=lambda e: (e["module"], e["functionName"])):
            lines.append(f"- `{entry['functionName']}`")
        lines.append("")
        lines.append("</details>")
        lines.append("")

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
        "--build-dir", required=True, action="append",
        help="Path to a module's build/compose-previews directory. "
             "Repeatable: pass `--build-dir` once per module to fold every "
             "module the a11y CLI rendered into one findings.json.",
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
        help="compose-preview/a11y/pr commit SHA (or branch) for image URLs",
    )
    cm.add_argument(
        "--baseline", default=None,
        help="Optional baseline findings.json (from compose-preview/a11y/main). "
             "When set, the comment subcommand emits empty stdout if findings "
             "haven't changed vs the baseline — the action takes that as 'skip'.",
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
