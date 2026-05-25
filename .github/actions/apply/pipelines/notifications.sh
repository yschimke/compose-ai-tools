#!/usr/bin/env bash
# Notification preview pipeline. Renders ${NOTIFY_MODULE}'s preview surface
# via `:${NOTIFY_MODULE}:composePreviewRenderAll`, stages the PNG+JSON
# sidecar tree, and either pushes a baseline (baseline mode) or stages a
# per-PR push + sticky comment (comment mode).
#
# Self-skips silently when $NOTIFY_MODULE is empty.
#
# Required env (set by action.yml):
#   MODE                    — baseline | comment
#   ACTION_PATH             — path to apply action
#   REPO                    — github.repository
#   NOTIFY_MODULE           — Gradle module path (e.g. samples:android); empty = skip
#   NOTIFY_BASELINE_BRANCH  — long-lived notification baseline branch
#   NOTIFY_PR_BRANCH        — per-PR notification branch (comment mode)
#   PR_NUMBER               — PR number (comment mode)
set -e

if [ -z "${NOTIFY_MODULE:-}" ]; then
  echo "notifications pipeline: NOTIFY_MODULE empty, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_notifications_rc"
  exit 0
fi

# Same Gradle entry the previous inline workflow used.
./gradlew ":${NOTIFY_MODULE}:composePreviewRenderAll" --no-daemon

MODULE_DIR="${NOTIFY_MODULE//://}"
python3 "$ACTION_PATH/../lib/notification-previews.py" stage \
  --build-dir "${MODULE_DIR}/build/compose-previews" \
  --output-dir _notification_renders

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
