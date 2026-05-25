#!/usr/bin/env bash
# Notification preview pipeline. Renders notification previews across one or
# more modules, stages the PNG+JSON sidecar tree into a single flat
# `_notification_renders/` dir, and either pushes a baseline (baseline mode)
# or stages a per-PR push + sticky comment (comment mode).
#
# Module selection
# ----------------
# * ``NOTIFY_MODULES`` — comma-separated allowlist of Gradle module paths
#   (e.g. ``samples:android,samples:phone``). Empty = run the unscoped
#   Gradle task (``./gradlew composePreviewRenderAll``), which Gradle
#   expands to every module that registers it.
# * ``NOTIFY_SKIP_MODULES`` — comma-separated denylist. Applied after disk
#   discovery, so the allowlist drives what gets *built* and skip drives
#   what gets *staged into the report*.
#
# Self-skips silently when an allowlist resolves to empty, or when no
# module wrote any ``*Notification*.png`` output.
#
# Required env (set by action.yml):
#   MODE                    — baseline | comment
#   ACTION_PATH             — path to apply action
#   REPO                    — github.repository
#   NOTIFY_MODULES          — comma-separated allowlist (empty = all)
#   NOTIFY_SKIP_MODULES     — comma-separated denylist (post-build)
#   NOTIFY_BASELINE_BRANCH  — long-lived notification baseline branch
#   NOTIFY_PR_BRANCH        — per-PR notification branch (comment mode)
#   PR_NUMBER               — PR number (comment mode)
set -e

split_csv() {
  local raw="${1:-}"
  if [ -z "$raw" ]; then
    return 0
  fi
  IFS=',' read -r -a _items <<< "$raw"
  for item in "${_items[@]}"; do
    local trimmed
    trimmed="$(echo "$item" | xargs)"
    [ -n "$trimmed" ] && echo "$trimmed"
  done
}

mapfile -t ALLOW_MODULES < <(split_csv "${NOTIFY_MODULES:-}")
mapfile -t SKIP_MODULES < <(split_csv "${NOTIFY_SKIP_MODULES:-}")

# When the allowlist is non-empty, only that subset's tasks get invoked
# (`./gradlew :foo:composePreviewRenderAll :bar:composePreviewRenderAll`).
# When empty, the unscoped task name lets Gradle expand to every module
# that registers it — same fan-out the Gradle plugin already does for
# `compose-preview a11y` without `--module`.
gradle_args=()
if [ "${#ALLOW_MODULES[@]}" -gt 0 ]; then
  for m in "${ALLOW_MODULES[@]}"; do
    gradle_args+=(":${m}:composePreviewRenderAll")
  done
else
  gradle_args+=("composePreviewRenderAll")
fi

# In auto-detect mode an absent task is a "project doesn't ship notification
# previews" signal rather than an error — drop into soft-skip so consumers
# don't have to remember to `skip: notifications`. With an explicit
# allowlist the missing task IS a misconfiguration and we let Gradle fail
# the run as before.
if [ "${#ALLOW_MODULES[@]}" -eq 0 ]; then
  if ! ./gradlew "${gradle_args[@]}" --no-daemon; then
    echo "notifications pipeline: ./gradlew ${gradle_args[*]} failed (likely no module registers the task); skipping."
    echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
    exit 0
  fi
else
  ./gradlew "${gradle_args[@]}" --no-daemon
fi

# Discover every module with notification PNG output under
# */build/compose-previews/renders/. Translates the on-disk path to a
# Gradle module path so the skip list can be matched canonically.
discovered=()
while IFS= read -r build_dir; do
  rel="${build_dir#./}"
  module_dir="${rel%/build/compose-previews}"
  module_path="${module_dir//\//:}"
  discovered+=("$module_path|$module_dir")
done < <(find . -type d -path '*/build/compose-previews' 2>/dev/null | sort)

if [ "${#discovered[@]}" -eq 0 ]; then
  echo "notifications pipeline: no modules produced compose-previews output; skipping."
  echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
  exit 0
fi

is_skipped() {
  local mod="$1"
  for s in "${SKIP_MODULES[@]}"; do
    [ "$s" = "$mod" ] && return 0
  done
  return 1
}

stage_args=(stage --output-dir _notification_renders)
kept=0
for entry in "${discovered[@]}"; do
  module_path="${entry%%|*}"
  module_dir="${entry##*|}"
  if is_skipped "$module_path"; then
    echo "notifications pipeline: skipping module ${module_path} (skip list)."
    continue
  fi
  stage_args+=(--build-dir "${module_dir}/build/compose-previews")
  kept=$((kept + 1))
done

if [ "$kept" -eq 0 ]; then
  echo "notifications pipeline: all discovered modules were skip-listed; nothing to stage."
  echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
  exit 0
fi

# `cmd_stage` returns rc=1 only when *every* fed build dir came up empty;
# modules with no notification PNGs are tolerated so we can pass the full
# set without per-module pre-filtering.
if ! python3 "$ACTION_PATH/../lib/notification-previews.py" "${stage_args[@]}"; then
  echo "notifications pipeline: stage step failed."
  echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
  exit 0
fi

if [ "$MODE" = "baseline" ]; then
  python3 "$ACTION_PATH/../lib/notification-previews.py" readme \
    _notification_renders/findings.json \
    --repo "$REPO" \
    --branch "$NOTIFY_BASELINE_BRANCH" \
    --output _notification_renders/README.md

  echo "Update notification baseline from ${GITHUB_SHA::8}" > _notification_renders/_push_msg
  echo "$NOTIFY_BASELINE_BRANCH" > _notification_renders/_push_branch
  echo "1" > _notification_renders/_skip_if_unchanged
else
  if git ls-remote --exit-code origin "$NOTIFY_BASELINE_BRANCH" >/dev/null 2>&1; then
    git fetch origin "$NOTIFY_BASELINE_BRANCH"
    git show "origin/${NOTIFY_BASELINE_BRANCH}:findings.json" \
      > _notification_baseline_findings.json 2>/dev/null \
      || echo '{"entries":[]}' > _notification_baseline_findings.json
  else
    echo '{"entries":[]}' > _notification_baseline_findings.json
  fi

  python3 "$ACTION_PATH/../lib/notification-previews.py" comment \
    _notification_renders/findings.json \
    --repo "$REPO" \
    --head-ref "$NOTIFY_PR_BRANCH" \
    --baseline _notification_baseline_findings.json \
    --baseline-branch "$NOTIFY_BASELINE_BRANCH" \
    > _notification_comment.md

  if [ -s _notification_comment.md ]; then
    python3 "$ACTION_PATH/../lib/notification-previews.py" readme \
      _notification_renders/findings.json \
      --repo "$REPO" \
      --branch "$NOTIFY_PR_BRANCH" \
      --output _notification_renders/README.md

    echo "Notification previews for PR #${PR_NUMBER} (${GITHUB_SHA::8})" > _notification_renders/_push_msg
    echo "$NOTIFY_PR_BRANCH" > _notification_renders/_push_branch
    echo "0" > _notification_renders/_skip_if_unchanged
  else
    echo "notifications pipeline: no changes vs ${NOTIFY_BASELINE_BRANCH}; skipping push + comment."
    rm -f _notification_comment.md
  fi
fi

echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
