#!/usr/bin/env bash
# Guard: the agent-access-grant lane turns itself on exactly where it can work.
#
# Why this needs a test rather than a comment. The lane mints credentials, so it needs a human
# identity to approve against — SERVE_GITHUB_AUTH_* — and on the open profile the server REFUSES TO
# START without one rather than letting anonymous visitors mint them. That makes both obvious
# defaults wrong in opposite directions: a flat `1` takes out every public box with no OAuth app
# configured, and a flat `0` leaves the feature switched off on the box it was built for.
#
# So the entrypoint derives it, and a derivation is exactly the kind of shell that rots silently: it
# has no output of its own, and getting it wrong either breaks unrelated deployments on their next
# roll or quietly ships a dead feature. Both failures are invisible until someone goes looking.
#
# Runs the real gate out of entrypoint.sh rather than a copy, so the two cannot drift.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# The derivation block, lifted verbatim from the entrypoint by its sentinel comment so this test
# exercises the shipped logic. Extracted rather than sourced because entrypoint.sh runs a server.
gate="$(awk '/^# Unset means "on where it can work"/,/^fi$/' "${entrypoint}")"
[[ -n "${gate}" ]] || {
  echo "FAIL: could not find the SERVE_AGENT_GRANTS derivation in ${entrypoint}" >&2
  echo "      (did the leading comment change? this test locates it by that line)" >&2
  exit 1
}

# $1 = expected value, $2… = environment assignments
expect() {
  local want="$1" desc="$2"
  shift 2
  local got
  # `env -i` — an EMPTY environment, not merely the assignments layered on top of this shell's.
  # A runner or operator shell that already exports SERVE_GITHUB_AUTH_* would otherwise leak those
  # into the "no auth" and "partial auth" cases, which would then be exercising full auth and
  # passing for the wrong reason. Reproduce the old behaviour with:
  #   SERVE_GITHUB_AUTH_CLIENT_ID=x SERVE_GITHUB_AUTH_CLIENT_SECRET=x \
  #     SERVE_GITHUB_AUTH_COOKIE_SECRET=x ./deploy/image/test-agent-grants-default.sh
  # PATH is restored because bash needs it; nothing else is.
  got="$(env -i PATH="${PATH}" "$@" bash -c "set -euo pipefail; ${gate}; echo \"\${SERVE_AGENT_GRANTS:-}\"")"
  if [[ "${got}" != "${want}" ]]; then
    echo "FAIL: ${desc}: expected SERVE_AGENT_GRANTS=${want}, got '${got}'" >&2
    exit 1
  fi
  echo "  ok: ${desc} -> ${got}"
}

auth=(
  SERVE_GITHUB_AUTH_CLIENT_ID=id
  SERVE_GITHUB_AUTH_CLIENT_SECRET=secret
  SERVE_GITHUB_AUTH_COOKIE_SECRET=cookie
)

echo "==> SERVE_AGENT_GRANTS derivation"
expect 1 "unset + full GitHub auth => on" "${auth[@]}"
expect 0 "unset + no GitHub auth => off"
expect 0 "unset + partial GitHub auth (no cookie secret) => off" \
  SERVE_GITHUB_AUTH_CLIENT_ID=id SERVE_GITHUB_AUTH_CLIENT_SECRET=secret
expect 0 "unset + only a client id => off" SERVE_GITHUB_AUTH_CLIENT_ID=id

# The other way to be an approver: a token-gated box, where the operator token is the identity.
expect 1 "unset + private with a token => on" SERVE_PUBLIC=0 SERVE_TOKEN=t
expect 1 "unset + private (SERVE_PUBLIC absent) with a token => on" SERVE_TOKEN=t
expect 0 "unset + public with a token but no auth => off" SERVE_PUBLIC=1 SERVE_TOKEN=t
expect 0 "unset + private with no token => off" SERVE_PUBLIC=0

# An explicit answer always wins, in both directions — including "insist without an approver",
# which is meant to reach the server's own refusal rather than being silently downgraded here.
expect 0 "explicit 0 with auth configured => stays off" SERVE_AGENT_GRANTS=0 "${auth[@]}"
expect 1 "explicit 1 without auth => stays on (server refuses, honestly)" SERVE_AGENT_GRANTS=1
expect true "explicit 'true' is passed through untouched" SERVE_AGENT_GRANTS=true

echo "PASS: the lane enables itself only where an approver exists"
