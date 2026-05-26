#!/usr/bin/env bash
# Prepares an external (third-party) Gradle/Kotlin project as the workspace
# for the VS Code extension e2e suite at `e2eExternal.test.ts`. Built around
# joreilly/Confetti by default — a real-world Compose Multiplatform + Wear OS
# consumer that already declares `alias(libs.plugins.composeai.preview)` —
# but the same shape works for any repo whose `gradle/libs.versions.toml`
# pins our plugin version through `composeai-preview`.
#
# Why a separate suite: the in-repo `:samples:*` e2e wires the plugin via
# `includeBuild("gradle-plugin")`, which is the perfectly-aligned dev-loop
# path. Real consumers resolve the plugin from Maven Central and hit a much
# harsher classpath (Apollo, KMP wasmJs+iOS, KMM Bridge, Firebase, ...).
# Failures that only surface against a real consumer — stale daemon-launch
# descriptors, classpath fingerprint mismatches, AGP/AndroidX alignment
# drift — go unseen until a user files an issue. This script + the matching
# suite close that gap by driving the published-coordinate path against a
# fixed third-party SHA.
#
# Usage:
#   scripts/setup-external-e2e.sh [TARGET_DIR]
#
# Env (override defaults):
#   EXTERNAL_REPO_URL   git URL (default: https://github.com/joreilly/Confetti.git)
#   EXTERNAL_REPO_REF   commit SHA / tag / branch (default: pinned SHA below)
#   PLUGIN_VERSION      plugin coordinate to inject (default: read from
#                       `.release-please-manifest.json` and bump to
#                       next-patch -SNAPSHOT, matching what
#                       `publishToMavenLocal` produced)
#   ANDROID_SDK_DIR     absolute path written into `local.properties`
#                       (default: ANDROID_HOME or /opt/android-sdk)
#
# Output: prints the absolute path to the prepared workspace on stdout.

set -euo pipefail

DEFAULT_REPO_URL="https://github.com/joreilly/Confetti.git"
# Track `main` rather than a pinned SHA. Lets the suite pick up upstream
# fixes (e.g. catalog dep adjustments needed for new plugin task wiring)
# without requiring a separate bump PR, and the catalog rewrite below
# still pins our plugin version to the SNAPSHOT we just published, so the
# only drift we're tracking is Confetti's own code. Override with
# `EXTERNAL_REPO_REF=<sha>` from `workflow_dispatch` when reproducing a
# specific regression.
DEFAULT_REPO_REF="main"

target_dir="${1:-/tmp/compose-preview-external-e2e}"
repo_url="${EXTERNAL_REPO_URL:-$DEFAULT_REPO_URL}"
repo_ref="${EXTERNAL_REPO_REF:-$DEFAULT_REPO_REF}"

# Resolve PLUGIN_VERSION the same way `scripts/generate-version.mjs` does so
# the catalog rewrite matches the extension's `BUNDLED_PLUGIN_VERSION`. The
# Node helper is the single source of truth — duplicating the bump logic in
# bash would drift.
script_dir="$(cd "$(dirname "$0")" && pwd)"
plugin_version="${PLUGIN_VERSION:-}"
if [[ -z "$plugin_version" ]]; then
  plugin_version="$(node -e '
    const { readFileSync } = require("node:fs");
    const { resolve } = require("node:path");
    const env = process.env.PLUGIN_VERSION;
    if (env) { process.stdout.write(env); process.exit(0); }
    const m = JSON.parse(readFileSync(resolve("'"$script_dir"'", "../../.release-please-manifest.json"), "utf8"));
    const [major, minor, patch] = String(m["."]).split(".").map((p) => parseInt(p, 10));
    process.stdout.write(`${major}.${minor}.${patch + 1}-SNAPSHOT`);
  ')"
fi

android_sdk_dir="${ANDROID_SDK_DIR:-${ANDROID_HOME:-/opt/android-sdk}}"

echo "[setup-external-e2e] target=$target_dir" >&2
echo "[setup-external-e2e] repo=$repo_url@$repo_ref" >&2
echo "[setup-external-e2e] plugin_version=$plugin_version" >&2
echo "[setup-external-e2e] android_sdk_dir=$android_sdk_dir" >&2

if [[ ! -d "$target_dir/.git" ]]; then
  rm -rf "$target_dir"
  # Shallow init + fetch-by-SHA — the default branch may not contain the
  # pinned SHA (after a force-push) and `git clone --branch <sha>` doesn't
  # work. init+fetch is the GitHub-recommended pattern for SHA-pinned
  # fixtures.
  git init -q "$target_dir"
  (
    cd "$target_dir"
    git remote add origin "$repo_url"
    git fetch --depth 1 origin "$repo_ref"
    git checkout -q FETCH_HEAD
  )
else
  (
    cd "$target_dir"
    current="$(git rev-parse HEAD)"
    if [[ "$current" != "$repo_ref"* ]]; then
      git fetch --depth 1 origin "$repo_ref"
      # `--force` discards uncommitted edits in the existing checkout
      # before moving HEAD. Required because the catalog rewrite below
      # is regenerated on every run and stays uncommitted: a plain
      # `checkout` would refuse with "your local changes would be
      # overwritten" the moment upstream advances `libs.versions.toml`
      # itself. The rewrite is re-applied right below, so dropping it
      # here is safe.
      git checkout -fq FETCH_HEAD
    fi
  )
fi

# Rewrite the catalog so the plugin resolves to our SNAPSHOT instead of the
# stale published version Confetti currently pins. The pre-applied detector
# in the extension's init-script keys off `alias(libs.plugins.<x>)`, so
# Gradle still applies it via the catalog — we just point the catalog at a
# different version. Idempotent: running twice produces the same file.
catalog="$target_dir/gradle/libs.versions.toml"
if [[ ! -f "$catalog" ]]; then
  echo "[setup-external-e2e] expected $catalog — repo layout changed?" >&2
  exit 1
fi
python3 - "$catalog" "$plugin_version" <<'PY'
import re
import sys

path, version = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8") as fh:
    text = fh.read()
new = re.sub(
    r'^(\s*composeai-preview\s*=\s*)"[^"]+"',
    rf'\g<1>"{version}"',
    text,
    count=1,
    flags=re.MULTILINE,
)
if new == text:
    sys.exit("composeai-preview entry not found in catalog; refusing to proceed")
with open(path, "w", encoding="utf-8") as fh:
    fh.write(new)
PY

# Android SDK path — Confetti and most Android consumers fail Gradle
# configuration without one of `local.properties:sdk.dir`, `ANDROID_HOME`,
# or `ANDROID_SDK_ROOT`. Writing the file is the most reliable signal
# across Gradle / AGP versions.
echo "sdk.dir=$android_sdk_dir" > "$target_dir/local.properties"

echo "$target_dir"
