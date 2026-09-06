#!/usr/bin/env bash
# Self-test for maven-line-baseline.sh.
#
# Two jobs in two separate workflow runs — `maven-publish-guard` before the publish and
# `maven-readiness.yml` after it — depend on this resolver returning the SAME answer at both
# moments. The tests below pin that property directly (the "same answer before and after" case),
# because the failure it prevents is silent: a readiness gate that proves the wrong version
# resolvable reports a release consumable when its plugin coordinate does not exist.
#
# Fixtures are literal maven-metadata.xml documents, so nothing here touches the network.

set -euo pipefail

cd "$(dirname "$0")/../.."
SCRIPT="$(pwd)/.github/scripts/maven-line-baseline.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

pass=0
fail=0
ok() { echo "  ok: $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $*" >&2; fail=$((fail + 1)); }

# Write a maven-metadata.xml carrying the given versions, and echo its path.
metadata() {
  local name="$1"; shift
  local path="${tmp}/${name}.xml"
  {
    echo '<metadata>'
    echo '  <groupId>ee.schimke.composeai</groupId>'
    echo '  <artifactId>renderer-desktop</artifactId>'
    echo '  <versioning>'
    printf '    <latest>%s</latest>\n' "${!#}"
    echo '    <versions>'
    for v in "$@"; do printf '      <version>%s</version>\n' "${v}"; done
    echo '    </versions>'
    echo '  </versioning>'
    echo '</metadata>'
  } > "${path}"
  printf '%s' "${path}"
}

# The resolver's answer, or the literal `error` when it exits non-zero.
baseline() {
  local out
  if out="$("${SCRIPT}" "$@" 2>/dev/null)"; then printf '%s' "${out}"; else printf 'error'; fi
}

expect() {
  local want="$1" got="$2" what="$3"
  if [ "${got}" = "${want}" ]; then ok "${what} → '${got}'"; else bad "${what}: want '${want}', got '${got}'"; fi
}

echo "== picks the greatest version below the release =="
meta="$(metadata simple 2.0.0 2.1.0 2.2.0)"
expect "2.2.0" "$(baseline --below 2.3.0 --metadata "${meta}")" "2.3.0 over a line ending at 2.2.0"

echo "== the release's own version is never its own baseline =="
# The property the two consumers hang on: `--below 2.2.0` answers 2.1.0 whether or not 2.2.0 is
# in the document, so the guard (before the publish) and the readiness gate (after it) agree.
before="$(metadata before 2.0.0 2.1.0)"
after="$(metadata after 2.0.0 2.1.0 2.2.0)"
b1="$(baseline --below 2.2.0 --metadata "${before}")"
b2="$(baseline --below 2.2.0 --metadata "${after}")"
expect "2.1.0" "${b1}" "before the publish"
expect "2.1.0" "${b2}" "after the publish"
if [ "${b1}" = "${b2}" ]; then ok "same answer before and after the publish"; else bad "resolver disagrees across the publish: '${b1}' vs '${b2}'"; fi

echo "== version ordering is numeric, not lexical =="
# The bug this pins: a string compare ranks 1.9.0 above 1.10.0, so the baseline would jump
# backwards past every release in between and the guard would diff against ancient history.
meta="$(metadata ordering 1.8.0 1.9.0 1.10.0 1.11.0)"
expect "1.11.0" "$(baseline --below 1.12.0 --metadata "${meta}")" "double-digit minor beats single-digit"
expect "1.9.0" "$(baseline --below 1.10.0 --metadata "${meta}")" "below 1.10.0 is 1.9.0, not 1.8.0"

echo "== versions above the release are ignored =="
# Central carries every version ever published, including ones cut after the tag being verified
# (a re-run of an old release's readiness gate, or a repair dispatch).
meta="$(metadata newer 2.0.0 2.1.0 2.2.0 2.3.0)"
expect "2.0.0" "$(baseline --below 2.1.0 --metadata "${meta}")" "a later line does not leak in"

echo "== fail-open paths print nothing and exit 0 =="
empty="${tmp}/empty.xml"; : > "${empty}"
expect "" "$(baseline --below 2.0.0 --metadata "${empty}")" "empty metadata"
meta="$(metadata first 2.0.0 2.1.0)"
expect "" "$(baseline --below 2.0.0 --metadata "${meta}")" "nothing below the first release"
expect "" "$(baseline --below 0.0.1 --metadata "${meta}")" "release older than the whole line"

echo "== a leading v is accepted =="
meta="$(metadata vprefix 2.0.0 2.1.0)"
expect "2.1.0" "$(baseline --below v2.2.0 --metadata "${meta}")" "--below v2.2.0"

echo "== argument errors are hard failures, not a silent empty baseline =="
# A caller that fat-fingers a flag must see a broken script, not "no baseline" — the latter is
# indistinguishable from a network failure and would quietly change the release's behaviour.
expect "error" "$(baseline --metadata "${meta}")" "missing --below"
expect "error" "$(baseline --below 2.2.0 --nonsense)" "unknown flag"

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
