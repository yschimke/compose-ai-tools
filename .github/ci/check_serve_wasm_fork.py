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

So this gate holds the shared sources byte-identical. Files that legitimately differ are NOT
compared at all, rather than compared and excused:

  * `build.gradle.kts` — the catalog dependency and the font/js-joda source paths are per-repository.
  * `README.md` — currently identical, but it describes each repository's own build.

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

# The shared application sources. Paths are relative to `<repo>/<module>/src`, which is the level at
# which the two trees are supposed to be identical.
SHARED = [
    "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/App.kt",
    "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/CatalogFonts.kt",
    "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/Main.kt",
    "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/NativeCatalog.kt",
    "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/UiComposer.kt",
    "wasmJsMain/resources/index.html",
    "wasmJsTest/kotlin/ee/schimke/composeai/servewasm/NativeCatalogTest.kt",
    "wasmJsTest/kotlin/ee/schimke/composeai/servewasm/OverrideSeedsTest.kt",
    "wasmJsTest/kotlin/ee/schimke/composeai/servewasm/UiComposerTest.kt",
]

# Deliberately NOT compared — see the module docstring.
NOT_COMPARED = ["build.gradle.kts", "README.md", "wasmJsMain/resources/js-joda.esm.js"]

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


def update(sha: str) -> int:
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    for rel in SHARED:
        try:
            body = fetch_upstream(sha, rel)
        except urllib.error.HTTPError as error:
            print(f"error: {rel} is not at {UPSTREAM_REPO}@{sha[:12]} ({error.code})")
            return 1
        target = VENDOR_DIR / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
    PIN_FILE.write_text(json.dumps({"sha": sha}, indent=2) + "\n")
    print(f"vendored {len(SHARED)} files from {UPSTREAM_REPO}@{sha[:12]}")
    return 0


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

    drifted: list[str] = []
    for rel in SHARED:
        local_path = LOCAL_DIR / rel
        vendor_path = VENDOR_DIR / rel
        if not local_path.is_file():
            drifted.append(f"{rel}: missing from cli/serve-wasm")
            continue
        if not vendor_path.is_file():
            drifted.append(f"{rel}: missing from the vendored upstream copy; run --update")
            continue
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
        f"({len(SHARED)} shared files, {len(ALLOWED_DELTAS)} recorded delta(s); "
        f"{len(NOT_COMPARED)} per-repository files not compared)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
