#!/usr/bin/env bash
#
# Which design-artifacts delivery branches does a change set dirty?
#
# Reads a newline-separated list of changed file paths on stdin and prints one
# `<system>=true|false` line per system. `design-artifacts.yml` uses this to scope
# a push-triggered run to the catalogs that actually moved, so a single-catalog
# merge costs one ~90-minute render instead of three.
#
# Lives in its own file (rather than inline in the workflow) so the mapping is
# testable — see scripts/design-artifacts/test-scope-systems.sh, which CI runs.
# A silently-wrong mapping here means a delivery branch quietly stops being
# regenerated, which is exactly the failure this automation exists to prevent.
#
# Usage:
#   printf '%s\n' "${changed[@]}" | scripts/design-artifacts/scope-systems.sh
#   scripts/design-artifacts/scope-systems.sh --all      # every system, no stdin
#
# Output (stable order, one per line):
#   compose-m3=true
#   wear-m3=false
#   remote-m3=false

set -euo pipefail

SYSTEMS=(compose-m3 wear-m3 remote-m3)

# Inputs that change the shape of EVERY bundle: the export driver, and the
# workflows that drive it. Any hit here fans out to all systems.
SHARED_RE='^(scripts/design-artifacts/|\.github/workflows/design-artifacts(-reusable)?\.yml$)'

# Per-system inputs. compose-m3 is assembled from several modules — the CMP
# catalog, its shared + Android-supplement tiers, and the Kotlin/Wasm app — so a
# change to any of them dirties that one branch.
system_pattern() {
  case "$1" in
    compose-m3) echo '^samples/(design-catalog-m3(-android|-shared)?|cmp-wasm-catalog)/' ;;
    wear-m3)    echo '^samples/design-catalog-wear-m3/' ;;
    remote-m3)  echo '^(samples/design-catalog-remote-m3/|\.github/ci/remote-m3-cmp-wasm-allowlist\.json$)' ;;
    *) echo "unknown system: $1" >&2; exit 2 ;;
  esac
}

emit_all() {
  for system in "${SYSTEMS[@]}"; do echo "$system=true"; done
  exit 0
}

# `if`, not `&&` — under `set -e` a failing `[ … ] && emit_all` AND-list would
# exit the script with the test's non-zero status instead of falling through.
if [ "${1:-}" = "--all" ]; then
  emit_all
fi

files="$(cat)"

# No resolvable change set → fail SAFE and regenerate everything. Publishing a
# fresh bundle is never wrong; skipping a stale one is.
if [ -z "$files" ]; then
  emit_all
fi

if grep -qE "$SHARED_RE" <<<"$files"; then
  emit_all
fi

for system in "${SYSTEMS[@]}"; do
  if grep -qE "$(system_pattern "$system")" <<<"$files"; then
    echo "$system=true"
  else
    echo "$system=false"
  fi
done
