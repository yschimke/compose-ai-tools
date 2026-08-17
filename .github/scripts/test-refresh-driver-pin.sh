#!/usr/bin/env bash
# Self-test for refresh-driver-pin.sh.
#
# The pin decides which revision of compose-ai-tools other people's CI executes,
# and it is rewritten unattended on the tail of a release. Every failure mode is
# quiet: a half-applied rewrite ships a file whose sha and tag disagree, and a
# malformed SHA does not fail until a consumer's run has already paid for a
# checkout and a JDK. Both are gated here, as is the exact regex the workflow
# uses to read the file — if those two ever disagree the pin silently stops
# resolving.

set -euo pipefail

cd "$(dirname "$0")/../.."
SCRIPT=".github/scripts/refresh-driver-pin.sh"
REAL=".github/design-artifacts-driver-pin.txt"
WORKFLOW=".github/workflows/design-artifacts-reusable.yml"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

pass=0
fail=0
ok() { echo "  ok: $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $*" >&2; fail=$((fail + 1)); }

OLD=1111111111111111111111111111111111111111
NEW=2222222222222222222222222222222222222222

fixture() {
  cat > "$1" <<EOF
# a comment, and a blank line follow

sha=${OLD}
tag=v1.0.0
date=2026-01-01
EOF
}

echo "== --check accepts a well-formed pin file"
f="${tmp}/good.txt"
fixture "${f}"
"${SCRIPT}" --file "${f}" --check >/dev/null && ok "well-formed file passes" || bad "well-formed file rejected"

echo "== --print reports the pinned SHA"
got="$("${SCRIPT}" --file "${f}" --print)"
[ "${got}" = "${OLD}" ] && ok "--print => ${got}" || bad "--print => ${got}, want ${OLD}"

echo "== --check rejects malformed values"
for mutate in "s/^sha=.*/sha=c990303/" "s/^sha=.*/sha=$(printf '%040d' 0 | tr 0 A)/" "s/^tag=.*/tag=1.12.0/" "s/^date=.*/date=17-08-2026/"; do
  f2="${tmp}/bad.txt"; fixture "${f2}"; sed -i "${mutate}" "${f2}"
  if "${SCRIPT}" --file "${f2}" --check >/dev/null 2>&1; then bad "accepted: ${mutate}"; else ok "rejected: ${mutate}"; fi
done

echo "== a duplicated key is an error, not last-one-wins"
f2="${tmp}/dup.txt"; fixture "${f2}"; echo "sha=${NEW}" >> "${f2}"
if "${SCRIPT}" --file "${f2}" --check >/dev/null 2>&1; then bad "duplicate sha accepted"; else ok "duplicate sha rejected"; fi

echo "== a missing key is an error"
f2="${tmp}/missing.txt"; fixture "${f2}"; sed -i "/^tag=/d" "${f2}"
if "${SCRIPT}" --file "${f2}" --check >/dev/null 2>&1; then bad "missing tag accepted"; else ok "missing tag rejected"; fi

echo "== a rewrite moves all three keys and keeps the comments"
f="${tmp}/write.txt"
fixture "${f}"
"${SCRIPT}" --file "${f}" --tag v2.3.4 --sha "${NEW}" --date 2026-05-06 >/dev/null
[ "$(sed -nE 's/^sha=(.*)/\1/p' "${f}")" = "${NEW}" ] && ok "sha moved" || bad "sha not moved"
[ "$(sed -nE 's/^tag=(.*)/\1/p' "${f}")" = "v2.3.4" ] && ok "tag moved" || bad "tag not moved"
[ "$(sed -nE 's/^date=(.*)/\1/p' "${f}")" = "2026-05-06" ] && ok "date moved" || bad "date not moved"
grep -q "^# a comment" "${f}" && ok "comments preserved" || bad "comments lost"
"${SCRIPT}" --file "${f}" --check >/dev/null && ok "rewritten file passes --check" || bad "rewritten file fails --check"

echo "== rewriting to the SHA already pinned is a no-op"
before="$(cat "${f}")"
"${SCRIPT}" --file "${f}" --tag v2.3.4 --sha "${NEW}" --date 2026-09-09 >/dev/null
[ "${before}" = "$(cat "${f}")" ] && ok "no-op left the file byte-identical" || bad "no-op modified the file"

echo "== malformed arguments are refused"
for args in "--tag v1 --sha ${NEW}" "--tag v1.2.3 --sha deadbeef" "--tag v1.2.3 --sha ${NEW} --date 6/5/2026"; do
  # shellcheck disable=SC2086
  if "${SCRIPT}" --file "${f}" ${args} >/dev/null 2>&1; then bad "accepted: ${args}"; else ok "refused: ${args}"; fi
done

echo "== the workflow's own reader agrees with this script"
# The exact pipeline from the "Resolve the export-driver revision" step. If the
# workflow's regex and --check ever drift, the pin resolves to empty at runtime
# and every external caller fails after paying for a checkout.
workflow_read() { sed -nE 's/^sha=([0-9a-f]{40})$/\1/p' "$1" | head -n1; }
[ "$(workflow_read "${REAL}")" = "$("${SCRIPT}" --print)" ] \
  && ok "workflow regex and --print agree on the real pin" \
  || bad "workflow regex and --print disagree"
grep -qF "s/^sha=([0-9a-f]{40})\$/\\1/p" "${WORKFLOW}" \
  && ok "workflow still uses the regex this test pins" \
  || bad "workflow's pin regex changed — update this test and re-verify"

echo "== the real pin file is well-formed"
"${SCRIPT}" --check >/dev/null && ok "${REAL} passes --check" || bad "${REAL} fails --check"

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
