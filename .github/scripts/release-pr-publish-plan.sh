#!/usr/bin/env bash
# Posts, on the open release PR, which Maven coordinates merging it will publish.
#
# The release PR says which commits a release carries; it said nothing about what the release will
# upload, which since the publish plan (`maven-publish-plan.sh`) is anywhere from nothing to all of
# them. This runs that same plan against the release PR's head, with the same Maven Central
# baseline the release job reads, and keeps one comment on the PR up to date with the answer: how
# many modules publish, which, and why when it is all of them.
#
# One comment, found by its marker and edited in place, rather than a line in the PR body:
# release-please rewrites the body on every update and would erase it.
#
# Informational only. A plan that cannot run (Central unreachable, a script error) is reported in
# the comment and never fails the job: the release itself recomputes the plan and is unaffected.
#
# Env:
#   GH_TOKEN, GITHUB_REPOSITORY  as in any workflow step
#   RP_PR                        release-please-action's `pr` output (JSON), if it touched the PR;
#                                otherwise the open release PR is looked up by its branch
#   DRY_RUN=1                    print the comment instead of posting it; PR_HEAD=<ref> then names
#                                the commit to plan instead of looking one up
set -uo pipefail

MARKER='<!-- maven-publish-plan -->'
REPO="${GITHUB_REPOSITORY:-}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

if [[ "${DRY_RUN:-}" == 1 ]]; then
  number="(dry run)"
  head="${PR_HEAD:-HEAD}"
else
  number="$(jq -r '.number // empty' <<< "${RP_PR:-}" 2>/dev/null)"
  if [[ -z "${number}" ]]; then
    # release-please only reports a PR it created or updated in this run.
    number="$(gh api "repos/${REPO}/pulls?state=open&per_page=100" --jq \
      '[.[] | select(.head.ref | startswith("release-please--branches--")) | select(.head.ref | endswith("--release-notes") | not)][0].number // empty')"
  fi
  if [[ -z "${number}" ]]; then
    echo "no open release PR; nothing to annotate"
    exit 0
  fi
  ref="$(gh api "repos/${REPO}/pulls/${number}" --jq '.head.ref')"
  git fetch --quiet --no-tags origin "${ref}" || { echo "::warning::could not fetch ${ref}"; exit 0; }
  head="FETCH_HEAD"
fi

# The plan diffs against release tags and reads the build from the working tree, so it runs in a
# worktree of the PR head with every tag present.
git fetch --quiet --tags origin 2>/dev/null || true
git worktree add --quiet --detach "${work}/tree" "${head}" || { echo "::warning::could not check out ${head}"; exit 0; }
sha="$(git -C "${work}/tree" rev-parse --short HEAD)"
(cd "${work}/tree" && "${here}/maven-publish-plan.sh" --head HEAD > "${work}/plan.out" 2> "${work}/plan.err")
status=$?
git worktree remove --force "${work}/tree" 2>/dev/null || true

mapfile -t modules < <(grep -v '^\s*$' "${work}/plan.out")
count="${#modules[@]}"
if [[ "${status}" -ne 0 ]]; then
  summary="The publish plan could not run on \`${sha}\` (exit ${status}); the release will compute it again."
elif grep -q 'publishing every module' "${work}/plan.err"; then
  summary="**All ${count} modules** publish, plus the BOM: a shared build input changed."
elif total="$(grep -oE '[0-9]+ of [0-9]+ modules publish' "${work}/plan.err" | grep -oE 'of [0-9]+' | grep -oE '[0-9]+')"; [[ -n "${total}" ]]; then
  if [[ "${count}" -eq 0 ]]; then
    summary="**Nothing** publishes to Maven Central: no module changed since the version it is published at."
  else
    summary="**${count} of ${total} modules** publish, plus the BOM."
  fi
else
  summary="**${count} modules** publish, plus the BOM."
fi

# Why, in the plan's own words: the lines that decide the set, not its whole log.
reasons="$(grep -E 'shared build input|publishing every module|catalog entries changed|uses a changed catalog entry|never published|could not|not a shared input|changed only in comments' \
  "${work}/plan.err" | sed -E 's/^[[:space:]]+//' | sort -u | head -20)"

{
  echo "${MARKER}"
  echo "### Maven Central"
  echo
  echo "${summary}"
  echo
  if [[ "${count}" -gt 0 ]]; then
    echo "<details><summary>Modules (${count})</summary>"
    echo
    printf -- '- `%s`\n' "${modules[@]}"
    echo
    echo "</details>"
    echo
  fi
  if [[ -n "${reasons}" ]]; then
    echo "<details><summary>Why</summary>"
    echo
    echo '```'
    echo "${reasons}"
    echo '```'
    echo
    echo "</details>"
    echo
  fi
  echo "_From \`.github/scripts/maven-publish-plan.sh\` on \`${sha}\`, against the versions Maven Central serves now. The release recomputes it; this comment updates whenever the release PR does._"
} > "${work}/body.md"

if [[ "${DRY_RUN:-}" == 1 ]]; then
  cat "${work}/body.md"
  exit 0
fi

existing="$(gh api "repos/${REPO}/issues/${number}/comments?per_page=100" \
  --jq "[.[] | select(.body | startswith(\"${MARKER}\"))][0].id // empty" 2>/dev/null)"
if [[ -n "${existing}" ]]; then
  jq -n --rawfile body "${work}/body.md" '{body: $body}' |
    gh api --method PATCH "repos/${REPO}/issues/comments/${existing}" --input - > /dev/null &&
    echo "updated the publish-plan comment on #${number}"
else
  jq -n --rawfile body "${work}/body.md" '{body: $body}' |
    gh api --method POST "repos/${REPO}/issues/${number}/comments" --input - > /dev/null &&
    echo "posted the publish-plan comment on #${number}"
fi
exit 0
