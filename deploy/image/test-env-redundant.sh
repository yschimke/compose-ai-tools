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

echo "PASS: all env-redundant checks"
