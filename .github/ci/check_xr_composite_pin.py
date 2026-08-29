#!/usr/bin/env python3
"""Fail a PR that changes the native compositor without moving the `xr-composite` pin.

The CLI (`XrCompositeProvision`) and the Gradle plugin (`xrCompositeCacheBinaryPath`) both resolve
the native `xr-composite` binary by the `xr-composite` version in `gradle/libs.versions.toml`, not
by their own version. That decoupling is what lets the compositor be published rarely — but it
introduces the failure it replaces: a change to `renderers/xr-composite/` that does NOT move the
pin ships nothing. Consumers keep fetching the previous release, the render still succeeds (the
composite is an optional capture and every provisioning failure is a graceful skip), and nobody
finds out. That is the same silent-degradation shape the extension split turned up in
`validate-report-schemas.mjs`, so it gets a hard gate rather than a convention.

The rule: if the diff touches `renderers/xr-composite/**` (excluding files that cannot affect the
built artifact — see NON_SHIPPING), the same diff must change the `xr-composite` pin.

Exits non-zero with a fix hint on violation. `--print-pin` just prints the pin, which is how
`xr-composite-release.yml` checks that it publishes the version consumers actually ask for.

A change that provably cannot alter the built binary's behaviour — a rename, a refactor, replacing
a literal with a constant of the same value — has nothing to publish, and bumping the pin for it
would name a release nobody cuts, which 404s into the same silent skip. Such a change opts out by
stating a reason: put a line

    XR-Release: none - <why this cannot change the binary>

in the pull request body. CI passes it in via `XR_RELEASE_OVERRIDE` and the gate prints it. The
reason is mandatory and lands in the PR record, so the opt-out is reviewable rather than a flag
anyone can pass. It is deliberately not a path allowlist: widening NON_SHIPPING would hide the
next real change to those same files.

Changed paths come from `git diff` against `--base` by default (what a contributor wants locally)
or, with `--changed -`, from stdin — the shape CI uses, since a PR runner has only a shallow
checkout and the base SHA is what the event carries. Unlike the informational consumer-contract
notice this fails CLOSED: a diff we cannot compute must not read as "the compositor is untouched",
which is the silent pass this gate exists to prevent.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = Path("gradle/libs.versions.toml")
WATCHED = "renderers/xr-composite/"

# Paths under the watched tree that cannot change the published binary or its materials, so they
# must not force a release. Kept deliberately short: anything not listed here is assumed shipping.
NON_SHIPPING = (
    "renderers/xr-composite/README.md",
    "renderers/xr-composite/test/",
)

PIN_RE = re.compile(r'^\s*xr-composite\s*=\s*"([^"]+)"', re.MULTILINE)

# `XR-Release: none - <reason>`, anywhere in the PR body. The reason is required: a bare
# `XR-Release: none` does not match, so the opt-out cannot be taken without saying why.
OVERRIDE_RE = re.compile(
    r"^\s*XR-Release:\s*none\s*[-\u2014:]\s*(\S.*?)\s*$", re.MULTILINE | re.IGNORECASE
)


def override_reason(body: str | None) -> str | None:
    """The stated reason for shipping a compositor change without a release, or None."""
    if not body:
        return None
    m = OVERRIDE_RE.search(body)
    return m.group(1) if m else None


def pin_from(text: str) -> str | None:
    """The `xr-composite` version declared in a `libs.versions.toml` body, or None."""
    m = PIN_RE.search(text)
    return m.group(1) if m else None


def current_pin() -> str:
    text = (REPO / CATALOG).read_text()
    pin = pin_from(text)
    if pin is None:
        raise SystemExit(f'could not find `xr-composite = "…"` in {CATALOG}')
    return pin


def shipping_changes(paths: list[str]) -> list[str]:
    """Changed paths under the watched tree that can affect the published artifact."""
    return [
        p
        for p in paths
        if p.startswith(WATCHED) and not p.startswith(NON_SHIPPING)
    ]


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO, check=True, capture_output=True, text=True
    ).stdout


def changed_paths(base: str) -> list[str]:
    return [p for p in git("diff", "--name-only", f"{base}...HEAD").splitlines() if p]


def pin_at(ref: str) -> str | None:
    """The pin as of [ref], or None when the catalog or the entry is absent there."""
    try:
        return pin_from(git("show", f"{ref}:{CATALOG}"))
    except subprocess.CalledProcessError:
        return None


def pin_moved(before: str | None, after: str) -> bool:
    """Whether the diff moved the pin, for a compositor change that needs one.

    `before is None` means the base has no `xr-composite` entry at all — only true for the PR that
    introduces it, which by definition sets the pin this diff should have. Treated as moved, so
    that one PR is not asked to bump a pin nobody was reading yet. Every later diff compares two
    real values.
    """
    if before is None:
        return True
    return before != after


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--print-pin",
        action="store_true",
        help="print the current xr-composite pin and exit",
    )
    ap.add_argument(
        "--base",
        default="origin/main",
        help="base ref to diff against and read the previous pin from (default: origin/main)",
    )
    ap.add_argument(
        "--changed",
        metavar="FILE",
        help="read changed paths from FILE ('-' for stdin) instead of asking git",
    )
    ap.add_argument(
        "--override-body",
        help="text to scan for an `XR-Release: none - <reason>` opt-out "
        "(default: the XR_RELEASE_OVERRIDE environment variable)",
    )
    args = ap.parse_args()

    if args.print_pin:
        print(current_pin())
        return 0

    if args.changed:
        raw = sys.stdin.read() if args.changed == "-" else Path(args.changed).read_text()
        paths = [line.strip() for line in raw.splitlines() if line.strip()]
    else:
        paths = changed_paths(args.base)
    touched = shipping_changes(paths)
    if not touched:
        return 0

    body = args.override_body if args.override_body is not None else os.environ.get(
        "XR_RELEASE_OVERRIDE"
    )
    reason = override_reason(body)
    if reason:
        print(f"ok: compositor changed with a stated no-release reason - {reason}")
        return 0

    before, after = pin_at(args.base), current_pin()
    if pin_moved(before, after):
        moved = f"{before} → {after}" if before is not None else f"introduced at {after}"
        print(f"ok: compositor changed and the pin {moved}")
        return 0

    listing = "\n".join(f"    {p}" for p in touched[:10])
    more = f"\n    … and {len(touched) - 10} more" if len(touched) > 10 else ""
    print(
        f"::error::this PR changes the native compositor but leaves `xr-composite = \"{after}\"` "
        f"in {CATALOG} untouched.\n"
        f"  Changed under {WATCHED}:\n{listing}{more}\n"
        f"  The CLI and the Gradle plugin both resolve the binary by that pin, so an unmoved pin "
        f"means consumers keep fetching the previous release and this change ships nowhere. It "
        f"fails quietly: a missing asset is a graceful skip, so the render still succeeds without "
        f"a composite.\n"
        f"  Fix: bump `xr-composite` in {CATALOG} to the release you will publish the new binaries "
        f"to, then run the `xr-composite release` workflow for that version once this lands. "
        f"Docs-only or test-only changes under {WATCHED} are exempt and do not reach this error.\n"
        f"  If this change cannot alter the built binary (a rename, a refactor, a literal replaced "
        f"by an equal constant), say so in the PR body instead:\n"
        f"    XR-Release: none - <why this cannot change the binary>",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
