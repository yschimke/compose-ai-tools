#!/usr/bin/env bash
# Self-test for push-history.sh, over a real local remote and a stub `compose-preview`.
#
# The helper's whole job is to leave a good branch alone when it cannot do better, so most of what
# is worth pinning here is the SKIP paths: a CLI that is missing, too old, or fails must never turn
# an already-published render set into a red run or a clobbered manifest.
set -euo pipefail

ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
HELPER=$(cd "$(dirname "$0")" && pwd)/push-history.sh
REMOTE="$ROOT/remote.git"
BRANCH=design-artifacts/m3

git init -q --bare "$REMOTE"

# Seed the delivery branch with one render commit, as a catalog publish would leave it.
seed="$ROOT/seed"
git init -q "$seed"
git -C "$seed" config user.email t@example.com
git -C "$seed" config user.name T
mkdir -p "$seed/images/button"
printf 'v1\n' > "$seed/images/button/ideal__default.png"
git -C "$seed" add -A
git -C "$seed" commit -q -m 'regenerate m3 catalog (2026-08-21, aaaaaaaa)'
git -C "$seed" push -q "$REMOTE" "HEAD:refs/heads/$BRANCH"

# A stub CLI on PATH. $STUB_MODE picks which of the helper's probes it satisfies.
BIN="$ROOT/bin"
mkdir -p "$BIN"
cat > "$BIN/compose-preview" <<'STUB'
#!/usr/bin/env bash
case "${STUB_MODE:-full}" in
  missing-subcommand) [ "${3:-}" = "--help" ] && exit 1 ;;
  no-layout) [ "${3:-}" = "--help" ] && { echo "  --branch REF"; exit 0; } ;;
  failing) [ "${3:-}" = "--help" ] && { echo "  --layout NAME"; exit 0; } ; exit 3 ;;
  # A release predating --layout: its help does not list the flag, and here it is made outright
  # fatal so passing it to such a CLI can never look like success.
  rejects-layout)
    [ "${3:-}" = "--help" ] && { echo "  --branch REF"; exit 0; }
    for a in "$@"; do [ "$a" = "--layout" ] && { echo "unknown option --layout" >&2; exit 64; }; done
    ;;
  *) [ "${3:-}" = "--help" ] && { echo "  --layout NAME"; exit 0; } ;;
esac
# Write whatever the caller asked for, so the push path is exercised for real.
out=""
while [ $# -gt 0 ]; do
  [ "$1" = "--output" ] && out="$2"
  shift
done
printf '%s\n' "${STUB_MANIFEST:-{\"previews\":{}\}}" > "$out"
STUB
chmod +x "$BIN/compose-preview"

run_helper() {
  ( cd "$ROOT" && env PATH="$BIN:$PATH" \
      TARGET_BRANCH="$BRANCH" REPO=local/test GITHUB_TOKEN_INLINE=test \
      REMOTE_URL="$REMOTE" OUTPUT="$ROOT/history.json" LAYOUT=images \
      MSG='update history' WORK_DIR="$ROOT/scratch" "$@" bash "$HELPER" )
}

tip_has_history() {
  check="$ROOT/check-$RANDOM"
  git clone -q --branch "$BRANCH" "$REMOTE" "$check"
  test -f "$check/history.json"
}

tip_commits() {
  check="$ROOT/count-$RANDOM"
  git clone -q --branch "$BRANCH" "$REMOTE" "$check"
  git -C "$check" rev-list --count HEAD
}

# 1. The happy path publishes history.json as its own commit.
before=$(tip_commits)
STUB_MANIFEST='{"previews":{"button__ideal__default":{}}}' run_helper STUB_MODE=full >/dev/null
tip_has_history || { echo "FAIL: history.json was not published" >&2; exit 1; }
test "$(tip_commits)" -eq "$((before + 1))" || {
  echo "FAIL: expected exactly one new commit" >&2; exit 1; }

# 2. Regenerating an identical manifest pushes nothing. Without this every publish would append a
#    history commit forever, which is precisely what anchoring generatedFrom to the render tip is
#    for on the generating side.
steady=$(tip_commits)
STUB_MANIFEST='{"previews":{"button__ideal__default":{}}}' run_helper STUB_MODE=full >/dev/null
test "$(tip_commits)" -eq "$steady" || {
  echo "FAIL: an unchanged manifest pushed a commit" >&2; exit 1; }

# 3. Every degraded CLI is a skip that leaves the published manifest intact — never a failure, and
#    never an empty file written over a good one.
for mode in missing-subcommand no-layout failing; do
  out=$(run_helper STUB_MODE="$mode" 2>&1) || {
    echo "FAIL: $mode should exit 0, got non-zero: $out" >&2; exit 1; }
  case "$out" in
    *skipping*) ;;
    *) echo "FAIL: $mode did not report a skip: $out" >&2; exit 1 ;;
  esac
  test "$(tip_commits)" -eq "$steady" || {
    echo "FAIL: $mode changed the branch" >&2; exit 1; }
done

# 4. The renders layout still refuses to run without the baselines.json it joins against, rather
#    than generating a manifest with no keys.
out=$( ( cd "$ROOT" && env PATH="$BIN:$PATH" \
    TARGET_BRANCH="$BRANCH" REPO=local/test GITHUB_TOKEN_INLINE=test REMOTE_URL="$REMOTE" \
    OUTPUT="$ROOT/history.json" LAYOUT=renders BASELINES="$ROOT/absent.json" \
    MSG=x WORK_DIR="$ROOT/scratch2" bash "$HELPER" ) 2>&1 )
case "$out" in
  *"no baselines.json"*) ;;
  *) echo "FAIL: renders layout did not skip on a missing baselines.json: $out" >&2; exit 1 ;;
esac

# 5. The commit carries the caller's identity as BOTH author and committer. `git commit-tree`
#    resolves the two independently, so a step passing only one leaves the other to whatever the
#    helper's `git config` happened to seed.
STUB_MANIFEST='{"previews":{"identity":{}}}' \
  GIT_AUTHOR_NAME='Design Bot' GIT_AUTHOR_EMAIL='design@example.com' \
  GIT_COMMITTER_NAME='Design Bot' GIT_COMMITTER_EMAIL='design@example.com' \
  run_helper STUB_MODE=full >/dev/null
ident="$ROOT/ident"
git clone -q --branch "$BRANCH" "$REMOTE" "$ident"
test "$(git -C "$ident" log -1 --format='%an <%ae>')" = 'Design Bot <design@example.com>' || {
  echo "FAIL: author identity not carried: $(git -C "$ident" log -1 --format='%an <%ae>')" >&2
  exit 1; }
test "$(git -C "$ident" log -1 --format='%cn <%ce>')" = 'Design Bot <design@example.com>' || {
  echo "FAIL: committer identity not carried" >&2; exit 1; }

# 6. The manifest is regenerated per attempt, not built once before the loop. A lost race means
#    someone published in between, so re-parenting a stale blob would overwrite their history.json
#    with one that omits their changes.
grep -q 'if ! generate; then' "$HELPER" || {
  echo "FAIL: generation is not inside the retry loop" >&2; exit 1; }
gen_line=$(grep -n 'if ! generate; then' "$HELPER" | cut -d: -f1)
loop_line=$(grep -n '^while :; do' "$HELPER" | cut -d: -f1)
test "$gen_line" -gt "$loop_line" || {
  echo "FAIL: generate() is called before the retry loop opens" >&2; exit 1; }

# 7. The default `renders` layout still publishes against a CLI that predates `--layout`. The flag
#    names that CLI's own default, so the manifest it produces is the right one — the helper must
#    therefore withhold the flag rather than pass it and take the rejection as a reason to skip.
printf '{"a":{"module":"m","renderBasename":"a.png"}}\n' > "$ROOT/baselines.json"
before7=$(tip_commits)
out=$( ( cd "$ROOT" && env PATH="$BIN:$PATH" \
    TARGET_BRANCH="$BRANCH" REPO=local/test GITHUB_TOKEN_INLINE=test REMOTE_URL="$REMOTE" \
    OUTPUT="$ROOT/history.json" LAYOUT=renders BASELINES="$ROOT/baselines.json" \
    STUB_MODE=rejects-layout STUB_MANIFEST='{"previews":{"legacy-cli":{}}}' \
    MSG='update history' WORK_DIR="$ROOT/scratch7" bash "$HELPER" ) 2>&1 )
case "$out" in
  *skipping*) echo "FAIL: renders layout skipped on a pre---layout CLI: $out" >&2; exit 1 ;;
esac
test "$(tip_commits)" -eq "$((before7 + 1))" || {
  echo "FAIL: renders layout published nothing against a pre---layout CLI: $out" >&2; exit 1; }

echo "push-history.sh: all checks passed."
