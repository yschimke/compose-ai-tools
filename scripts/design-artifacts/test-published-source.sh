#!/usr/bin/env bash
#
# Self-test for scripts/design-artifacts/published-source.sh — the baseline a
# self-healing scope diffs each design-artifacts lane from (issue #5438).
#
# Two halves, both covered:
#
#   • the SUBJECT GRAMMAR (`--parse-subject`). This reads the delivery branch's own
#     publish commit, which is the only baseline a lane published before the marker
#     ref existed has. A grammar that quietly stops matching would not fail
#     anything — it would silently send every lane back to the push range and
#     restore the defect.
#   • the RESOLUTION ORDER: marker ref, then branch subject, then nothing. The ref
#     has to win, because the subject lags by every render whose output was
#     byte-identical (the publish runs SKIP_IF_UNCHANGED and commits nothing then).
#
# `gh` is stubbed; nothing here touches the network.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/design-artifacts/published-source.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() {
  local name="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    printf 'PASS  %s -> %s\n' "$name" "${got:-<empty>}"
  else
    printf 'FAIL  %s -> got "%s", want "%s"\n' "$name" "$got" "$want"
    failures=$((failures + 1))
  fi
}

# expect_subject <name> <want> <subject>
expect_subject() {
  check "$1" "$2" "$("$script" --parse-subject "$3")"
}

RIGHT_SHAPE='chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12, 484d178c)'

# --- the two subjects the publishers actually stamp -------------------------
expect_subject 'catalog publish' '484d178c' "$RIGHT_SHAPE"
expect_subject 'render-history publish' 'deadbee' \
  'chore(design-artifacts): update m3-catalog render history (2026-09-12, deadbee)'
expect_subject 'full 40-hex sha' '0123456789abcdef0123456789abcdef01234567' \
  'chore(design-artifacts): regenerate x catalog (2026-01-02, 0123456789abcdef0123456789abcdef01234567)'
# A system whose name contains the date-and-sha shape must not confuse the match:
# the expression is anchored to the trailing `)`.
expect_subject 'sha taken from the trailing group' 'cafebabe' \
  'chore(design-artifacts): regenerate a (2020-01-01, 00000000) catalog (2026-09-12, cafebabe)'

# --- anything else is not a baseline ----------------------------------------
expect_subject 'unrelated commit' '' 'fix(deps): update rc-players to v1.63.0 (#5464)'
expect_subject 'no sha'           '' 'chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12)'
expect_subject 'not hex'          '' 'chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12, zzzzzzzz)'
expect_subject 'too short'        '' 'chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12, abc)'
expect_subject 'empty'            '' ''
# A body line that happens to match must not be read as the subject: the caller
# feeds only the first line, and the expression is anchored at both ends.
expect_subject 'trailing text' '' "$RIGHT_SHAPE and then some"

# --- resolution order -------------------------------------------------------
REF_SHA='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

# stub_gh <ref-sha-or-empty> <subject-or-empty>
stub_gh() {
  local dir="$1" ref="$2" subject="$3"
  mkdir -p "$dir/bin"
  {
    echo '#!/usr/bin/env bash'
    echo 'case "$2" in'
    if [ -n "$ref" ]; then
      printf '  */git/ref/*) echo %q ;;\n' "$ref"
    else
      echo '  */git/ref/*) exit 1 ;;'
    fi
    if [ -n "$subject" ]; then
      printf '  */commits/*) echo %q ;;\n' "$subject"
    else
      echo '  */commits/*) exit 1 ;;'
    fi
    echo '  *) exit 1 ;;'
    echo 'esac'
  } > "$dir/bin/gh"
  chmod +x "$dir/bin/gh"
}

# resolve <name> <want> <ref> <subject>
resolve() {
  local name="$1" want="$2" dir; dir="$(mktemp -d "$work/case.XXXXXX")"
  stub_gh "$dir" "$3" "$4"
  check "$name" "$want" \
    "$(PATH="$dir/bin:$PATH" "$script" --system wear-m3 --repo owner/name)"
}

resolve 'marker ref wins'          "$REF_SHA" "$REF_SHA" "$RIGHT_SHAPE"
resolve 'subject when no ref'      '484d178c' ''         "$RIGHT_SHAPE"
resolve 'nothing when neither'     ''         ''         ''
# A ref that is not a full object id is not something the compare endpoint can use,
# so it must fall through rather than be handed on.
resolve 'short ref falls through'  '484d178c' 'aaaaaaa'  "$RIGHT_SHAPE"

if [ "$failures" -ne 0 ]; then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
printf '\nall checks passed\n'
