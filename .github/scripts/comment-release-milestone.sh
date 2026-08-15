#!/usr/bin/env bash
# Post (or update) a release milestone comment on the merged release PR.
#
# Why this exists: merging the `chore(main): release X.Y.Z` PR is the one manual step of a
# release, and everything after it happens in workflow logs nobody is watching. The two moments
# that actually matter to a human — "the preview server is running the new version" and "the
# artifacts resolve from Maven Central" — arrive 10-40 minutes apart, in two different reusable
# workflows, with no signal anywhere the releaser is looking. This turns each of them into a
# comment on the PR they just merged.
#
# It is a COURTESY, never a gate. Every caller runs it with `continue-on-error: true`, after the
# milestone has already been reached, so it can neither delay the release nor fail it. It also
# adds no waiting of its own: the deploy convergence poll and the Maven readiness poll already
# existed for their own reasons, and this only reports what they concluded.
#
# Targeting. The PR is resolved from the release commit rather than passed in, because the
# reusable workflows that call this only receive a tag. `commits/<sha>/pulls` gives the PRs
# containing the commit; we keep only one that looks like a release-please PR — head branch
# `release-please--*` AND authored by `github-actions[bot]`, the same pair ci.yml uses to detect
# release PRs. That pair is what makes a stray `workflow_dispatch` on main a no-op instead of a
# comment on whatever unrelated PR happened to be main's head: the branch name alone is
# contributor-controlled, so it isn't trusted on its own. No match → exit 0, silently skipped.
#
# Idempotent. Each milestone carries an invisible `<!-- release-milestone:<key>:<tag> -->`
# marker; a re-run finds its own previous comment and PATCHes it instead of stacking a second
# one. That matters because both callers are re-runnable repair paths (a failed Maven readiness
# job is re-run by hand often enough to have its own section in docs/RELEASING.md).
#
# Usage:
#   MILESTONE_KEY=server-deployed MILESTONE_TAG=v1.7.0 MILESTONE_BODY='### …' \
#     GH_TOKEN=… GITHUB_REPOSITORY=owner/repo RELEASE_SHA=<merge sha> \
#     comment-release-milestone.sh
#
# MILESTONE_PR short-circuits the resolution above (used by the self-test, and available as an
# escape hatch when commenting on a PR the commit lookup can't reach).
set -uo pipefail

: "${MILESTONE_KEY:?MILESTONE_KEY required}"
: "${MILESTONE_TAG:?MILESTONE_TAG required}"
: "${MILESTONE_BODY:?MILESTONE_BODY required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
SHA="${RELEASE_SHA:-${GITHUB_SHA:-}}"

MARKER="<!-- release-milestone:${MILESTONE_KEY}:${MILESTONE_TAG} -->"

pr="${MILESTONE_PR:-}"
if [[ -z "${pr}" ]]; then
  if [[ -z "${SHA}" ]]; then
    echo "no RELEASE_SHA/GITHUB_SHA to resolve a release PR from — skipping ${MILESTONE_KEY}."
    exit 0
  fi
  # `// empty` rather than `// null`: an unmatched lookup must produce an empty string, so the
  # caller-side check below is a plain emptiness test and never the literal "null".
  pr="$(gh api "repos/${REPO}/commits/${SHA}/pulls" --jq '
    [ .[]
      | select((.head.ref // "") | startswith("release-please--"))
      | select(.user.login == "github-actions[bot]")
    ][0].number // empty' 2>/dev/null || true)"
fi

if [[ -z "${pr}" ]]; then
  echo "commit ${SHA} has no release-please PR — skipping the ${MILESTONE_KEY} milestone comment."
  exit 0
fi

body="${MARKER}
${MILESTONE_BODY}"

# `--paginate` emits one JSON document per page, so slurp and flatten (`.[][]`) instead of
# parsing a single array — a release PR with a busy thread would otherwise silently look like it
# had no prior marker and get a duplicate comment on every re-run.
existing=""
if comments="$(gh api --paginate "repos/${REPO}/issues/${pr}/comments" 2>/dev/null)"; then
  existing="$(printf '%s' "${comments}" | jq -s -r --arg m "${MARKER}" '
    [ .[][] | select((.body // "") | contains($m)) ][0].id // empty')"
fi

payload="$(jq -n --arg body "${body}" '{body: $body}')"

if [[ -n "${existing}" ]]; then
  if printf '%s' "${payload}" \
      | gh api --method PATCH "repos/${REPO}/issues/comments/${existing}" --input - >/dev/null; then
    echo "updated the ${MILESTONE_KEY} milestone comment on #${pr}."
    exit 0
  fi
  echo "::warning::could not update the ${MILESTONE_KEY} milestone comment on #${pr}."
  exit 1
fi

if printf '%s' "${payload}" \
    | gh api --method POST "repos/${REPO}/issues/${pr}/comments" --input - >/dev/null; then
  echo "posted the ${MILESTONE_KEY} milestone comment on #${pr}."
  exit 0
fi
echo "::warning::could not post the ${MILESTONE_KEY} milestone comment on #${pr}."
exit 1
