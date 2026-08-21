#!/usr/bin/env bash
# Regenerates a delivery branch's history.json and pushes it as its own commit.
#
# Extracted from the `apply` action's baseline step so the design-artifact catalog branches can
# publish a timeline too — they had none, which is why `<cp-history-menu>` drew nothing on
# preview.coo.ee: the viewer fetched `design-artifacts/<system>/history.json` and got a 404. Shared
# rather than copied for the same reason push-branch.sh is: two divergent copies of a push-race
# retry loop is how one of them quietly stops working.
#
# Inputs (env):
#   TARGET_BRANCH        — delivery branch to read and push to.
#   REPO                 — `org/name`.
#   GITHUB_TOKEN_INLINE  — token used in the URL.
#   OUTPUT               — absolute path to write history.json to before committing it.
#   LAYOUT               — `renders` (baseline branches) or `images` (design catalogs). Passed
#                          through to the CLI, which decides the pathspec and how render paths are
#                          keyed to preview ids.
#   BASELINES            — absolute path to the baselines.json to join against. Required by the
#                          `renders` layout, meaningless to `images`, which derives its ids.
#   MSG                  — commit subject.
#   WORK_DIR             — scratch clone directory. Default `_history_repo`.
#   MAX_ATTEMPTS         — push race retry budget. Default 5.
#   REMOTE_URL           — test seam; defaults to the authenticated GitHub repository URL.
#
# Exits 0 on success AND on every skip. Skipping is the designed behaviour, not leniency: by the
# time this runs the renders are already published, so failing here would turn a good publish into
# a red run over a file the branch can simply refresh next time.
set -euo pipefail

: "${TARGET_BRANCH:?TARGET_BRANCH required}"
: "${REPO:?REPO required}"
: "${GITHUB_TOKEN_INLINE:?GITHUB_TOKEN_INLINE required}"
: "${OUTPUT:?OUTPUT required}"
: "${MSG:?MSG required}"
LAYOUT="${LAYOUT:-renders}"
BASELINES="${BASELINES:-}"
WORK_DIR="${WORK_DIR:-_history_repo}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"
# `generate` resolves WORK_DIR relative to here, and the push loop cd's into it.
START_DIR=$(pwd)

if [ "$LAYOUT" = "renders" ] && [ ! -f "$BASELINES" ]; then
  echo "history manifest: no baselines.json at '${BASELINES}', skipping."
  exit 0
fi

# The CLI may be absent (`cli-version: none`) or predate a subcommand — `cli-version: auto`, the
# default, installs the release matching the consumer's pinned plugin, which will not know
# `history-manifest`, or its `--layout` flag, until one ships. Probe for the capability instead of
# parsing versions, and skip rather than fail.
if ! command -v compose-preview >/dev/null 2>&1; then
  echo "::notice::history manifest: no compose-preview on PATH (cli-version: none?); skipping history.json."
  exit 0
fi
if ! compose-preview inspect history-manifest --help >/dev/null 2>&1; then
  echo "::notice::history manifest: installed CLI has no 'inspect history-manifest'; skipping history.json. Upgrade the CLI to publish render history."
  exit 0
fi
# `--layout` is newer than the subcommand itself, so an older CLI would take `images` as an
# unrecognised flag and silently emit a `renders`-layout manifest — empty for a design catalog, and
# published over a good one. Probe separately.
if [ "$LAYOUT" != "renders" ] && \
    ! compose-preview inspect history-manifest --help 2>/dev/null | grep -q -- "--layout"; then
  echo "::notice::history manifest: installed CLI has no '--layout'; skipping history.json for the ${LAYOUT} layout."
  exit 0
fi

# A caller's checkout is typically --depth=1, and a shallow log would silently truncate every
# timeline. Fetch the branch's history into a scratch repo instead. --filter=blob:none is exact
# rather than merely cheap: the extractor reads blob SHAs out of tree objects via `git log --raw`
# and never opens a render, so no image content is needed.
rm -rf "$WORK_DIR"
git init -q "$WORK_DIR"
# commit-tree refuses to run without a committer identity, and a freshly `git init`ed repo has
# none. Without it the manifest generates fine and then the commit dies with exit 128, so the
# branch still advances with renders and only history.json is silently lost.
git -C "$WORK_DIR" config user.name "${GIT_COMMITTER_NAME:-github-actions[bot]}"
git -C "$WORK_DIR" config user.email \
  "${GIT_COMMITTER_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
git -C "$WORK_DIR" remote add origin \
  "${REMOTE_URL:-https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git}"
if ! git -C "$WORK_DIR" fetch --quiet --filter=blob:none origin "$TARGET_BRANCH"; then
  echo "::warning::history manifest: could not fetch ${TARGET_BRANCH}; skipping history.json this run."
  exit 0
fi

# Join against the baselines.json being published, not the one on the branch, so the manifest and
# its keys are always the same snapshot. The images layout passes none and derives its keys.
generate() {
  set -- --repo "$WORK_DIR" --branch FETCH_HEAD --layout "$LAYOUT" --output "$OUTPUT"
  [ -n "$BASELINES" ] && [ "$LAYOUT" = "renders" ] && set -- "$@" --baselines "$BASELINES"
  compose-preview inspect history-manifest "$@"
}

# Second commit, following the render push rather than riding along with it: computed beforehand,
# the manifest could not describe the very publish it ships with, so the newest render would be
# missing from its own timeline. The extractor filters on the render pathspec, so this commit is
# invisible to the next run's history and cannot pollute it.
#
# Built as a genuine delta — the tip's tree with one path replaced — NOT via push-branch.sh. That
# helper snapshots a directory into a fixed tree and re-parents it onto whatever the current tip
# is, so pushing a staging dir that was staged earlier silently reverts anything published in
# between: a concurrent run's renders would be overwritten by this run's older copies. Touching
# only history.json makes that impossible regardless of how the runs interleave.
#
# read-tree/write-tree work in the blob:none clone because they only need tree objects and the one
# new blob; the renders' contents are never fetched.
attempt=1
while :; do
  # Generated INSIDE the loop, against whatever tip was just fetched. A lost race means someone
  # else published between our fetch and our push, so a manifest built before the loop no longer
  # describes the branch — re-parenting it would overwrite the winner's history.json with one that
  # omits their image changes, leaving the timeline stale until some later publish happened to fix
  # it. Regenerating costs one `git log --raw` over an already-fetched repo.
  if ! generate; then
    echo "::warning::history manifest: generation failed for ${TARGET_BRANCH}; skipping history.json this run."
    exit 0
  fi
  cd "$WORK_DIR"
  PARENT=$(git rev-parse FETCH_HEAD)
  NEW_BLOB=$(git hash-object -w "$OUTPUT")
  OLD_BLOB=$(git rev-parse --quiet --verify "FETCH_HEAD:history.json" 2>/dev/null || true)
  if [ "$NEW_BLOB" = "$OLD_BLOB" ]; then
    echo "history manifest: history.json unchanged on ${TARGET_BRANCH}; nothing to push."
    break
  fi
  git read-tree "$PARENT"
  git update-index --add --cacheinfo "100644,${NEW_BLOB},history.json"
  TREE=$(git write-tree)
  COMMIT=$(git commit-tree "$TREE" -p "$PARENT" -m "$MSG")
  if git push --quiet origin "${COMMIT}:refs/heads/${TARGET_BRANCH}" 2>&1; then
    echo "history manifest: published ${COMMIT} to ${TARGET_BRANCH}."
    break
  fi
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "::warning::history manifest: lost the push race ${attempt}x; skipping history.json this run."
    break
  fi
  echo "history manifest: lost the push race; retry ${attempt}/${MAX_ATTEMPTS}."
  attempt=$((attempt + 1))
  sleep $((attempt * 2))
  # Re-fetch so the retry re-parents onto — and regenerates against — the new tip.
  git fetch --quiet --filter=blob:none origin "$TARGET_BRANCH" || true
  cd "$START_DIR"
done
