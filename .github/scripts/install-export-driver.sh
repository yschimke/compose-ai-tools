#!/usr/bin/env bash
# Install the design-artifacts export driver from the reviewed lock in
# .github/design-artifacts-driver/, and print the driver's directory.
#
#   install-export-driver.sh <dest>
#
# The driver's scripts come from the published `@design-parity/export-driver` package
# (design-parity's packages/export-driver is their source). Which version, and every
# package under it, is decided by the committed package-lock.json, never by the
# registry on the day: a privileged job must run a tree someone reviewed. Renovate
# moves the version and the lock together.
#
# It runs `npm ci --omit=dev` against that lock, which checks every tarball against the
# lock's integrity: the driver plus the npm packages its scripts import. Every job takes
# the whole tree. There used to be a `--scripts-only` mode that unpacked the driver's own
# tarball and nothing else, for jobs running only its dependency-free scripts; since
# design-parity moved the spec join's helpers into @design-parity/catalog-export, those
# scripts import it too. The flag is still accepted, and ignored, so a caller pinned to
# an older reusable workflow that passes it keeps working.
#
# The driver lands at <dest>/node_modules/@design-parity/export-driver. Under GitHub
# Actions the script also exports DRIVER_DIR (that directory) and DRIVER_ROOT (<dest>,
# whose node_modules/.bin holds the locked playwright).

set -euo pipefail

if [ "${1:-}" = "--scripts-only" ]; then
  shift
fi
[ $# -eq 1 ] || { echo "usage: $0 <dest>" >&2; exit 2; }
dest="$1"

# EXPORT_DRIVER_LOCK_DIR exists for test-install-export-driver.sh, which points it at a tampered copy.
lock_dir="$(cd "${EXPORT_DRIVER_LOCK_DIR:-$(dirname "$0")/../design-artifacts-driver}" && pwd)"
pkg="@design-parity/export-driver"

mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
driver="$dest/node_modules/$pkg"

cp "$lock_dir/package.json" "$lock_dir/package-lock.json" "$dest/"
(cd "$dest" && npm ci --omit=dev --no-audit --no-fund --loglevel=warn) >&2

[ -f "$driver/package.json" ] || { echo "::error::no driver at $driver after install" >&2; exit 1; }

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "DRIVER_DIR=$driver"
    echo "DRIVER_ROOT=$dest"
  } >> "$GITHUB_ENV"
fi
echo "$driver"
