#!/usr/bin/env bash
# Guard: env-redundant.sh finds the dead lines, keeps the live ones, and NEVER prints a value.
#
# The no-values property is the one worth a test. The file it reads holds SERVE_TOKEN,
# SERVE_ADMIN_TOKEN, the GitHub OAuth secret and the deploy hook token, and the whole point of the
# tool is that its output can be pasted somewhere — an issue, a chat, an agent session. A refactor
# that started echoing "$key=$value" for the active list would look like an improvement in a diff
# and would quietly turn a tidy-up into a credential leak.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

cat > "${tmp}/.env" <<'ENV'
# A comment, and a blank line follow.

ROLLOUT_INTERVAL=1200
SERVE_THEME_CACHE_DIR=/theme-cache
SERVE_PUBLIC=1
SERVE_CATALOG_MAX_IMAGES=
SERVE_TOKEN=sup3rs3cr3t-token-value
SERVE_ADMIN_TOKEN="quoted-secret-value"
SERVE_LIVE_SEATS=8
ROLLOUT_HEALTH_TIMEOUT=900
ENV

out="$(ENV_FILE="${tmp}/.env" "${here}/env-redundant.sh")"

# 1. Values that equal a compose default are named as deletable.
for key in ROLLOUT_INTERVAL SERVE_THEME_CACHE_DIR SERVE_PUBLIC; do
  grep -q "^  ${key}=" <<<"${out}" || {
    echo "FAIL: ${key} restates a default and was not reported" >&2
    echo "${out}" >&2
    exit 1
  }
done
echo "PASS: entries restating a default are reported"

# 2. An empty assignment is the same as unset.
grep -q "SERVE_CATALOG_MAX_IMAGES" <<<"${out}" || {
  echo "FAIL: an empty assignment was not reported" >&2
  exit 1
}
echo "PASS: empty assignments are reported"

# 3. Values that genuinely differ are kept, by NAME only.
for key in SERVE_LIVE_SEATS ROLLOUT_HEALTH_TIMEOUT SERVE_TOKEN; do
  grep -q "  ${key}$" <<<"${out}" || {
    echo "FAIL: ${key} differs from stock and was not listed as active" >&2
    echo "${out}" >&2
    exit 1
  }
done
echo "PASS: entries that differ from stock are kept"

# 4. THE property: no secret value appears anywhere in the output.
for secret in sup3rs3cr3t-token-value quoted-secret-value; do
  if grep -qF "${secret}" <<<"${out}"; then
    echo "FAIL: a secret VALUE reached stdout — this tool's output is meant to be pasteable" >&2
    exit 1
  fi
done
echo "PASS: no value reaches stdout"

# 5. Self-test of the detector: a tool that printed key=value for actives would fail check 4.
if ! grep -qF "sup3rs3cr3t-token-value" <<<"SERVE_TOKEN=sup3rs3cr3t-token-value"; then
  echo "FAIL: self-test — the secret matcher does not match a key=value line" >&2
  exit 1
fi
echo "PASS: self-test — the secret matcher would catch a key=value leak"

# 6. A missing .env is not an error; there is simply nothing to tidy.
ENV_FILE="${tmp}/absent" "${here}/env-redundant.sh" >/dev/null 2>&1 || {
  echo "FAIL: a missing .env must exit cleanly" >&2
  exit 1
}
echo "PASS: a missing .env exits cleanly"

# 7. A trailing backslash is fatal and must be reported as such — this is the failure that took
#    preview.coo.ee down: .env is not a shell script, so the backslash lands in the value, reaches
#    JAVA_TOOL_OPTIONS, and the JVM refuses to start behind a 502 with nothing naming the cause.
cat > "${tmp}/.env-cont" <<'ENV'
SERVE_JAVA_OPTS=-Dcomposeai.serve.themeOptimizationIdleMillis=10000 \
  -Dcomposeai.serve.optimizerResumeQuietMillis=5000
ENV
if ENV_FILE="${tmp}/.env-cont" "${here}/env-redundant.sh" >/dev/null 2>&1; then
  echo "FAIL: a line ending in a backslash must be reported and must exit non-zero" >&2
  exit 1
fi
cont_out="$(ENV_FILE="${tmp}/.env-cont" "${here}/env-redundant.sh" 2>&1 || true)"
grep -q "SERVE_JAVA_OPTS" <<<"${cont_out}" || {
  echo "FAIL: the offending key was not named" >&2
  echo "${cont_out}" >&2
  exit 1
}
echo "PASS: a trailing backslash is reported as fatal"

# 8. ...and a normal file must not trip that check.
ENV_FILE="${tmp}/.env" "${here}/env-redundant.sh" >/dev/null || {
  echo "FAIL: a well-formed .env must still exit cleanly" >&2
  exit 1
}
echo "PASS: a well-formed .env is unaffected"

# 9. A DUPLICATED key: Compose uses the last assignment, so classifying each line independently
#    told an operator the final SERVE_PUBLIC=1 was safe to delete while an earlier SERVE_PUBLIC=0
#    was still in the file. Deleting it exposes the 0 — the box goes from public to token-gated,
#    and fails to start if no token is configured. This is a config change dressed as tidying.
cat > "${tmp}/.env-dup" <<'ENV'
SERVE_PUBLIC=0
SERVE_LIVE_SEATS=8
SERVE_PUBLIC=1
ENV
dup_out="$(ENV_FILE="${tmp}/.env-dup" "${here}/env-redundant.sh")"
grep -q "^DUPLICATED" <<<"${dup_out}" || {
  echo "FAIL: a duplicated key was not reported before the deletion advice" >&2
  echo "${dup_out}" >&2
  exit 1
}
grep -q "SERVE_PUBLIC (2 assignments" <<<"${dup_out}" || {
  echo "FAIL: the duplicate report does not name the key and its count" >&2
  echo "${dup_out}" >&2
  exit 1
}
# And the classification follows the WINNER (=1, the compose default), not the first line.
grep -q "^  SERVE_PUBLIC=1$" <<<"${dup_out}" || {
  echo "FAIL: the effective (last) assignment is not the one classified" >&2
  echo "${dup_out}" >&2
  exit 1
}
echo "PASS: a duplicated key is reported, and the last assignment is the one classified"

# 10. An inline comment on an unquoted value. The README hands out copyable lines in exactly this
#     form, and keeping the comment made the one line that IS pure decoration read as live config.
cat > "${tmp}/.env-comment" <<'ENV'
SERVE_THEME_CACHE_DIR=/theme-cache # the default
SERVE_ADMIN_TOKEN="a value # with a hash inside it"
ENV
com_out="$(ENV_FILE="${tmp}/.env-comment" "${here}/env-redundant.sh")"
grep -q "^  SERVE_THEME_CACHE_DIR=/theme-cache$" <<<"${com_out}" || {
  echo "FAIL: an inline comment was not stripped before comparing" >&2
  echo "${com_out}" >&2
  exit 1
}
# A quoted value keeps its hash — it is not a comment inside quotes — and stays active, by name.
grep -q "  SERVE_ADMIN_TOKEN$" <<<"${com_out}" || {
  echo "FAIL: a hash inside a quoted value must not be treated as a comment" >&2
  echo "${com_out}" >&2
  exit 1
}
if grep -qF "with a hash inside it" <<<"${com_out}"; then
  echo "FAIL: a quoted value reached stdout" >&2
  exit 1
fi
echo "PASS: inline comments are stripped, and a hash inside quotes is not one"

# 11. The entrypoint's default beats an EMPTY compose pass-through. `${VAR:-}` in the compose file
#     declares a pass-through, not a value; first-wins recorded the empty string and reported the
#     entrypoint's own default as a genuine deviation.
cat > "${tmp}/compose-passthrough.yml" <<'YML'
services:
  preview:
    environment:
      SERVE_TIMEOUT: ${SERVE_TIMEOUT:-}
YML
cat > "${tmp}/entrypoint-defaults.sh" <<'SH'
: "${SERVE_TIMEOUT:-1800}"
SH
printf 'SERVE_TIMEOUT=1800\n' > "${tmp}/.env-passthrough"
pass_out="$(
  ENV_FILE="${tmp}/.env-passthrough" \
  COMPOSE_FILE_UNDER_TEST="${tmp}/compose-passthrough.yml" \
  ENTRYPOINT_FILE="${tmp}/entrypoint-defaults.sh" \
  "${here}/env-redundant.sh"
)"
grep -q "^  SERVE_TIMEOUT=1800$" <<<"${pass_out}" || {
  echo "FAIL: an empty compose pass-through must not shadow the entrypoint's default" >&2
  echo "${pass_out}" >&2
  exit 1
}
echo "PASS: the entrypoint's default replaces an empty compose pass-through"

# 12. COMPOSE_FILE selects the overlay too. The documented deployment enables
#     docker-compose.deploy-config.yml, whose own defaults are invisible to a reader pinned to the
#     base file — so an operator restating one was told it differed from stock.
cat > "${tmp}/base.yml" <<'YML'
services:
  preview:
    environment:
      SERVE_LIVE_SEATS: ${SERVE_LIVE_SEATS:-4}
YML
cat > "${tmp}/overlay.yml" <<'YML'
services:
  preview:
    environment:
      DEPLOY_CONFIG_DIR: ${DEPLOY_CONFIG_DIR:-../preview.coo.ee}
YML
printf 'DEPLOY_CONFIG_DIR=../preview.coo.ee\n' > "${tmp}/.env-overlay"
ovl_out="$(
  ENV_FILE="${tmp}/.env-overlay" \
  COMPOSE_FILE_UNDER_TEST="${tmp}/base.yml:${tmp}/overlay.yml" \
  ENTRYPOINT_FILE="${tmp}/entrypoint-defaults.sh" \
  "${here}/env-redundant.sh"
)"
grep -q "DEPLOY_CONFIG_DIR=../preview.coo.ee" <<<"${ovl_out}" || {
  echo "FAIL: an overlay's default was not read" >&2
  echo "${ovl_out}" >&2
  exit 1
}
echo "PASS: every compose file COMPOSE_FILE selects is read"

# 13. An empty DEPLOY_HOOK_TOKEN is NOT the same as unset: setup.sh backfills a generated token
#     when no assignment is present, so deleting the line arms the instant-roll webhook an operator
#     deliberately disarmed.
printf 'DEPLOY_HOOK_TOKEN=\nSERVE_CATALOG_MAX_IMAGES=\n' > "${tmp}/.env-hook"
hook_out="$(ENV_FILE="${tmp}/.env-hook" "${here}/env-redundant.sh")"
grep -q "NOT the same as unset" <<<"${hook_out}" || {
  echo "FAIL: an empty migration-sensitive key was not separated from the deletable ones" >&2
  echo "${hook_out}" >&2
  exit 1
}
awk '/^Set but empty — same as unset/,/^$/' <<<"${hook_out}" | grep -q "DEPLOY_HOOK_TOKEN" && {
  echo "FAIL: DEPLOY_HOOK_TOKEN was listed as safe to delete" >&2
  echo "${hook_out}" >&2
  exit 1
}
awk '/^Set but empty — same as unset/,/^$/' <<<"${hook_out}" | grep -q "SERVE_CATALOG_MAX_IMAGES" || {
  echo "FAIL: an ordinary empty key must still be reported as deletable" >&2
  echo "${hook_out}" >&2
  exit 1
}
echo "PASS: an empty migration-sensitive key is not advertised as deletable"

# 14. A malformed continuation's second line has no `=`, so naming "the key" printed the whole
#     line — a value fragment on stderr, from the one tool whose output is meant to be pasteable.
cat > "${tmp}/.env-frag" <<'ENV'
SERVE_JAVA_OPTS=-Dfirst=1 \
  --token sup3rs3cr3t-fragment \
ENV
frag_out="$(ENV_FILE="${tmp}/.env-frag" "${here}/env-redundant.sh" 2>&1 || true)"
if grep -qF "sup3rs3cr3t-fragment" <<<"${frag_out}"; then
  echo "FAIL: a value fragment from a malformed continuation reached the output" >&2
  echo "${frag_out}" >&2
  exit 1
fi
grep -q "line 1: SERVE_JAVA_OPTS" <<<"${frag_out}" || {
  echo "FAIL: the offending line number and key were not named" >&2
  echo "${frag_out}" >&2
  exit 1
}
grep -q "line 2:" <<<"${frag_out}" || {
  echo "FAIL: the second offending line was not reported at all" >&2
  echo "${frag_out}" >&2
  exit 1
}
echo "PASS: a malformed continuation is located by line, never by value"

# 15. A final line with no terminating newline. `read` assigns it and then returns failure, so the
#     loop body never saw it — a file ending in exactly the fatal typo this guard exists for exited
#     0 with nothing said.
printf 'SERVE_JAVA_OPTS=-Dfirst=1 \\' > "${tmp}/.env-noeol"
if ENV_FILE="${tmp}/.env-noeol" "${here}/env-redundant.sh" >/dev/null 2>&1; then
  echo "FAIL: an unterminated final line ending in a backslash must still be fatal" >&2
  exit 1
fi
noeol_out="$(ENV_FILE="${tmp}/.env-noeol" "${here}/env-redundant.sh" 2>&1 || true)"
grep -q "line 1: SERVE_JAVA_OPTS" <<<"${noeol_out}" || {
  echo "FAIL: the unterminated final line was not named" >&2
  echo "${noeol_out}" >&2
  exit 1
}
echo "PASS: an unterminated final line is still examined"

echo "PASS: all env-redundant checks"
