#!/usr/bin/env bash
set -euo pipefail

ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
HELPER=$(cd "$(dirname "$0")" && pwd)/push-branch.sh
REMOTE="$ROOT/remote.git"
git init -q --bare "$REMOTE"

publish() {
  work="$1"
  mode="$2"
  (
    cd "$work"
    env TARGET_BRANCH=design-artifacts/m3 REPO=local/test GITHUB_TOKEN_INLINE=test \
      MSG=test REMOTE_URL="$REMOTE" "$mode"=parity/issues.json "$HELPER"
  )
}

seed="$ROOT/seed"
mkdir -p "$seed/parity"
printf 'render-v1\n' > "$seed/render.txt"
printf 'issues-old\n' > "$seed/parity/issues.json"
publish "$seed" DELTA_ON_TIP_PATHS

assert_tip() {
  expected_render="$1"
  expected_issues="$2"
  check="$ROOT/check-$RANDOM"
  git clone -q --branch design-artifacts/m3 "$REMOTE" "$check"
  test "$(cat "$check/render.txt")" = "$expected_render"
  test "$(cat "$check/parity/issues.json")" = "$expected_issues"
}

prepare() {
  name="$1"
  dir="$ROOT/$name"
  git clone -q --branch design-artifacts/m3 "$REMOTE" "$dir"
  git -C "$dir" remote remove origin
  printf '%s\n' "$dir"
}

# Index lands first; the stale render publisher retries and carries the newer index forward.
render=$(prepare render-first-order)
index=$(prepare index-first-order)
printf 'render-v2\n' > "$render/render.txt"
printf 'issues-new\n' > "$index/parity/issues.json"
publish "$index" DELTA_ON_TIP_PATHS
publish "$render" CARRY_FORWARD_PATHS
assert_tip render-v2 issues-new

# Reverse the ordering: the stale index publisher changes only its owned path on the new tip.
render=$(prepare render-second-order)
index=$(prepare index-second-order)
printf 'render-v3\n' > "$render/render.txt"
printf 'issues-newer\n' > "$index/parity/issues.json"
publish "$render" CARRY_FORWARD_PATHS
publish "$index" DELTA_ON_TIP_PATHS
assert_tip render-v3 issues-newer

echo "push-branch race tests passed"
