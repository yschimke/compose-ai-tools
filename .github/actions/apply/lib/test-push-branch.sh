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

# A catalog publish carries the prior index forward and promotes its `current` inventory under the
# actual parent SHA. The staging directory intentionally has no preview-index.json: the helper owns
# that generated branch metadata.
indexed="$ROOT/indexed"
mkdir -p "$indexed"
printf '%s\n' '{"components":[{"images":[{"path":"images/old/ideal.png"}]}]}' > "$indexed/catalog.json"
(
  cd "$indexed"
  env TARGET_BRANCH=design-artifacts/index REPO=local/test GITHUB_TOKEN_INLINE=test MSG=first \
    REMOTE_URL="$REMOTE" REVISION_PREVIEW_INDEX=1 "$HELPER"
)
first=$(git --git-dir="$REMOTE" rev-parse refs/heads/design-artifacts/index)
indexed2="$ROOT/indexed2"
mkdir -p "$indexed2"
printf '%s\n' '{"components":[{"images":[{"path":"images/new/ideal.png"}]}]}' > "$indexed2/catalog.json"
(
  cd "$indexed2"
  env TARGET_BRANCH=design-artifacts/index REPO=local/test GITHUB_TOKEN_INLINE=test MSG=second \
    REMOTE_URL="$REMOTE" REVISION_PREVIEW_INDEX=1 "$HELPER"
)
index_check="$ROOT/index-check"
git clone -q --branch design-artifacts/index "$REMOTE" "$index_check"
node -e '
  const fs = require("fs");
  const index = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (index.current.join() !== "new__ideal") throw new Error("new current missing");
  const prior = index.revisions.find((entry) => entry.commit === process.argv[2]);
  if (!prior || prior.previews.join() !== "old__ideal") throw new Error("prior current not promoted");
' "$index_check/preview-index.json" "$first"

indexed3="$ROOT/indexed3"
mkdir -p "$indexed3"
cp "$indexed2/catalog.json" "$indexed3/catalog.json"
before=$(git --git-dir="$REMOTE" rev-parse refs/heads/design-artifacts/index)
(
  cd "$indexed3"
  env TARGET_BRANCH=design-artifacts/index REPO=local/test GITHUB_TOKEN_INLINE=test MSG=unchanged \
    REMOTE_URL="$REMOTE" REVISION_PREVIEW_INDEX=1 SKIP_IF_UNCHANGED=1 "$HELPER"
)
after=$(git --git-dir="$REMOTE" rev-parse refs/heads/design-artifacts/index)
test "$before" = "$after"

echo "push-branch race tests passed"
