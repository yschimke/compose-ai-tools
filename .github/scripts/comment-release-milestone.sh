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
# Targeting. The reusable workflows that call this only receive a tag, so the PR is resolved
# from THE TAG's commit — deliberately not from GITHUB_SHA. Those workflows are also the manual
# repair paths (`workflow_dispatch` with an older tag), and there GITHUB_SHA is the ref the run
# was launched from, typically `main`, whose head is a release merge commit for as long as the
# release is the most recent thing on the branch. Resolving from GITHUB_SHA would then find the
# NEWER release PR and post the older tag's milestone on it. Anchoring on the tag makes the
# lookup say what it means: which PR produced THIS release.
#
# Then `commits/<sha>/pulls` gives the PRs containing that commit, and a match must clear three
# checks: head branch `release-please--*`, author `github-actions[bot]` (the same pair ci.yml
# uses to detect release PRs — the branch name alone is contributor-controlled, so it isn't
# trusted on its own), and a title naming this release's version, which is the belt-and-braces
# against any remaining way to reach a PR belonging to a different release. No match, or no such
# tag → exit 0, silently skipped.
#
# Idempotent. Each milestone carries an invisible `<!-- release-milestone:<key>:<tag> -->`
# marker; a re-run finds its own previous comment and PATCHes it instead of stacking a second
# one. That matters because both callers are re-runnable repair paths (a failed Maven readiness
# job is re-run by hand often enough to have its own section in docs/RELEASING.md). The marker
# is predictable and release PRs are public, so the search is restricted to comments authored by
# `github-actions[bot]`: otherwise anyone could post the marker ahead of the release and have
# their comment either overwritten in place or — if GitHub refuses the cross-author edit —
# swallow the milestone entirely, since the failure is deliberately tolerated by the caller.
#
# Usage:
#   MILESTONE_KEY=server-deployed MILESTONE_TAG=v1.7.0 MILESTONE_BODY='### …' \
#     GH_TOKEN=… GITHUB_REPOSITORY=owner/repo comment-release-milestone.sh
#
# RELEASE_SHA overrides the tag→commit lookup, and MILESTONE_PR short-circuits PR resolution
# entirely (both used by the self-test, and available as escape hatches).
set -uo pipefail

: "${MILESTONE_KEY:?MILESTONE_KEY required}"
: "${MILESTONE_TAG:?MILESTONE_TAG required}"
: "${MILESTONE_BODY:?MILESTONE_BODY required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"

MARKER="<!-- release-milestone:${MILESTONE_KEY}:${MILESTONE_TAG} -->"
# `v1.7.0` → `1.7.0`, and `clients-v1.2.3` → `1.2.3`, so a component-prefixed tag from another
# release train still yields the version its PR title carries.
VERSION="${MILESTONE_TAG##*v}"

pr="${MILESTONE_PR:-}"
if [[ -z "${pr}" ]]; then
  # `commits/<ref>` (unlike `commits/<sha>/pulls`) resolves a tag name and dereferences an
  # annotated tag, so this works whether the tag was written by the API or by `git tag -a`.
  SHA="${RELEASE_SHA:-$(gh api "repos/${REPO}/commits/${MILESTONE_TAG}" --jq '.sha' 2>/dev/null || true)}"
  if [[ -z "${SHA}" ]]; then
    echo "no commit for tag ${MILESTONE_TAG} — skipping the ${MILESTONE_KEY} milestone comment."
    exit 0
  fi
  # `// empty` rather than `// null`: an unmatched lookup must produce an empty string, so the
  # caller-side check below is a plain emptiness test and never the literal "null".
  if pulls="$(gh api "repos/${REPO}/commits/${SHA}/pulls" 2>/dev/null)"; then
    pr="$(printf '%s' "${pulls}" | jq -r --arg v "${VERSION}" '
      [ .[]
        | select((.head.ref // "") | startswith("release-please--"))
        | select(.user.login == "github-actions[bot]")
        | select((.title // "") | contains($v))
      ][0].number // empty')"
  fi
fi

if [[ -z "${pr}" ]]; then
  echo "tag ${MILESTONE_TAG} has no release PR for ${VERSION} — skipping the ${MILESTONE_KEY} milestone comment."
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
    [ .[][]
      | select(.user.login == "github-actions[bot]")
      | select((.body // "") | contains($m))
    ][0].id // empty')"
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
