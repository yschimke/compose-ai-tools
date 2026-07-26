#!/usr/bin/env bash
# Tests for deploy/image/env-migrations.sh — the `.env` rewrites setup.sh runs on
# an already-deployed box. These edit an operator's live config, so the cases that
# matter are the ones where NOTHING should change: a custom catalog list, or a
# custom list sitting alongside the legacy pin.
#
#   ./deploy/image/test-env-migrations.sh
set -uo pipefail
cd "$(dirname "$0")"

# shellcheck source=deploy/image/env-migrations.sh
source ./env-migrations.sh

LEGACY="${LEGACY_COMPOSE_SAMPLES_CATALOGS}"
CUSTOM='compose-m3,wear-m3,acme@acme/design-system'
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

pass=0
fail=0

# run_case <name> <expected-return: removed|kept> <expected .env contents> <.env contents>
run_case() {
  local name="$1" expect_rc="$2" expected="$3" input="$4"
  local env_file="${TMP}/env" rc=0 actual
  printf '%s' "${input}" > "${env_file}"
  chmod 600 "${env_file}"
  migrate_legacy_serve_catalogs "${env_file}" || rc=$?

  local want_rc=0
  [[ "${expect_rc}" == "removed" ]] || want_rc=1
  actual="$(cat "${env_file}")"

  if [[ "${rc}" -ne "${want_rc}" ]]; then
    echo "FAIL ${name}: expected ${expect_rc} (rc ${want_rc}), got rc ${rc}"
    fail=$((fail + 1))
  elif [[ "${actual}" != "${expected%$'\n'}" ]]; then
    echo "FAIL ${name}: .env contents differ"
    diff <(printf '%s\n' "${expected%$'\n'}") <(printf '%s\n' "${actual}") || true
    fail=$((fail + 1))
  else
    pass=$((pass + 1))
  fi
}

run_case "removes the exact legacy pin" removed \
  "DOMAIN=preview.coo.ee
SERVE_TOKEN=abc123" \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=${LEGACY}
SERVE_TOKEN=abc123
"

run_case "removes a quoted legacy pin" removed \
  "DOMAIN=preview.coo.ee" \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=\"${LEGACY}\"
"

run_case "removes an exported legacy pin with trailing whitespace" removed \
  "DOMAIN=preview.coo.ee" \
  "DOMAIN=preview.coo.ee
export SERVE_CATALOGS=${LEGACY}
"

run_case "removes a CRLF legacy pin" removed \
  "DOMAIN=preview.coo.ee" \
  "$(printf 'DOMAIN=preview.coo.ee\nSERVE_CATALOGS=%s\r\n' "${LEGACY}")"

# The point of the migration: an operator's own list is config, not legacy cruft.
run_case "keeps an operator's custom list" kept \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=${CUSTOM}" \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=${CUSTOM}
"

# Docker Compose takes the LAST assignment, so a custom line after the legacy one
# is the value actually in force — removing it would silently reset the box.
run_case "keeps a custom list that follows the legacy pin" removed \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=${CUSTOM}" \
  "DOMAIN=preview.coo.ee
SERVE_CATALOGS=${LEGACY}
SERVE_CATALOGS=${CUSTOM}
"

run_case "keeps a superset that merely contains the legacy list" kept \
  "SERVE_CATALOGS=${LEGACY},reply@yschimke/compose-samples" \
  "SERVE_CATALOGS=${LEGACY},reply@yschimke/compose-samples
"

run_case "keeps an unrelated key whose value is the legacy list" kept \
  "SERVE_CATALOGS_UNLISTED=${LEGACY}" \
  "SERVE_CATALOGS_UNLISTED=${LEGACY}
"

run_case "leaves a file without SERVE_CATALOGS alone" kept \
  "DOMAIN=preview.coo.ee
SERVE_TOKEN=abc123" \
  "DOMAIN=preview.coo.ee
SERVE_TOKEN=abc123
"

# A rewrite must not widen the token file's permissions.
mode_file="${TMP}/mode-env"
printf 'SERVE_TOKEN=abc123\nSERVE_CATALOGS=%s\n' "${LEGACY}" > "${mode_file}"
chmod 600 "${mode_file}"
migrate_legacy_serve_catalogs "${mode_file}" || true
mode="$(stat -c '%a' "${mode_file}" 2>/dev/null || stat -f '%Lp' "${mode_file}")"
if [[ "${mode}" == "600" ]]; then
  pass=$((pass + 1))
else
  echo "FAIL preserves 0600 mode: got ${mode}"
  fail=$((fail + 1))
fi

# A missing .env is a no-op, not an error the installer should die on.
rc=0
migrate_legacy_serve_catalogs "${TMP}/does-not-exist" || rc=$?
if [[ "${rc}" -eq 1 && ! -e "${TMP}/does-not-exist" ]]; then
  pass=$((pass + 1))
else
  echo "FAIL missing .env is a no-op: rc ${rc}"
  fail=$((fail + 1))
fi

echo "${pass} passed, ${fail} failed"
[[ "${fail}" -eq 0 ]]
