#!/usr/bin/env python3
"""Copy rules_compose_preview's Android aspect into the CLI's bundled resources.

The CLI installs these files into `.compose-preview/bazel/android/` so a workspace that does not
fetch `@rules_compose_preview` can still apply the Android aspect (compose-ai-tools#5945). They are
copied byte for byte from one release tag and never edited here; `provenance.json` records the tag
and each file's SHA-256, which `BazelAndroidAspectTest` checks.

Usage: scripts/sync-bazel-android-aspect.py v1.0.0
"""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

FILES = ["android.bzl", "android_providers.bzl", "java_info.bzl", "runtime.bzl", "link.sh"]
REPOSITORY = "yschimke/compose-preview-bazel"
DESTINATION = (
    Path(__file__).resolve().parents[1]
    / "cli/src/main/resources/ee/schimke/composeai/cli/bazel/android"
)


def main(tag):
    hashes = {}
    for name in FILES:
        url = f"https://raw.githubusercontent.com/{REPOSITORY}/{tag}/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read()
        (DESTINATION / name).write_bytes(content)
        hashes[name] = hashlib.sha256(content).hexdigest()
    provenance = {"repository": REPOSITORY, "tag": tag, "sha256": hashes}
    (DESTINATION / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"Synced {len(FILES)} files from {REPOSITORY}@{tag}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
