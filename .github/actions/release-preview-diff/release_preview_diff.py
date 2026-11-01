#!/usr/bin/env python3
"""Compare two `compose-preview show --json` runs: the previous release and this one.

Writes:
  <out>/diff.md          the comparison, in the same shape as the PR `<!-- preview-diff -->`
                         comment (`### Changed` / `### New` sections naming each function as
                         **`Function`** (module)), so a Preview Audit can read it
  <out>/images/...       the PNGs the markdown links to: base/ and head/ copies of every
                         changed capture, head/ copies of every new one
  <out>/summary.json     counts, for the workflow's job summary

Captures are matched by (module, preview id, capture index) and compared by the sha256 the CLI
reports, so an unchanged render is never copied or listed.

Usage: release_preview_diff.py BASE_JSON HEAD_JSON --base-tag T --head-tag T
           --out DIR --image-url-prefix URL [--max-functions N]
           [--base-root DIR] [--head-root DIR]
"""

import argparse
import json
import os
import shutil
import sys
from collections import OrderedDict


def load(path, root):
    """Captures by key; relative pngPaths are resolved against `root` (the render's checkout)."""
    with open(path) as f:
        data = json.load(f)
    previews = data["previews"] if isinstance(data, dict) else data
    captures = OrderedDict()
    for p in previews:
        caps = p.get("captures") or [
            {"pngPath": p.get("pngPath"), "sha256": p.get("sha256")}
        ]
        for i, c in enumerate(caps):
            if not c.get("pngPath") or not c.get("sha256"):
                continue
            png = c["pngPath"]
            if not os.path.isabs(png):
                png = os.path.join(root, png)
            key = (p["module"], p["id"], i)
            captures[key] = {
                "module": p["module"],
                "function": p.get("functionName") or p["id"],
                "label": (p.get("params") or {}).get("name") or c.get("parameterLabel") or "",
                "png": png,
                "sha": c["sha256"],
            }
    return captures


def safe(name):
    return "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base_json")
    ap.add_argument("head_json")
    ap.add_argument("--base-tag", required=True)
    ap.add_argument("--head-tag", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--image-url-prefix", required=True)
    ap.add_argument("--max-functions", type=int, default=60)
    ap.add_argument("--base-root", default=".", help="directory relative base pngPaths resolve against")
    ap.add_argument("--head-root", default=".", help="directory relative head pngPaths resolve against")
    args = ap.parse_args()

    base = load(args.base_json, args.base_root)
    head = load(args.head_json, args.head_root)
    images = os.path.join(args.out, "images")
    os.makedirs(images, exist_ok=True)

    def copy(side, cap):
        rel = f"{side}/{safe(cap['module'])}/{safe(os.path.basename(cap['png']))}"
        dest = os.path.join(images, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if os.path.exists(cap["png"]):
            shutil.copyfile(cap["png"], dest)
            return f"{args.image_url_prefix}/{rel}"
        return None

    changed = OrderedDict()  # (module, function) -> [(base_url, head_url, label)]
    new = OrderedDict()
    removed = OrderedDict()
    for key, cap in head.items():
        group = (cap["module"], cap["function"])
        old = base.get(key)
        if old is None:
            new.setdefault(group, []).append((None, copy("head", cap), cap["label"]))
        elif old["sha"] != cap["sha"]:
            changed.setdefault(group, []).append(
                (copy("base", old), copy("head", cap), cap["label"])
            )
    for key, cap in base.items():
        if key not in head:
            removed.setdefault((cap["module"], cap["function"]), []).append(cap["label"])

    unchanged = len({(c["module"], c["function"]) for c in head.values()}) - len(
        set(changed) | set(new)
    )

    lines = [
        "<!-- preview-diff -->",
        f"## Preview changes in {args.head_tag} since {args.base_tag}",
        "",
    ]
    if not changed and not new and not removed:
        lines.append("No visual changes detected.")

    def section(title, groups, render):
        if not groups:
            return
        variants = sum(len(v) for v in groups.values())
        lines.append(f"### {title} ({variants} variant(s) across {len(groups)} function(s))")
        lines.append("")
        for n, ((module, function), items) in enumerate(groups.items()):
            if n >= args.max_functions:
                lines.append(
                    f"_…and {len(groups) - n} more function(s); the full set of images is on "
                    f"the `compose-preview/releases` branch under `{args.head_tag}/`._"
                )
                lines.append("")
                break
            lines.append(f"**`{function}`** ({module})")
            lines.append("")
            render(items)
            lines.append("")

    def render_changed(items):
        before, after, _ = items[0]
        lines.append("| Before | After |")
        lines.append("|--------|-------|")
        lines.append(
            f'| <img src="{before}" width="200" /> | <img src="{after}" width="200" /> |'
        )
        if len(items) > 1:
            lines.append("")
            lines.append(
                "Other variants: "
                + ", ".join(f"[{label or 'variant'}]({a})" for _, a, label in items[1:] if a)
            )

    def render_new(items):
        _, after, _ = items[0]
        lines.append(f'<img src="{after}" width="200" />')
        if len(items) > 1:
            lines.append("")
            lines.append(
                "Variants: "
                + ", ".join(f"[{label or 'variant'}]({a})" for _, a, label in items[1:] if a)
            )

    section("Changed", changed, render_changed)
    section("New", new, render_new)
    if removed:
        lines.append(f"### Removed ({len(removed)} function(s))")
        lines.append("")
        for (module, function) in removed:
            lines.append(f"- **`{function}`** ({module})")
        lines.append("")
    lines.append(f"_Unchanged ({unchanged} function(s))._")

    with open(os.path.join(args.out, "diff.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    summary = {
        "changed": len(changed),
        "new": len(new),
        "removed": len(removed),
        "unchanged": unchanged,
    }
    with open(os.path.join(args.out, "summary.json"), "w") as f:
        json.dump(summary, f)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
