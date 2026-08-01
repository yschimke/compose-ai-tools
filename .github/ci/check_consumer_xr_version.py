#!/usr/bin/env python3
"""Guard the `androidx.xr.compose` version skew between us and the JetStream consumer patch.

The `adaptive-apps-samples (AdaptiveJetStream — XR spatial Compose)` integration cell renders the
sample's `@XrSubspacePreview`s on OUR render classpath: the app supplies `androidx.xr.compose:compose`
at the version its own catalog pins (rewritten by `.github/ci/patches/adaptive-apps-samples-xr-upgrade
.patch`), while `androidx.xr.compose:compose-testing` — the library `SubspaceSceneRecorder` reflects
into to enumerate subspace nodes — comes from `:renderer-xr`, pinned by `xr-compose` in
`gradle/libs.versions.toml`.

Those two must be the same version. When they drift, nothing fails to compile: the testing library
simply finds no subspace hierarchies and every XR render dies at runtime with

    IllegalStateException: No subspace compose hierarchies found in the app

which only reproduces on `main` (the XR step is skipped on PRs). Bumping `xr-compose` without
re-cutting the patch is therefore a silent, main-only break — this check turns it into a PR failure.

Exits non-zero with a fix hint on mismatch.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "gradle" / "libs.versions.toml"
PATCH = REPO / ".github" / "ci" / "patches" / "adaptive-apps-samples-xr-upgrade.patch"


def ours() -> str:
    for line in CATALOG.read_text().splitlines():
        m = re.match(r'\s*xr-compose\s*=\s*"([^"]+)"', line)
        if m:
            return m.group(1)
    raise SystemExit(f"could not find `xr-compose = \"…\"` in {CATALOG}")


def consumer() -> str:
    # The patch's added line for the sample's own catalog: `+xr = "1.0.0-alphaNN"`.
    for line in PATCH.read_text().splitlines():
        m = re.match(r'\+xr\s*=\s*"([^"]+)"', line)
        if m:
            return m.group(1)
    raise SystemExit(f"could not find an added `xr = \"…\"` line in {PATCH}")


def main() -> int:
    mine, theirs = ours(), consumer()
    if mine == theirs:
        print(f"ok: androidx.xr.compose pinned at {mine} on both sides")
        return 0
    print(
        f"::error::androidx.xr.compose version skew: `xr-compose = \"{mine}\"` in "
        f"gradle/libs.versions.toml but the AdaptiveJetStream consumer patch pins "
        f"`xr = \"{theirs}\"`.\n"
        f"  The integration cell renders the sample's Subspaces with OUR compose-testing "
        f"({mine}) against ITS compose ({theirs}); a skew makes every XR render fail at runtime "
        f'with "No subspace compose hierarchies found in the app" — on main only, since the XR '
        f"step is skipped on PRs.\n"
        f"  Fix: update `xr` (and `xr-material3`) in "
        f".github/ci/patches/adaptive-apps-samples-xr-upgrade.patch to {mine}, then re-run the "
        f"integration cell to confirm the sample still compiles against the new APIs.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
