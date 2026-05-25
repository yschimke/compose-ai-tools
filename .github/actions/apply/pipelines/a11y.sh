#!/usr/bin/env bash
# A11y pipeline. Runs `compose-preview a11y` on $A11Y_MODULE, copies the
# annotated PNGs / findings.json out, and either generates an a11y baseline
# (baseline mode) or compares against the baseline and stages a per-PR
# branch push + sticky comment (comment mode).
#
# Self-skips silently when $A11Y_MODULE is empty.
#
# Required env (set by action.yml):
#   MODE                  — baseline | comment
#   ACTION_PATH           — path to apply action
#   REPO                  — github.repository
#   A11Y_MODULE           — Gradle module path (e.g. samples:wear); empty = skip
#   A11Y_BASELINE_BRANCH  — long-lived a11y baseline branch
#   A11Y_PR_BRANCH        — per-PR a11y branch (comment mode)
#   PR_NUMBER             — PR number (comment mode)
set -e

if [ -z "${A11Y_MODULE:-}" ]; then
  echo "a11y pipeline: A11Y_MODULE empty, skipping."
  echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
  exit 0
fi

# Use the same CLI that the install step put on $PATH (release tarball or
# source build); the legacy `:cli:installDist` rebuild was a leftover from
# before the unified install step.
compose-preview a11y --module ":${A11Y_MODULE}"

# Translate Gradle module path to on-disk project dir.
MODULE_DIR="${A11Y_MODULE//://}"
python3 "$ACTION_PATH/../lib/a11y-report.py" copy-annotated \
  --build-dir "${MODULE_DIR}/build/compose-previews" \
  --output-dir _a11y_renders

if [ "$MODE" = "baseline" ]; then
  python3 "$ACTION_PATH/../lib/a11y-report.py" readme \
    _a11y_renders/findings.json \
    --repo "$REPO" \
    --branch "$A11Y_BASELINE_BRANCH" \
    --output _a11y_renders/README.md

  echo "Update accessibility baseline from ${GITHUB_SHA::8}" > _a11y_renders/_push_msg
  echo "$A11Y_BASELINE_BRANCH" > _a11y_renders/_push_branch
  echo "1" > _a11y_renders/_skip_if_unchanged
else
  # comment mode — compare vs baseline, stay silent when unchanged.
  if git ls-remote --exit-code origin "$A11Y_BASELINE_BRANCH" >/dev/null 2>&1; then
    git fetch origin "$A11Y_BASELINE_BRANCH"
    git show "origin/${A11Y_BASELINE_BRANCH}:findings.json" \
      > _a11y_baseline_findings.json 2>/dev/null \
      || echo '{"entries":[]}' > _a11y_baseline_findings.json
  else
    echo '{"entries":[]}' > _a11y_baseline_findings.json
  fi

  # Provisional --head-ref; rewritten post-push with the actual SHA.
  python3 "$ACTION_PATH/../lib/a11y-report.py" comment \
    _a11y_renders/findings.json \
    --repo "$REPO" \
    --head-ref "$A11Y_PR_BRANCH" \
    --baseline _a11y_baseline_findings.json \
    > _a11y_comment.md

  if [ -s _a11y_comment.md ]; then
    python3 "$ACTION_PATH/../lib/a11y-report.py" readme \
      _a11y_renders/findings.json \
      --repo "$REPO" \
      --branch "$A11Y_PR_BRANCH" \
      --output _a11y_renders/README.md

    echo "A11y report for PR #${PR_NUMBER} (${GITHUB_SHA::8})" > _a11y_renders/_push_msg
    echo "$A11Y_PR_BRANCH" > _a11y_renders/_push_branch
    echo "0" > _a11y_renders/_skip_if_unchanged
  else
    echo "a11y pipeline: no changes vs ${A11Y_BASELINE_BRANCH}; skipping push + comment."
    rm -f _a11y_comment.md
  fi
fi

echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
