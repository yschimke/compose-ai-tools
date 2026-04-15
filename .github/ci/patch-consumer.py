#!/usr/bin/env python3
"""Patch consumer Gradle module(s) to apply the compose-ai preview plugin.

Two modes:

  --module-build PATH     patch a single build.gradle(.kts)
  --walk ROOT             recursively patch every build.gradle(.kts) under
                          ROOT that declares one of the Android/Compose
                          plugin ids we care about

Idempotent: files that already reference the plugin id are skipped.

The settings-level pluginManagement repositories are mutated separately by
apply-compose-ai.init.gradle.kts so Gradle can resolve our plugin marker
from mavenLocal.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


PLUGIN_ID = "ee.schimke.composeai.preview"

# A module build file is a candidate for patching if it applies any of these
# plugin ids. Match either `id("…")`, `id '…'`, or the `alias(...)` shorthand
# whose target happens to contain the id in its version-catalog declaration.
TRIGGER_SUBSTRINGS = (
    "com.android.application",
    "com.android.library",
    "org.jetbrains.compose",
    "jetbrainsCompose",  # common alias name in version catalogs
)


def patch_plugins_block(path: Path, version: str) -> bool:
    """Insert the plugin id into the first top-level `plugins { }` block.

    Returns True if the file was modified.
    """
    text = path.read_text()
    if PLUGIN_ID in text:
        return False

    is_kts = path.suffix == ".kts"
    line = (
        f'    id("{PLUGIN_ID}") version "{version}"'
        if is_kts
        else f'    id "{PLUGIN_ID}" version "{version}"'
    )

    new_text, count = re.subn(
        r"(^plugins\s*\{)",
        lambda m: m.group(1) + "\n" + line,
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count == 0:
        return False  # no plugins block — this isn't a candidate module

    path.write_text(new_text)
    return True


def is_candidate(path: Path) -> bool:
    try:
        text = path.read_text()
    except (OSError, UnicodeDecodeError):
        return False
    return any(s in text for s in TRIGGER_SUBSTRINGS)


def walk_and_patch(root: Path, version: str) -> int:
    patched = 0
    skipped = 0
    for path in list(root.rglob("build.gradle.kts")) + list(root.rglob("build.gradle")):
        # Ignore buildSrc / included-build `build-logic` outputs and any nested
        # build directories.
        if "build/" in path.as_posix() or "/build/" in path.as_posix():
            continue
        if not is_candidate(path):
            continue
        if patch_plugins_block(path, version):
            print(f"[patch-consumer] + {path}")
            patched += 1
        else:
            print(f"[patch-consumer] = {path} (already patched or no plugins block)")
            skipped += 1
    print(f"[patch-consumer] patched={patched} skipped={skipped}")
    return patched


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--module-build", type=Path)
    g.add_argument("--walk", type=Path)
    ap.add_argument("--plugin-version", required=True)
    args = ap.parse_args()

    if args.module_build is not None:
        if not args.module_build.exists():
            print(f"[patch-consumer] ERROR: {args.module_build} not found", file=sys.stderr)
            return 1
        patched = patch_plugins_block(args.module_build, args.plugin_version)
        print(f"[patch-consumer] {'patched' if patched else 'skipped'} {args.module_build}")
        return 0

    if not args.walk.exists():
        print(f"[patch-consumer] ERROR: {args.walk} not found", file=sys.stderr)
        return 1
    count = walk_and_patch(args.walk, args.plugin_version)
    if count == 0:
        print("[patch-consumer] WARNING: no candidate build files patched", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
