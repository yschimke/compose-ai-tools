#!/usr/bin/env bash
#
# Self-test for scripts/design-artifacts/scope-systems.sh — the path→system
# mapping that decides which design-artifacts delivery branches a merge
# regenerates. CI runs this on every PR (ci.yml).
#
# Both directions matter and both are covered below:
#   • a false NEGATIVE silently strands a delivery branch on stale renders — the
#     exact failure the push trigger exists to prevent;
#   • a false POSITIVE burns a ~90-minute render per system on every unrelated
#     merge.

set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scope-systems.sh"
failures=0

# expect <name> <expected-systems-csv-or-"none"> <changed files…>
expect() {
  local name="$1" want="$2"; shift 2
  local got
  got="$(printf '%s\n' "$@" | "$SCRIPT" | grep '=true$' | cut -d= -f1 | paste -sd, -)"
  : "${got:=none}"
  if [ "$got" = "$want" ]; then
    printf 'PASS  %s -> %s\n' "$name" "$got"
  else
    printf 'FAIL  %s -> got "%s", want "%s"\n' "$name" "$got" "$want"
    failures=$((failures + 1))
  fi
}

# --- one catalog changes → exactly that system -----------------------------
expect 'wear catalog'            'wear-m3'   'samples/design-catalog-wear-m3/src/main/kotlin/CatalogTheme.kt'
expect 'remote catalog'          'remote-m3' 'samples/design-catalog-remote-m3/catalog.spec.json'
expect 'remote wasm allowlist'   'remote-m3' '.github/ci/remote-m3-cmp-wasm-allowlist.json'
expect 'compose-m3 CMP tier'     'compose-m3' 'samples/design-catalog-m3/src/main/kotlin/A.kt'
expect 'compose-m3 android tier' 'compose-m3' 'samples/design-catalog-m3-android/src/main/kotlin/A.kt'
expect 'compose-m3 shared tier'  'compose-m3' 'samples/design-catalog-m3-shared/src/main/kotlin/A.kt'
expect 'compose-m3 wasm app'     'compose-m3' 'samples/cmp-wasm-catalog/src/main/kotlin/A.kt'

# --- several at once --------------------------------------------------------
expect 'two catalogs' 'compose-m3,wear-m3' \
  'samples/design-catalog-m3/A.kt' 'samples/design-catalog-wear-m3/B.kt'

# --- shared inputs fan out to every system ----------------------------------
expect 'export driver'      'compose-m3,wear-m3,remote-m3' 'scripts/design-artifacts/generate-design-catalog.mjs'
expect 'this workflow'      'compose-m3,wear-m3,remote-m3' '.github/workflows/design-artifacts.yml'
expect 'reusable workflow'  'compose-m3,wear-m3,remote-m3' '.github/workflows/design-artifacts-reusable.yml'
expect 'scope script itself' 'compose-m3,wear-m3,remote-m3' 'scripts/design-artifacts/scope-systems.sh'

# --- fail-safe: an unresolvable change set regenerates everything -----------
expect 'empty change set' 'compose-m3,wear-m3,remote-m3' ''

# --- must NOT trigger anything ----------------------------------------------
# The renderer deliberately doesn't drive a push run (see design-artifacts.yml):
# it's touched by most merges and the Monday cron covers its drift.
expect 'renderer'          'none' 'gradle-plugin/src/main/kotlin/RenderPreviewsTask.kt'
expect 'cli'               'none' 'cli/src/main/kotlin/Main.kt'
expect 'unrelated sample'  'none' 'samples/wear/src/main/kotlin/Previews.kt'
expect 'docs only'         'none' 'docs/design/DESIGN_CATALOGS.md'
# `$`-anchored so a differently-named workflow can't fan out to every system.
expect 'other workflow'    'none' '.github/workflows/design-artifacts-other.yml'
# Substring-safe: a path merely *containing* a catalog name isn't that catalog.
expect 'lookalike path'    'none' 'docs/samples/design-catalog-wear-m3-notes.md'

# --- --all bypasses stdin entirely (cron / dispatch / release chain) ---------
all_out="$("$SCRIPT" --all </dev/null | grep -c '=true$')"
if [ "$all_out" = "3" ]; then
  printf 'PASS  --all -> 3 systems\n'
else
  printf 'FAIL  --all -> got %s systems, want 3\n' "$all_out"
  failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
printf '\nall checks passed\n'
