#!/usr/bin/env bash
# Install the design-artifacts export driver from the reviewed lock in
# .github/design-artifacts-driver/, and print the driver's directory.
#
#   install-export-driver.sh [--scripts-only] <dest>
#
# The driver's scripts come from the published `@design-parity/export-driver` package
# (design-parity's packages/export-driver is their source). Which version, and every
# package under it, is decided by the committed package-lock.json, never by the
# registry on the day: a privileged job must run a tree someone reviewed. Renovate
# moves the version and the lock together.
#
# The default is `npm ci --omit=dev` against that lock: the driver plus the npm
# packages its catalog and comparison steps import.
#
# --scripts-only fetches the driver's own tarball and nothing else, for jobs that run
# only its dependency-free scripts (a render shard's partition and spec checks, the
# publish job's marker). The tarball is the one the lock names, checked against the
# lock's sha512 integrity before it is unpacked, so it is the same bytes `npm ci`
# would install.
#
# Either way the driver lands at <dest>/node_modules/@design-parity/export-driver.
# Under GitHub Actions the script also exports DRIVER_DIR (that directory) and
# DRIVER_ROOT (<dest>, whose node_modules/.bin holds the locked playwright).

set -euo pipefail

scripts_only=0
if [ "${1:-}" = "--scripts-only" ]; then
  scripts_only=1
  shift
fi
[ $# -eq 1 ] || { echo "usage: $0 [--scripts-only] <dest>" >&2; exit 2; }
dest="$1"

# EXPORT_DRIVER_LOCK_DIR exists for test-install-export-driver.sh, which points it at a tampered copy.
lock_dir="$(cd "${EXPORT_DRIVER_LOCK_DIR:-$(dirname "$0")/../design-artifacts-driver}" && pwd)"
pkg="@design-parity/export-driver"

mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
driver="$dest/node_modules/$pkg"

if [ "$scripts_only" = 1 ]; then
  read -r resolved integrity < <(node -e '
    const entry = require(process.argv[1]).packages?.["node_modules/" + process.argv[2]];
    if (!entry?.resolved || !entry?.integrity) process.exit(1);
    console.log(entry.resolved, entry.integrity);
  ' "$lock_dir/package-lock.json" "$pkg") || {
    echo "::error::$lock_dir/package-lock.json has no resolved+integrity entry for $pkg" >&2
    exit 1
  }
  case "$integrity" in
    sha512-*) ;;
    *) echo "::error::$pkg's lock integrity is not sha512: $integrity" >&2; exit 1 ;;
  esac
  tarball="$(mktemp)"
  trap 'rm -f "$tarball"' EXIT
  curl -fsSL --retry 3 -o "$tarball" "$resolved"
  actual="sha512-$(openssl dgst -sha512 -binary "$tarball" | base64 -w0)"
  if [ "$actual" != "$integrity" ]; then
    echo "::error::$resolved does not match the lock's integrity (want $integrity, got $actual)" >&2
    exit 1
  fi
  rm -rf "$driver"
  mkdir -p "$driver"
  tar -xzf "$tarball" -C "$driver" --strip-components=1
else
  cp "$lock_dir/package.json" "$lock_dir/package-lock.json" "$dest/"
  (cd "$dest" && npm ci --omit=dev --no-audit --no-fund --loglevel=warn) >&2
fi

[ -f "$driver/package.json" ] || { echo "::error::no driver at $driver after install" >&2; exit 1; }

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "DRIVER_DIR=$driver"
    echo "DRIVER_ROOT=$dest"
  } >> "$GITHUB_ENV"
fi
echo "$driver"
