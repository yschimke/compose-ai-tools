#!/usr/bin/env bash
#
# Record the commit a design-artifacts lane was just rendered from, as the git ref
# `refs/design-artifacts/source/<system>` (issue #5438).
#
# Run at the END of a successful publish, whatever the publish did. That last part
# is the whole point: the publish uses `SKIP_IF_UNCHANGED=1`, so a render whose
# output is byte-identical to the delivery branch tip commits nothing at all. The
# lane WAS rendered from this commit and is not stale — but nothing on the branch
# says so, and a scope that read its baseline off the branch would keep re-diffing
# from the last commit that happened to change bytes. A ref costs no commit, adds
# no noise to the delivery branch's per-regeneration history, and advances every
# time. See published-source.sh for the read side.
#
# A failure here is a NOTICE, not a job failure. The baseline degrades to the
# delivery branch subject (conservative: it over-renders, never under-renders), and
# a lane that published a good bundle must not be reported red because a bookkeeping
# ref would not move.
#
# Usage:
#   scripts/design-artifacts/mark-published-source.sh --system wear-m3 --sha "$GITHUB_SHA"
#
# Needs `contents: write` and a `GH_TOKEN` — the same grant the publish step itself
# holds, which is why this runs there and not in the read-only render jobs.

set -euo pipefail

SYSTEM=''
SHA=''
REPO="${GITHUB_REPOSITORY:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --system) SYSTEM="${2:-}"; shift 2 ;;
    --sha)    SHA="${2:-}"; shift 2 ;;
    --repo)   REPO="${2:-}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

: "${SYSTEM:?--system required}"
: "${SHA:?--sha required}"
: "${REPO:?--repo required (or set GITHUB_REPOSITORY)}"

# The read side only accepts a full 40-hex object id, so refuse to write anything
# else rather than store a value that silently never resolves.
if ! [[ "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::notice::design-artifacts source marker: '$SHA' is not a full commit SHA; leaving the marker for $SYSTEM unchanged."
  exit 0
fi

ref="design-artifacts/source/$SYSTEM"

# PATCH first: the steady state is a ref that already exists, and `force` is right
# here — the marker tracks the latest successful render, which is not required to be
# a descendant of the previous one (a lane can be re-rendered from an older ref by a
# dispatch, and that is still the truth about what is published).
if gh api --method PATCH "repos/$REPO/git/refs/$ref" \
     -f "sha=$SHA" -F 'force=true' > /dev/null 2>&1; then
  echo "design-artifacts source marker: $ref -> $SHA"
  exit 0
fi

# First publish for this lane — the ref does not exist yet.
if gh api --method POST "repos/$REPO/git/refs" \
     -f "ref=refs/$ref" -f "sha=$SHA" > /dev/null 2>&1; then
  echo "design-artifacts source marker: created $ref -> $SHA"
  exit 0
fi

echo "::notice::design-artifacts source marker: could not update $ref; the next scope falls back to the delivery branch subject."
