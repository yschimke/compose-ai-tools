#!/usr/bin/env bash
# Finish a GitHub Release that was cut as a draft and then abandoned.
#
# WHY THIS EXISTS
# ---------------
# The whole release chain in release-please.yml hangs off one value:
# `needs.release-please.outputs.release_created == 'true'`. The release-please ACTION sets that
# output last, so any throw after it has already cut the draft — and it cuts the draft several
# API calls before it returns — leaves the output empty, and with it the tag step, release.yml
# and `finalize-release` all skipped. The draft sits there with nothing left in the run that
# would ever publish it. Two real instances: `Error adding to tree` (which stranded 0.16.34,
# draft and untagged, until a human fixed it) and `Label does not exist` on 2026-09-05, when two
# invocations raced to remove `autorelease: pending` from #5148 and the loser died holding
# v1.78.0's freshly cut draft. `!cancelled()` guards do not help: the JOB failing is not the
# problem, the OUTPUT never being set is.
#
# v1.78.0 recovered only by luck — the next push to main re-ran release-please, which cut the
# release again and carried it through. That accident is gone now: cutting a release is the
# release-PR merge run's job alone (the fix for the race above), so recovery is this sweeper's
# job, deliberately, on a schedule.
#
# WHAT IT DOES
# ------------
# For each draft release older than MIN_AGE_MINUTES, when no release run is in flight:
#   * no tag           → create it, but only at an explicit commit SHA (never a guess);
#   * assets missing   → the build never finished: dispatch release.yml for the tag;
#   * assets present   → publish it, once the plugin is actually resolvable from Maven Central.
# It escalates to a job failure — the point of a scheduled job nobody watches — when a draft has
# been stuck for longer than ESCALATE_AFTER_MINUTES and this run could not finish it.
#
# WHAT IT WILL NOT DO
# -------------------
#   * Publish a release whose required assets are not attached. That is `finalize-release`'s
#     invariant (`/releases/latest` must never point at a version whose tarball is missing) and
#     the sweeper shares its list — see required-release-assets.sh.
#   * Publish a release the Gradle plugin never reached Maven Central for. `finalize-release`
#     gets this from `needs.release.result == 'success'`; out of band there is no run to ask, so
#     the sweeper asks Central directly.
#   * Guess where a missing tag belongs. A draft whose `target_commitish` is a branch rather than
#     a commit is left for a human with tag-release.yml, because tagging main's tip would ship a
#     release built from commits the release never contained.
#   * Touch anything that is not `v<major>.<minor>.<patch>`.
#
# Usage:
#   GITHUB_REPOSITORY=owner/repo GH_TOKEN=… sweep-stranded-releases.sh
# Knobs (all optional): MIN_AGE_MINUTES, ESCALATE_AFTER_MINUTES, RECOVERY_COOLDOWN_MINUTES,
# DRY_RUN, CENTRAL_BASE_URL.
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
# A release takes ~15-20 minutes end to end (v1.78.0: merge 08:23, published 08:37). The floor is
# well clear of that, so a release that is merely slow is never mistaken for one that died — the
# in-flight check below is the real guard, this is the belt to its braces.
MIN_AGE_MINUTES="${MIN_AGE_MINUTES:-45}"
ESCALATE_AFTER_MINUTES="${ESCALATE_AFTER_MINUTES:-1440}"
# A dispatched release.yml run that fails will keep failing; re-dispatching every hour turns one
# broken release into a wall of red runs. Try again at a human cadence instead, and escalate.
RECOVERY_COOLDOWN_MINUTES="${RECOVERY_COOLDOWN_MINUTES:-360}"
DRY_RUN="${DRY_RUN:-false}"
CENTRAL_BASE_URL="${CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"

now="$(date -u +%s)"
exit_code=0

minutes_since() { # <ISO-8601 timestamp> → whole minutes, or empty if unreadable
  local epoch
  epoch="$(date -u -d "${1:-}" +%s 2>/dev/null)" || return 0
  [[ -n "${epoch}" ]] && echo $(( (now - epoch) / 60 ))
}

# Never act while the release chain is running: the draft this sweeper would "rescue" is almost
# certainly the one `finalize-release` is minutes away from publishing itself. release-please.yml
# runs on every push to main, so this also parks the sweep behind ordinary pushes — a cost worth
# paying, since the alternative is racing the very workflow we exist to back up.
for workflow in release-please.yml release.yml; do
  for status in queued in_progress; do
    running="$(gh api "repos/${REPO}/actions/workflows/${workflow}/runs?status=${status}&per_page=1" \
      --jq '.total_count // 0' 2>/dev/null)"
    if [[ "${running:-0}" =~ ^[0-9]+$ ]] && (( running > 0 )); then
      echo "::notice::${workflow} has a ${status} run — a release is in flight; sweeping nothing."
      exit 0
    fi
  done
done

drafts="$(gh api "repos/${REPO}/releases?per_page=100" \
  --jq '.[] | select(.draft) | [.id, .tag_name, .created_at, .target_commitish] | @tsv' 2>/dev/null)"

if [[ -z "${drafts}" ]]; then
  echo "No draft releases. Nothing to sweep."
  exit 0
fi

while IFS=$'\t' read -r id tag created_at commitish; do
  [[ -n "${id:-}" ]] || continue
  echo "::group::${tag} (draft ${id})"

  # `untagged-…` drafts and anything else non-release-shaped are not ours to finish.
  if [[ ! "${tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "::notice::${tag} is not a v<major>.<minor>.<patch> draft; leaving it alone."
    echo "::endgroup::"; continue
  fi

  age="$(minutes_since "${created_at}")"
  if [[ -z "${age}" ]]; then
    echo "::warning::could not read ${tag}'s creation time (${created_at}); leaving it alone."
    echo "::endgroup::"; continue
  fi
  if (( age < MIN_AGE_MINUTES )); then
    echo "${tag} was cut ${age}m ago (< ${MIN_AGE_MINUTES}m) — still within the release window."
    echo "::endgroup::"; continue
  fi

  version="${tag#v}"
  stuck=1  # cleared once this run finishes the release; drives the escalation below

  # 1. The tag. A draft release carries no tag ref of its own, and release.yml checks out
  #    `ref: <tag>` — so without it nothing can be rebuilt.
  if gh api "repos/${REPO}/git/ref/tags/${tag}" >/dev/null 2>&1; then
    echo "tag ${tag} exists."
  elif [[ ! "${commitish}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "::warning::${tag} has no tag and its target is '${commitish}', not a commit SHA — refusing to guess. Create it with tag-release.yml at the release PR's merge commit."
    echo "::endgroup::"
    (( age > ESCALATE_AFTER_MINUTES )) && exit_code=1
    continue
  elif [[ "${DRY_RUN}" == true ]]; then
    echo "[dry-run] would create tag ${tag} at ${commitish}."
  elif jq -n --arg ref "refs/tags/${tag}" --arg sha "${commitish}" '{ref: $ref, sha: $sha}' |
       gh api --method POST "repos/${REPO}/git/refs" --input - >/dev/null 2>&1; then
    echo "::notice::created the missing tag ${tag} at ${commitish}."
  else
    echo "::warning::could not create tag ${tag} at ${commitish}."
    echo "::endgroup::"
    (( age > ESCALATE_AFTER_MINUTES )) && exit_code=1
    continue
  fi

  # 2. The assets. Same list `finalize-release` gates on, from the same file.
  have="$(gh api "repos/${REPO}/releases/${id}/assets?per_page=100" --jq '.[].name' 2>/dev/null)"
  missing=()
  while read -r asset; do
    [[ -n "${asset}" ]] || continue
    grep -qxF "${asset}" <<< "${have}" || missing+=("${asset}")
  done < <("$(dirname "${BASH_SOURCE[0]}")/required-release-assets.sh" "${version}")

  if (( ${#missing[@]} > 0 )); then
    echo "${tag} is missing ${#missing[@]} required asset(s): ${missing[*]}"
    # The build never finished, so re-run it for the tag. release.yml is idempotent for a tag:
    # it clobbers its own assets and skips an npm version already published.
    last_recovery="$(gh api "repos/${REPO}/actions/workflows/release.yml/runs?event=workflow_dispatch&per_page=1" \
      --jq '.workflow_runs[0].created_at // empty' 2>/dev/null)"
    since_recovery="$(minutes_since "${last_recovery}")"
    if [[ -n "${since_recovery}" ]] && (( since_recovery < RECOVERY_COOLDOWN_MINUTES )); then
      echo "::warning::a release.yml recovery run was dispatched ${since_recovery}m ago (< ${RECOVERY_COOLDOWN_MINUTES}m); not dispatching another for ${tag}."
    elif [[ "${DRY_RUN}" == true ]]; then
      echo "[dry-run] would dispatch release.yml for ${tag}."
    elif jq -n --arg tag "${tag}" '{ref: "main", inputs: {tag: $tag}}' |
         gh api --method POST "repos/${REPO}/actions/workflows/release.yml/dispatches" --input - >/dev/null 2>&1; then
      echo "::notice::dispatched release.yml for ${tag} to rebuild its assets; the next sweep publishes it."
    else
      echo "::warning::could not dispatch release.yml for ${tag}."
    fi
    echo "::endgroup::"
    (( age > ESCALATE_AFTER_MINUTES )) && exit_code=1
    continue
  fi
  echo "${tag} carries every required asset."

  # 3. Maven Central. In the chain this is implied by `needs.release.result == 'success'`; here
  #    there is no run left to ask, and un-drafting a version whose Gradle plugin never published
  #    is exactly what that gate exists to prevent. Ask Central for the plugin marker POM — the
  #    same coordinate maven-readiness.yml resolves. A miss is usually just CDN propagation, so it
  #    costs a sweep, not a release.
  pom="${CENTRAL_BASE_URL}/ee/schimke/composeai/preview/ee.schimke.composeai.preview.gradle.plugin/${version}/ee.schimke.composeai.preview.gradle.plugin-${version}.pom"
  if ! curl -fsSL --max-time 30 -o /dev/null "${pom}" 2>/dev/null; then
    echo "::warning::${tag}'s plugin is not on Maven Central yet (${pom}) — leaving it a draft. If the publish genuinely failed, re-run the release, don't publish this by hand."
    echo "::endgroup::"
    (( age > ESCALATE_AFTER_MINUTES )) && exit_code=1
    continue
  fi
  echo "plugin ${version} resolves from Maven Central."

  # 4. Publish. `make_latest` matches `gh release edit --latest` in finalize-release.
  if [[ "${DRY_RUN}" == true ]]; then
    echo "[dry-run] would publish ${tag} (un-draft + mark latest)."
    stuck=0
  elif jq -n '{draft: false, make_latest: "true"}' |
       gh api --method PATCH "repos/${REPO}/releases/${id}" --input - >/dev/null 2>&1; then
    echo "::notice::published ${tag} — it had been a draft for ${age}m after its release run died."
    stuck=0
  else
    echo "::warning::could not publish ${tag}."
  fi

  (( stuck == 1 && age > ESCALATE_AFTER_MINUTES )) && exit_code=1
  echo "::endgroup::"
done <<< "${drafts}"

if (( exit_code != 0 )); then
  echo "::error::a release has been stuck as a draft for more than ${ESCALATE_AFTER_MINUTES}m and this sweep could not finish it — see the warnings above."
fi
exit "${exit_code}"
