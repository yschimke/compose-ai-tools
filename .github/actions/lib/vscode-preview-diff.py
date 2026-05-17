#!/usr/bin/env python3
"""Generate vscode-preview baselines or compare against them.

The VS Code panel's `preview-harness` writes `<fixture>.<theme>.png`
files into `vscode-extension/preview-harness/out/`. Each
(fixture, theme) pair is the unit of comparison. This is a much
simpler shape than the gradle-preview pipeline (no per-preview JSON
manifest, no nested module dirs), so we keep its diff helper
separate from `compare-previews.py` rather than overloading it.

Modes
-----
generate
    Hash every PNG in the input dir, copy them under `<output>/renders/`,
    and emit `<output>/baselines.json` plus a `<output>/README.md` index
    that GitHub renders inline (the baseline branch is a browsable
    gallery, same UX as `compose-preview/main`).

compare
    Hash every PNG in the input dir, diff against `baselines.json`,
    and emit a Markdown PR-comment body to stdout. Empty diff prints
    the sentinel `No visual changes detected.` so the workflow can
    skip the comment.

copy-changed
    Copy only new/changed PNGs into `<output>/renders/` so they can
    be pushed to a per-PR shared branch. Sibling of `compare`'s
    image lookups — the After URLs in the comment pin to commits on
    that branch.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

# `<fixture>.<theme>.png` — see `vscode-extension/preview-harness/snapshot.mjs`.
_NAME_RE = re.compile(r"^(?P<fixture>.+)\.(?P<theme>[a-z][a-z0-9-]*)\.png$")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        text = path.read_text()
    except OSError:
        return {}
    if not text.strip():
        return {}
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _scan(input_dir: Path) -> dict[str, dict]:
    """Return `{ "<fixture>.<theme>.png": {fixture, theme, sha, size} }`."""
    out: dict[str, dict] = {}
    for png in sorted(input_dir.glob("*.png")):
        m = _NAME_RE.match(png.name)
        if not m:
            print(f"skipping non-matrix file: {png.name}", file=sys.stderr)
            continue
        out[png.name] = {
            "fixture": m.group("fixture"),
            "theme": m.group("theme"),
            "sha256": _sha256(png),
            "size": png.stat().st_size,
        }
    return out


def _image_url(repo: str, ref: str, path: str) -> str:
    # `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>` —
    # same pattern as `compare-previews.py`. Spaces stay url-safe via the
    # PNG names having no spaces in them today.
    return f"https://raw.githubusercontent.com/{repo}/{ref}/{path}"


# Pixel count above pixelmatch's per-pixel threshold that we still treat as
# perceptually unchanged. Headless Chromium emits ±1-channel jitter on
# antialiased edges between runs even when nothing about the fixture has
# changed — `inspection-tree.dark.png` reproducibly flips between 2 pixel
# values on a single column without any source change. Same tolerance as
# the composable side (`compare-previews.py`).
_PERCEPTUAL_PIXEL_TOLERANCE = 16


def _perceptually_changed(prior_png: Path, current_png: Path) -> bool:
    """Return True if the two PNGs differ by more than rounding noise.

    Mirrors `compare-previews._perceptually_changed`: uses Mapbox's
    pixelmatch with AA detection on, so a handful of antialiased edge
    pixels around glyphs and rounded corners — which the harness produces
    non-deterministically between runs — don't count as real differences.

    Falls back to ``True`` (i.e. defer to sha-mismatch) when the library
    isn't importable, either PNG can't be located, or decoding fails. The
    fallback is strictly no more permissive than the previous sha-only
    behaviour.
    """
    try:
        from pixelmatch.contrib.PIL import pixelmatch
        from PIL import Image
    except ImportError:
        return True
    if not prior_png.exists() or not current_png.exists():
        return True
    try:
        with Image.open(prior_png) as prior, Image.open(current_png) as current:
            if prior.size != current.size:
                return True
            diff = pixelmatch(prior, current, threshold=0.1, includeAA=False)
    except Exception:
        return True
    return diff > _PERCEPTUAL_PIXEL_TOLERANCE


def _is_changed(
    name: str,
    cur_meta: dict,
    prior_meta: dict,
    baseline_renders: Path | None,
    input_dir: Path,
) -> bool:
    """Decide whether ``cur_meta`` is a real change vs ``prior_meta``.

    Fast path: shas match → unchanged. On mismatch, if we have a local
    copy of the prior PNG, run the perceptual filter; otherwise fall back
    to the strict sha behaviour.
    """
    if prior_meta.get("sha256") == cur_meta["sha256"]:
        return False
    if baseline_renders is None:
        return True
    return _perceptually_changed(baseline_renders / name, input_dir / name)


# ---------------------------------------------------------------------------
# generate
# ---------------------------------------------------------------------------

def cmd_generate(args: argparse.Namespace) -> int:
    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    renders = output_dir / "renders"
    renders.mkdir(parents=True, exist_ok=True)

    entries = _scan(input_dir)
    for name in entries:
        shutil.copy2(input_dir / name, renders / name)

    baselines = {
        "schema": 1,
        "captures": entries,
    }
    (output_dir / "baselines.json").write_text(json.dumps(baselines, indent=2) + "\n")

    # Browsable index — `compose-preview/main` does the same trick so a
    # human can scroll the baseline branch on github.com and eyeball
    # changes without checking out anything.
    by_fixture: dict[str, list[tuple[str, str]]] = {}
    for name, meta in entries.items():
        by_fixture.setdefault(meta["fixture"], []).append((meta["theme"], name))
    lines = [
        "# VS Code preview-harness baselines",
        "",
        "Auto-generated by `.github/workflows/vscode-preview-baselines.yml`.",
        "Each row is one fixture from `vscode-extension/preview-harness/fixtures/`,",
        "rendered in dark and light themes. Updated on every push to `main`.",
        "",
    ]
    for fixture in sorted(by_fixture):
        lines.append(f"## {fixture}")
        lines.append("")
        lines.append("| Theme | Capture |")
        lines.append("| --- | --- |")
        for theme, name in sorted(by_fixture[fixture]):
            lines.append(f"| `{theme}` | ![{fixture}/{theme}](renders/{name}) |")
        lines.append("")
    (output_dir / "README.md").write_text("\n".join(lines) + "\n")
    return 0


# ---------------------------------------------------------------------------
# copy-changed
# ---------------------------------------------------------------------------

def cmd_copy_changed(args: argparse.Namespace) -> int:
    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    baselines = _load_json(Path(args.baselines)).get("captures", {})
    current = _scan(input_dir)

    raw_renders = getattr(args, "baseline_renders", None)
    baseline_renders = Path(raw_renders) if raw_renders else None

    renders = output_dir / "renders"
    renders.mkdir(parents=True, exist_ok=True)

    copied = 0
    for name, meta in current.items():
        prior = baselines.get(name) or {}
        if not _is_changed(name, meta, prior, baseline_renders, input_dir):
            continue
        shutil.copy2(input_dir / name, renders / name)
        copied += 1
    print(f"copied {copied} changed/new captures into {renders}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# compare
# ---------------------------------------------------------------------------

def cmd_compare(args: argparse.Namespace) -> int:
    input_dir = Path(args.input_dir)
    baselines = _load_json(Path(args.baselines)).get("captures", {})
    current = _scan(input_dir)

    raw_renders = getattr(args, "baseline_renders", None)
    baseline_renders = Path(raw_renders) if raw_renders else None

    new: list[tuple[str, dict]] = []
    changed: list[tuple[str, dict, dict]] = []
    removed: list[tuple[str, dict]] = []

    for name, meta in sorted(current.items()):
        prior = baselines.get(name)
        if prior is None:
            new.append((name, meta))
        elif _is_changed(name, meta, prior, baseline_renders, input_dir):
            changed.append((name, prior, meta))

    for name, meta in sorted(baselines.items()):
        if name not in current:
            removed.append((name, meta))

    if not new and not changed and not removed:
        print("<!-- vscode-preview-diff -->")
        print("## VS Code preview-harness diff")
        print("")
        print("No visual changes detected.")
        return 0

    repo = args.repo
    base_ref = args.base_ref
    head_ref = args.head_ref

    out: list[str] = []
    out.append("<!-- vscode-preview-diff -->")
    out.append("## VS Code preview-harness diff")
    out.append("")
    summary = []
    if changed:
        summary.append(f"{len(changed)} changed")
    if new:
        summary.append(f"{len(new)} new")
    if removed:
        summary.append(f"{len(removed)} removed")
    out.append(f"_{', '.join(summary)} across {len(current)} captures._")
    out.append("")
    out.append("Baseline branch: [`{b}`](https://github.com/{r}/tree/{b}).".format(b=args.base_branch, r=repo))
    out.append("")

    if changed:
        out.append("### Changed")
        out.append("")
        out.append("| Fixture | Theme | Before | After |")
        out.append("| --- | --- | --- | --- |")
        for name, prior, meta in changed:
            before = _image_url(repo, base_ref, f"renders/{name}")
            after = _image_url(repo, head_ref, f"renders/{name}")
            out.append(
                f"| `{meta['fixture']}` | `{meta['theme']}` "
                f"| <img src=\"{before}\" width=\"320\"> "
                f"| <img src=\"{after}\" width=\"320\"> |"
            )
        out.append("")

    if new:
        out.append("### New")
        out.append("")
        out.append("| Fixture | Theme | Capture |")
        out.append("| --- | --- | --- |")
        for name, meta in new:
            after = _image_url(repo, head_ref, f"renders/{name}")
            out.append(
                f"| `{meta['fixture']}` | `{meta['theme']}` "
                f"| <img src=\"{after}\" width=\"320\"> |"
            )
        out.append("")

    if removed:
        out.append("### Removed")
        out.append("")
        out.append("| Fixture | Theme |")
        out.append("| --- | --- |")
        for name, meta in removed:
            out.append(f"| `{meta['fixture']}` | `{meta['theme']}` |")
        out.append("")

    print("\n".join(out))
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("generate", help="Build baselines.json + README.md from an out/ dir")
    g.add_argument("input_dir")
    g.add_argument("--output-dir", required=True)
    g.set_defaults(func=cmd_generate)

    cp = sub.add_parser("copy-changed", help="Copy only new/changed PNGs into a staging dir")
    cp.add_argument("input_dir")
    cp.add_argument("--baselines", required=True)
    cp.add_argument("--output-dir", required=True)
    # Optional. When supplied, sha-mismatched pairs run through pixelmatch
    # before being copied — collapses the antialiased-edge jitter the
    # headless Chromium harness emits between runs (see
    # `_perceptually_changed`). Strict-bytes fallback when omitted.
    cp.add_argument("--baseline-renders", default=None)
    cp.set_defaults(func=cmd_copy_changed)

    c = sub.add_parser("compare", help="Emit a markdown PR comment body")
    c.add_argument("input_dir")
    c.add_argument("--baselines", required=True)
    c.add_argument("--repo", required=True)
    c.add_argument("--base-ref", required=True)
    c.add_argument("--head-ref", required=True)
    c.add_argument("--base-branch", required=True)
    # Same as `copy-changed --baseline-renders` — pixelmatch path for the
    # PR-comment diff so the two surfaces agree.
    c.add_argument("--baseline-renders", default=None)
    c.set_defaults(func=cmd_compare)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
