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

# GitHub's comment API caps a single body at 65,536 characters ("Body is
# too long"). Streaming from a file dodges ARG_MAX but *not* this ceiling,
# so truncate an over-long body here — defence in depth for every comment
# the action posts (e.g. a large module's a11y report). The budget is in
# bytes; a UTF-8 string's byte length is >= its character count, so keeping
# under the byte budget guarantees we're under the character cap, with
# headroom for the truncation notice. We keep the head of the body (the
# marker + summary live at the top) and cut on a line boundary.
MAX_BYTES="${COMMENT_MAX_BYTES:-60000}"
BODY_BYTES=$(wc -c < "$BODY_FILE")
if [ "$BODY_BYTES" -gt "$MAX_BYTES" ]; then
  NOTICE=$'\n\n---\n> ⚠️ This comment was truncated because it exceeded GitHub'\''s 65,536-character limit. See the full report in the render branch / workflow artifacts.'
  NOTICE_BYTES=$(printf '%s' "$NOTICE" | wc -c)
  BUDGET=$((MAX_BYTES - NOTICE_BYTES))
  TRUNCATED=$(mktemp)
  # Cut to the byte budget, then drop the final (possibly partial) line so
  # we always end on a clean line boundary and never leave a half-written
  # multi-byte character.
  head -c "$BUDGET" "$BODY_FILE" | sed '$d' > "$TRUNCATED"
  printf '%s\n' "$NOTICE" >> "$TRUNCATED"
  echo "post-comment: body was ${BODY_BYTES} bytes; truncated to fit GitHub's 65,536-char comment limit." >&2
  BODY_FILE="$TRUNCATED"
fi

COMMENT_ID=$(gh api \
  "repos/${REPO}/issues/${PR_NUMBER}/comments" \
  --paginate \
  --jq ".[] | select(.body | startswith(\"${MARKER}\")) | .id" \
  | head -1)

if [ -n "$COMMENT_ID" ]; then
  # `-F`, not `-f`: only `--field` expands a leading `@` into the file's
  # contents. `--raw-field` sends the value verbatim, so `-f body=@file`
  # PATCHed the sticky comment to the literal string "@_comment_body.md",
  # wiping the rendered diff off every PR whose comment already existed
  # (issue #2868). The first post is unaffected — it goes through
  # `gh pr comment --body-file` below.
  gh api "repos/${REPO}/issues/comments/${COMMENT_ID}" \
    -X PATCH -F "body=@${BODY_FILE}"
else
  gh pr comment "$PR_NUMBER" --body-file "$BODY_FILE"
fi
