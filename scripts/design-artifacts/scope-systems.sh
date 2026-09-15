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
#   scripts/design-artifacts/scope-systems.sh --all            # every system, no stdin
#   scripts/design-artifacts/scope-systems.sh --only wear-m3   # exactly these, no stdin
#   printf '%s\n' "${changed[@]}" | scripts/design-artifacts/scope-systems.sh --system wear-m3
#   scripts/design-artifacts/scope-systems.sh --list           # system names, one per line
#   scripts/design-artifacts/scope-systems.sh --table          # `<system>\t<regex>` rows
#   scripts/design-artifacts/scope-systems.sh --shared-re      # the all-systems regex
#
# Output (stable order, one per line):
#   compose-m3=true
#   wear-m3=false
#
# `--system` exists because each lane is now diffed over its OWN range — the commit
# that lane was last rendered from, not the one push that started the run (issue
# #5438). scope-step.sh resolves those baselines and calls back in here once per
# lane; `--table` / `--shared-re` expose the same mapping to that generic driver,
# and to the composite action external callers use, without either of them
# restating this repo's paths.
#
# `--only` is the dispatch lane selector: the manual remedy for one stale lane
# should re-render that lane, not every catalog in the repository.

set -euo pipefail

SYSTEMS=(compose-m3 wear-m3)

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
    *) echo "unknown system: $1" >&2; exit 2 ;;
  esac
}

# The systems this invocation reports on: every system, or the single one
# `--system` named. Set by the argument scan below.
selected=("${SYSTEMS[@]}")

emit_all() {
  for system in "${selected[@]}"; do echo "$system=true"; done
  exit 0
}

# `if`, not `&&` — under `set -e` a failing `[ … ] && emit_all` AND-list would
# exit the script with the test's non-zero status instead of falling through.
case "${1:-}" in
  --all)
    emit_all
    ;;
  --list)
    printf '%s\n' "${SYSTEMS[@]}"
    exit 0
    ;;
  --shared-re)
    echo "$SHARED_RE"
    exit 0
    ;;
  --table)
    # `<system>\t<regex>`, the mapping in the one shape a generic driver can consume.
    for system in "${SYSTEMS[@]}"; do
      printf '%s\t%s\n' "$system" "$(system_pattern "$system")"
    done
    exit 0
    ;;
  --only)
    # The dispatch lane selector. Named systems regenerate, the rest are reported
    # false — so a stale `wear-m3` costs one render rather than every catalog here.
    # An unknown name is a typo that would otherwise read as "regenerate nothing
    # unusual" and silently do the wrong thing, so it is rejected loudly.
    #
    # Tokens are NORMALISED before they are validated OR matched, and both halves
    # matter. `compose-m3, wear-m3` is the form a human types — it is the form this
    # input's own description shows — and an earlier cut validated the split tokens
    # (where word splitting had already eaten the space) while matching against the
    # raw string (where it had not). Every name after the first then validated fine
    # and quietly reported `false`: a lane the operator explicitly asked for,
    # silently left stale. That is the exact failure this selector exists to end,
    # reintroduced inside the fix for it.
    #
    # Unquoted command substitution does the trimming: it word-splits on whitespace,
    # so surrounding spaces, tabs and newlines are gone before `system_pattern` sees
    # a name. A token with whitespace INSIDE it splits into two names and is
    # rejected, which is the right answer for `wear m3`.
    only=','
    for name in $(tr ',' '\n' <<<"${2:-}"); do
      system_pattern "$name" > /dev/null
      only="$only$name,"
    done
    # Empty, blank, or nothing but separators. Falling through would report every
    # system false and regenerate nothing at all — a dispatch that silently does
    # less than the default it replaced.
    if [ "$only" = ',' ]; then
      echo "--only needs a comma-separated system list" >&2
      exit 2
    fi
    for system in "${SYSTEMS[@]}"; do
      case "$only" in
        *",$system,"*) echo "$system=true" ;;
        *)             echo "$system=false" ;;
      esac
    done
    exit 0
    ;;
  --system)
    # Validates the name as a side effect: an unknown one exits 2 here rather
    # than reporting a lane nobody publishes.
    system_pattern "${2:-}" > /dev/null
    selected=("$2")
    ;;
  '') ;;
  *)
    echo "unknown option: $1" >&2
    exit 2
    ;;
esac

files="$(cat)"

# No resolvable change set → fail SAFE and regenerate everything. Publishing a
# fresh bundle is never wrong; skipping a stale one is.
if [ -z "$files" ]; then
  emit_all
fi

if grep -qE "$SHARED_RE" <<<"$files"; then
  emit_all
fi

for system in "${selected[@]}"; do
  if grep -qE "$(system_pattern "$system")" <<<"$files"; then
    echo "$system=true"
  else
    echo "$system=false"
  fi
done
