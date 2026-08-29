#!/usr/bin/env python3
"""Fail a PR whose `xr-composite` pin does not name a real, fully-published release.

The native compositor now lives in its own repository (yschimke/compose-preview-xr) and releases on
its own cadence. Upstream consumes it purely by version: the CLI (`XrCompositeProvision`) and the
Gradle plugin (`xrCompositeCacheBinaryPath`) both resolve the binary from the `xr-composite` entry
in `gradle/libs.versions.toml`, and nothing in this repository builds it any more.

That is the point of the split, and it moves the failure mode rather than removing it. Before, the
hazard was a compositor change that shipped nowhere because the pin stayed put; this gate used to
catch that, and it is now compose-preview-xr's problem. The hazard *here* is the mirror image: a
pin naming a version whose Release does not exist, or exists with only some of its three platform
tarballs attached. Both 404 on download, and every provisioning failure downstream is a **graceful
skip** — the render still succeeds, just without a composite, and nobody finds out. So it gets a
hard gate rather than a convention, exactly as the drift version did.

Two checks, in order:

1. **Offline.** The pin parses as a version, and the repository this gate resolves against is the
   same one the CLI bakes into the download URL (`XR_COMPOSITE_REPO` in `Version.kt`). A gate that
   verifies a different repository than the CLI downloads from proves nothing.
2. **Online.** The `v<pin>` Release on that repository exists and carries all three platform
   tarballs, under exactly the names `XrCompositeProvision.assetName` derives.

The online half distinguishes an *answer* from *no answer*, and only the former fails the build. A
404, or a Release missing an asset, is GitHub telling us the pin is broken — fail closed. A
transport error or a rate-limit is GitHub telling us nothing; it warns and passes, because the
alternative is a gate that goes red for reasons unrelated to the diff, and because publication has
its own safety net: compose-preview-xr's release workflow refuses to finish a Release that is
missing any of the three tarballs. Pass `--offline` to skip the online half entirely.

`--print-pin` and `--print-repo` print the pin and the release repository and exit, so callers
that need to fetch the binary derive both from here rather than hard-coding a second copy.
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

# Where the compositor is released from. Must equal `XR_COMPOSITE_REPO` in the CLI's Version.kt —
# `declared_repo()` proves it rather than trusting this copy.
XR_REPO = "yschimke/compose-preview-xr"
VERSION_KT = Path("cli/src/main/kotlin/ee/schimke/composeai/cli/Version.kt")

# The asset matrix compose-preview-xr publishes, and the matrix `XrCompositeProvision.platformToken`
# maps a host onto. A platform missing from a Release is unprovisionable on that host alone, which
# is the most silent shape this failure takes — the gate treats it exactly like a missing Release.
PLATFORMS = ("linux-x86_64", "macos-arm64", "windows-x86_64")

PIN_RE = re.compile(r'^\s*xr-composite\s*=\s*"([^"]+)"', re.MULTILINE)
REPO_CONST_RE = re.compile(r'XR_COMPOSITE_REPO\s*[:=][^"]*"([^"]+)"')

# Deliberately loose: the pin must look like a release tag consumers can resolve, not conform to
# full semver. A prerelease suffix is allowed because compose-preview-xr may cut one; `-SNAPSHOT`
# is not, because it is this repository's own dev-version convention and is the way a pin most
# plausibly ends up naming something nobody publishes — which 404s into a graceful skip.
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(?:-(?!SNAPSHOT$)[0-9A-Za-z.-]+)?$", re.IGNORECASE)

RELEASE_API = "https://api.github.com/repos/{repo}/releases/tags/v{version}"


def pin_from(text: str) -> str | None:
    """The `xr-composite` version declared in a `libs.versions.toml` body, or None."""
    m = PIN_RE.search(text)
    return m.group(1) if m else None


def current_pin() -> str:
    pin = pin_from((REPO / CATALOG).read_text())
    if pin is None:
        raise SystemExit(f'could not find `xr-composite = "…"` in {CATALOG}')
    return pin


def repo_from(kotlin: str) -> str | None:
    """The `XR_COMPOSITE_REPO` constant declared in a Version.kt body, or None."""
    m = REPO_CONST_RE.search(kotlin)
    return m.group(1) if m else None


def declared_repo() -> str | None:
    return repo_from((REPO / VERSION_KT).read_text())


def asset_name(version: str, platform: str) -> str:
    """Mirror of `XrCompositeProvision.assetName` — the two must derive the same filename."""
    return f"xr-composite-{platform}-{version}.tar.gz"


def missing_assets(published: set[str], version: str) -> list[str]:
    """Which platform tarballs the Release does not carry, in matrix order."""
    return [
        asset_name(version, p) for p in PLATFORMS if asset_name(version, p) not in published
    ]


class Unanswered(Exception):
    """GitHub told us nothing — a transport error or a rate-limit, not a verdict on the pin."""


def fetch_release_assets(repo: str, version: str, token: str | None = None) -> set[str] | None:
    """Asset names on the `v<version>` Release, or None when there is no such Release.

    Raises [Unanswered] when the API could not be reached or refused to answer, which the caller
    must not read as "the pin is broken".
    """
    req = urllib.request.Request(RELEASE_API.format(repo=repo, version=version))
    req.add_header("Accept", "application/vnd.github+json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        # 403/429 is the rate limit; 5xx is GitHub being unwell. Neither is a verdict.
        raise Unanswered(f"HTTP {e.code} from the releases API") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise Unanswered(f"could not reach the releases API: {e}") from e
    return {a.get("name", "") for a in body.get("assets", [])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--print-pin", action="store_true", help="print the current xr-composite pin and exit"
    )
    ap.add_argument(
        "--print-repo",
        action="store_true",
        help="print the repository the compositor is released from and exit",
    )
    ap.add_argument(
        "--offline",
        action="store_true",
        help="run only the offline checks (pin shape, repo agreement)",
    )
    args = ap.parse_args()

    pin = current_pin()
    if args.print_pin:
        print(pin)
        return 0
    if args.print_repo:
        # From Version.kt, not this file's XR_REPO: a caller fetching the binary must resolve the
        # same repository the CLI does, and the agreement between the two is checked below.
        declared = declared_repo()
        if declared is None:
            raise SystemExit(f"could not find `XR_COMPOSITE_REPO` in {VERSION_KT}")
        print(declared)
        return 0

    if not VERSION_RE.match(pin):
        print(
            f'::error::`xr-composite = "{pin}"` in {CATALOG} does not look like a release version.\n'
            f"  Consumers resolve the binary by this value alone, and anything that is not a "
            f"published tag 404s into a graceful skip — the render succeeds without a composite "
            f"and nothing is logged.",
            file=sys.stderr,
        )
        return 1

    declared = declared_repo()
    if declared is None:
        print(
            f"::error::could not find `XR_COMPOSITE_REPO` in {VERSION_KT}. This gate verifies the "
            f"pin against a repository, and it must be the one the CLI downloads from.",
            file=sys.stderr,
        )
        return 1
    if declared != XR_REPO:
        print(
            f"::error::this gate checks {XR_REPO} but the CLI downloads from {declared} "
            f"({VERSION_KT}). Verifying a different repository than the one consumers fetch from "
            f"proves nothing.\n"
            f"  Fix: update XR_REPO in {Path(__file__).relative_to(REPO)} to match.",
            file=sys.stderr,
        )
        return 1

    if args.offline:
        print(f"ok (offline): pin {pin} is well-formed and resolves against {declared}")
        return 0

    try:
        published = fetch_release_assets(declared, pin, os.environ.get("GITHUB_TOKEN"))
    except Unanswered as e:
        # No answer is not a failed answer. Say so loudly enough to be searchable, then pass —
        # compose-preview-xr's own release workflow refuses to finish a Release missing a tarball,
        # so this is a second line of defence, not the only one.
        print(f"::warning::could not verify the xr-composite pin {pin}: {e}", file=sys.stderr)
        return 0

    if published is None:
        print(
            f"::error::`xr-composite = \"{pin}\"` in {CATALOG} names a release that does not "
            f"exist: {declared} has no tag v{pin}.\n"
            f"  The CLI and the Gradle plugin both download the binary from that tag, so this pin "
            f"provisions nothing — quietly, because a missing asset is a graceful skip and the "
            f"render still succeeds without a composite.\n"
            f"  Fix: pin a version {declared} has actually released, or cut that release first.",
            file=sys.stderr,
        )
        return 1

    missing = missing_assets(published, pin)
    if missing:
        listing = "\n".join(f"    {m}" for m in missing)
        have = "\n".join(f"    {a}" for a in sorted(published)) or "    (none)"
        print(
            f"::error::{declared} v{pin} is missing platform tarballs:\n{listing}\n"
            f"  Attached to that Release:\n{have}\n"
            f"  A host whose platform is missing cannot provision the compositor at all, and says "
            f"nothing about it — the failure is a graceful skip. Re-run compose-preview-xr's "
            f"release workflow for v{pin} to re-attach, or move the pin to a complete release.",
            file=sys.stderr,
        )
        return 1

    print(f"ok: {declared} v{pin} exists with all {len(PLATFORMS)} platform tarballs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
