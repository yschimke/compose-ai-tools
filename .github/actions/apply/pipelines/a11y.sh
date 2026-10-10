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
#   BASELINE_REMOTE       — git remote holding the baseline branches (comment
#                           mode); default origin, set when artifact-repository differs
#   A11Y_MODULES          — comma-separated allowlist (empty = all)
#   A11Y_SKIP_MODULES     — comma-separated denylist (applied post-build)
#   A11Y_BASELINE_BRANCH  — long-lived a11y baseline branch
#   A11Y_PR_BRANCH        — per-PR a11y branch (comment mode)
#   PR_NUMBER             — PR number (comment mode)
#   A11Y_BASELINE         — `true` keeps an a11y baseline: baseline mode checks every
#                           preview and pushes A11Y_BASELINE_BRANCH, and comment mode
#                           diffs the PR's previews against it. Anything else (the
#                           default) skips baseline mode and reports the PR's previews
#                           on their own.
#   A11Y_SCOPE_OVERRIDE   — `full` checks every preview of the affected modules on a PR
#                           too (the action's `scope: full`, a manual full rerun).
#
# What a PR checks
# ----------------
# Only the previews the PR changed: those the compose pipeline's visual diff found new or
# changed (`_changed_previews.json`) and those whose source file the PR changed
# (`_pr_changed_files.txt`), selected by `a11y-scope.py` and handed to the CLI as `--id-file`.
# ATF runs once per preview, serially, so checking a whole catalog on a PR costs an hour where
# the PR changed a dozen previews. When this job ran no compose pipeline there is no visual diff
# to scope by, and every preview of the affected modules is checked, as before.
set -e
: "${BASELINE_REMOTE:=origin}"

if [ "${SKIP_SCOPED_A11Y:-false}" = true ]; then
  echo "a11y pipeline: affected modules do not include the configured a11y modules; skipping."
  echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
  exit 0
fi

# A baseline push checks every preview, and only a repository that asked for an a11y baseline
# reads one: without it, the whole-catalog run would be paid on every push to main for a branch
# nothing compares against.
if [ "$MODE" = "baseline" ] && [ "${A11Y_BASELINE:-false}" != "true" ]; then
  echo "a11y pipeline: no a11y baseline requested (a11y-baseline: false); skipping the baseline run."
  echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
  exit 0
fi

scoped_to_pr=false
rm -f _a11y_preview_ids.txt
if [ "$MODE" != "baseline" ] && [ "${A11Y_SCOPE_OVERRIDE:-auto}" != "full" ]; then
  selected=$(python3 "$ACTION_PATH/a11y-scope.py" \
    --changed _changed_previews.json \
    --changed-files _pr_changed_files.txt \
    --out _a11y_preview_ids.txt) || selected=full
  case "$selected" in
    full)
      echo "::notice::compose-preview/apply: a11y has no visual diff to scope by (the compose pipeline did not run in this job); checking every preview of the affected modules."
      ;;
    0)
      echo "a11y pipeline: this PR changed no previews; nothing to check."
      echo "0" > "$GITHUB_WORKSPACE/_a11y_rc"
      exit 0
      ;;
    *)
      echo "a11y pipeline: checking the ${selected} preview(s) this PR changed."
      scoped_to_pr=true
      ;;
  esac
fi

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

cli_args=(a11y --progress)
if [ "$scoped_to_pr" = true ]; then
  cli_args+=(--id-file "$GITHUB_WORKSPACE/_a11y_preview_ids.txt")
fi
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
[ "$scoped_to_pr" = true ] && copy_args+=(--changed-previews)
kept=0
for entry in "${discovered[@]}"; do
  module_path="${entry%%|*}"
  module_dir="${entry##*|}"
  if is_skipped "$module_path"; then
    echo "a11y pipeline: skipping module ${module_path} (skip list)."
    continue
  fi
  # A module the CLI never checked has a manifest (the compose pipeline wrote
  # it) but no accessibility.json; reporting its previews would list every
  # one as clean. On a PR-scoped run that is every module the PR's previews
  # are not in.
  if [ ! -f "${module_dir}/build/compose-previews/accessibility.json" ]; then
    echo "a11y pipeline: ${module_path} was not checked this run; leaving it out of the report."
    continue
  fi
  copy_args+=(--build-dir "${module_dir}/build/compose-previews")
  kept=$((kept + 1))
done

if [ "$kept" -eq 0 ]; then
  echo "a11y pipeline: no checked module left to report (skip-listed or not checked)."
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
  # comment mode. With an a11y baseline, compare against it and stay silent
  # when unchanged; without one (the default), report the checked previews.
  comment_args=()
  if [ "${A11Y_BASELINE:-false}" = "true" ]; then
    if git ls-remote --exit-code "$BASELINE_REMOTE" "$A11Y_BASELINE_BRANCH" >/dev/null 2>&1; then
      git fetch "$BASELINE_REMOTE" "$A11Y_BASELINE_BRANCH"
      git show "${BASELINE_REMOTE}/${A11Y_BASELINE_BRANCH}:findings.json" \
        > _a11y_baseline_findings.json 2>/dev/null \
        || echo '{"entries":[]}' > _a11y_baseline_findings.json
    else
      echo '{"entries":[]}' > _a11y_baseline_findings.json
    fi

    # A scoped PR compares only what it checked: the affected modules, and on
    # a per-preview run only those previews. Without filtering, every baseline
    # preview this job never looked at reads as resolved.
    filter_args=()
    if [ -n "${SCOPE_MODULES:-}" ] && [ "$SCOPE_MODULES" != full ]; then
      filter_args+=(--modules "$SCOPE_MODULES")
    fi
    [ "$scoped_to_pr" = true ] && filter_args+=(--previews _a11y_preview_ids.txt)
    if [ "${#filter_args[@]}" -gt 0 ]; then
      python3 "$ACTION_PATH/filter-findings-scope.py" \
        _a11y_baseline_findings.json \
        --current _a11y_renders/findings.json \
        "${filter_args[@]}"
    fi
    comment_args+=(--baseline _a11y_baseline_findings.json)
  fi

  # Provisional --head-ref; rewritten post-push with the actual SHA.
  python3 "$ACTION_PATH/../lib/a11y-report.py" comment \
    _a11y_renders/findings.json \
    --repo "$REPO" \
    --head-ref "$A11Y_PR_BRANCH" \
    "${comment_args[@]}" \
    > _a11y_comment.md

  if [ -s _a11y_comment.md ] && grep -qF '<!-- a11y-report:clean -->' _a11y_comment.md; then
    # Nothing to picture: the comment step only updates an existing sticky
    # comment with this body, so no renders are pushed for it.
    echo "a11y pipeline: no findings on the checked previews."
  elif [ -s _a11y_comment.md ]; then
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
