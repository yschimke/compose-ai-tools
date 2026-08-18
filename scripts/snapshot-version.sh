#!/usr/bin/env bash
# Compute the `-SNAPSHOT` coordinate `snapshot.yml` publishes under.
#
# Take the latest `v*` tag, strip the leading `v`, bump the patch digit, append `-SNAPSHOT`. `main`
# keeps the stable next-patch coordinate documented for downstream consumers; manual runs on
# branches get a ref+SHA suffix by default, so a PR snapshot can be tested without overwriting the
# `main` coordinate.
#
# This lives in a script rather than inline in the workflow because TWO jobs need the answer — the
# Linux publish and the macOS `:rc-player-*` publish, which is separate only because Kotlin/Native
# Apple klibs cannot be built on Linux. Two copies of this arithmetic would eventually disagree and
# publish half the stack under a different version.
#
# Reads $REF_NAME, $INPUT_SUFFIX, $GITHUB_SHA; writes `version=` to $GITHUB_OUTPUT and a line to
# $GITHUB_STEP_SUMMARY when those are set. Prints the version on stdout regardless.
#
# Usage: scripts/snapshot-version.sh
#        scripts/snapshot-version.sh --self-test
set -euo pipefail

compute() {
  local last_tag="$1" ref_name="$2" input_suffix="$3" sha="$4"
  local base major rest minor patch next_base suffix
  base="${last_tag#v}"
  major="${base%%.*}"
  rest="${base#*.}"
  minor="${rest%%.*}"
  patch="${rest#*.}"
  patch="${patch%%-*}" # drop any pre-release suffix
  next_base="${major}.${minor}.$((patch + 1))"

  suffix="$input_suffix"
  if [[ -z "$suffix" && "$ref_name" != "main" ]]; then
    suffix="${ref_name}-${sha:0:7}"
  fi
  if [[ -n "$suffix" ]]; then
    suffix="$(
      printf '%s' "$suffix" |
        tr '[:upper:]' '[:lower:]' |
        sed -E 's/[^a-z0-9.-]+/-/g; s/^-+//; s/-+$//; s/-+/-/g'
    )"
    if [[ -z "$suffix" ]]; then
      echo "Snapshot suffix resolved to empty after sanitization." >&2
      return 1
    fi
    printf '%s-%s-SNAPSHOT\n' "$next_base" "$suffix"
  else
    printf '%s-SNAPSHOT\n' "$next_base"
  fi
}

self_test() {
  local got
  got="$(compute v1.15.1 main '' abcdef1234567890)"
  [ "$got" = "1.15.2-SNAPSHOT" ] || { echo "self-test: main => $got" >&2; return 1; }

  got="$(compute v1.15.1 agent/Some_Branch '' abcdef1234567890)"
  [ "$got" = "1.15.2-agent-some-branch-abcdef1-SNAPSHOT" ] ||
    { echo "self-test: branch => $got" >&2; return 1; }

  got="$(compute v1.15.1 main 'My Suffix!' abcdef1234567890)"
  [ "$got" = "1.15.2-my-suffix-SNAPSHOT" ] || { echo "self-test: suffix => $got" >&2; return 1; }

  # A pre-release tag bumps the same patch digit it already names, not the pre-release text.
  got="$(compute v2.0.0-rc01 main '' abcdef1234567890)"
  [ "$got" = "2.0.1-SNAPSHOT" ] || { echo "self-test: prerelease => $got" >&2; return 1; }

  # A suffix that sanitizes to nothing is a hard error rather than a silent plain snapshot, which
  # would overwrite main's coordinate from a branch run.
  if compute v1.15.1 main '!!!' abcdef1234567890 2>/dev/null; then
    echo "self-test: an empty sanitized suffix should have failed" >&2
    return 1
  fi

  echo "snapshot-version self-test: ok"
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit 0
fi

last_tag="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || echo v0.0.0)"
next="$(compute "$last_tag" "${REF_NAME:-}" "${INPUT_SUFFIX:-}" "${GITHUB_SHA:-}")"

echo "Last tag: $last_tag -> snapshot version: $next"
[ -n "${GITHUB_OUTPUT:-}" ] && echo "version=$next" >> "$GITHUB_OUTPUT"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### Maven snapshot" >> "$GITHUB_STEP_SUMMARY"
  echo "\`$next\`" >> "$GITHUB_STEP_SUMMARY"
fi
exit 0
