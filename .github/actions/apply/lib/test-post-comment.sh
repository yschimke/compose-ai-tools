#!/usr/bin/env bash
# Self-test for `post-comment.sh`, the sticky-comment upsert every preview
# pipeline posts through.
#
# The regression it guards (issue #2868): the PATCH branch passed the body as
# `gh api -f body=@FILE`. `--raw-field` sends its value verbatim, so the
# sticky comment was rewritten to the literal string "@_comment_body.md" —
# silently destroying the rendered preview diff on every PR whose comment
# already existed. Only `--field` (`-F`) expands a leading `@` into the file's
# contents. The fake `gh` below models exactly that distinction, so this test
# fails if the flag ever regresses.
#
# Pure bash + a stub `gh` on PATH; no network, no gh CLI required.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
UNDER_TEST="$SCRIPT_DIR/post-comment.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAILURES=0

fail() {
  echo "FAIL: $*" >&2
  FAILURES=$((FAILURES + 1))
}

pass() {
  echo "ok - $*"
}

# --- stub gh -----------------------------------------------------------------
# Models the three calls post-comment.sh makes:
#   gh api repos/O/R/issues/N/comments --paginate --jq ...   → prints $GH_EXISTING_ID
#   gh api repos/O/R/issues/comments/ID -X PATCH -F body=... → records the body
#   gh pr comment N --body-file FILE                         → records the body
# `-F name=@file` reads the file; `-f name=@file` does not. That asymmetry is
# the whole point of the fixture.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

record() { printf '%s' "$1" > "$GH_RECORDED_BODY"; }

case "${1:-}" in
  api)
    shift
    # Listing call: `--jq` present and no `-X PATCH`.
    if [[ " $* " == *" --paginate "* ]]; then
      printf '%s\n' "${GH_EXISTING_ID:-}"
      exit 0
    fi
    echo "PATCH" > "$GH_RECORDED_METHOD"
    while [ $# -gt 0 ]; do
      case "$1" in
        -F)
          value="${2#*=}"
          # --field: a leading @ means "read from this file".
          if [ "${value:0:1}" = "@" ]; then
            record "$(cat "${value:1}")"
          else
            record "$value"
          fi
          shift 2
          ;;
        -f)
          # --raw-field: verbatim, @ and all. This is the bug shape.
          record "${2#*=}"
          shift 2
          ;;
        *) shift ;;
      esac
    done
    ;;
  pr)
    shift # pr
    shift # comment
    echo "POST" > "$GH_RECORDED_METHOD"
    while [ $# -gt 0 ]; do
      case "$1" in
        --body-file) record "$(cat "$2")"; shift 2 ;;
        --body) record "$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    ;;
  *)
    echo "stub gh: unexpected command: $*" >&2
    exit 64
    ;;
esac
STUB
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH"

export REPO="acme/widgets"
export PR_NUMBER="42"
export MARKER="<!-- preview-diff -->"
export GH_TOKEN="stub-token"
export GH_RECORDED_BODY="$WORK/body.out"
export GH_RECORDED_METHOD="$WORK/method.out"

run_under_test() {
  : > "$GH_RECORDED_BODY"
  : > "$GH_RECORDED_METHOD"
  bash "$UNDER_TEST" > /dev/null
}

# --- 1. updating an existing comment sends the file's contents ---------------
BODY_FILE="$WORK/comment_body.md"
export BODY_FILE
{
  echo "$MARKER"
  echo "## Preview Diff"
  echo
  echo "| Preview | Before | After |"
  echo "| --- | --- | --- |"
  echo "| LottieSpin | ![](a.png) | ![](b.png) |"
} > "$BODY_FILE"

GH_EXISTING_ID="991122" run_under_test
if [ "$(cat "$GH_RECORDED_METHOD")" != "PATCH" ]; then
  fail "an existing sticky comment should be PATCHed, got $(cat "$GH_RECORDED_METHOD")"
elif [ "$(cat "$GH_RECORDED_BODY")" != "$(cat "$BODY_FILE")" ]; then
  fail "PATCH body should be the file's contents, got: $(cat "$GH_RECORDED_BODY")"
elif [ "$(cat "$GH_RECORDED_BODY")" = "@$BODY_FILE" ]; then
  fail "PATCH body is the literal @path — the -f/-F regression is back"
else
  pass "PATCH sends the body file's contents, not its path"
fi

# --- 2. a first-time comment posts the same contents -------------------------
GH_EXISTING_ID="" run_under_test
if [ "$(cat "$GH_RECORDED_METHOD")" != "POST" ]; then
  fail "with no existing comment a fresh one should be posted"
elif [ "$(cat "$GH_RECORDED_BODY")" != "$(cat "$BODY_FILE")" ]; then
  fail "POST body should be the file's contents, got: $(cat "$GH_RECORDED_BODY")"
else
  pass "POST sends the body file's contents"
fi

# --- 3. an over-long body is truncated, still by file ------------------------
BIG_FILE="$WORK/big_body.md"
export BODY_FILE="$BIG_FILE"
{
  echo "$MARKER"
  for _ in $(seq 1 4000); do
    echo "a padding line that exists purely to blow past the byte budget......"
  done
} > "$BIG_FILE"

COMMENT_MAX_BYTES=5000 GH_EXISTING_ID="991122" run_under_test
sent_bytes=$(wc -c < "$GH_RECORDED_BODY")
if [ "$sent_bytes" -ge 5000 ]; then
  fail "over-long body should be truncated under the budget, sent $sent_bytes bytes"
elif ! head -1 "$GH_RECORDED_BODY" | grep -qF "$MARKER"; then
  fail "truncated body should keep the marker on the first line"
elif ! grep -qF "truncated" "$GH_RECORDED_BODY"; then
  fail "truncated body should carry the truncation notice"
else
  pass "over-long body is truncated, keeps its marker, and is still sent by file"
fi

if [ "$FAILURES" -ne 0 ]; then
  echo "post-comment self-test: $FAILURES failure(s)" >&2
  exit 1
fi
echo "post-comment self-test: all checks passed"
