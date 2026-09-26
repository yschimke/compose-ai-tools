#!/usr/bin/env python3
"""Keep Gradle plugin examples release-safe without guessing a Maven version."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys


MARKER = "published-plugin-version-example"
PLACEHOLDER = "<published-version>"
PIN_SITES = {
    pathlib.Path("docs/RELEASING.md"): re.compile(
        r'id\("ee\.schimke\.composeai\.preview"\) version "([^"]+)"'
    ),
    pathlib.Path("site/index.md"): re.compile(
        r'id\("ee\.schimke\.composeai\.preview"\) version "([^"]+)"'
    ),
    pathlib.Path(".github/actions/apply/README.md"): re.compile(
        r'^composePreviewPlugin = "([^"]+)"$', re.MULTILINE
    ),
}


def inside_release_please_marker(text: str, offset: int) -> bool:
    start = text.rfind("x-release-please-start-version", 0, offset)
    end = text.rfind("x-release-please-end", 0, offset)
    return start > end


def validate(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    for relative, pattern in PIN_SITES.items():
        path = root / relative
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"{relative}: cannot read file: {error}")
            continue

        marker_count = text.count(MARKER)
        if marker_count != 1:
            errors.append(f"{relative}: expected exactly one example marker, found {marker_count}")
            continue

        marker_offset = text.index(MARKER)
        match = pattern.search(text, marker_offset)
        if match is None:
            errors.append(f"{relative}: no plugin pin follows the example marker")
            continue
        actual = match.group(1)
        if actual != PLACEHOLDER:
            errors.append(f"{relative}: plugin example is {actual}, expected {PLACEHOLDER}")
        if inside_release_please_marker(text, marker_offset) or inside_release_please_marker(
            text, match.start()
        ):
            errors.append(
                f"{relative}: plugin pin is inside a release-please version marker; "
                "CLI-only releases must not rewrite it"
            )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path("."))
    args = parser.parse_args()

    errors = validate(args.root)
    if errors:
        for error in errors:
            print(f"release plugin pin: {error}", file=sys.stderr)
        return 1
    print("Release plugin examples use the Maven Central placeholder outside version markers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
