#!/usr/bin/env python3
"""Patch a consumer Gradle project's module build.gradle(.kts) to apply the
compose-ai preview plugin.

Idempotent: if the plugin id is already present, the file is left alone.

The settings-level pluginManagement repositories are mutated separately by the
init script (apply-compose-ai.init.gradle.kts), so Gradle can resolve our
plugin marker from mavenLocal.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


PLUGIN_ID = "ee.schimke.composeai.preview"


def patch_plugins_block(path: Path, version: str) -> None:
    text = path.read_text()
    if PLUGIN_ID in text:
        print(f"[patch-consumer] {path} already references {PLUGIN_ID}; skipping")
        return

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
        # No top-level plugins block — prepend one.
        new_text = f"plugins {{\n{line}\n}}\n\n{text}"

    path.write_text(new_text)
    print(f"[patch-consumer] added {PLUGIN_ID} to {path}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--module-build", type=Path, required=True)
    ap.add_argument("--plugin-version", required=True)
    args = ap.parse_args()

    if not args.module_build.exists():
        print(f"[patch-consumer] ERROR: {args.module_build} not found", file=sys.stderr)
        return 1

    patch_plugins_block(args.module_build, args.plugin_version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
