#!/usr/bin/env python3
"""Keep hand-written Gradle plugin examples on the version Maven will publish."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys


PIN_SITES = {
    pathlib.Path("docs/RELEASING.md"): re.compile(
        r'id\("ee\.schimke\.composeai\.preview"\) version "(\d+\.\d+\.\d+)"'
    ),
    pathlib.Path("site/index.md"): re.compile(
        r'id\("ee\.schimke\.composeai\.preview"\) version "(\d+\.\d+\.\d+)"'
    ),
    pathlib.Path(".github/actions/apply/README.md"): re.compile(
        r'^composePreviewPlugin = "(\d+\.\d+\.\d+)"$', re.MULTILINE
    ),
}


def inside_release_please_marker(text: str, offset: int) -> bool:
    start = text.rfind("x-release-please-start-version", 0, offset)
    end = text.rfind("x-release-please-end", 0, offset)
    return start > end


def validate(root: pathlib.Path, expected: str) -> list[str]:
    errors: list[str] = []
    for relative, pattern in PIN_SITES.items():
        path = root / relative
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"{relative}: cannot read file: {error}")
            continue

        matches = list(pattern.finditer(text))
        if len(matches) != 1:
            errors.append(f"{relative}: expected exactly one plugin pin, found {len(matches)}")
            continue

        match = matches[0]
        actual = match.group(1)
        if actual != expected:
            errors.append(f"{relative}: plugin pin is {actual}, expected {expected}")
        if inside_release_please_marker(text, match.start()):
            errors.append(
                f"{relative}: plugin pin is inside a release-please version marker; "
                "CLI-only releases must not rewrite it"
            )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected", required=True)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path("."))
    args = parser.parse_args()

    errors = validate(args.root, args.expected)
    if errors:
        for error in errors:
            print(f"release plugin pin: {error}", file=sys.stderr)
        return 1
    print(f"Release plugin examples consistently use published line {args.expected}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
