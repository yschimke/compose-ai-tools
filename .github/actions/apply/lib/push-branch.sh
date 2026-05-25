#!/usr/bin/env bash
# Shared retry-on-race git push helper. Pushes the contents of the
# current working directory (cwd) as a new commit on $TARGET_BRANCH of
# the remote, appended on top of the existing tip (orphan commit if the
# branch doesn't exist yet).
#
# Inputs (env):
#   TARGET_BRANCH        — destination branch on the remote.
#   REPO                 — `org/name`.
#   GITHUB_TOKEN_INLINE  — token used in the URL.
#   MSG                  — commit message.
#   SKIP_IF_UNCHANGED    — "1" to skip push silently when the tree
#                          matches the parent (used on baseline pushes).
#   SHA_OUTPUT_FILE      — path (absolute) to write the pushed commit
#                          SHA to. Empty = don't write.
#   MAX_ATTEMPTS         — push race retry budget. Default 5.
#
# Exits 0 on success / skip, non-zero on push failure.
set -euo pipefail

: "${TARGET_BRANCH:?TARGET_BRANCH required}"
: "${REPO:?REPO required}"
: "${GITHUB_TOKEN_INLINE:?GITHUB_TOKEN_INLINE required}"
: "${MSG:?MSG required}"
SKIP_IF_UNCHANGED="${SKIP_IF_UNCHANGED:-0}"
SHA_OUTPUT_FILE="${SHA_OUTPUT_FILE:-}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"

git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git remote add origin \
  "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git"

git add -A
TREE=$(git write-tree)

# Retry loop handles the push race when two PRs finish concurrently
# against the same shared branch. Per-PR concurrency in the workflow
# serialises a single PR's pushes, but different PRs push to the same
# shared branch and second-to-push hits "non-fast-forward". Re-fetch
# tip, re-parent, re-push.
attempt=1
while :; do
  PARENT=""
  if git fetch --depth=1 --quiet origin "$TARGET_BRANCH" 2>/dev/null; then
    PARENT=$(git rev-parse FETCH_HEAD)
    PARENT_TREE=$(git rev-parse "${PARENT}^{tree}")
    if [ "$SKIP_IF_UNCHANGED" = "1" ] && [ "$TREE" = "$PARENT_TREE" ]; then
      echo "Tree unchanged vs ${TARGET_BRANCH}; skipping push."
      if [ -n "$SHA_OUTPUT_FILE" ]; then
        : > "$SHA_OUTPUT_FILE"
      fi
      exit 0
    fi
  fi

  if [ -n "$PARENT" ]; then
    COMMIT=$(git commit-tree "$TREE" -p "$PARENT" -m "$MSG")
  else
    # First push ever for this branch — orphan commit.
    COMMIT=$(git commit-tree "$TREE" -m "$MSG")
  fi

  if git push --quiet origin "${COMMIT}:refs/heads/${TARGET_BRANCH}" 2>&1; then
    if [ -n "$SHA_OUTPUT_FILE" ]; then
      echo "$COMMIT" > "$SHA_OUTPUT_FILE"
    fi
    exit 0
  fi

  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "Push to ${TARGET_BRANCH} failed after ${attempt} attempts." >&2
    exit 1
  fi
  delay=$((attempt * 2 + RANDOM % 3))
  echo "Push to ${TARGET_BRANCH} lost the race; retry ${attempt}/${MAX_ATTEMPTS} in ${delay}s…" >&2
  attempt=$((attempt + 1))
  sleep "$delay"
done
