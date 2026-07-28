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
# `gh pr comment --body-file` and `gh api -F key=@file` both stream from
# the file instead of expanding it into the command line.

# The exact string a `-f body=@file` PATCH would have written for THIS
# invocation (see the `-F` note below). Captured before the truncation
# branch can repoint BODY_FILE at a mktemp path, so it names the caller's
# body file rather than a temp file no comment could ever contain.
PLACEHOLDER="@$(basename "$BODY_FILE")"

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

list_comments() {
  gh api "repos/${REPO}/issues/${PR_NUMBER}/comments" --paginate --jq "$1"
}

# Self-heal PRs still carrying a placeholder body from the `-f` bug. Those
# comments no longer start with MARKER, so the lookup below can never
# reclaim one — it posts a fresh comment beside the corpse, on every run,
# forever (issue #2869). Deleting the corpse is what converges an affected
# PR back to a single comment.
#
# The match is deliberately narrow: a bot-authored body that is *exactly*
# the placeholder this invocation would itself have produced. Each caller
# cleans up only its own pipeline's corpse (`@_a11y_comment.md` for the
# a11y run, `@_comment_body.md` for compose, …), so an unrelated
# integration that happens to post a bare `@reports/result.md` is never
# touched.
PLACEHOLDER_JQ=".[] | select(.user.type == \"Bot\") | select(.body == \"${PLACEHOLDER}\") | .id"
while IFS= read -r stale_id; do
  [ -n "$stale_id" ] || continue
  echo "post-comment: deleting orphaned '${PLACEHOLDER}' comment ${stale_id} (issue #2869)." >&2
  gh api "repos/${REPO}/issues/comments/${stale_id}" -X DELETE >/dev/null || true
done < <(list_comments "$PLACEHOLDER_JQ" || true)

MARKER_JQ=".[] | select(.body | startswith(\"${MARKER}\")) | .id"
COMMENT_ID=$(list_comments "$MARKER_JQ" | head -1)

if [ -n "$COMMENT_ID" ]; then
  # `-F`, not `-f`: only `--field` expands a leading `@` into the file's
  # contents. `--raw-field` sends the value verbatim, so `-f body=@file`
  # PATCHed the sticky comment to the literal string "@_comment_body.md",
  # wiping the rendered diff off every PR whose comment already existed
  # (issue #2868) — and with it the MARKER, which is what turned a garbled
  # comment into a fresh duplicate on every later run (issue #2869). The
  # first post is unaffected — it goes through `gh pr comment --body-file`
  # below.
  gh api "repos/${REPO}/issues/comments/${COMMENT_ID}" \
    -X PATCH -F "body=@${BODY_FILE}" >/dev/null
else
  gh pr comment "$PR_NUMBER" --body-file "$BODY_FILE"
fi

# The write above must leave a comment we can find again next run. If the
# marker isn't there, the body didn't land the way we think it did and the
# very next run will post a duplicate — fail the step now, while the cause
# is still on screen, rather than letting the PR accumulate comments
# quietly.
if ! list_comments "$MARKER_JQ" | head -1 | grep -q '[0-9]'; then
  echo "::error::post-comment: no comment starting with '${MARKER}' after writing it — the body did not land intact (see issue #2869). Refusing to leave the sticky comment unrecoverable." >&2
  exit 1
fi
