#!/usr/bin/env bash
# Android XML resource preview pipeline. Drives `compose-preview show-resources`
# and either generates resource baselines or stages PR renders + a sibling
# sticky-comment body. Self-skips silently when the workspace has no
# `resources.json` (CLI emits "No Android resource previews found." and the
# envelope yields no rows).
#
# Required env (set by action.yml):
#   MODE                  — baseline | comment
#   RENDER_TIMEOUT        — render timeout in seconds
#   ACTION_PATH           — path to apply action
#   REPO                  — github.repository
#   GITHUB_TOKEN_INLINE   — token used for git fetch/push
#   RESOURCE_BRANCH       — resource baselines branch
#   RESOURCE_HEAD_BRANCH  — per-PR resource branch (comment mode)
#   PR_NUMBER             — PR number (comment mode)
#   SKIP_RENDER           — when "true", reuse pre-staged _resources.json
#                           instead of invoking `compose-preview show-resources`.
#                           Mirrors the compose pipeline's skip-render path
#                           for non-Gradle build systems.
set +e

if [ "${SKIP_RENDER:-false}" = "true" ]; then
  if [ ! -s _resources.json ]; then
    echo "resources pipeline: skip-render=true and no _resources.json staged; skipping."
    echo "0" > "$GITHUB_WORKSPACE/_resources_render_rc"
    echo "0" > "$GITHUB_WORKSPACE/_resources_rc"
    exit 0
  fi
  echo "resources pipeline: skip-render=true; reusing pre-staged _resources.json."
  echo "0" > "$GITHUB_WORKSPACE/_resources_render_rc"
else
  compose-preview show-resources --json --timeout "$RENDER_TIMEOUT" > _resources.json
  RENDER_RC=$?
  echo "$RENDER_RC" > "$GITHUB_WORKSPACE/_resources_render_rc"
fi

if [ ! -s _resources.json ]; then
  echo "resources pipeline: no _resources.json — workspace has no Android resource manifests, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_resources_rc"
  exit 0
fi

# Skip cleanly when the workspace has no resources (`show-resources` exits 0
# with an empty envelope on no-XML projects).
if python3 -c '
import json, sys
try:
    data = json.load(open("_resources.json"))
except Exception:
    sys.exit(1)
entries = data.get("entries") or data.get("resources") or []
sys.exit(0 if not entries else 1)
'; then
  echo "resources pipeline: no resource entries, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_resources_rc"
  exit 0
fi

set -e

if [ "$MODE" = "baseline" ]; then
  mkdir -p _prior_resource_baselines
  if git ls-remote --exit-code \
        "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
        "refs/heads/${RESOURCE_BRANCH}" >/dev/null 2>&1; then
    git fetch --depth=1 --quiet \
        "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
        "${RESOURCE_BRANCH}"
    git show "FETCH_HEAD:resource-baselines.json" \
      > _prior_resource_baselines/resource-baselines.json 2>/dev/null || true
    git archive FETCH_HEAD renders 2>/dev/null \
      | tar -x -C _prior_resource_baselines/ 2>/dev/null || true
  fi

  python3 "$ACTION_PATH/../lib/compare-previews.py" generate-resources \
    _resources.json \
    --output-dir _resource_baselines \
    --prior-baselines _prior_resource_baselines/resource-baselines.json \
    --prior-renders _prior_resource_baselines/renders

  if [ -d _resource_baselines ] && [ -f _resource_baselines/resource-baselines.json ]; then
    echo "Update resource baselines from ${GITHUB_SHA::8}" > _resource_baselines/_push_msg
    echo "$RESOURCE_BRANCH" > _resource_baselines/_push_branch
    echo "1" > _resource_baselines/_skip_if_unchanged
  fi
else
  # comment mode — fetch resource baselines tree
  mkdir -p _resource_baselines
  # `_baselines/` is shared with the compose pipeline (it holds
  # `resource-baselines.json` alongside `baselines.json`). Create it up
  # front so the `git show` redirect below doesn't fail when compose is
  # skipped (e.g. `only: resources`).
  mkdir -p _baselines
  if git ls-remote --exit-code origin "$RESOURCE_BRANCH" >/dev/null 2>&1; then
    git fetch origin "$RESOURCE_BRANCH"
    git show "origin/${RESOURCE_BRANCH}:resource-baselines.json" \
      > _baselines/resource-baselines.json 2>/dev/null || true
    git archive "origin/${RESOURCE_BRANCH}" renders 2>/dev/null \
      | tar -x -C _resource_baselines/ 2>/dev/null || true
  fi

  # Pin Before to the resource baseline tip SHA.
  RESOURCE_BASE_SHA=$(git ls-remote \
    "https://x-access-token:${GITHUB_TOKEN_INLINE}@github.com/${REPO}.git" \
    "refs/heads/${RESOURCE_BRANCH}" | awk '{print $1}')
  [ -z "$RESOURCE_BASE_SHA" ] && RESOURCE_BASE_SHA="$RESOURCE_BRANCH"
  echo "$RESOURCE_BASE_SHA" > _resource_base_sha

  python3 "$ACTION_PATH/../lib/compare-previews.py" copy-changed-resources \
    _resources.json \
    --baselines _baselines/resource-baselines.json \
    --baseline-renders _resource_baselines/renders \
    --output-dir _pr_resource_renders

  if [ -d _pr_resource_renders/renders ] && [ -n "$(ls -A _pr_resource_renders/renders 2>/dev/null)" ]; then
    echo "Resource preview renders for PR #${PR_NUMBER} (${GITHUB_SHA::8})" > _pr_resource_renders/_push_msg
    echo "$RESOURCE_HEAD_BRANCH" > _pr_resource_renders/_push_branch
    echo "0" > _pr_resource_renders/_skip_if_unchanged
  fi
fi

echo "0" > "$GITHUB_WORKSPACE/_resources_rc"
