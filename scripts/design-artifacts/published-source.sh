#!/usr/bin/env bash
#
# The commit a design-artifacts lane was last RENDERED FROM — the baseline a
# self-healing scope diffs against (issue #5438).
#
# ## Why this exists
#
# The `Scope` job in every design-artifacts caller used to diff exactly one range:
# `github.event.before..$GITHUB_SHA`, the push that started the run. That premise —
# *"the diff of one push is the whole truth"* — holds only if every push gets a run
# that finishes. It does not. GitHub keeps at most one PENDING run per concurrency
# group, so a newly queued run cancels the one already waiting; the cancelled run's
# catalogs are then never re-rendered, because its replacement scopes to its own
# push, which touched something else. The lane goes stale on `main` silently and
# stays that way until the Monday cron.
#
# Diffing from the commit the lane was last rendered from closes that hole: a
# dropped run is picked up by the NEXT push whatever that push touched, because the
# range still reaches back over the change nobody rendered.
#
# ## Where the baseline comes from
#
# Two sources, in order, because one of them is exact and the other is the one that
# already exists on branches published before this script did:
#
#  1. **The marker ref** `refs/design-artifacts/source/<system>`, written by
#     mark-published-source.sh at the end of every successful publish. This is the
#     load-bearing one, and the reason a ref is used rather than the delivery
#     branch's own history: the publish runs with `SKIP_IF_UNCHANGED=1`, so a render
#     whose output is byte-identical to the tip commits NOTHING. Reading the
#     baseline off the branch would leave it pinned at the last commit that changed
#     bytes, and a shared-input edit that moves no pixel (a comment in the driver,
#     a test-only change) would then re-scope EVERY later push to every lane —
#     ~90 minutes of render per lane, per merge, forever. The ref advances on every
#     successful render whether or not anything was committed.
#  2. **The delivery branch tip's subject**, `chore(design-artifacts): regenerate
#     <system> catalog (<date>, <sha>)`. Back-compat only: it is what a lane that
#     has not run since this landed still has. It is behind by exactly the
#     unchanged-output renders described above, which makes it conservative —
#     it over-renders, never under-renders.
#
# Prints the resolved SHA, or nothing when neither source answers. An empty answer
# is not an error: the caller falls back to `github.event.before`, which is what the
# scope did before this existed.
#
# Usage:
#   scripts/design-artifacts/published-source.sh --system wear-m3 --repo owner/name
#   scripts/design-artifacts/published-source.sh --parse-subject "$subject"
#
# `--parse-subject` is the pure half, split out so the subject grammar is unit-tested
# (test-published-source.sh) without a network or a `gh` stub.

set -euo pipefail

SYSTEM=''
REPO="${GITHUB_REPOSITORY:-}"
BRANCH_PREFIX='design-artifacts'
PARSE_SUBJECT=''
PARSE_ONLY=0

# The publish subject both publishers stamp. `regenerate` is the catalog publish;
# `update … render history` is the index-only follow-up, which is stamped from the
# same source commit and is just as valid a baseline.
#
# The SHA is `--short=8` at the stamp site, so match a short-or-full hex run and
# anchor it to the trailing `)` — a date can't be mistaken for it, and a layer name
# inside the subject can't either.
SUBJECT_RE='^chore\(design-artifacts\): (regenerate|update) .* \([0-9]{4}-[0-9]{2}-[0-9]{2}, ([0-9a-f]{7,40})\)$'

parse_subject() {
  # `sed` rather than BASH_REMATCH so the grammar is one expression shared by both
  # the match and the extraction.
  sed -nE "s/$SUBJECT_RE/\\2/p" <<<"$1" | head -1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --system)        SYSTEM="${2:-}"; shift 2 ;;
    --repo)          REPO="${2:-}"; shift 2 ;;
    --branch-prefix) BRANCH_PREFIX="${2:-}"; shift 2 ;;
    --parse-subject) PARSE_SUBJECT="${2:-}"; PARSE_ONLY=1; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ "$PARSE_ONLY" = 1 ]; then
  parse_subject "$PARSE_SUBJECT"
  exit 0
fi

: "${SYSTEM:?--system required}"
: "${REPO:?--repo required (or set GITHUB_REPOSITORY)}"

# 1. The marker ref. `|| true` throughout: a lane that has never published has no
# ref, and a 404 here is an ordinary answer, not a failure. Note the ref is read
# WITHOUT the leading `refs/`, which is the shape this endpoint takes.
ref_sha="$(
  gh api "repos/$REPO/git/ref/design-artifacts/source/$SYSTEM" \
    --jq '.object.sha' 2>/dev/null || true
)"
if [[ "$ref_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "$ref_sha"
  exit 0
fi

# 2. The delivery branch tip's subject.
subject="$(
  gh api "repos/$REPO/commits/$BRANCH_PREFIX/$SYSTEM" \
    --jq '.commit.message' 2>/dev/null | head -1 || true
)"
parse_subject "$subject"
