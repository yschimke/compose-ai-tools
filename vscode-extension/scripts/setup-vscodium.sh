#!/usr/bin/env bash
# Provision a VSCodium binary for running the VS Code extension's
# electron test suites (`test:electron`, `test:e2e`, `test:e2e-external`)
# in environments where the Microsoft VS Code download hosts are
# unreachable.
#
# Why this exists: @vscode/test-electron's `downloadAndUnzipVSCode()`
# fetches stock VS Code from `update.code.visualstudio.com` /
# `vscode.download.prss.microsoft.com`. Cloud sandboxes whose egress
# policy is an allowlist (the Claude Code on the web "custom" network
# mode is the motivating case) commonly permit github.com but 403 the
# entire Microsoft/VS Code host family — which also takes out the
# marketplace, open-vsx, and the Playwright CDN. VSCodium publishes
# its Linux build as a GitHub release asset, which the allowlist *does*
# permit, and `runTest.ts` honours `VSCODE_TEST_EXECUTABLE` to point
# the harness at a pre-provisioned binary. Our extension and the fake
# gradle/kotlin stubs are side-loaded via `extensionDevelopmentPath`,
# so the marketplace is never consulted — VSCodium runs the suite the
# same way stock VS Code would.
#
# Usage:
#   vscode-extension/scripts/setup-vscodium.sh [INSTALL_DIR]
#
# Env (override defaults):
#   VSCODIUM_VERSION   release tag to pin (default: latest GA)
#   VSCODIUM_DIR       install dir (default: /opt/vscodium, or arg 1)
#
# Output: prints the absolute path to the VSCodium executable on stdout
# (suitable for `export VSCODE_TEST_EXECUTABLE=$(setup-vscodium.sh)`).

set -euo pipefail

install_dir="${1:-${VSCODIUM_DIR:-/opt/vscodium}}"
exe="$install_dir/codium"

# Idempotent: a populated install dir is reused as-is.
if [[ -x "$exe" ]]; then
  echo "[setup-vscodium] reusing existing VSCodium at $exe" >&2
  echo "$exe"
  exit 0
fi

arch="$(uname -m)"
case "$arch" in
  x86_64)  vscodium_arch="x64" ;;
  aarch64|arm64) vscodium_arch="arm64" ;;
  *) echo "[setup-vscodium] unsupported arch: $arch" >&2; exit 1 ;;
esac

tag="${VSCODIUM_VERSION:-}"
if [[ -z "$tag" ]]; then
  # The /releases/latest redirect resolves to /releases/tag/<version>
  # without hitting the GitHub API (no token, no rate-limit surprises).
  tag="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
    https://github.com/VSCodium/vscodium/releases/latest | sed 's#.*/tag/##')"
fi

url="https://github.com/VSCodium/vscodium/releases/download/${tag}/VSCodium-linux-${vscodium_arch}-${tag}.tar.gz"
echo "[setup-vscodium] tag=$tag arch=$vscodium_arch" >&2
echo "[setup-vscodium] downloading $url" >&2

mkdir -p "$install_dir"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fsSL -o "$tmp" "$url"
tar -xzf "$tmp" -C "$install_dir"

if [[ ! -x "$exe" ]]; then
  echo "[setup-vscodium] expected executable at $exe after extract — layout changed?" >&2
  exit 1
fi

echo "[setup-vscodium] installed VSCodium $tag at $exe" >&2
echo "$exe"
