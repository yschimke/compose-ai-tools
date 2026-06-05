#!/usr/bin/env python3
"""Fold `@XrSubspacePreview` render output into a browsable preview gallery.

`composePreviewRenderXr` writes, per XR preview,
``build/compose-previews/renders/<preview>/scene.json`` (the framework-computed
spatial panel layout — see ``docs/design/SPATIAL_SCENE_CONTRACT.md``) plus one
``<panelId>.png`` texture per ``SpatialPanel``. Unlike a flat ``@Preview`` (one
preview-named PNG), an XR preview produces a *directory* of artifacts and no
single PNG, so the CLI's ``compose-preview show --json`` envelope carries no
``pngPath`` for it (``kind == "XR_SUBSPACE"``) — which is exactly why
``compare-previews.py generate`` (envelope-driven) can't reach these files and
leaves XR previews out of the gallery README.

This helper closes that gap from the filesystem side: after the gallery has been
staged, it copies each scene's ``scene.json`` + panel textures under
``<gallery>/renders/xr/<preview>/`` and appends an "XR spatial previews" section
to the gallery ``README.md`` — a per-preview table of panel textures plus a link
to the recovered ``scene.json``. The integration workflow's "Fold XR scenes into
the browsable gallery" step runs it against the ``_integration_baselines``
payload before the publish job pushes the ``compose-preview/integration/<slug>``
branch.

Idempotent within a run (single append); re-running ``generate`` upstream wipes
``renders/`` first, so the fold always re-copies fresh.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

# Marker so a second invocation against an already-folded README is a no-op
# rather than appending the section twice.
_SECTION_MARKER = "<!-- xr-spatial-previews -->"

# Render-output layout written by composePreviewRenderXr.
_SCENE_GLOB = "**/build/compose-previews/renders/*/scene.json"


def _safe_texture_name(texture: object) -> str | None:
    """Return [texture] iff it's a plain basename safe to copy/link, else None.

    `texture` comes from a `scene.json` the renderer wrote, but the staged gallery
    is later pushed to a shared branch by the `publish-previews` job, so a crafted
    scene must not be able to pull files from outside the render directory into the
    published tree (path traversal). The renderer only ever emits a bare
    `<panelId>.png` sibling of scene.json, so we accept *only* a basename: anything
    with a directory component, an absolute path, or a `..` segment is rejected.
    """
    if not isinstance(texture, str) or not texture:
        return None
    name = Path(texture).name
    # `Path(texture).name` strips any directory part, so a value that survives
    # unchanged has no `/`, no leading `/`, and isn't `.`/`..`.
    if name != texture or name in (".", ".."):
        return None
    return name


def _panel_rows(panels: list[dict], rel_dir: str, columns: int = 3) -> list[str]:
    """Markdown tables (one per `columns`-wide chunk) embedding each panel texture.

    Each panel carries `id`, `sizeDp` ({width,height}) and `texture` (the PNG
    basename, a sibling of scene.json). Panels whose texture is missing on disk
    are still listed (header only) so a partial render is visible rather than
    silently dropped.
    """
    lines: list[str] = []
    for start in range(0, len(panels), columns):
        chunk = panels[start : start + columns]
        headers = []
        cells = []
        for panel in chunk:
            pid = str(panel.get("id", "?"))
            size = panel.get("sizeDp") or {}
            w, h = size.get("width"), size.get("height")
            dims = f" ({w}×{h})" if w is not None and h is not None else ""
            headers.append(f"`{pid}`{dims}")
            texture = _safe_texture_name(panel.get("texture"))
            if texture:
                cells.append(f"![{pid}]({rel_dir}/{texture})")
            else:
                cells.append("_(no texture)_")
        lines.append("| " + " | ".join(headers) + " |")
        lines.append("| " + " | ".join("---" for _ in chunk) + " |")
        lines.append("| " + " | ".join(cells) + " |")
        lines.append("")
    return lines


def fold(gallery_dir: Path, search_root: Path) -> int:
    """Copy XR scene artifacts into [gallery_dir] and append a README section.

    Returns the number of scenes folded. A missing README (gallery wasn't
    staged — e.g. the upstream `generate` bailed) or no scenes found are both
    no-ops returning 0, so the caller never fails the build over a missing
    gallery.
    """
    readme = gallery_dir / "README.md"
    if not readme.is_file():
        print(f"no staged gallery README at {readme}; nothing to fold")
        return 0
    if _SECTION_MARKER in readme.read_text():
        print("README already contains the XR section; skipping")
        return 0

    scenes = sorted(
        p
        for p in search_root.glob(_SCENE_GLOB)
        if "/external/build/" not in p.as_posix()
    )
    if not scenes:
        print("no scene.json under the search root; nothing to fold")
        return 0

    lines = [
        "",
        _SECTION_MARKER,
        "## XR spatial previews",
        "",
        "Each `@XrSubspacePreview` renders to a `scene.json` (the framework-computed "
        "spatial panel layout) plus one texture per `SpatialPanel`, recovered offline "
        "by `composePreviewRenderXr` — no headset, no OpenXR.",
        "",
    ]
    for scene in scenes:
        name = scene.parent.name
        dest = gallery_dir / "renders" / "xr" / name
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(scene, dest / "scene.json")
        try:
            data = json.loads(scene.read_text())
        except (OSError, json.JSONDecodeError) as exc:
            print(f"::warning::could not parse {scene}: {exc}; linking raw only")
            data = {}
        panels = data.get("panels") if isinstance(data.get("panels"), list) else []
        # Copy the textures the scene references (must be basenames living next
        # to scene.json — see _safe_texture_name for why traversal is rejected).
        for panel in panels:
            texture = _safe_texture_name(panel.get("texture"))
            if not texture:
                if panel.get("texture"):
                    print(
                        f"::warning::ignoring unsafe texture path "
                        f"{panel.get('texture')!r} in {scene}"
                    )
                continue
            src = scene.parent / texture
            if src.is_file():
                shutil.copy2(src, dest / texture)
        rel_dir = f"renders/xr/{name}"
        lines.append(f"### `{name}` — {len(panels)} panel(s)")
        lines.append("")
        lines.append(f"[`scene.json`]({rel_dir}/scene.json)")
        lines.append("")
        lines.extend(_panel_rows(panels, rel_dir))

    with readme.open("a") as fh:
        fh.write("\n".join(lines).rstrip() + "\n")
    print(f"folded {len(scenes)} XR scene(s) into {readme}")
    return len(scenes)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    fold_p = sub.add_parser("fold", help="fold XR scene artifacts into a gallery")
    fold_p.add_argument(
        "--gallery-dir",
        required=True,
        help="the staged gallery dir (contains README.md + renders/)",
    )
    fold_p.add_argument(
        "--search-root",
        default=".",
        help="root to scan for build/compose-previews/renders/*/scene.json",
    )
    args = parser.parse_args(argv)
    if args.command == "fold":
        fold(Path(args.gallery_dir), Path(args.search_root))
        return 0
    parser.error(f"unknown command {args.command!r}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
