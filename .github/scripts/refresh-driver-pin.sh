#!/usr/bin/env bash
# Refresh (or verify) the design-artifacts export-driver pin.
#
# WHY THIS EXISTS
# ---------------
# `design-artifacts-reusable.yml` is a privileged workflow that other repos call,
# so the compose-ai-tools revision it executes is pinned to an immutable commit
# rather than a movable ref. The consequence is that a fix merged to `main` does
# not reach any external consumer until the pin moves, and moving the pin was a
# hand-written PR every time: #4079, #4084, #4085, #4106 are four of them, each
# one authored only AFTER a consumer's CI went red on a bug this repo had already
# fixed. Issue #4107 is that loop.
#
# The pin can only ever be "the newest release", and resolving a tag to a commit
# is mechanical, so this script owns the edit and `refresh-driver-pin.yml` runs
# it on the tail of every release.
#
# The pin lives in a data file rather than in the workflow because `GITHUB_TOKEN`
# cannot push changes under `.github/workflows/` — see the header of the pin file
# itself for why that is trust-neutral.
#
# It also cannot be folded into release-please's `extra-files`: the pin has to
# name the release COMMIT, which is the squash of the release PR and so does not
# exist until after that PR merges. Post-release is the earliest any mechanism
# can know it.
#
# USAGE
#   refresh-driver-pin.sh --check                 verify the pin file's shape
#   refresh-driver-pin.sh --print                 print the currently pinned SHA
#   refresh-driver-pin.sh --tag vX.Y.Z --sha <40-hex> [--date YYYY-MM-DD]

set -euo pipefail

FILE="${DRIVER_PIN_FILE:-.github/design-artifacts-driver-pin.txt}"

die() {
  echo "refresh-driver-pin: $*" >&2
  exit 1
}

mode=''
tag=''
sha=''
date="$(date -u +%Y-%m-%d)"

while [ $# -gt 0 ]; do
  case "$1" in
    --check) mode=check ;;
    --print) mode=print ;;
    --tag) tag="${2:?--tag needs a value}"; mode="${mode:-write}"; shift ;;
    --sha) sha="${2:?--sha needs a value}"; mode="${mode:-write}"; shift ;;
    --date) date="${2:?--date needs a value}"; shift ;;
    --file) FILE="${2:?--file needs a value}"; shift ;;
    -h|--help) sed -n '28,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

[ -n "${mode}" ] || die "one of --check / --print / --tag+--sha is required"
[ -f "${FILE}" ] || die "no such file: ${FILE}"

# Read one `key=value` line, ignoring comments. A key that appears twice is an
# error rather than a last-one-wins guess: the workflow reads this file with a
# `sed` that takes the FIRST match, so a duplicate would make the two disagree.
read_key() {
  local key="$1" out
  out="$(sed -nE "s/^${key}=(.*)$/\\1/p" "${FILE}")"
  [ "$(printf '%s\n' "${out}" | grep -c .)" -eq 1 ] \
    || die "expected exactly one '${key}=' line in ${FILE}"
  printf '%s' "${out}"
}

current_sha="$(read_key sha)"
current_tag="$(read_key tag)"
current_date="$(read_key date)"

validate() {
  printf '%s' "$1" | grep -qE "$2" || die "$3"
}

case "${mode}" in
  print)
    validate "${current_sha}" '^[0-9a-f]{40}$' "pinned sha is not a 40-hex commit: '${current_sha}'"
    echo "${current_sha}"
    ;;

  check)
    # The workflow's `sed -nE 's/^sha=([0-9a-f]{40})$/\1/p'` matches nothing if the
    # SHA is abbreviated or upper-case, and the run then fails closed at the
    # 40-hex guard — after the caller has already paid for a checkout and a JDK.
    # Catch the shape here instead, on every PR.
    validate "${current_sha}" '^[0-9a-f]{40}$' "pinned sha is not a lower-case 40-hex commit: '${current_sha}'"
    validate "${current_tag}" '^v[0-9]+\.[0-9]+\.[0-9]+$' "pinned tag is not vX.Y.Z: '${current_tag}'"
    validate "${current_date}" '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' "pin date is not YYYY-MM-DD: '${current_date}'"
    echo "driver pin OK: ${current_sha} (${current_tag}, pinned ${current_date})"
    ;;

  write)
    [ -n "${tag}" ] || die "--tag is required to rewrite the pin"
    [ -n "${sha}" ] || die "--sha is required to rewrite the pin"
    validate "${tag}" '^v[0-9]+\.[0-9]+\.[0-9]+$' "tag must look like vX.Y.Z, got '${tag}'"
    validate "${sha}" '^[0-9a-f]{40}$' "sha must be a lower-case 40-hex commit, got '${sha}'"
    validate "${date}" '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' "date must be YYYY-MM-DD, got '${date}'"

    if [ "${sha}" = "${current_sha}" ]; then
      echo "driver pin already at ${sha} (${current_tag}) — nothing to do"
      exit 0
    fi

    sed -E -i \
      -e "s|^sha=.*$|sha=${sha}|" \
      -e "s|^tag=.*$|tag=${tag}|" \
      -e "s|^date=.*$|date=${date}|" \
      "${FILE}"

    # Re-read rather than trust the substitution: a rewrite that silently missed
    # a key would otherwise ship a pin file whose sha and tag disagree.
    [ "$(read_key sha)" = "${sha}" ] && [ "$(read_key tag)" = "${tag}" ] \
      && [ "$(read_key date)" = "${date}" ] \
      || die "rewrite did not converge"

    echo "driver pin ${current_sha} (${current_tag}) -> ${sha} (${tag})"
    ;;
esac
