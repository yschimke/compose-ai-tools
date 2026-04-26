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
# generate-resources mode
#
# Sibling of `generate`. Walks every `<module>/build/compose-previews/resources.json`
# under `--workspace-root` and copies the rendered PNGs / GIFs into
# `<output_dir>/renders/<module>/resources/<...>` so they land in `preview_main`
# alongside the existing composable baselines. Writes `resource-baselines.json`
# next to `baselines.json` and appends a section to the README.
#
# Independent walk (rather than reusing the `compose-preview show --json`
# output) — the CLI doesn't know about resources today, and keeping the
# resources path orthogonal to the composable path means modules with no
# `resources.json` (consumers who applied the plugin but never enabled
# `composePreview.resourcePreviews`) don't perturb the existing baselines tree.
# ---------------------------------------------------------------------------

def _resource_render_dest(render_output: str) -> str:
    """`renders/resources/drawable/foo.png` → `resources/drawable/foo.png`.

    The leading `renders/` segment is stripped so the destination tree is
    `<output>/renders/<module>/resources/...`, consistent with how the
    composable path lays out `<output>/renders/<module>/<basename>`.
    """
    if render_output.startswith("renders/"):
        return render_output[len("renders/"):]
    return render_output


def load_resource_manifests(workspace_root: Path) -> dict[str, dict]:
    """Globs `<module>/build/compose-previews/resources.json` and returns a
    flat key→entry map. Key shape: `<module>::<resourceId>::<renderOutput>` —
    stable enough to detect changes across runs without colliding when a single
    resource has multiple captures (e.g. adaptive-icon shape masks).

    Returns an empty dict when no `resources.json` exists anywhere — that's
    the dominant case for projects that don't write XML drawables, and an
    empty result is a no-op for `cmd_generate_resources`.
    """
    out: dict[str, dict] = {}
    for manifest_path in sorted(workspace_root.glob("*/build/compose-previews/resources.json")):
        module_dir = manifest_path.parent.parent.parent
        module = str(module_dir.relative_to(workspace_root)).replace("\\", "/")
        try:
            manifest = json.loads(manifest_path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        for resource in manifest.get("resources", []) or []:
            resource_id = resource.get("id")
            resource_type = resource.get("type", "")
            if not resource_id:
                continue
            for capture in resource.get("captures", []) or []:
                render_output = capture.get("renderOutput") or ""
                if not render_output:
                    continue
                src = module_dir / "build" / "compose-previews" / render_output
                key = f"{module}::{resource_id}::{render_output}"
                out[key] = {
                    "module": module,
                    "moduleDir": module_dir,
                    "resourceId": resource_id,
                    "resourceType": resource_type,
                    "renderOutput": render_output,
                    "destRelative": _resource_render_dest(render_output),
                    "pngPath": src,
                    "sha256": sha256(src) if src.exists() else None,
                    "qualifiers": (capture.get("variant") or {}).get("qualifiers"),
                    "shape": (capture.get("variant") or {}).get("shape"),
                }
    return out


def cmd_generate_resources(args: argparse.Namespace) -> int:
    workspace_root = Path(args.workspace_root).resolve()
    out_dir = Path(args.output_dir)

    entries = load_resource_manifests(workspace_root)
    if not entries:
        # Not an error — modules without resources.json are common.
        print("No Android resource manifests found; skipping resource baselines.",
              file=sys.stderr)
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    resource_baselines: dict[str, dict] = {}
    by_module: dict[str, list[tuple[str, dict]]] = {}
    for key, info in entries.items():
        if not info["sha256"]:
            continue
        dest = out_dir / "renders" / info["module"] / info["destRelative"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(info["pngPath"], dest)
        resource_baselines[key] = {
            "sha256": info["sha256"],
            "module": info["module"],
            "resourceId": info["resourceId"],
            "resourceType": info["resourceType"],
            "renderBasename": info["destRelative"],
            "qualifiers": info["qualifiers"],
            "shape": info["shape"],
        }
        by_module.setdefault(info["module"], []).append((key, info))

    if not resource_baselines:
        print("Resource manifests existed but no rendered PNGs were on disk; "
              "skipping resource baselines.",
              file=sys.stderr)
        return 0

    (out_dir / "resource-baselines.json").write_text(
        json.dumps(resource_baselines, indent=2, sort_keys=True) + "\n")

    # Append the resource gallery to the README. The composable `cmd_generate`
    # has already written the file; we add a sibling section. When run on its
    # own (no prior README), seed the file so the section header has a parent.
    readme = out_dir / "README.md"
    existing = readme.read_text() if readme.exists() else "# Preview Baselines\n"
    body_lines: list[str] = []
    if not existing.rstrip().endswith(""):
        body_lines.append("")
    body_lines += [
        "",
        "## Android XML Resource Previews",
        "",
        "Rendered from `:<module>:renderAndroidResources`. One row per "
        "(resource × qualifier × shape) capture. See "
        "[`design/RESOURCE_PREVIEWS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/design/RESOURCE_PREVIEWS.md) "
        "for the rendering catalogue.",
        "",
    ]
    for module in sorted(by_module):
        module_entries = sorted(by_module[module], key=lambda kv: kv[0])
        body_lines.append(f"### {module}")
        body_lines.append("")
        body_lines.append("| Resource | Type | Qualifiers | Shape | Image |")
        body_lines.append("|---|---|---|---|---|")
        for _, info in module_entries:
            qualifiers = info["qualifiers"] or "—"
            shape = info["shape"] or "—"
            img_path = f"renders/{info['module']}/{info['destRelative']}"
            body_lines.append(
                f"| `{info['resourceId']}` | {info['resourceType']} | "
                f"`{qualifiers}` | {shape} | <img src=\"{img_path}\" height=\"96\" /> |"
            )
        body_lines.append("")
    readme.write_text(existing.rstrip() + "\n" + "\n".join(body_lines).lstrip("\n") + "\n")
    return 0


# ---------------------------------------------------------------------------
# copy-changed-resources mode
# ---------------------------------------------------------------------------

def cmd_copy_changed_resources(args: argparse.Namespace) -> int:
    """Sibling of [cmd_copy_changed]. Globs every
    `<workspace>/<module>/build/compose-previews/resources.json`, compares the
    rendered PNGs / GIFs against `resource-baselines.json`, and copies new or
    changed ones into `<output>/renders/<module>/resources/<...>`.

    Output layout matches [cmd_generate_resources] so the push to `preview_pr`
    lands these PNGs at paths the comment markdown can `_render_url` to.
    Modules without `resources.json` are skipped silently — same behaviour as
    `cmd_generate_resources`.
    """
    workspace_root = Path(args.workspace_root).resolve()
    baselines_path = Path(args.baselines)
    out_dir = Path(args.output_dir)

    entries = load_resource_manifests(workspace_root)
    if not entries:
        return 0
    baselines = json.loads(baselines_path.read_text()) if baselines_path.exists() else {}

    copied = 0
    for key, info in entries.items():
        if not info["sha256"]:
            continue
        is_new = key not in baselines
        is_changed = not is_new and info["sha256"] != baselines[key]["sha256"]
        if not (is_new or is_changed):
            continue
        dest = out_dir / "renders" / info["module"] / info["destRelative"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(info["pngPath"], dest)
        copied += 1

    print(f"Copied {copied} changed/new resource preview(s) to {out_dir}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# compare-resources mode
# ---------------------------------------------------------------------------

def _resource_url(repo: str, ref: str, module: str, basename: str) -> str:
    """Same shape as [_render_url] but the resource basenames carry their own
    `resources/<type>/...` prefix, so the URL encodes
    `renders/<module>/resources/...`."""
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{module}/{basename}"
    )


def _resource_label(info: dict) -> str:
    """Human-readable label for one resource capture: `xhdpi`, `night-xhdpi`,
    `xhdpi · CIRCLE`, or `default` when no qualifiers / shape are set. Mirrors
    [_entry_label]'s role for composables."""
    parts: list[str] = []
    qualifiers = info.get("qualifiers")
    shape = info.get("shape")
    if qualifiers:
        parts.append(qualifiers)
    if shape:
        parts.append(shape)
    return " · ".join(parts) if parts else "default"


def cmd_compare_resources(args: argparse.Namespace) -> int:
    """Sibling of [cmd_compare] that emits a "Resource changes" markdown
    section against `resource-baselines.json`. No leading marker — the
    composable comment owns the marker, and this output is concatenated by
    `preview-comment/action.yml` after [cmd_compare]'s body. Emits an empty
    string when no resource manifests exist or no diff is detected, so the
    action can append unconditionally without polluting the comment.
    """
    workspace_root = Path(args.workspace_root).resolve()
    baselines_path = Path(args.baselines)
    repo = args.repo
    base_ref = args.base_ref
    head_ref = args.head_ref

    current = load_resource_manifests(workspace_root)
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

    if not (new or changed or removed):
        return 0  # nothing to append

    lines: list[str] = ["## Resource Changes", ""]

    if changed:
        # Group by (module, resourceId) so all captures of the same resource
        # land under one heading — adaptive icons fan out to 4 shape masks
        # per icon and the per-capture rows would otherwise drown the diff.
        groups: dict[tuple[str, str], list[tuple[str, dict, dict]]] = {}
        for key, cur, bl in changed:
            groups.setdefault((cur["module"], cur["resourceId"]), []).append((key, cur, bl))
        lines.append(
            f"### Changed ({len(changed)} variant(s) across {len(groups)} resource(s))"
        )
        lines.append("")
        for (module, resource_id), entries in sorted(groups.items()):
            hero_key, hero_cur, hero_bl = entries[0]
            before = _resource_url(repo, base_ref, module, hero_cur["destRelative"])
            after = _resource_url(repo, head_ref, module, hero_cur["destRelative"])
            lines.append(f"**`{resource_id}`** ({module}, {hero_cur['resourceType']})")
            lines.append("")
            lines.append("| Before | After |")
            lines.append("|--------|-------|")
            lines.append(
                f"| <img src=\"{before}\" width=\"200\" /> "
                f"| <img src=\"{after}\" width=\"200\" /> |"
            )
            if len(entries) > 1:
                variant_links = []
                for _okey, ocur, _obl in entries[1:]:
                    label = _resource_label(ocur)
                    link = _resource_url(repo, head_ref, module, ocur["destRelative"])
                    variant_links.append(f"[{label}]({link})")
                lines.append("")
                lines.append(f"Other variants: {', '.join(variant_links)}")
            lines.append("")

    if new:
        groups_new: dict[tuple[str, str], list[tuple[str, dict]]] = {}
        for key, info in new:
            groups_new.setdefault((info["module"], info["resourceId"]), []).append((key, info))
        lines.append(
            f"### New ({len(new)} variant(s) across {len(groups_new)} resource(s))"
        )
        lines.append("")
        for (module, resource_id), entries in sorted(groups_new.items()):
            hero_key, hero_info = entries[0]
            after = _resource_url(repo, head_ref, module, hero_info["destRelative"])
            lines.append(
                f"**`{resource_id}`** ({module}, {hero_info['resourceType']}) "
                f"<img src=\"{after}\" width=\"200\" />"
            )
            if len(entries) > 1:
                variant_links = []
                for _okey, oinfo in entries[1:]:
                    label = _resource_label(oinfo)
                    link = _resource_url(repo, head_ref, module, oinfo["destRelative"])
                    variant_links.append(f"[{label}]({link})")
                lines.append(f"Variants: {', '.join(variant_links)}")
            lines.append("")

    if removed:
        # Group by (module, resourceId) — a resource being deleted typically
        # removes all its captures at once, surfacing them as N rows would be
        # noise.
        rm_resources = sorted({(bl_info.get("module", "?"), bl_info.get("resourceId", "?"))
                               for _, bl_info in removed})
        lines.append(f"### Removed ({len(removed)} variant(s) across {len(rm_resources)} resource(s))")
        lines.append("")
        for module, resource_id in rm_resources:
            lines.append(f"- ~`{resource_id}`~ ({module})")
        lines.append("")

    print("\n".join(lines))
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

    gen_res = sub.add_parser(
        "generate-resources",
        help="Walk every <module>/build/compose-previews/resources.json and stage the "
             "rendered PNGs / GIFs into the baselines tree. No-ops on workspaces "
             "without any resources.json.",
    )
    gen_res.add_argument("--output-dir", required=True)
    gen_res.add_argument("--workspace-root", default=".",
                         help="Root to glob for <module>/build/compose-previews/resources.json")

    cp_res = sub.add_parser(
        "copy-changed-resources",
        help="Sibling of `copy-changed` for resource captures.",
    )
    cp_res.add_argument("--baselines", required=True,
                        help="Path to resource-baselines.json (fetched from preview_main)")
    cp_res.add_argument("--output-dir", required=True)
    cp_res.add_argument("--workspace-root", default=".")

    cmp_res = sub.add_parser(
        "compare-resources",
        help="Sibling of `compare` for resource captures. Emits the markdown section "
             "to append after the composable diff. Empty stdout when nothing changed.",
    )
    cmp_res.add_argument("--baselines", required=True,
                         help="Path to resource-baselines.json (fetched from preview_main)")
    cmp_res.add_argument("--repo", required=True)
    cmp_res.add_argument("--base-ref", default="preview_main")
    cmp_res.add_argument("--head-ref", required=True)
    cmp_res.add_argument("--workspace-root", default=".")

    args = ap.parse_args()
    handlers = {
        "generate": cmd_generate,
        "compare": cmd_compare,
        "copy-changed": cmd_copy_changed,
        "generate-resources": cmd_generate_resources,
        "copy-changed-resources": cmd_copy_changed_resources,
        "compare-resources": cmd_compare_resources,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
