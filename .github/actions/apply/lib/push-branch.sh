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
#   CARRY_FORWARD_PATHS  — whitespace-separated paths the fetched tip owns. On every retry their
#                          tip versions replace the working tree versions (render publisher mode).
#   DELTA_ON_TIP_PATHS   — whitespace-separated paths this invocation owns. On every retry start
#                          from the fetched tip and replace only these paths (index publisher mode).
#   REMOTE_URL           — test seam; defaults to the authenticated GitHub repository URL.
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
CARRY_FORWARD_PATHS="${CARRY_FORWARD_PATHS:-}"
DELTA_ON_TIP_PATHS="${DELTA_ON_TIP_PATHS:-}"

if [ -n "$CARRY_FORWARD_PATHS" ] && [ -n "$DELTA_ON_TIP_PATHS" ]; then
  echo "CARRY_FORWARD_PATHS and DELTA_ON_TIP_PATHS are mutually exclusive." >&2
  exit 2
fi

validate_owned_paths() {
  for path in $1; do
    case "$path" in
      /*|../*|*/../*|*/..) echo "Unsafe owned path: $path" >&2; exit 2 ;;
    esac
  done
}
validate_owned_paths "$CARRY_FORWARD_PATHS"
validate_owned_paths "$DELTA_ON_TIP_PATHS"

git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
REMOTE_URL="${REMOTE_URL:-https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git}"
git remote add origin "$REMOTE_URL"

git add -A
TREE=$(git write-tree)

# Build the race winner's candidate tree without merging unrelated working-directory content.
# `git read-tree` resets only the index; the caller's files remain available for diagnostics.
tree_for_parent() {
  parent="$1"
  if [ -n "$DELTA_ON_TIP_PATHS" ] && [ -n "$parent" ]; then
    git read-tree "$parent"
    for path in $DELTA_ON_TIP_PATHS; do
      if git cat-file -e "${TREE}:${path}" 2>/dev/null; then
        git restore --source="$TREE" --staged -- "$path"
      else
        git rm -q --cached --ignore-unmatch -- "$path"
      fi
    done
    git write-tree
    return
  fi
  if [ -n "$CARRY_FORWARD_PATHS" ] && [ -n "$parent" ]; then
    git read-tree "$TREE"
    for path in $CARRY_FORWARD_PATHS; do
      if git cat-file -e "${parent}:${path}" 2>/dev/null; then
        git restore --source="$parent" --staged -- "$path"
      else
        git rm -q --cached --ignore-unmatch -- "$path"
      fi
    done
    git write-tree
    return
  fi
  echo "$TREE"
}

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
  fi
  CANDIDATE_TREE=$(tree_for_parent "$PARENT")
  if [ -n "$PARENT" ]; then
    if [ "$SKIP_IF_UNCHANGED" = "1" ] && [ "$CANDIDATE_TREE" = "$PARENT_TREE" ]; then
      echo "Tree unchanged vs ${TARGET_BRANCH}; skipping push."
      if [ -n "$SHA_OUTPUT_FILE" ]; then
        : > "$SHA_OUTPUT_FILE"
      fi
      exit 0
    fi
  fi

  if [ -n "$PARENT" ]; then
    COMMIT=$(git commit-tree "$CANDIDATE_TREE" -p "$PARENT" -m "$MSG")
  else
    # First push ever for this branch — orphan commit.
    COMMIT=$(git commit-tree "$CANDIDATE_TREE" -m "$MSG")
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
