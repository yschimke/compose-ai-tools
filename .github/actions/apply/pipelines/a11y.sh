#!/usr/bin/env bash
# A11y pipeline. Runs `compose-preview a11y` across one or more modules,
# copies every module's annotated PNGs / findings.json into a single
# `_a11y_renders/` tree, and either generates an a11y baseline (baseline
# mode) or compares against the baseline and stages a per-PR branch push +
# sticky comment (comment mode).
#
# Module selection
# ----------------
# * ``A11Y_MODULES`` — comma-separated allowlist of Gradle module paths
#   (e.g. ``samples:wear,samples:phone``). Empty = auto-detect every module
#   the plugin / CLI knows about (CLI default when ``--module`` is
#   omitted).
# * ``A11Y_SKIP_MODULES`` — comma-separated denylist. Applied to the set of
#   modules discovered on disk after the CLI runs, so the allowlist is the
#   "what gets built" knob and skip is the "what gets reported" knob.
#
# Self-skips silently when an allowlist resolves to an empty set, or when
# auto-detect found zero modules with a11y output. The pipeline never
# fails just because a single module had nothing to report.
#
# Required env (set by action.yml):
#   MODE                  — baseline | comment
#   ACTION_PATH           — path to apply action
#   REPO                  — github.repository
#   A11Y_MODULES          — comma-separated allowlist (empty = all)
#   A11Y_SKIP_MODULES     — comma-separated denylist (applied post-build)
#   A11Y_BASELINE_BRANCH  — long-lived a11y baseline branch
#   A11Y_PR_BRANCH        — per-PR a11y branch (comment mode)
#   PR_NUMBER             — PR number (comment mode)
set -e

# Comma-split + trim helper. Empty input → empty array.
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

mapfile -t ALLOW_MODULES < <(split_csv "${A11Y_MODULES:-}")
mapfile -t SKIP_MODULES < <(split_csv "${A11Y_SKIP_MODULES:-}")

cli_args=(a11y)
if [ "${#ALLOW_MODULES[@]}" -gt 0 ]; then
  for m in "${ALLOW_MODULES[@]}"; do
    cli_args+=(--module ":${m}")
  done
fi
if [ -n "${MISSING_RENDERS:-}" ]; then
  cli_args+=(--missing-renders "${MISSING_RENDERS}")
fi
# Same per-Gradle-invocation ceiling the compose/resources pipelines get.
# Without it the a11y run sits on the CLI's 300s default, which cannot fit
# the full re-render this command triggers (`activeExtensions=a11y` changes
# every render task's inputs) — a below-median runner times the build out,
# the pipeline silently self-skips, and the a11y baseline never updates.
if [ -n "${RENDER_TIMEOUT:-}" ]; then
  cli_args+=(--timeout "${RENDER_TIMEOUT}")
fi

# Use the same CLI that the install step put on $PATH (release tarball or
# source build); the legacy `:cli:installDist` rebuild was a leftover from
# before the unified install step.
#
# In auto-detect mode a missing-plugin / no-candidate-modules failure is
# treated as "project doesn't ship a11y" rather than a hard error —
# consumers shouldn't have to remember to `skip: a11y`. With an explicit
# allowlist we let the CLI fail loud since the user named a specific
# module that must exist.
if [ "${#ALLOW_MODULES[@]}" -eq 0 ]; then
  if ! compose-preview "${cli_args[@]}"; then
    echo "a11y pipeline: compose-preview ${cli_args[*]} failed (likely no module applies the plugin); skipping."
    echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
    exit 0
  fi
else
  compose-preview "${cli_args[@]}"
fi

# Discover every module that produced a previews.json under
# */build/compose-previews. Translates the on-disk path to a Gradle module
# path (`foo/bar/build/compose-previews/previews.json` → `foo:bar`) so the
# skip list can be matched against canonical Gradle paths.
discovered=()
while IFS= read -r path; do
  rel="${path#./}"
  module_dir="${rel%/build/compose-previews/previews.json}"
  module_path="${module_dir//\//:}"
  discovered+=("$module_path|$module_dir")
done < <(find . -type f -name previews.json -path '*/build/compose-previews/*' 2>/dev/null | sort)

if [ "${#discovered[@]}" -eq 0 ]; then
  echo "a11y pipeline: no modules produced previews.json; skipping."
  echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
  exit 0
fi

is_skipped() {
  local mod="$1"
  for s in "${SKIP_MODULES[@]}"; do
    [ "$s" = "$mod" ] && return 0
  done
  return 1
}

# Filter discovered modules through the skip list and only feed surviving
# build dirs to `copy-annotated`. The CLI already ran across the full
# allowlist, so skip just controls what shows up in the report.
copy_args=(copy-annotated --output-dir _a11y_renders)
kept=0
for entry in "${discovered[@]}"; do
  module_path="${entry%%|*}"
  module_dir="${entry##*|}"
  if is_skipped "$module_path"; then
    echo "a11y pipeline: skipping module ${module_path} (skip list)."
    continue
  fi
  copy_args+=(--build-dir "${module_dir}/build/compose-previews")
  kept=$((kept + 1))
done

if [ "$kept" -eq 0 ]; then
  echo "a11y pipeline: all discovered modules were skip-listed; nothing to report."
  echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
  exit 0
fi

python3 "$ACTION_PATH/../lib/a11y-report.py" "${copy_args[@]}"

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
