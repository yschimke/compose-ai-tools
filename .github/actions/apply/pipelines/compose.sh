#!/usr/bin/env bash
# Composable @Preview pipeline. Drives `compose-preview show` and either
# generates baselines (baseline mode) or diffs vs the baseline branch and
# stages PR renders + a sticky-comment body (comment mode).
#
# Self-skips silently when the workspace has no compose modules — detected
# by an empty `_previews.json` (CLI emits an envelope with no entries).
#
# Required env (set by action.yml):
#   MODE                 — baseline | comment
#   RENDER_TIMEOUT       — render timeout in seconds
#   ACTION_PATH          — path to apply action (sources lib helpers via ../lib)
#   REPO                 — github.repository
#   GITHUB_TOKEN_INLINE  — token used for git fetch/push
#   BASELINE_BRANCH      — composable baselines branch
#   RESOURCE_BRANCH      — resource baselines branch (only used in baseline mode)
#   PR_HEAD_BRANCH       — per-PR composable branch (only used in comment mode)
#   PR_NUMBER            — PR number (comment mode)
#   COMMENT_ON_EMPTY_DIFF — passthrough
#   SKIP_RENDER          — when "true", reuse pre-staged _previews.json
#                          instead of invoking `compose-preview show`. Lets
#                          non-Gradle build systems drive the baseline /
#                          comment half of this pipeline with envelopes
#                          produced by the Phase A CLIs.
set +e

if [ "${SKIP_RENDER:-false}" = "true" ]; then
  # Pre-staged path: the caller has already written _previews.json to
  # $GITHUB_WORKSPACE. Validate it's non-empty + parseable; treat missing
  # as a clean skip (matches the no-compose-modules branch below).
  if [ ! -s _previews.json ]; then
    echo "compose pipeline: skip-render=true and no _previews.json staged; skipping."
    echo "0" > "$GITHUB_WORKSPACE/_compose_render_rc"
    echo "0" > "$GITHUB_WORKSPACE/_compose_rc"
    exit 0
  fi
  echo "compose pipeline: skip-render=true; reusing pre-staged _previews.json."
  echo "0" > "$GITHUB_WORKSPACE/_compose_render_rc"
else
  # Render. Don't fail on non-zero — partial envelope still drives the rest.
  compose-preview show --json --timeout "$RENDER_TIMEOUT" > _previews.json
  RENDER_RC=$?
  echo "$RENDER_RC" > "$GITHUB_WORKSPACE/_compose_render_rc"
fi

if [ ! -s _previews.json ]; then
  echo "compose pipeline: no _previews.json — workspace has no compose modules, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_compose_rc"
  exit 0
fi

# `compose-preview show` emits an envelope even with zero previews; treat
# `entries: []` as a silent skip. Malformed JSON falls through so the
# downstream generate/compare step surfaces the parse failure (same shape
# as the legacy preview-baselines / preview-comment actions).
if python3 -c '
import json, sys
try:
    data = json.load(open("_previews.json"))
except Exception:
    sys.exit(1)  # malformed: fall through
entries = data.get("entries") or data.get("previews") or []
sys.exit(0 if not entries else 1)
' 2>/dev/null; then
  echo "compose pipeline: previews.json has no entries, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_compose_rc"
  exit 0
fi

set -e

if [ "$MODE" = "baseline" ]; then
  # Fetch prior baselines so per-preview flakes don't drop entries.
  mkdir -p _prior_baselines
  if git ls-remote --exit-code \
        "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
        "refs/heads/${BASELINE_BRANCH}" >/dev/null 2>&1; then
    git fetch --depth=1 --quiet \
        "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
        "${BASELINE_BRANCH}"
    git show "FETCH_HEAD:baselines.json" > _prior_baselines/baselines.json 2>/dev/null || true
    git archive FETCH_HEAD renders 2>/dev/null \
      | tar -x -C _prior_baselines/ 2>/dev/null || true
  fi

  python3 "$ACTION_PATH/../lib/compare-previews.py" generate _previews.json \
    --output-dir _baselines \
    --repo "$REPO" \
    --branch "$BASELINE_BRANCH" \
    --prior-baselines _prior_baselines/baselines.json \
    --prior-renders _prior_baselines/renders

  # Stage the push commit MSG into the staging dir for the post-wait step.
  echo "Update preview baselines from ${GITHUB_SHA::8}" > _baselines/_push_msg
  echo "$BASELINE_BRANCH" > _baselines/_push_branch
  echo "1" > _baselines/_skip_if_unchanged
else
  # comment mode
  mkdir -p _baselines
  if git ls-remote --exit-code origin "$BASELINE_BRANCH" >/dev/null 2>&1; then
    git fetch origin "$BASELINE_BRANCH"
    git show "origin/${BASELINE_BRANCH}:baselines.json" \
      > _baselines/baselines.json 2>/dev/null || true
    git archive "origin/${BASELINE_BRANCH}" renders 2>/dev/null \
      | tar -x -C _baselines/ 2>/dev/null || true
  else
    echo "compose pipeline: no $BASELINE_BRANCH yet — treating all previews as new."
  fi

  # Pin Before to the current baseline tip SHA.
  BASE_SHA=$(git ls-remote \
    "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
    "refs/heads/${BASELINE_BRANCH}" | awk '{print $1}')
  [ -z "$BASE_SHA" ] && BASE_SHA="$BASELINE_BRANCH"
  echo "$BASE_SHA" > _base_sha

  python3 "$ACTION_PATH/../lib/compare-previews.py" copy-changed _previews.json \
    --baselines _baselines/baselines.json \
    --baseline-renders _baselines/renders \
    --output-dir _pr_renders

  # Stage push metadata for the post-wait push step.
  if [ -d _pr_renders/renders ] && [ -n "$(ls -A _pr_renders/renders 2>/dev/null)" ]; then
    echo "Preview renders for PR #${PR_NUMBER} (${GITHUB_SHA::8})" > _pr_renders/_push_msg
    echo "$PR_HEAD_BRANCH" > _pr_renders/_push_branch
    echo "0" > _pr_renders/_skip_if_unchanged
  fi
fi

echo "0" > "$GITHUB_WORKSPACE/_compose_rc"
