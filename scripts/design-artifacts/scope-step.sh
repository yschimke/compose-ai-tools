#!/usr/bin/env bash
#
# Which design-artifacts delivery branches does THIS run have to regenerate?
#
# The whole `Scope` job body, in a file rather than a YAML heredoc, for the two
# reasons that keep being the same reason: it is testable here (test-scope-step.sh),
# and every caller of the design-artifacts pipeline can run the identical logic
# instead of keeping its own copy. The Scope job was duplicated across three callers
# and the defect issue #5438 describes lives in that job's premise, so fixing it in
# one caller would have left the others broken — which is exactly what
# `design-artifacts.yml`'s own header says not to do.
#
# ## The premise that was wrong
#
# The old body diffed one range: `github.event.before..$GITHUB_SHA`, the push that
# started the run. That is the whole truth only if every push gets a run that
# finishes. `cancel-in-progress: false` protects the IN-PROGRESS run, not the
# PENDING one — GitHub keeps at most one pending run per concurrency group, so a
# newly queued run cancels the one already waiting. The cancelled run's catalogs are
# never re-rendered, because its replacement scopes to its own push, which touched
# something else. The lane then serves stale bytes on `main` until the Monday cron,
# silently.
#
# ## The predicate now
#
# Per lane, not per run: *is this lane's last rendered source an ancestor of
# `$GITHUB_SHA`, and did anything in its input set change since?* A dropped run is
# then picked up by the NEXT push whatever that push touched, because the lane's
# range still reaches back over the change nobody rendered. published-source.sh
# resolves that baseline and documents where it comes from.
#
# Every unresolvable answer regenerates. The asymmetry is deliberate and is the
# same one the mapper states: publishing a fresh bundle is never wrong, skipping a
# stale one is.
#
# ## Inputs (env)
#
#   EVENT         github.event_name. Anything but `push` regenerates everything.
#   FORCE_ALL     `true` ⇒ regenerate everything. The explicit signal a called
#                 workflow needs, because it inherits the CALLER's github context
#                 and so never sees `workflow_call` in EVENT.
#   ONLY          Comma-separated lane selector for a manual dispatch. Empty ⇒ the
#                 automatic path below.
#   BEFORE        github.event.before — the fallback baseline for a lane that has
#                 never published, i.e. the behaviour that shipped.
#   AFTER         github.sha.
#   REPO          owner/name.
#   SCOPE_MAPPER  The path→lane mapping (default: this repo's scope-systems.sh).
#                 A caller in another repository points this at its own.
#   GH_TOKEN      Read access for the compare and ref lookups.
#
# Prints `<system>=true|false`, one per line, in the mapper's order. The workflow
# tees that into $GITHUB_OUTPUT. A human-readable account goes to
# $GITHUB_STEP_SUMMARY when it is set.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCOPE_MAPPER="${SCOPE_MAPPER:-$here/scope-systems.sh}"
PUBLISHED_SOURCE="${PUBLISHED_SOURCE:-$here/published-source.sh}"
EVENT="${EVENT:-}"
FORCE_ALL="${FORCE_ALL:-}"
ONLY="${ONLY:-}"
BEFORE="${BEFORE:-}"
AFTER="${AFTER:-}"
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

say() { echo "$*" >> "$SUMMARY"; }

# A manual dispatch that named lanes means those lanes and no others. Without it the
# only manual remedy for one stale lane was to re-render every catalog in the repo.
if [ -n "$ONLY" ]; then
  say "Lane selector: \`$ONLY\` — regenerating only the systems named."
  "$SCOPE_MAPPER" --only "$ONLY"
  exit 0
fi

# Cron / dispatch / the release chain exist to refresh everything.
if [ "$FORCE_ALL" = "true" ] || [ "$EVENT" != "push" ]; then
  if [ "$FORCE_ALL" = "true" ]; then
    why="force-all (called workflow)"
  else
    why="event=$EVENT"
  fi
  say "$why — regenerating every system"
  "$SCOPE_MAPPER" --all
  exit 0
fi

: "${REPO:?REPO required}"
: "${AFTER:?AFTER required}"

# Resolve the changed files between `$1` and AFTER, or print nothing when the range
# is unusable. Unusable covers more than a failed request:
#
#  • an all-zero / empty base is branch creation — there is no range;
#  • `behind` or `diverged` means the base is NOT an ancestor of AFTER (a force-push,
#    or a marker written from another branch), so the diff would not describe what
#    this run has to catch up on;
#  • the compare endpoint caps `files` at 300 and paginates COMMITS, not files, so
#    `--paginate` would not fetch the rest — it would hand back a silently partial
#    list. A lane past the cap would read as "unchanged" and rot while the run
#    reports success, so a capped response is treated as unresolvable too.
#
# `identical` is the one case that resolves to an empty list MEANINGFULLY: the lane
# has already been rendered from AFTER. It is reported through RANGE_STATUS rather
# than as an empty list, because an empty list means "regenerate" everywhere else.
#
# Writes the file list to stdout and reports the range's shape in RANGE_STATUS.
# Deliberately NOT called through `$(…)`: a command substitution runs in a subshell,
# where an assignment to RANGE_STATUS would be discarded and every lane would read
# as `unresolved`. A plain redirect keeps it in this shell.
RANGE_STATUS=''
changed_files() {
  local base="$1" compare count
  RANGE_STATUS='unresolved'
  case "$base" in
    ''|0000000000000000000000000000000000000000) return 0 ;;
  esac
  compare="$(gh api "repos/$REPO/compare/$base...$AFTER" 2>/dev/null || true)"
  [ -n "$compare" ] || return 0
  case "$(jq -r '.status // ""' <<<"$compare")" in
    identical) RANGE_STATUS='identical'; return 0 ;;
    ahead) ;;
    *) return 0 ;;
  esac
  count="$(jq -r '(.files // []) | length' <<<"$compare")"
  if [ "$count" -ge 300 ]; then
    say "- compare \`${base:0:8}...\` returned $count files (API cap) — treating as truncated"
    return 0
  fi
  RANGE_STATUS='ahead'
  jq -r '(.files // [])[].filename' <<<"$compare"
}

range_files="$(mktemp)"
trap 'rm -f "$range_files"' EXIT

for system in $("$SCOPE_MAPPER" --list); do
  # The lane's own baseline, then the push range, then nothing — in decreasing order
  # of how much history it can vouch for.
  base="$("$PUBLISHED_SOURCE" --system "$system" --repo "$REPO" 2>/dev/null || true)"
  if [ -n "$base" ]; then
    origin="last rendered from \`${base:0:8}\`"
  else
    base="$BEFORE"
    origin="no published source; falling back to this push's \`${base:0:8}\`"
  fi

  changed_files "$base" > "$range_files"
  files="$(cat "$range_files")"

  if [ "$RANGE_STATUS" = 'identical' ]; then
    say "- \`$system\` — $origin, already this commit → unchanged, skipped"
    echo "$system=false"
    continue
  fi
  if [ "$RANGE_STATUS" != 'ahead' ]; then
    say "- \`$system\` — $origin, range unusable → regenerate"
    echo "$system=true"
    continue
  fi

  count="$(sed '/^$/d' <<<"$files" | wc -l | tr -d ' ')"
  verdict="$(printf '%s\n' "$files" | "$SCOPE_MAPPER" --system "$system")"
  case "$verdict" in
    *=true)  say "- \`$system\` — $origin, $count changed file(s) → regenerate" ;;
    *)       say "- \`$system\` — $origin, $count changed file(s) → unchanged, skipped" ;;
  esac
  echo "$verdict"
done
