#!/usr/bin/env python3
"""Fail a PR whose `host-natives` pin does not name a complete `host-natives-v<pin>` release.

`compose-preview launch` fetches the host builds of APK native libraries (`HostNativesProvision`)
from this repository's `host-natives-v<pin>` prerelease, published by a manual dispatch of
`host-natives.yml`. A pin naming a release nobody published, or one missing a platform, 404s into a
graceful skip: the launch carries on and the app fails with the `UnsatisfiedLinkError` the build
exists to prevent. So, as for `xr-composite` (`check_xr_composite_pin.py`), a gate.

A 404 or a missing tarball fails; a transport error or a rate-limit warns and passes, because it
says nothing about the pin. `--offline` checks only the pin's shape. `--print-pin` prints the pin.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = Path("gradle/libs.versions.toml")

# Where the CLI downloads from: `REPO` in Version.kt, the repository this file lives in.
RELEASE_REPO = "yschimke/compose-ai-tools"

# host-natives.yml's build matrix, and what `HostNativesProvision.platformToken` maps a host onto.
PLATFORMS = ("linux-x86_64", "macos-universal")

PIN_RE = re.compile(r'^\s*host-natives\s*=\s*"([^"]+)"', re.MULTILINE)
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
RELEASE_API = "https://api.github.com/repos/{repo}/releases/tags/host-natives-v{version}"


def pin_from(text: str) -> str | None:
    """The `host-natives` version declared in a `libs.versions.toml` body, or None."""
    m = PIN_RE.search(text)
    return m.group(1) if m else None


def asset_name(version: str, platform: str) -> str:
    """Mirror of `HostNativesProvision.assetName` and `host-natives/build.sh`'s archive name."""
    return f"host-natives-{platform}-{version}.tar.gz"


def missing_assets(published: set[str], version: str) -> list[str]:
    """The platform tarballs, and `SHA256SUMS`, the release does not carry."""
    wanted = [asset_name(version, p) for p in PLATFORMS] + ["SHA256SUMS"]
    return [a for a in wanted if a not in published]


class Unanswered(Exception):
    """GitHub told us nothing: a transport error or a rate-limit, not a verdict on the pin."""


def fetch_release_assets(version: str, token: str | None = None) -> set[str] | None:
    """Asset names on `host-natives-v<version>`, or None when there is no such release."""
    req = urllib.request.Request(RELEASE_API.format(repo=RELEASE_REPO, version=version))
    req.add_header("Accept", "application/vnd.github+json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise Unanswered(f"HTTP {e.code} from the releases API") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise Unanswered(f"could not reach the releases API: {e}") from e
    return {a.get("name", "") for a in body.get("assets", [])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--print-pin", action="store_true", help="print the pin and exit")
    ap.add_argument("--offline", action="store_true", help="check only the pin's shape")
    args = ap.parse_args()

    pin = pin_from((REPO / CATALOG).read_text())
    if pin is None:
        print(f'::error::could not find `host-natives = "…"` in {CATALOG}', file=sys.stderr)
        return 1
    if args.print_pin:
        print(pin)
        return 0
    if not VERSION_RE.match(pin):
        print(
            f'::error::`host-natives = "{pin}"` in {CATALOG} is not a host-natives/VERSION '
            f"(X.Y.Z); the CLI downloads host-natives-v<pin> and nothing else.",
            file=sys.stderr,
        )
        return 1
    if args.offline:
        print(f"ok (offline): host-natives pin {pin} is well-formed")
        return 0

    try:
        published = fetch_release_assets(pin, os.environ.get("GITHUB_TOKEN"))
    except Unanswered as e:
        print(f"::warning::could not verify the host-natives pin {pin}: {e}", file=sys.stderr)
        return 0
    if published is None:
        print(
            f'::error::`host-natives = "{pin}"` in {CATALOG} names a release that does not exist: '
            f"{RELEASE_REPO} has no tag host-natives-v{pin}.\n"
            f"  `compose-preview launch` would skip the host libraries without a word, and apps "
            f"would fail with UnsatisfiedLinkError.\n"
            f"  Fix: run the Host Natives workflow from main with `publish` ticked (it publishes "
            f"host-natives/VERSION), or pin a version already published.",
            file=sys.stderr,
        )
        return 1
    missing = missing_assets(published, pin)
    if missing:
        listing = "\n".join(f"    {m}" for m in missing)
        print(
            f"::error::{RELEASE_REPO} host-natives-v{pin} is missing:\n{listing}\n"
            f"  A published version is never republished; bump host-natives/VERSION, publish, "
            f"and move the pin.",
            file=sys.stderr,
        )
        return 1
    print(f"ok: {RELEASE_REPO} host-natives-v{pin} carries all {len(PLATFORMS)} platform tarballs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
