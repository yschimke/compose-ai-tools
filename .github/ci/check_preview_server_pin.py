#!/usr/bin/env python3
"""Fail a PR whose preview-server pin does not name a release carrying a server distribution.

`compose-preview serve` and `browse` are launchers (#5177). The thing they launch ships from
yschimke/compose-preview-server, and since #5183 the CLI fetches it itself on first use rather than
expecting the installer to: `ServerDistributionProvision` downloads
`compose-preview-server-<pin>.tar.gz` from the Release tagged `v<pin>`, where `<pin>` is the
`composeai-preview-serve` entry in `gradle/libs.versions.toml`, baked into the jar at build time.

So that pin is now load-bearing for a command, not just for a compile. A pin naming a version whose
Release does not exist — or exists without the distribution attached — is a `serve` that cannot
start for anyone who installed the documented way. That is exactly the state #5183 reported, and it
went unnoticed because nothing checked. This gate checks.

Two halves, in order:

1. **Offline.** The pin parses as a release version, and the repository this gate resolves against
   is the one the CLI actually downloads from (`PREVIEW_SERVER_REPO` in `Version.kt`). Verifying a
   different repository than consumers fetch from proves nothing.
2. **Online.** The `v<pin>` Release exists on that repository and carries the asset
   `ServerDistributionProvision.assetName` derives.

The online half distinguishes an *answer* from *no answer*, and only the former fails the build. A
404, or a Release without the distribution, is GitHub telling us the pin is broken — fail closed. A
transport error or rate-limit is GitHub telling us nothing; warn and pass, because a gate that goes
red for reasons unrelated to the diff stops being read.

`--print-pin` and `--print-repo` print the pin and the release repository and exit, so a caller that
needs to fetch the distribution derives both from here rather than keeping a second copy.
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

# Where the server is released from. Must equal `PREVIEW_SERVER_REPO` in the CLI's Version.kt —
# `declared_repo()` proves it rather than trusting this copy.
SERVER_REPO = "yschimke/compose-preview-server"
VERSION_KT = Path("cli/src/main/kotlin/ee/schimke/composeai/cli/Version.kt")

PIN_RE = re.compile(r'^\s*composeai-preview-serve\s*=\s*"([^"]+)"', re.MULTILINE)
REPO_CONST_RE = re.compile(r'PREVIEW_SERVER_REPO\s*[:=][^"]*"([^"]+)"')

# Deliberately loose: the pin must look like a release tag a consumer can resolve, not conform to
# full semver. A prerelease suffix is allowed; `-SNAPSHOT` is not, because it is this repository's
# own dev-version convention and is the likeliest way a pin ends up naming something nobody
# published.
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(?:-(?!SNAPSHOT$)[0-9A-Za-z.-]+)?$", re.IGNORECASE)

RELEASE_API = "https://api.github.com/repos/{repo}/releases/tags/v{version}"


def pin_from(text: str) -> str | None:
    """The `composeai-preview-serve` version declared in a `libs.versions.toml` body, or None."""
    m = PIN_RE.search(text)
    return m.group(1) if m else None


def current_pin() -> str:
    pin = pin_from((REPO / CATALOG).read_text())
    if pin is None:
        raise SystemExit(f'could not find `composeai-preview-serve = "…"` in {CATALOG}')
    return pin


def repo_from(kotlin: str) -> str | None:
    """The `PREVIEW_SERVER_REPO` constant declared in a Version.kt body, or None."""
    m = REPO_CONST_RE.search(kotlin)
    return m.group(1) if m else None


def declared_repo() -> str | None:
    return repo_from((REPO / VERSION_KT).read_text())


def asset_name(version: str) -> str:
    """Mirror of `ServerDistributionProvision.assetName` — the two must derive one filename."""
    return f"compose-preview-server-{version}.tar.gz"


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
        raise Unanswered(f"HTTP {e.code} from the releases API") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise Unanswered(f"could not reach the releases API: {e}") from e
    return {a.get("name", "") for a in body.get("assets", [])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--print-pin",
        action="store_true",
        help="print the current compose-preview-serve pin and exit",
    )
    ap.add_argument(
        "--print-repo",
        action="store_true",
        help="print the repository the server is released from and exit",
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
        # From Version.kt, not this file's SERVER_REPO: a caller fetching the distribution must
        # resolve the same repository the CLI does, and the two are checked against each other
        # below.
        declared = declared_repo()
        if declared is None:
            raise SystemExit(f"could not find `PREVIEW_SERVER_REPO` in {VERSION_KT}")
        print(declared)
        return 0

    if not VERSION_RE.match(pin):
        print(
            f'::error::`composeai-preview-serve = "{pin}"` in {CATALOG} does not look like a '
            f"release version.\n"
            f"  `compose-preview serve` resolves the server distribution by this value alone, so "
            f"anything that is not a published tag leaves the command unable to start.",
            file=sys.stderr,
        )
        return 1

    declared = declared_repo()
    if declared is None:
        print(
            f"::error::could not find `PREVIEW_SERVER_REPO` in {VERSION_KT}. This gate verifies "
            f"the pin against a repository, and it must be the one the CLI downloads from.",
            file=sys.stderr,
        )
        return 1
    if declared != SERVER_REPO:
        print(
            f"::error::this gate checks {SERVER_REPO} but the CLI downloads from {declared} "
            f"({VERSION_KT}). Verifying a different repository than the one consumers fetch from "
            f"proves nothing.\n"
            f"  Fix: update SERVER_REPO in {Path(__file__).relative_to(REPO)} to match.",
            file=sys.stderr,
        )
        return 1

    if args.offline:
        print(f"ok (offline): pin {pin} is well-formed and resolves against {declared}")
        return 0

    try:
        published = fetch_release_assets(declared, pin, os.environ.get("GITHUB_TOKEN"))
    except Unanswered as e:
        print(
            f"::warning::could not verify the preview-server pin {pin}: {e}",
            file=sys.stderr,
        )
        return 0

    if published is None:
        print(
            f'::error::`composeai-preview-serve = "{pin}"` in {CATALOG} names a release that does '
            f"not exist: {declared} has no tag v{pin}.\n"
            f"  The CLI fetches the server from that tag on first `serve`, so this pin serves "
            f"nobody.\n"
            f"  Fix: pin a version {declared} has actually released, or cut that release first.",
            file=sys.stderr,
        )
        return 1

    wanted = asset_name(pin)
    if wanted not in published:
        have = "\n".join(f"    {a}" for a in sorted(published)) or "    (none)"
        print(
            f"::error::{declared} v{pin} does not carry {wanted}.\n"
            f"  Attached to that Release:\n{have}\n"
            f"  Without it `compose-preview serve` has nothing to fetch and exits with an "
            f"installation hint. Re-run compose-preview-server's release workflow for v{pin} to "
            f"attach the distribution, or move the pin to a release that has one.",
            file=sys.stderr,
        )
        return 1

    print(f"ok: {declared} v{pin} exists and carries {wanted}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
