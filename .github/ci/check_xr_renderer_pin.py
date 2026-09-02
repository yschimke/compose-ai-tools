#!/usr/bin/env python3
"""Fail when the independently released renderer-xr pin is not on Maven Central."""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "gradle" / "libs.versions.toml"
PIN_RE = re.compile(r'^\s*xr-renderer\s*=\s*"([^"]+)"', re.MULTILINE)
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(?:-(?!SNAPSHOT$)[0-9A-Za-z.-]+)?$", re.IGNORECASE)
ARTIFACT_URL = (
    "https://repo.maven.apache.org/maven2/ee/schimke/composeai/renderer-xr/"
    "{version}/renderer-xr-{version}.aar"
)


class Unanswered(Exception):
    """The registry supplied no verdict because transport or service failed."""


def pin_from(text: str) -> str | None:
    match = PIN_RE.search(text)
    return match.group(1) if match else None


def current_pin() -> str:
    pin = pin_from(CATALOG.read_text())
    if pin is None:
        raise SystemExit(f'could not find `xr-renderer = "…"` in {CATALOG.relative_to(REPO)}')
    return pin


def artifact_exists(version: str) -> bool:
    request = urllib.request.Request(ARTIFACT_URL.format(version=version), method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise Unanswered(f"HTTP {error.code} from Maven Central") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise Unanswered(f"could not reach Maven Central: {error}") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--print-pin", action="store_true")
    args = parser.parse_args(argv)
    pin = current_pin()

    if args.print_pin:
        print(pin)
        return 0
    if not VERSION_RE.match(pin):
        print(f'::error::xr-renderer pin "{pin}" is not a release version', file=sys.stderr)
        return 1
    if args.offline:
        print(f"ok (offline): renderer-xr pin {pin} is well formed")
        return 0
    try:
        exists = artifact_exists(pin)
    except Unanswered as error:
        print(f"::warning::could not verify renderer-xr {pin}: {error}", file=sys.stderr)
        return 0
    if not exists:
        print(
            f"::error::ee.schimke.composeai:renderer-xr:{pin} is not published on Maven Central; "
            "XR rendering would fail dependency resolution",
            file=sys.stderr,
        )
        return 1
    print(f"ok: renderer-xr {pin} is published on Maven Central")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

