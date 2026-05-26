#!/usr/bin/env bash
# Upsert a sticky PR comment identified by an HTML marker at the start
# of the body. Used by all four pipelines that post sticky diffs.
#
# Inputs (env):
#   REPO       — `org/name`.
#   PR_NUMBER  — PR number to comment on.
#   MARKER     — exact string that must appear at the start of the
#                comment body (e.g. `<!-- preview-diff -->`).
#   BODY_FILE  — file holding the new comment body. Required.
#   GH_TOKEN   — gh CLI token (must be set by caller).
#
# Exits 0 on success. Caller decides whether to invoke (e.g. only on
# non-empty body) — this script always posts/PATCHes.
set -euo pipefail

: "${REPO:?REPO required}"
: "${PR_NUMBER:?PR_NUMBER required}"
: "${MARKER:?MARKER required}"
: "${BODY_FILE:?BODY_FILE required}"
: "${GH_TOKEN:?GH_TOKEN required}"

# Pass the body via file rather than argv — preview/a11y diffs can exceed
# ARG_MAX (~128KB on Linux), which fails with "Argument list too long".
# `gh pr comment --body-file` and `gh api -f key=@file` both stream from
# the file instead of expanding it into the command line.

COMMENT_ID=$(gh api \
  "repos/${REPO}/issues/${PR_NUMBER}/comments" \
  --paginate \
  --jq ".[] | select(.body | startswith(\"${MARKER}\")) | .id" \
  | head -1)

if [ -n "$COMMENT_ID" ]; then
  gh api "repos/${REPO}/issues/comments/${COMMENT_ID}" \
    -X PATCH -f "body=@${BODY_FILE}"
else
  gh pr comment "$PR_NUMBER" --body-file "$BODY_FILE"
fi
