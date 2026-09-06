#!/usr/bin/env bash
# The version of this repository's Maven artifacts that is on Central *below* a given release.
#
# ONE resolver, TWO consumers in TWO SEPARATE WORKFLOW RUNS, and they must agree exactly:
#   * `maven-publish-guard` in release.yml, which runs BEFORE the publish and decides whether
#     this release needs one; and
#   * `maven-readiness.yml`, which runs AFTER it (from release-please.yml, a different workflow
#     run with no access to the first one's outputs) and has to know which coordinate to prove
#     resolvable.
#
# That "before and after" is the whole reason this is a script and not two inline `sed`
# expressions. Central's `<latest>` means different things at those two moments — it is
# `v(N-1)` before the publish and `vN` after it — so a resolver built on it would have the two
# jobs disagree about what happened on exactly the releases that DID publish, and the readiness
# gate would then verify the wrong version. Selecting the greatest version **strictly below**
# the release under consideration is stable across the publish: it is `v(N-1)` at both moments,
# whether or not `vN` ever reaches Central.
#
# `renderer-desktop` is the sentinel coordinate. It is published on every Central publish this
# repo does and it is the artifact the injected plugin resolves, so if it is absent at a version
# then nothing else is there either. See `maven-publish-needed.sh` for the all-or-nothing rule
# that makes one sentinel sufficient.
#
# Prints the bare version (no leading `v`) on stdout, or NOTHING when it cannot resolve one —
# a network failure, an empty metadata document, or a first-ever release with nothing below it.
# Exit status is 0 in both cases: "no baseline" is an answer, and every caller is required to
# fail open on it (publish; verify the release's own version). A non-zero exit means the script
# itself broke.
#
# Usage:
#   maven-line-baseline.sh --below <version>              # query Central
#   maven-line-baseline.sh --below <version> --metadata <file>   # read a maven-metadata.xml
set -euo pipefail

METADATA_URL="https://repo1.maven.org/maven2/ee/schimke/composeai/renderer-desktop/maven-metadata.xml"

BELOW=""
METADATA_FILE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --below) BELOW="${2:?--below needs a version}"; shift 2 ;;
    --metadata) METADATA_FILE="${2:?--metadata needs a path}"; shift 2 ;;
    *) echo "maven-line-baseline.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "${BELOW}" ] || { echo "maven-line-baseline.sh: --below is required" >&2; exit 2; }
BELOW="${BELOW#v}"

if [ -n "${METADATA_FILE}" ]; then
  meta="$(cat "${METADATA_FILE}")"
else
  # `|| true`: a network failure must leave us with an empty document and print nothing, not
  # abort the calling job. Fail-open is the caller's contract, and it can only honour it if it
  # gets an answer back.
  meta="$(curl -fsSL --max-time 30 "${METADATA_URL}" || true)"
fi

# One <version> element per line. `sed -n s///p` over the whole document rather than a parse:
# Central writes this file, its shape is fixed, and adding an XML parser to a release-critical
# path buys nothing.
versions="$(printf '%s' "${meta}" | tr '<' '\n' | sed -n 's:^version>\(.*\)$:\1:p')"

[ -n "${versions}" ] || exit 0

# Keep only versions strictly below ${BELOW}. `sort -V` is the comparison — a string compare
# puts 1.9.0 above 1.10.0, which would silently pick a baseline from six releases back.
below_only=""
while IFS= read -r v; do
  [ -n "${v}" ] || continue
  [ "${v}" != "${BELOW}" ] || continue
  lower="$(printf '%s\n%s\n' "${v}" "${BELOW}" | sort -V | head -1)"
  [ "${lower}" = "${v}" ] || continue
  below_only="${below_only}${v}
"
done <<< "${versions}"

[ -n "${below_only}" ] || exit 0

printf '%s' "${below_only}" | sort -V | tail -1
