#!/usr/bin/env python3
"""Fail a PR whose `cli/serve-wasm` sources have drifted from compose-preview-server's `wasm-ui`.

`cli/serve-wasm` and yschimke/compose-preview-server's `wasm-ui` are the same Compose/Wasm preview
frontend, compiled twice. Each repository stages its build into `preview-ui/` in its own
distribution, and each compiles the app against ITS OWN M3 catalog — `:samples:design-catalog-m3-shared`
here, `:native-catalog-m3` there. That difference is why one published artifact cannot serve both
and why the source is duplicated rather than depended upon.

The duplication is therefore accepted; the SILENCE about it was not. Between 2026-08-29 and
2026-08-30 the two copies drifted with nothing to notice: upstream fixed #4821 (harness-driven
variants must decline and fall back to the server's snapshot, instead of composing a plain Button
and labelling it "Pressed") and added the whole `CatalogFonts` lane. This repository shipped the
old behaviour inside `compose-preview`'s `preview-ui/` — the wrong picture, presented as the right
one — while `compose-preview serve` ran the fixed server. Two copies with no gate is how that
happens quietly.

So this gate holds the shared sources byte-identical. The inventory is DERIVED by walking both
trees rather than listed in a constant: a hand-maintained list silently ignores whatever nobody
remembered to add to it, which is the same failure mode the gate exists to close. A file present on
one side and not the other is drift, not an opt-out.

Only `src/` is compared. `build.gradle.kts` and `README.md` are outside it and per-repository by
design — the catalog dependency, the font/js-joda staging paths, and each repository's own build
instructions. `js-joda.esm.js` is a 10k-line vendored bundle inside `src/`, byte-identical in both
and excluded by name to keep the diff output readable.

`ALLOWED_DELTAS` is for the one case that is neither: a shared source file carrying a paragraph that
is only true in one repository. Each entry states the file, the exact upstream text, the exact local
text, and why. An entry is a review decision, not a mute button — anything not listed must match.

Offline by default against a vendored copy under `.github/ci/serve-wasm-upstream/`, refreshed by
`--update` from a pinned upstream SHA (`UPSTREAM_PIN`). Pinning rather than tracking `main` means
upstream's next commit does not redden an unrelated PR here; bumping the pin is the deliberate act
of taking their changes, and the diff this gate prints is the review.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LOCAL_DIR = REPO_ROOT / "cli/serve-wasm/src"
VENDOR_DIR = Path(__file__).resolve().parent / "serve-wasm-upstream"
PIN_FILE = Path(__file__).resolve().parent / "serve-wasm-upstream-pin.json"

UPSTREAM_REPO = "yschimke/compose-preview-server"
UPSTREAM_PREFIX = "wasm-ui/src"

# Files that are per-repository by design and therefore never compared. Everything else under
# `src/` is shared and MUST match — the inventory is derived, not listed, so a file added on either
# side cannot slip through by being absent from a hand-maintained list.
NOT_COMPARED = {"wasmJsMain/resources/js-joda.esm.js"}


def inventory(base: Path) -> set[str]:
    """Every file under `base`, relative and slash-separated, minus the per-repository ones."""
    if not base.is_dir():
        return set()
    found = {
        path.relative_to(base).as_posix() for path in base.rglob("*") if path.is_file()
    }
    return found - NOT_COMPARED


ALLOWED_DELTAS = [
    {
        "file": "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/CatalogFonts.kt",
        "why": (
            "The font files are byte-identical in both repositories; only where they are staged "
            "from differs (`assets/rc-fonts` upstream, `:samples:cmp-wasm-catalog`'s own "
            "`resources/fonts` here). The KDoc names that path, so it cannot be true in both."
        ),
        "upstream": (
            " * ones `:samples:cmp-wasm-catalog` loads upstream and the same ones the offline parity harness\n"
            " * registers — here they are the repository's own `assets/rc-fonts`, packaged into the frontend\n"
            " * bundle as `fonts/` by `wasmFrontendDist`, so there is one vendored copy rather than two."
        ),
        "local": (
            " * ones `:samples:cmp-wasm-catalog` loads and the same ones the offline parity harness registers —\n"
            " * here they are staged straight out of that sample's `resources/fonts`, packaged into the frontend\n"
            " * bundle as `fonts/` by `wasmFrontendDist`, so there is one vendored copy rather than two.\n"
            " *\n"
            " * (Upstream in compose-preview-server the same files come from that repository's `assets/rc-fonts`.\n"
            " * The two sets are byte-identical; only the path differs, and `check-serve-wasm-fork.py` records\n"
            " * this paragraph as the one permitted delta between the two copies of this file.)"
        ),
    }
]


def read_pin() -> str:
    return json.loads(PIN_FILE.read_text())["sha"]


def fetch_upstream(sha: str, rel: str) -> bytes:
    url = f"https://raw.githubusercontent.com/{UPSTREAM_REPO}/{sha}/{UPSTREAM_PREFIX}/{rel}"
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")


def update(sha: str) -> int:
    # A full commit hash, not a branch or tag. `--update main` would write "main" into a file the
    # gate reports as a pinned SHA, and the vendored snapshot would then have no reproducible
    # provenance: the same recorded value fetches different bytes next week. Rejecting here is the
    # only place that can tell the difference.
    if not SHA_RE.match(sha):
        print(
            f"error: --update needs a full 40-character commit SHA, not {sha!r}.\n"
            f"       A branch or tag is mutable, so the pin would stop identifying the bytes that\n"
            f"       were actually vendored. Resolve it first, e.g.\n"
            f"         git ls-remote https://github.com/{UPSTREAM_REPO} {sha}"
        )
        return 1
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)

    # The upstream inventory comes from the git tree API, not from a list here, so a file ADDED
    # upstream is vendored on the next pin bump instead of being invisible until someone notices.
    try:
        listing = fetch_tree(sha)
    except urllib.error.HTTPError as error:
        print(f"error: cannot list {UPSTREAM_REPO}@{sha[:12]} ({error.code})")
        return 1

    if VENDOR_DIR.is_dir():
        shutil.rmtree(VENDOR_DIR)
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)

    for rel in sorted(listing):
        try:
            body = fetch_upstream(sha, rel)
        except urllib.error.HTTPError as error:
            print(f"error: {rel} is not at {UPSTREAM_REPO}@{sha[:12]} ({error.code})")
            return 1
        target = VENDOR_DIR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
    PIN_FILE.write_text(json.dumps({"sha": sha}, indent=2) + "\n")
    print(f"vendored {len(listing)} files from {UPSTREAM_REPO}@{sha[:12]}")
    return 0


def fetch_tree(sha: str) -> set[str]:
    """The upstream `wasm-ui/src` inventory at `sha`, relative and slash-separated."""
    url = f"https://api.github.com/repos/{UPSTREAM_REPO}/git/trees/{sha}?recursive=1"
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        tree = json.loads(response.read())["tree"]
    prefix = UPSTREAM_PREFIX + "/"
    found = {
        node["path"][len(prefix) :]
        for node in tree
        if node["type"] == "blob" and node["path"].startswith(prefix)
    }
    return found - NOT_COMPARED


def normalise(rel: str, text: str) -> str:
    """Apply the allowed deltas to the LOCAL text, rewriting it back to upstream's wording."""
    for delta in ALLOWED_DELTAS:
        if delta["file"] != rel:
            continue
        if delta["local"] not in text:
            # The delta no longer applies as written. Do not silently pass: an entry that has
            # rotted is exactly as dangerous as no entry, because it excuses a diff nobody read.
            raise SystemExit(
                f"error: the allowed delta for {rel} no longer matches the local file.\n"
                f"       Its recorded local text is not present, so the exemption cannot be\n"
                f"       applied and the comparison below would be meaningless.\n"
                f"       Re-state the delta in ALLOWED_DELTAS, or remove it if it is obsolete."
            )
        text = text.replace(delta["local"], delta["upstream"])
    return text


def main(argv: list[str] | None = None) -> int:
    # `argv` is a parameter rather than read straight from `sys.argv` so the unit tests can call
    # `main([])`. Without it they inherit the test runner's own flags (`-v`) and argparse exits 2.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", metavar="SHA", help="re-vendor from this upstream commit")
    args = parser.parse_args(argv)

    if args.update:
        return update(args.update)

    sha = read_pin()
    if not VENDOR_DIR.is_dir():
        print(f"error: {VENDOR_DIR.relative_to(REPO_ROOT)} is missing; run --update <sha>")
        return 1

    local_files = inventory(LOCAL_DIR)
    vendor_files = inventory(VENDOR_DIR)

    drifted: list[str] = []
    # Inventory first: a file on one side only is drift the content comparison cannot see, and is
    # exactly the case a hard-coded list used to miss.
    for rel in sorted(vendor_files - local_files):
        drifted.append(f"{rel}: upstream has it, cli/serve-wasm does not")
    for rel in sorted(local_files - vendor_files):
        drifted.append(
            f"{rel}: cli/serve-wasm has it, upstream does not "
            f"(port it, or add it to NOT_COMPARED with a reason)"
        )

    for rel in sorted(local_files & vendor_files):
        local_path = LOCAL_DIR / rel
        vendor_path = VENDOR_DIR / rel
        local = normalise(rel, local_path.read_text())
        if local != vendor_path.read_text():
            diff = subprocess.run(
                ["diff", "-u", str(vendor_path), str(local_path)],
                capture_output=True,
                text=True,
            ).stdout
            drifted.append(f"{rel}:\n{diff}")

    if drifted:
        print(f"`cli/serve-wasm` has drifted from {UPSTREAM_REPO}@{sha[:12]}'s `wasm-ui`:\n")
        for entry in drifted:
            print(f"  - {entry}")
        print(
            "\nThese two trees are the same application compiled twice, and they must stay\n"
            "byte-identical outside the build wiring. Either port the change to the other\n"
            "repository and bump the pin with `--update <sha>`, or, if the difference is a\n"
            "paragraph that can only be true in one repository, record it in ALLOWED_DELTAS\n"
            "with its reason.\n"
            "\n"
            "The failure this prevents: #4821 was fixed upstream and not here, so\n"
            "`compose-preview`'s bundled `preview-ui/` drew an unpressed button labelled\n"
            '"Pressed" for a day while `serve` ran the fixed server.'
        )
        return 1

    print(
        f"cli/serve-wasm matches {UPSTREAM_REPO}@{sha[:12]}'s wasm-ui "
        f"({len(local_files)} shared files, {len(ALLOWED_DELTAS)} recorded delta(s); "
        f"{len(NOT_COMPARED)} per-repository file(s) not compared)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
