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
# is mechanical, so RENOVATE owns the routine edit now — see the
# `design-artifacts-driver-pin` custom manager in .github/renovate.json. This
# script remains the VALIDATOR (`--check`, run by ci.yml on every PR) and the
# manual escape hatch (`--tag`/`--sha`) for repairing a pin by hand.
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
#   refresh-driver-pin.sh --tag vX.Y.Z --sha <40-hex>

set -euo pipefail

FILE="${DRIVER_PIN_FILE:-.github/design-artifacts-driver-pin.txt}"
PIN_REPO="${DRIVER_PIN_REPO:-yschimke/compose-ai-tools}"

die() {
  echo "refresh-driver-pin: $*" >&2
  exit 1
}

mode=''
tag=''
sha=''

while [ $# -gt 0 ]; do
  case "$1" in
    --check) mode=check ;;
    --print) mode=print ;;
    --tag) tag="${2:?--tag needs a value}"; mode="${mode:-write}"; shift ;;
    --sha) sha="${2:?--sha needs a value}"; mode="${mode:-write}"; shift ;;
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

    # The Renovate custom manager (.github/renovate.json) matches `sha=` IMMEDIATELY
    # followed by `tag=`, as one dependency. read_key above is order-agnostic and
    # tolerates comments between the keys, so without this a harmless manual reorder
    # would pass every check here while Renovate silently extracted nothing and
    # stopped refreshing the pin forever. Fail loudly on the layout instead.
    # Exact string comparison, not a regex: the tag's dots would otherwise be
    # metacharacters, and `grep -z` does not interpret a `\n` in an ERE anyway.
    awk -v s="sha=${current_sha}" -v t="tag=${current_tag}" '
      $0 == s { if ((getline nxt) > 0 && nxt == t) found = 1 }
      END { exit(found ? 0 : 1) }
    ' "${FILE}" \
      || die "sha= must be immediately followed by tag= (the layout .github/renovate.json matches)"

    # The pin must name a PUBLISHED release, not merely an existing tag.
    # release-please writes the tag while the GitHub Release is still a draft and
    # publishes it only once the artifacts are up, so a stranded draft leaves a real
    # tag behind whose artifacts never shipped — v1.55.0 is exactly that, tagged
    # cd234ab1 with nothing on Maven Central. Renovate's github-tags datasource
    # cannot tell the two apart, so the gate the old refresh-driver-pin.yml applied
    # before opening its PR lives here instead, where it fails the PR rather than
    # shipping every external caller a driver revision that was never released.
    #
    # Needs a token, so it is a hard gate in CI and a notice locally.
    token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
    published='unverified (no token)'
    if [ -z "${token}" ]; then
      echo "driver pin: no GH_TOKEN/GITHUB_TOKEN — skipping the published-release check" >&2
    else
      code="$(curl -sS -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer ${token}" \
        -H 'Accept: application/vnd.github+json' \
        "https://api.github.com/repos/${PIN_REPO}/releases/tags/${current_tag}" || echo 000)"
      # A draft release is not returned by this endpoint at all, so 404 covers both
      # "still a draft" and "no such release" — neither may be pinned.
      [ "${code}" = "200" ] \
        || die "${current_tag} is not a published release of ${PIN_REPO} (HTTP ${code}) — a draft or missing release must not be pinned"
      published='published'
    fi

    echo "driver pin OK: ${current_sha} (${current_tag}, ${published})"
    ;;

  write)
    [ -n "${tag}" ] || die "--tag is required to rewrite the pin"
    [ -n "${sha}" ] || die "--sha is required to rewrite the pin"
    validate "${tag}" '^v[0-9]+\.[0-9]+\.[0-9]+$' "tag must look like vX.Y.Z, got '${tag}'"
    validate "${sha}" '^[0-9a-f]{40}$' "sha must be a lower-case 40-hex commit, got '${sha}'"

    if [ "${sha}" = "${current_sha}" ]; then
      echo "driver pin already at ${sha} (${current_tag}) — nothing to do"
      exit 0
    fi

    sed -E -i \
      -e "s|^sha=.*$|sha=${sha}|" \
      -e "s|^tag=.*$|tag=${tag}|" \
      "${FILE}"

    # Re-read rather than trust the substitution: a rewrite that silently missed
    # a key would otherwise ship a pin file whose sha and tag disagree.
    [ "$(read_key sha)" = "${sha}" ] && [ "$(read_key tag)" = "${tag}" ] \
      || die "rewrite did not converge"

    echo "driver pin ${current_sha} (${current_tag}) -> ${sha} (${tag})"
    ;;
esac
