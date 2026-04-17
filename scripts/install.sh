#!/usr/bin/env bash
#
# Bootstrap installer for the compose-preview CLI.
#
# Downloads a pinned (or latest) release tarball from GitHub, verifies the
# sha256 against the GitHub release API, installs into a versioned directory
# under ~/.local/opt/compose-preview, and symlinks ~/.local/bin/compose-preview
# to the new launcher. Idempotent: rerunning with the same version is a no-op.
#
# Usage:
#   scripts/install.sh               # install latest release
#   scripts/install.sh 0.3.2         # install a specific version
#   VERSION=0.3.2 scripts/install.sh # same, via env
#
# Override locations:
#   PREFIX=$HOME/.local scripts/install.sh
#   REPO=yschimke/compose-ai-tools scripts/install.sh
#
# Requires: bash, curl, tar, sha256sum (or shasum), and Java 21 on PATH at
# run time (not install time).

set -euo pipefail

REPO="${REPO:-yschimke/compose-ai-tools}"
PREFIX="${PREFIX:-$HOME/.local}"
VERSION="${1:-${VERSION:-}}"

BIN_DIR="$PREFIX/bin"
OPT_DIR="$PREFIX/opt/compose-preview"

die() { echo "error: $*" >&2; exit 1; }
log() { echo "==> $*"; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "neither sha256sum nor shasum available"
  fi
}

require curl
require tar

# ---- Resolve version ------------------------------------------------------

if [[ -z "$VERSION" ]]; then
  log "resolving latest release of $REPO"
  VERSION="$(
    curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" |
      sed -n 's/.*"tag_name":[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' |
      head -n1
  )"
  [[ -n "$VERSION" ]] || die "could not resolve latest version from GitHub API"
fi

ASSET="compose-preview-${VERSION}.tar.gz"
DEST="$OPT_DIR/$VERSION"

# Locate the extracted launcher by search rather than by hardcoded path —
# some release tarballs have a top-level dir named literally
# `compose-preview-<version>.tar.gz/` instead of `compose-preview-<version>/`.
find_launcher() {
  [[ -d "$1" ]] || return 1
  find "$1" -type f -name compose-preview -path '*/bin/compose-preview' \
    -print -quit 2>/dev/null
}

# ---- Idempotent short-circuit --------------------------------------------

EXISTING_LAUNCHER="$(find_launcher "$DEST" || true)"
if [[ -n "$EXISTING_LAUNCHER" && -x "$EXISTING_LAUNCHER" \
      && "$(readlink "$BIN_DIR/compose-preview" 2>/dev/null || true)" == "$EXISTING_LAUNCHER" ]]; then
  log "compose-preview $VERSION already installed and linked"
  "$EXISTING_LAUNCHER" --help >/dev/null 2>&1 || die "installed launcher is broken: $EXISTING_LAUNCHER"
  exit 0
fi

# ---- Fetch release metadata ----------------------------------------------

log "fetching release metadata for v$VERSION"
META="$(curl -fsSL \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/releases/tags/v$VERSION")" \
  || die "release v$VERSION not found on $REPO"

ASSET_URL="$(printf '%s' "$META" |
  sed -n 's/.*"browser_download_url":[[:space:]]*"\([^"]*'"$ASSET"'\)".*/\1/p' |
  head -n1)"
ASSET_DIGEST="$(printf '%s' "$META" |
  awk -v asset="$ASSET" '
    /"name":/ { in_asset = ($0 ~ asset) }
    in_asset && /"digest":/ {
      sub(/.*"digest":[[:space:]]*"sha256:/, "")
      sub(/".*/, "")
      print
      exit
    }
  ')"

[[ -n "$ASSET_URL" ]] || die "asset $ASSET not found in release v$VERSION"

# ---- Download + verify ----------------------------------------------------

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

log "downloading $ASSET_URL"
curl -fL --progress-bar -o "$TMP/$ASSET" "$ASSET_URL"

if [[ -n "${ASSET_DIGEST:-}" ]]; then
  got="$(sha256_of "$TMP/$ASSET")"
  [[ "$got" == "$ASSET_DIGEST" ]] \
    || die "sha256 mismatch: expected $ASSET_DIGEST, got $got"
  log "verified sha256 $got"
else
  log "warning: no sha256 digest advertised in release metadata; skipping verification"
fi

# ---- Install --------------------------------------------------------------

log "installing to $DEST"
mkdir -p "$DEST"
tar -xzf "$TMP/$ASSET" -C "$DEST"

LAUNCHER="$(find_launcher "$DEST" || true)"
[[ -n "$LAUNCHER" && -x "$LAUNCHER" ]] \
  || die "launcher not found after extract under $DEST (looked for */bin/compose-preview)"

mkdir -p "$BIN_DIR"
ln -sf "$LAUNCHER" "$BIN_DIR/compose-preview"
log "symlinked $BIN_DIR/compose-preview -> $LAUNCHER"

# ---- Smoke test -----------------------------------------------------------

if ! "$LAUNCHER" --help >/dev/null 2>&1; then
  die "launcher failed smoke test (needs Java 21 on PATH or JAVA_HOME)"
fi

# ---- PATH advice ----------------------------------------------------------

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    cat >&2 <<EOF

note: $BIN_DIR is not on your PATH.

  bash/zsh:  echo 'export PATH="$BIN_DIR:\$PATH"' >> ~/.bashrc  # or ~/.zshrc
  fish:      fish_add_path $BIN_DIR

EOF
    ;;
esac

log "installed compose-preview $VERSION"
log "next: run 'compose-preview doctor' in your project to verify Gradle + GitHub Packages access"
