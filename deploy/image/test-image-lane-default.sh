#!/usr/bin/env bash
# Guard: the image lane turns itself on exactly where the operator named its gate.
#
# Why this needs a test rather than a comment. SERVE_IMAGE_UPLOAD_REPO exists for nothing but this
# lane, so a box that has it set and still 404s POST /images is never what the operator meant — and
# that failure is silent at both ends: the server logs nothing about a lane it wasn't asked for, and
# an uploader's 404 reads like a wrong --serve-url. Hence the derivation.
#
# The derivation must ALSO stay narrow, which is the half a comment protects worst. The gating
# repository falls back to SERVE_GITHUB_AUTH_REPO, which this image defaults to
# yschimke/compose-ai-tools for the playground — so keying the default on "auth is configured", the
# way the agent-grant lane legitimately does, would open an authenticated upload lane on every
# adopter's box, gated by OUR collaborators rather than theirs. That is one edited condition away at
# all times, and nothing else would catch it.
#
# Runs the real gate out of entrypoint.sh rather than a copy, so the two cannot drift.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# The derivation block, lifted verbatim from the entrypoint by its sentinel comment so this test
# exercises the shipped logic. Extracted rather than sourced because entrypoint.sh runs a server.
gate="$(awk '/^# Unset means "on when the operator named the gate"/,/^fi$/' "${entrypoint}")"
[[ -n "${gate}" ]] || {
  echo "FAIL: could not find the SERVE_ACCEPT_IMAGES derivation in ${entrypoint}" >&2
  echo "      (did the leading comment change? this test locates it by that line)" >&2
  exit 1
}

# $1 = expected value, $2 = description, $3… = environment assignments
expect() {
  local want="$1" desc="$2"
  shift 2
  local got
  # `env -i` — an EMPTY environment, so an operator or runner shell that already exports
  # SERVE_IMAGE_UPLOAD_REPO / SERVE_GITHUB_AUTH_* cannot make a case pass for the wrong reason.
  # PATH is restored because bash needs it; nothing else is.
  got="$(env -i PATH="${PATH}" "$@" bash -c "set -euo pipefail; ${gate}; echo \"\${SERVE_ACCEPT_IMAGES:-}\"")"
  if [[ "${got}" != "${want}" ]]; then
    echo "FAIL: ${desc}: expected SERVE_ACCEPT_IMAGES=${want}, got '${got}'" >&2
    exit 1
  fi
  echo "  ok: ${desc} -> ${got}"
}

auth=(
  SERVE_GITHUB_AUTH_CLIENT_ID=id
  SERVE_GITHUB_AUTH_CLIENT_SECRET=secret
  SERVE_GITHUB_AUTH_COOKIE_SECRET=cookie
)

echo "==> SERVE_ACCEPT_IMAGES derivation"
expect 1 "unset + an upload repository => on" SERVE_IMAGE_UPLOAD_REPO=owner/repo
expect 0 "unset + no upload repository => off"

# The narrowness above, stated as cases: neither GitHub auth nor a playground-shaped auth repo is a
# request for an upload lane, because neither is this lane's variable.
expect 0 "unset + GitHub auth configured, no upload repository => off" "${auth[@]}"
expect 0 "unset + an auth repository only => off" \
  "${auth[@]}" SERVE_GITHUB_AUTH_REPO=yschimke/compose-ai-tools
expect 1 "unset + both, upload repository wins => on" \
  "${auth[@]}" SERVE_GITHUB_AUTH_REPO=owner/other SERVE_IMAGE_UPLOAD_REPO=owner/repo

# An explicit answer always wins, in both directions — including "insist without a repository",
# which is meant to reach the server's own refusal rather than being silently downgraded here.
expect 0 "explicit 0 with an upload repository => stays off" \
  SERVE_ACCEPT_IMAGES=0 SERVE_IMAGE_UPLOAD_REPO=owner/repo
expect 1 "explicit 1 without a repository => stays on (server refuses, honestly)" \
  SERVE_ACCEPT_IMAGES=1
expect true "explicit 'true' is passed through untouched" SERVE_ACCEPT_IMAGES=true

echo "PASS: the lane enables itself only where its own gating repository is named"
