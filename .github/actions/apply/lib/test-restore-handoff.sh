#!/usr/bin/env bash
# Runs the publish phase's "Restore fork-safe handoff" step body, extracted from action.yml, and
# then the PR render push the way the push step does. The handoff is produced by a job that ran
# fork code, so a `.git` planted in a staged dir must never reach push-branch.sh: its `git init`
# keeps an existing config, and `git add -A` would run its `core.fsmonitor` with the token.
set -euo pipefail

ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
LIB=$(cd "$(dirname "$0")" && pwd)
ACTION="$LIB/../action.yml"

# The step's `run: |` block, de-indented.
awk '
  /^    - name: Restore fork-safe handoff$/ { in_step = 1; next }
  in_step && /^    - name:/ { exit }
  in_step && /^      run: \|$/ { in_run = 1; next }
  in_run { sub(/^        /, ""); print }
' "$ACTION" > "$ROOT/restore.sh"
grep -q 'cp -a "\$entry"' "$ROOT/restore.sh" || { echo "FAIL: could not extract the restore step" >&2; exit 1; }

REMOTE="$ROOT/remote.git"
git init -q --bare "$REMOTE"

# Builds a handoff.tar the way "Stage fork-safe handoff" does, restores it into a fresh
# workspace, and pushes `_pr_renders`. Returns 10 if the restore step refused the handoff,
# otherwise the push's exit status.
run_case() {
  name="$1"; shift
  src="$ROOT/$name-src"; ws="$ROOT/$name-ws"; handoff="$ws/_handoff"
  mkdir -p "$src/_pr_renders/renders" "$handoff"
  printf 'png\n' > "$src/_pr_renders/renders/Foo.png"
  printf '1\n' > "$src/_pr_number"
  "$@" "$src"
  tar -cf "$handoff/handoff.tar" -C "$src" _pr_renders _pr_number
  (
    cd "$ws"
    env -u PR_NUMBER -u RUN_HEAD_REPO -u AB_CONFIG GITHUB_WORKSPACE="$ws" HANDOFF_DIR="$handoff" \
      bash "$ROOT/restore.sh"
  ) > "$ROOT/$name.log" 2>&1 || return 10
  (
    cd "$ws/_pr_renders"
    rm -f _push_msg _push_branch _skip_if_unchanged
    env TARGET_BRANCH="compose-preview/$name" REPO=local/test GITHUB_TOKEN_INLINE=FAKE_TOKEN \
      MSG=test REMOTE_URL="$REMOTE" bash "$LIB/push-branch.sh"
  ) >> "$ROOT/$name.log" 2>&1
}

benign() { :; }

marker="$ROOT/fsmonitor-ran"
plant_git_dir() {
  git init -q "$1/_pr_renders"
  git -C "$1/_pr_renders" config core.fsmonitor "echo \"\$GITHUB_TOKEN_INLINE\" > '$marker'; false"
}
plant_nested_git_dir() {
  git init -q "$1/_pr_renders/renders"
  git -C "$1/_pr_renders/renders" config core.fsmonitor "echo \"\$GITHUB_TOKEN_INLINE\" > '$marker'; false"
}
plant_gitfile() {
  printf 'gitdir: %s\n' "$ROOT" > "$1/_pr_renders/.git"
}

# A clean handoff still restores and pushes.
run_case benign benign || { cat "$ROOT/benign.log" >&2; echo "FAIL: clean handoff did not restore and push" >&2; exit 1; }
git --git-dir="$REMOTE" cat-file -e compose-preview/benign:renders/Foo.png

failed=0
for plant in plant_git_dir plant_nested_git_dir plant_gitfile; do
  rm -f "$marker"
  status=0
  run_case "$plant" "$plant" || status=$?
  if [ "$status" != 10 ]; then
    echo "FAIL ($plant): restore accepted a handoff carrying a .git path" >&2
    failed=1
  fi
  if [ -e "$marker" ]; then
    echo "FAIL ($plant): planted git config ran during push, saw token '$(cat "$marker")'" >&2
    failed=1
  fi
done
# A staged pr-number with no head repository to bind it to (a deleted fork) is refused, as is
# one that only passes a line-by-line numeric check.
for bad in "1:" $'5\nx:local/fork'; do
  number="${bad%%:*}"; head_repo="${bad#*:}"
  ws="$ROOT/unbound-$RANDOM"; mkdir -p "$ws/_handoff"
  if ( cd "$ws" && env -u AB_CONFIG GITHUB_WORKSPACE="$ws" HANDOFF_DIR="$ws/_handoff" \
      PR_NUMBER="$number" RUN_HEAD_REPO="$head_repo" RUN_HEAD_BRANCH=feature GH_TOKEN=none \
      REPO=local/test bash "$ROOT/restore.sh" ) > "$ws.log" 2>&1; then
    echo "FAIL: restore accepted pr-number '$number' with head repository '$head_repo'" >&2
    failed=1
  fi
  grep -q 'pr-number' "$ws.log" || { cat "$ws.log" >&2; echo "FAIL: refused for another reason" >&2; failed=1; }
done

# push-branch.sh refuses a planted `.git` on its own, for callers that never pass through restore.
for plant in plant_git_dir plant_nested_git_dir plant_gitfile; do
  rm -f "$marker"
  direct="$ROOT/direct-$plant"
  mkdir -p "$direct/_pr_renders/renders"
  printf 'png\n' > "$direct/_pr_renders/renders/Foo.png"
  "$plant" "$direct"
  if (
    cd "$direct/_pr_renders"
    env TARGET_BRANCH="compose-preview/direct-$plant" REPO=local/test \
      GITHUB_TOKEN_INLINE=FAKE_TOKEN MSG=test REMOTE_URL="$REMOTE" bash "$LIB/push-branch.sh"
  ) > "$ROOT/direct-$plant.log" 2>&1; then
    echo "FAIL ($plant): push-branch.sh pushed a directory carrying a .git path" >&2
    failed=1
  fi
  if [ -e "$marker" ]; then
    echo "FAIL ($plant): planted git config ran in push-branch.sh, saw token '$(cat "$marker")'" >&2
    failed=1
  fi
done
[ "$failed" = 0 ] || exit 1
echo "restore-handoff tests passed"
