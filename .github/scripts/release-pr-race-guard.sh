#!/usr/bin/env bash
# Keep an ordinary main push from computing a release PR while a release is still publishing.
#
# Why this exists. release-please's PR half baselines on the latest PUBLISHED GitHub Release.
# This repo cuts releases as DRAFTS (release-please-config.json `"draft": true`) and only
# un-drafts them in `finalize-release`, once every required asset is verified present — 17
# minutes after the merge for v1.42.0. For that whole window the version main already carries in
# `.release-please-manifest.json` is invisible to release-please, so any invocation of the PR
# half re-proposes the version that is *currently being released*: it force-updates the release
# branch and opens a duplicate `chore(main): release <the version being released>` PR.
#
# `release-please.yml` already keeps the RELEASE run itself out of that trap — its first
# invocation passes `skip-github-pull-request`, and `reconcile-release-pr` runs the PR half after
# the release is tagged and published. What it could not stop is a DIFFERENT run doing the PR
# half concurrently: an ordinary main push and a release-PR merge deliberately hold separate
# concurrency slots, so a feature merged seconds before the release PR leaves its
# `update-release-pr` job running straight through the cut. That is exactly how #4645 appeared —
# release-please read the baseline as v1.41.0 at 08:56:04, saw #4628 still open at 08:56:09
# (it had merged one second earlier), rebuilt the release branch it had just deleted, and then
# failed trying to update a merged PR: `state cannot be changed. The pull request cannot be
# reopened.`
#
# So this guard answers one question — is a release in flight? — and the job asks it twice:
#
#   MODE=check   before running release-please, which skips the common case where the push run
#                starts after the merge; and
#   MODE=repair  after, which closes the window the pre-flight check cannot: the merge can land
#                mid-invocation, as it did above. If a release went in flight while
#                release-please was computing, whatever it just opened targets the version being
#                released, so we close it and let `reconcile-release-pr` open the right PR from
#                the published baseline.
#
# In flight means: the version in `.release-please-manifest.json` on main has no PUBLISHED
# release. `releases/tags/<tag>` deliberately does not resolve drafts, so "no published release"
# covers both halves of the window — before the draft is cut, and while it is still a draft.
#
# Fail open, always. Every unreadable answer (a manifest we cannot fetch, an API that errors) is
# treated as "no release in flight", and a version that has sat unpublished for longer than
# STALE_AFTER_MINUTES is treated as stranded rather than in flight. A guard that failed closed
# would silently stop the release PR from being updated at all — a much worse failure than the
# duplicate PR it exists to prevent, and one this repo has already been bitten by once
# (0.16.34, draft and untagged, wedged until it was fixed by hand).
#
# Usage:
#   MODE=check  GITHUB_REPOSITORY=owner/repo GH_TOKEN=… release-pr-race-guard.sh
#   MODE=repair RP_PR='{"number":4645}' GITHUB_REPOSITORY=owner/repo GH_TOKEN=… release-pr-race-guard.sh
#
# Outputs (GITHUB_OUTPUT): `in_flight`/`version` in check mode, `raced`/`closed` in repair mode.
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
MODE="${MODE:-check}"
BRANCH="${RELEASE_BRANCH:-main}"
STALE_AFTER_MINUTES="${STALE_AFTER_MINUTES:-120}"

emit() { [[ -n "${GITHUB_OUTPUT:-}" ]] && printf '%s\n' "$1" >> "${GITHUB_OUTPUT}"; return 0; }

version=""
in_flight=false

manifest="$(gh api "repos/${REPO}/contents/.release-please-manifest.json?ref=${BRANCH}" \
  -H "Accept: application/vnd.github.raw" 2>/dev/null)"
if [[ -n "${manifest}" ]]; then
  version="$(jq -r '.["."] // empty' <<< "${manifest}" 2>/dev/null)"
fi

if [[ -z "${version}" ]]; then
  echo "::warning::could not read the release version from .release-please-manifest.json on ${BRANCH}; assuming no release is in flight"
else
  tag="v${version}"
  # Anything but an explicit `false` — a draft (which this endpoint does not resolve), a release
  # not cut yet, or an API error — means ${tag} is not published.
  if [[ "$(gh api "repos/${REPO}/releases/tags/${tag}" --jq '.draft' 2>/dev/null)" == "false" ]]; then
    echo "${tag} is published; no release is in flight."
  else
    in_flight=true
    # The commit that set the manifest to this version is the moment the release started. If it
    # was long enough ago, the release is stranded rather than in flight, and blocking the PR
    # half on it forever would be the worse failure.
    bumped_at="$(gh api "repos/${REPO}/commits?sha=${BRANCH}&path=.release-please-manifest.json&per_page=1" \
      --jq '.[0].commit.committer.date // empty' 2>/dev/null)"
    bumped_epoch="$(date -u -d "${bumped_at:-}" +%s 2>/dev/null)"
    if [[ -n "${bumped_epoch}" ]]; then
      age_minutes=$(( ( $(date -u +%s) - bumped_epoch ) / 60 ))
      if (( age_minutes > STALE_AFTER_MINUTES )); then
        echo "::warning::${tag} has been unpublished for ${age_minutes}m (> ${STALE_AFTER_MINUTES}m) — treating it as a stranded release, not one in flight"
        in_flight=false
      else
        echo "${tag} is not published yet (manifest bumped ${age_minutes}m ago) — a release is in flight."
      fi
    else
      echo "${tag} is not published yet — a release is in flight."
    fi
  fi
fi

if [[ "${MODE}" == "check" ]]; then
  emit "in_flight=${in_flight}"
  emit "version=${version}"
  [[ "${in_flight}" == true ]] &&
    echo "::notice::v${version} is still publishing — leaving the release PR to reconcile-release-pr"
  exit 0
fi

closed=""
finish() { emit "raced=${in_flight}"; emit "closed=${closed}"; exit 0; }

[[ "${in_flight}" == true ]] || finish

# A release went in flight while release-please was computing. Anything it opened or updated is
# for the version being released.
number="$(jq -r '.number // empty' <<< "${RP_PR:-}" 2>/dev/null)"
if [[ -z "${number}" ]]; then
  echo "::notice::a release went in flight mid-run, but release-please opened no pull request; nothing to discard"
  finish
fi

state="" title="" author="" head=""
IFS=$'\t' read -r state title author head < <(
  gh api "repos/${REPO}/pulls/${number}" \
    --jq '[.state, .title, .user.login, .head.ref] | @tsv' 2>/dev/null
)

# Refuse to touch anything that is not release-please's own open PR for the version being
# released. The branch name alone is contributor-controlled, so the author and the title carry
# the check with it.
if [[ "${state}" != "open" ]]; then
  echo "::notice::#${number} is ${state:-unreadable}; leaving it alone"
elif [[ "${author}" != "github-actions[bot]" || "${head}" != release-please--* ]]; then
  echo "::warning::#${number} is not a release-please pull request (${author:-?} on ${head:-?}); leaving it alone"
elif [[ "${title}" != "chore(main): release ${version}" ]]; then
  echo "::notice::#${number} is titled \"${title}\", not the in-flight v${version}; leaving it alone"
else
  jq -n --arg body "\
This pull request was opened by an ordinary \`main\` push whose release-please invocation \
overlapped the merge of the v${version} release PR, so it re-proposes the version that was \
already being released. Closing it: \`reconcile-release-pr\` opens the next release PR once \
v${version} is published." '{body: $body}' |
    gh api --method POST "repos/${REPO}/issues/${number}/comments" --input - > /dev/null 2>&1
  if gh api --method PATCH "repos/${REPO}/pulls/${number}" -f state=closed > /dev/null 2>&1; then
    echo "closed #${number}, a duplicate release PR for the in-flight v${version}."
    closed="${number}"
  else
    echo "::warning::could not close #${number}; it duplicates the in-flight v${version} release PR"
  fi
fi
finish
