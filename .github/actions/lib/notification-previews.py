#!/usr/bin/env python3
"""Stage notification preview PNGs and generate diff comments / READMEs.

Sibling of ``a11y-report.py`` but scoped to the notification-rendering surface
(``samples/android`` → ``compose-preview/notifications/{main,pr}``). Reads the
rendered ``*Notification*.png`` files plus the optional structured-fields
``*.notification.json`` sidecars and emits either:

* A baseline-branch ``README.md`` browsing each notification preview, or
* A PR-comment Markdown body listing added / changed / removed previews
  vs the ``compose-preview/notifications/main`` baseline. Changes are
  detected via sha256 of the PNG content so that re-renders of the same
  bytes don't wake the comment.

Subcommands
-----------
stage
    Collect every ``*Notification*.png`` under
    ``samples/android/build/compose-previews/renders/`` plus the matching
    ``*.notification.json`` sidecar under
    ``samples/android/build/compose-previews/data/notifications/`` into a
    staging dir, and write ``findings.json`` summarising what was staged
    (one entry per preview: id, png filename, sidecar filename, sha256) plus
    ``declaredPreviewIds`` — every notification previewId the manifests
    declared — and ``declaredFanoutBases`` — the unsuffixed stems of any
    ``@PreviewParameter`` previews, whose rendered ids gain a ``_<label>``
    suffix the manifest stem lacks. The comment uses both to tell a removal
    from a failed render.

readme
    Render ``findings.json`` to a browsable Markdown gallery with inline
    images (raw.githubusercontent SHA-pinned through the branch name).

comment
    Render ``findings.json`` plus a baseline ``findings.json`` to a
    PR-comment Markdown body listing added / changed / removed entries. A
    baseline preview missing from the head render is only reported as
    *removed* when the head manifest no longer declares it; one that's still
    declared but didn't render is surfaced under a *not rendered* warning
    instead of a false removal. Emits empty stdout (the action treats that as
    "skip") when the diff against the baseline is empty.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from fnmatch import fnmatch
from pathlib import Path


# ---------------------------------------------------------------------------
# stage
# ---------------------------------------------------------------------------

# Glob globs intentionally permissive — `*Notification*` catches both the
# `NotificationPreviewsKt.*` FQN-discovered output and the
# `NotificationVariantPreviewsKt.*` / `NotificationStyleGalleryKt.*` `@Preview`
# helper output. See `notification-previews.yml` for the matching shell glob.
_PNG_GLOB = "*Notification*.png"


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _preview_id_from_png(png_path: Path) -> str:
    """Recover the previewId from a rendered PNG basename.

    The Gradle plugin writes ``renders/<previewId>.png`` so stripping the
    ``.png`` suffix gives the previewId verbatim. Keeping this as a single
    helper so the sidecar lookup mirrors the same derivation.
    """
    return png_path.stem


# The renderer's sidecar writer sanitises the previewId before using it as a
# filename. Mirror that here so we can pair PNGs with their sidecars without
# re-reading the JSON. Keep this in sync with
# `NotificationSidecar.sanitize` in renderer-android.
_SANITIZE_REPLACE = set('/\\:*?"<>| \t\n\r')


def _sanitize_preview_id(preview_id: str) -> str:
    return "".join("_" if c in _SANITIZE_REPLACE else c for c in preview_id)


def _declared_notification_ids(build_dir: Path) -> tuple[set[str], set[str]]:
    """Notification previewIds the module *declares*, read from previews.json.

    Returns ``(declared, fanout_bases)``.

    ``declared`` mirrors the staging glob: a manifest preview counts as a
    notification preview when any of its capture render outputs is a
    ``*Notification*.png``. The id is derived from the PNG basename (its stem)
    rather than the manifest ``id`` so it matches the previewId
    [_preview_id_from_png] assigns to the staged render — including
    ``@Preview`` gallery variants whose render basename carries a ``_<name>``
    suffix the manifest id lacks.

    ``fanout_bases`` holds the stems of previews that carry a
    ``@PreviewParameter`` provider. The renderer expands those *after* loading
    the manifest, appending a ``_<label>`` / ``_PARAM_<idx>`` suffix to each
    capture's renderOutput (see ``RobolectricRenderTest.expandParameterProvider``),
    so the actual rendered (and baseline) previewIds are ``<stem>_<suffix>`` —
    ids the unsuffixed manifest stem never matches. The comment subcommand
    prefix-matches missing baseline ids against these bases so a parameterized
    notification preview that fails to render is still classed as *not
    rendered* rather than *removed*.

    Used by the comment subcommand to tell a *removed* preview (gone from the
    manifest) apart from one that's declared but failed to render this run.
    """
    previews_json = build_dir / "previews.json"
    if not previews_json.exists():
        return set(), set()
    try:
        manifest = json.loads(previews_json.read_text())
    except json.JSONDecodeError:
        return set(), set()
    declared: set[str] = set()
    fanout_bases: set[str] = set()
    for preview in manifest.get("previews", []):
        has_provider = bool((preview.get("params") or {}).get("previewParameterProviderClassName"))
        for capture in preview.get("captures", []):
            name = Path(capture.get("renderOutput") or "").name
            if fnmatch(name, _PNG_GLOB):
                stem = Path(name).stem
                declared.add(stem)
                if has_provider:
                    fanout_bases.add(stem)
    return declared, fanout_bases


def cmd_stage(args: argparse.Namespace) -> int:
    # ``--build-dir`` is repeatable so one `stage` invocation can fold every
    # module that registers ``composePreviewRenderAll`` into a single flat
    # ``_notification_renders/renders/`` tree. argparse hands a list; older
    # callers that pass a bare string still work via the normalize.
    raw = args.build_dir
    build_dirs = [raw] if isinstance(raw, (str, Path)) else list(raw)
    out_dir = Path(args.output_dir)

    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    renders_out = out_dir / "renders"
    renders_out.mkdir(parents=True, exist_ok=True)
    sidecars_out = out_dir / "data" / "notifications"

    entries: list[dict] = []
    seen_basenames: dict[str, str] = {}
    total_pngs = 0
    modules_seen: list[str] = []
    declared_ids: set[str] = set()
    fanout_bases: set[str] = set()

    for raw_build_dir in build_dirs:
        build_dir = Path(raw_build_dir)
        renders_dir = build_dir / "renders"
        sidecar_dir = build_dir / "data" / "notifications"
        if not renders_dir.is_dir():
            print(
                f"::error::renders dir not found at {renders_dir}",
                file=sys.stderr,
            )
            return 1

        # Record what the manifest declares even when a render is missing, so
        # the comment can distinguish a failed render from a real removal.
        module_declared, module_fanout = _declared_notification_ids(build_dir)
        declared_ids |= module_declared
        fanout_bases |= module_fanout

        pngs = sorted(renders_dir.glob(_PNG_GLOB))
        if not pngs:
            # No-match in a single build dir is informational, not fatal:
            # when ``compose-preview a11y`` and the notifications Gradle task
            # both run across every module, plenty of modules will have
            # ``build/compose-previews/renders/`` directories with no
            # ``*Notification*.png`` inside. We only fail when *every* module
            # produced nothing (checked after the loop).
            print(
                f"notifications stage: no PNGs matched {_PNG_GLOB} under {renders_dir}",
                file=sys.stderr,
            )
            continue
        # Module label is best-effort — pulled from previews.json when present
        # so the per-entry ``module`` is provenance the comment renderer can
        # use later. Falls back to the build dir's grandparent path otherwise.
        module_label = ""
        previews_json = build_dir / "previews.json"
        if previews_json.exists():
            try:
                module_label = json.loads(previews_json.read_text()).get("module", "")
            except json.JSONDecodeError:
                module_label = ""
        if not module_label:
            module_label = build_dir.parent.parent.name if build_dir.parent.parent else ""
        modules_seen.append(module_label or str(build_dir))

        for png in pngs:
            preview_id = _preview_id_from_png(png)
            png_basename = png.name
            # Collisions across modules would silently clobber the flat
            # `renders/` dir. Fail loud — the renderer ships FQN-derived
            # previewIds so this should never trip; if it does, the modules
            # are genuinely shipping the same notification under the same id
            # and that needs fixing upstream rather than papering over.
            if png_basename in seen_basenames and seen_basenames[png_basename] != module_label:
                print(
                    f"::error::Notification preview basename collision: "
                    f"'{png_basename}' produced by both "
                    f"{seen_basenames[png_basename]!r} and {module_label!r}.",
                    file=sys.stderr,
                )
                return 1
            seen_basenames[png_basename] = module_label
            shutil.copy2(png, renders_out / png_basename)
            sidecar_basename = ""
            if sidecar_dir.is_dir():
                candidate = sidecar_dir / f"{_sanitize_preview_id(preview_id)}.notification.json"
                if candidate.is_file():
                    sidecars_out.mkdir(parents=True, exist_ok=True)
                    sidecar_basename = candidate.name
                    shutil.copy2(candidate, sidecars_out / sidecar_basename)
            entries.append({
                "module": module_label,
                "previewId": preview_id,
                "pngBasename": png_basename,
                "sidecarBasename": sidecar_basename,
                "sha256": _sha256(png),
            })
            total_pngs += 1

    if not entries:
        # Every module produced nothing — keep the historical hard-fail so a
        # green CI run can't quietly ship an empty baseline.
        print(
            f"::error::No notification preview PNGs were rendered under any of: "
            f"{', '.join(str(p) for p in build_dirs)}",
            file=sys.stderr,
        )
        return 1

    entries.sort(key=lambda e: (e["module"], e["previewId"]))
    (out_dir / "findings.json").write_text(
        json.dumps(
            {
                "entries": entries,
                "declaredPreviewIds": sorted(declared_ids),
                "declaredFanoutBases": sorted(fanout_bases),
            },
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    print(
        f"Staged {total_pngs} notification preview(s) across "
        f"{len(modules_seen)} module(s) to {out_dir}",
        file=sys.stderr,
    )
    return 0


# ---------------------------------------------------------------------------
# Shared rendering helpers
# ---------------------------------------------------------------------------

def _image_url(repo: str, ref: str, basename: str) -> str:
    return (
        f"https://raw.githubusercontent.com/{repo}/{ref}"
        f"/renders/{basename}"
    )


def _load_entries(path: Path) -> list[dict]:
    if not path.exists():
        return []
    try:
        return json.loads(path.read_text()).get("entries", [])
    except json.JSONDecodeError:
        return []


def _load_declared(path: Path) -> tuple[set[str], set[str]]:
    """``(declaredPreviewIds, declaredFanoutBases)`` from a findings.json (see stage)."""
    if not path.exists():
        return set(), set()
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        return set(), set()
    return (
        set(payload.get("declaredPreviewIds", [])),
        set(payload.get("declaredFanoutBases", [])),
    )


def _is_declared(preview_id: str, declared: set[str], fanout_bases: set[str]) -> bool:
    """Whether the head manifest declared ``preview_id``.

    Exact match covers static previews and multi-``@Preview`` variants (the
    manifest carries their suffixed renderOutput verbatim). The prefix check
    covers ``@PreviewParameter`` fan-out, whose rendered ids are
    ``<base>_<label>`` / ``<base>_PARAM_<idx>`` — the manifest only has the
    unsuffixed ``<base>`` (the renderer appends the suffix post-load), so a
    baseline id beginning with a declared fan-out base + ``_`` is still
    declared.
    """
    if preview_id in declared:
        return True
    return any(preview_id.startswith(f"{base}_") for base in fanout_bases)


def _by_id(entries: list[dict]) -> dict[str, dict]:
    return {e["previewId"]: e for e in entries}


# ---------------------------------------------------------------------------
# readme
# ---------------------------------------------------------------------------

def cmd_readme(args: argparse.Namespace) -> int:
    entries = _load_entries(Path(args.findings))
    entries = sorted(entries, key=lambda e: e["previewId"])

    lines = [
        "# Notification Previews",
        "",
        f"_Auto-generated from `{args.branch}`. "
        f"{len(entries)} notification preview(s)._",
        "",
        "Browse inline; image URLs reference the branch tip so links keep "
        "resolving as long as the branch advances.",
        "",
    ]
    if not entries:
        lines.append("No notification previews.")
        lines.append("")

    for entry in entries:
        preview_id = entry["previewId"]
        png = entry["pngBasename"]
        lines.append(f"### `{preview_id}`")
        lines.append("")
        if png:
            url = _image_url(args.repo, args.branch, png)
            lines.append(f'<img src="{url}" width="400" />')
            lines.append("")
        if entry.get("sidecarBasename"):
            sidecar_url = (
                f"https://github.com/{args.repo}/blob/{args.branch}"
                f"/data/notifications/{entry['sidecarBasename']}"
            )
            lines.append(f"- Structured fields: [`{entry['sidecarBasename']}`]({sidecar_url})")
            lines.append("")

    body = "\n".join(lines).rstrip() + "\n"
    out_path = Path(args.output) if args.output else None
    if out_path:
        out_path.write_text(body)
    else:
        sys.stdout.write(body)
    return 0


# ---------------------------------------------------------------------------
# comment
# ---------------------------------------------------------------------------

def _diff(
    head: list[dict], baseline: list[dict]
) -> tuple[list[dict], list[tuple[dict, dict]], list[dict]]:
    """Return (added, changed, removed) lists.

    Added: previewId in head but not baseline.
    Changed: previewId in both, but sha256 differs.
    Removed: previewId in baseline but not head.
    """
    head_by_id = _by_id(head)
    base_by_id = _by_id(baseline)
    added: list[dict] = []
    changed: list[tuple[dict, dict]] = []
    removed: list[dict] = []
    for pid, entry in sorted(head_by_id.items()):
        if pid not in base_by_id:
            added.append(entry)
        elif entry.get("sha256") != base_by_id[pid].get("sha256"):
            changed.append((entry, base_by_id[pid]))
    for pid, entry in sorted(base_by_id.items()):
        if pid not in head_by_id:
            removed.append(entry)
    return added, changed, removed


def cmd_comment(args: argparse.Namespace) -> int:
    head = _load_entries(Path(args.findings))
    declared, fanout_bases = _load_declared(Path(args.findings))
    baseline = _load_entries(Path(args.baseline)) if args.baseline else []

    added, changed, removed = _diff(head, baseline)

    # Split the baseline previews that are missing from the head render into
    # genuine removals vs render failures. A preview the head still *declares*
    # in its manifest but that produced no PNG this run wasn't deleted — its
    # render (or its module's build) failed. Reporting it as "Removed" turns a
    # CI breakage into a false "someone deleted this" alarm (#1588). Only call a
    # missing preview removed when the head no longer declares it; when there's
    # no manifest coverage at all (empty `declared`) we can't be sure, so we
    # err toward "not rendered" rather than asserting a removal.
    has_coverage = bool(declared or fanout_bases)
    not_rendered: list[dict] = []
    truly_removed: list[dict] = []
    for entry in removed:
        if not has_coverage or _is_declared(entry["previewId"], declared, fanout_bases):
            not_rendered.append(entry)
        else:
            truly_removed.append(entry)
    removed = truly_removed

    if not added and not changed and not removed and not not_rendered:
        # Empty stdout signals the workflow to skip the push + upsert. PRs
        # that don't touch notifications shouldn't generate noise.
        return 0

    marker = "<!-- notification-previews -->"
    summary = f"{len(added)} added · {len(changed)} changed · {len(removed)} removed"
    if not_rendered:
        summary += f" · {len(not_rendered)} not rendered"
    summary += f" vs `{args.baseline_branch}`."
    lines = [
        marker,
        "## Notification Previews",
        "",
        summary,
        "",
    ]

    if added:
        lines.append("### Added")
        lines.append("")
        lines.append("| Preview | Image |")
        lines.append("|---|---|")
        for entry in added:
            url = _image_url(args.repo, args.head_ref, entry["pngBasename"])
            lines.append(
                f"| `{entry['previewId']}` "
                f'| <img src="{url}" width="240" /> |'
            )
        lines.append("")

    if changed:
        lines.append("### Changed")
        lines.append("")
        lines.append("| Preview | Baseline | PR |")
        lines.append("|---|---|---|")
        for head_entry, base_entry in changed:
            head_url = _image_url(
                args.repo, args.head_ref, head_entry["pngBasename"]
            )
            base_url = _image_url(
                args.repo, args.baseline_branch, base_entry["pngBasename"]
            )
            lines.append(
                f"| `{head_entry['previewId']}` "
                f'| <img src="{base_url}" width="240" /> '
                f'| <img src="{head_url}" width="240" /> |'
            )
        lines.append("")

    if removed:
        lines.append("### Removed")
        lines.append("")
        lines.append("| Preview | Last baseline image |")
        lines.append("|---|---|")
        for entry in removed:
            url = _image_url(
                args.repo, args.baseline_branch, entry["pngBasename"]
            )
            lines.append(
                f"| `{entry['previewId']}` "
                f'| <img src="{url}" width="240" /> |'
            )
        lines.append("")

    if not_rendered:
        lines.append("### ⚠️ Not rendered")
        lines.append("")
        lines.append("> [!WARNING]")
        lines.append(
            "> These previews are still declared in the head manifest but "
            "produced no render this run — a render or build failure, **not** a "
            "removal. Images below are the last known baseline renders."
        )
        lines.append("")
        lines.append("| Preview | Last baseline image |")
        lines.append("|---|---|")
        for entry in sorted(not_rendered, key=lambda e: e["previewId"]):
            url = _image_url(
                args.repo, args.baseline_branch, entry["pngBasename"]
            )
            lines.append(
                f"| `{entry['previewId']}` "
                f'| <img src="{url}" width="240" /> |'
            )
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

    st = sub.add_parser(
        "stage",
        help="Collect PNGs + sidecars into a staging dir and emit findings.json",
    )
    st.add_argument(
        "--build-dir", required=True, action="append",
        help="Path to a module's build/compose-previews directory. "
             "Repeatable: pass `--build-dir` once per module to fold every "
             "module's notifications into one findings.json.",
    )
    st.add_argument("--output-dir", required=True)

    rd = sub.add_parser("readme", help="Render findings.json to README.md")
    rd.add_argument("findings", help="Path to findings.json")
    rd.add_argument("--repo", required=True, help="owner/repo")
    rd.add_argument("--branch", required=True, help="Branch hosting the renders")
    rd.add_argument(
        "--output", default=None,
        help="Output README path (default: stdout)",
    )

    cm = sub.add_parser("comment", help="Render diff vs baseline to PR comment body")
    cm.add_argument("findings", help="Path to head findings.json")
    cm.add_argument("--repo", required=True)
    cm.add_argument(
        "--head-ref", required=True,
        help="compose-preview/notifications/pr commit SHA (or branch) for image URLs",
    )
    cm.add_argument(
        "--baseline", default=None,
        help="Path to baseline findings.json (from compose-preview/notifications/main).",
    )
    cm.add_argument(
        "--baseline-branch", default="compose-preview/notifications/main",
        help="Baseline branch name (used for image URLs of removed/changed entries).",
    )

    args = ap.parse_args()
    handlers = {
        "stage": cmd_stage,
        "readme": cmd_readme,
        "comment": cmd_comment,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
