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
    (one entry per preview: id, png filename, sidecar filename, sha256).

readme
    Render ``findings.json`` to a browsable Markdown gallery with inline
    images (raw.githubusercontent SHA-pinned through the branch name).

comment
    Render ``findings.json`` plus a baseline ``findings.json`` to a
    PR-comment Markdown body listing added / changed / removed entries.
    Emits empty stdout (the action treats that as "skip") when the diff
    against the baseline is empty.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
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


def cmd_stage(args: argparse.Namespace) -> int:
    build_dir = Path(args.build_dir)
    out_dir = Path(args.output_dir)
    renders_dir = build_dir / "renders"
    sidecar_dir = build_dir / "data" / "notifications"

    if not renders_dir.is_dir():
        print(
            f"::error::renders dir not found at {renders_dir}",
            file=sys.stderr,
        )
        return 1

    pngs = sorted(renders_dir.glob(_PNG_GLOB))
    if not pngs:
        # Empty result is a hard failure — if nothing matched, either the
        # sample lost its notification previews or the discovery path
        # silently dropped them, and a green CI run would happily ship
        # nothing. Mirrors the inline shell guard the workflow used to do.
        print(
            f"::error::No notification preview PNGs were rendered under {renders_dir}",
            file=sys.stderr,
        )
        return 1

    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    renders_out = out_dir / "renders"
    renders_out.mkdir(parents=True, exist_ok=True)
    sidecars_out = out_dir / "data" / "notifications"

    entries: list[dict] = []
    for png in pngs:
        preview_id = _preview_id_from_png(png)
        png_basename = png.name
        shutil.copy2(png, renders_out / png_basename)
        sidecar_basename = ""
        if sidecar_dir.is_dir():
            candidate = sidecar_dir / f"{_sanitize_preview_id(preview_id)}.notification.json"
            if candidate.is_file():
                sidecars_out.mkdir(parents=True, exist_ok=True)
                sidecar_basename = candidate.name
                shutil.copy2(candidate, sidecars_out / sidecar_basename)
        entries.append({
            "previewId": preview_id,
            "pngBasename": png_basename,
            "sidecarBasename": sidecar_basename,
            "sha256": _sha256(png),
        })

    entries.sort(key=lambda e: e["previewId"])
    (out_dir / "findings.json").write_text(
        json.dumps({"entries": entries}, indent=2, sort_keys=True) + "\n"
    )
    print(
        f"Staged {len(entries)} notification preview(s) to {out_dir}",
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
    baseline = _load_entries(Path(args.baseline)) if args.baseline else []

    added, changed, removed = _diff(head, baseline)
    if not added and not changed and not removed:
        # Empty stdout signals the workflow to skip the push + upsert. PRs
        # that don't touch notifications shouldn't generate noise.
        return 0

    marker = "<!-- notification-previews -->"
    lines = [
        marker,
        "## Notification Previews",
        "",
        f"{len(added)} added · {len(changed)} changed · {len(removed)} removed "
        f"vs `{args.baseline_branch}`.",
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
        "--build-dir", required=True,
        help="Path to samples/android/build/compose-previews",
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
