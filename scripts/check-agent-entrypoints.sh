#!/usr/bin/env bash
# Agent-entrypoint reachability gate.
#
# This repository is worked by more than one coding agent, and each resolves its
# instructions from a DIFFERENT file by a DIFFERENT mechanism:
#
#   Claude Code   CLAUDE.md              @path imports are inlined at launch
#   Codex CLI     AGENTS.md              directory-walk concatenation, NO includes
#   Gemini CLI    GEMINI.md              @path.md memory imports are inlined
#   Copilot       .github/copilot-instructions.md, and root AGENTS.md natively
#
# None of them follows an ordinary markdown link. So a rule that lives only
# behind `See [docs/AGENTS.md](docs/AGENTS.md)` is invisible to every one of
# them, and the failure is silent: the agent does not deviate visibly, it just
# goes red on a gate it never saw. That is the failure mode this script exists to
# make loud, on the PR, before it reaches an agent.
#
# What is checked:
#   1. root AGENTS.md exists and fits Codex's default project_doc_max_bytes
#      (32 KiB) — past that Codex silently truncates and the tail is gone;
#   2. every CI-enforced invariant carries its `<!-- invariant: <id> -->` anchor
#      in AGENTS.md, exactly once;
#   3. no anchor appears anywhere else, so each rule has exactly one home;
#   4. each per-agent entrypoint actually REACHES AGENTS.md by that agent's own
#      mechanism — an import for Claude and Gemini, a mention for Copilot;
#   5. the per-agent entrypoints stay pointer-sized, so invariants cannot creep
#      back in as second copies.
#
# Usage:
#   scripts/check-agent-entrypoints.sh [--root <dir>] [--report]
#
#   --root    tree to check (default: the git top level of this script)
#   --report  also print the per-agent context cost table, and skip nothing
#
# Exit codes:
#   0  everything reachable
#   1  a problem was found (details on stderr)
#   2  could not check — bad arguments, or a missing root
#
# FAIL CLOSED: a missing file is a failure, never a skipped check.
set -uo pipefail

# Codex CLI's `project_doc_max_bytes` default. A root AGENTS.md larger than this
# is truncated mid-file with no warning, so the gate treats it as a hard failure
# rather than a style note.
CODEX_PROJECT_DOC_MAX_BYTES=32768
# Pointer files carry per-agent mechanics only. The cap is generous enough for
# that and far too small to hold a second copy of the invariants.
POINTER_MAX_BYTES=4096

# The CI-enforced invariants, by anchor id. Adding a gate to CI means adding it
# here and anchoring it in AGENTS.md — otherwise the gate can ship without any
# agent being able to see the rule it enforces.
INVARIANTS=(
  agent-attribution
  branch-prefix
  conventional-commits
  format-before-commit
  pr-state-recheck
)

root=""
report=0
while [ $# -gt 0 ]; do
  case "$1" in
    --root) root="${2:-}"; shift 2 || { printf 'error: --root needs a value\n' >&2; exit 2; } ;;
    --report) report=1; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) printf 'error: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [ -z "$root" ]; then
  root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel 2>/dev/null)" || root="$(cd "$(dirname "$0")/.." && pwd)"
fi
if [ ! -d "$root" ]; then
  printf 'error: not a directory: %s\n' "$root" >&2
  exit 2
fi

problems=0
fail() { problems=$((problems + 1)); printf 'FAIL  %s\n' "$1" >&2; }
ok()   { printf 'ok    %s\n' "$1"; }

bytes_of() { wc -c < "$1" | tr -d ' '; }

CANON="$root/AGENTS.md"

# -------------------------------------------------------------------------- 1. AGENTS.md
if [ ! -f "$CANON" ]; then
  fail "root AGENTS.md is missing — Codex CLI and Copilot would resolve no repository instructions at all"
else
  size="$(bytes_of "$CANON")"
  if [ "$size" -gt "$CODEX_PROJECT_DOC_MAX_BYTES" ]; then
    fail "AGENTS.md is ${size} bytes, over Codex's default project_doc_max_bytes (${CODEX_PROJECT_DOC_MAX_BYTES}); everything past the limit is silently dropped. Move detail into docs/."
  else
    pct=$((size * 100 / CODEX_PROJECT_DOC_MAX_BYTES))
    ok "AGENTS.md is ${size} bytes (${pct}% of Codex's ${CODEX_PROJECT_DOC_MAX_BYTES}-byte budget)"
    [ "$pct" -ge 80 ] && printf 'warn  AGENTS.md is at %s%% of the Codex budget — start moving detail into docs/\n' "$pct" >&2
  fi
fi

# ----------------------------------------------------------- 2 + 3. invariant anchors
if [ -f "$CANON" ]; then
  for id in "${INVARIANTS[@]}"; do
    n="$(grep -c -- "<!-- invariant: ${id} -->" "$CANON")"
    case "$n" in
      1) ok "invariant '${id}' is anchored in AGENTS.md" ;;
      0) fail "invariant '${id}' has no <!-- invariant: ${id} --> anchor in AGENTS.md — CI enforces a rule no agent is guaranteed to see" ;;
      *) fail "invariant '${id}' is anchored ${n} times in AGENTS.md — it must have exactly one home" ;;
    esac
  done

  # Anchors anywhere else mean a second copy of a rule. Search tracked files
  # only, so a scratch checkout or a build directory cannot trip the gate.
  strays=""
  if git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
    strays="$(git -C "$root" grep -l -- '<!-- invariant: ' -- '*.md' 2>/dev/null | grep -v '^AGENTS\.md$')"
  else
    strays="$(grep -rl --include='*.md' -- '<!-- invariant: ' "$root" 2>/dev/null | sed "s|^$root/||" | grep -v '^AGENTS\.md$')"
  fi
  if [ -n "$strays" ]; then
    while IFS= read -r f; do
      [ -n "$f" ] && fail "invariant anchor found outside AGENTS.md: ${f} — a rule with two homes drifts"
    done <<< "$strays"
  else
    ok "no invariant anchors outside AGENTS.md"
  fi
fi

# ----------------------------------------------------------------- 4 + 5. entrypoints
#
# Print <file> with every fenced code block removed, so the import matcher below
# sees only lines an agent would actually act on.
#
# The fence state tracks the OPENING delimiter, not just "a fence marker seen".
# CommonMark closes a fence only on the same character, at least as long as the
# opener, with nothing after it — so a literal ~~~ line inside a ``` block is
# content, and treating it as a close would re-open the file to the `@AGENTS.md`
# on the next line. An inert pointer certified as reachable is the exact failure
# this gate exists to prevent, so it is worth the extra state.
strip_fenced_blocks() {
  awk '
    {
      marker = ""
      if (match($0, /^[ \t]*(`+|~+)/)) {
        marker = substr($0, RSTART, RLENGTH)
        gsub(/^[ \t]*/, "", marker)
      }
      len = length(marker)
      if (len >= 3) {
        ch = substr(marker, 1, 1)
        if (!fence) {
          # An opener may carry an info string ("```sh"); a closer may not.
          fence = 1; fence_ch = ch; fence_len = len
          next
        }
        rest = substr($0, RSTART + RLENGTH)
        if (ch == fence_ch && len >= fence_len && rest ~ /^[ \t]*$/) {
          fence = 0
          next
        }
      }
      if (!fence) print
    }
  ' "$1"
}

# Does <file> carry a live <regex> import — one on its own line, outside every
# fenced code block?
#
# The regex is handed to `grep -E`, never to `awk -v`: awk processes C string
# escapes in a -v assignment, so `\.` arrives as a bare `.` — a wildcard — and
# `@xAGENTSymd` would certify as an import. One ERE, one engine.
import_line_outside_code() {
  strip_fenced_blocks "$1" | grep -Eq "$2"
}

# check_import <label> <file> <regex> <human-readable mechanism>
check_import() {
  local label="$1" file="$2" re="$3" mech="$4"
  if [ ! -f "$root/$file" ]; then
    fail "${label}: ${file} is missing — ${label} would resolve no repository instructions"
    return
  fi
  # An import must be its own line and must not be inside a code span or a fenced
  # block: Claude Code and Gemini CLI both skip those, so `@AGENTS.md` in
  # backticks is documentation, not an import.
  #
  # The check is per-LINE, deliberately. It used to be an anchored match for the
  # import plus a FILE-WIDE veto on any backticked mention — which vetoed a
  # perfectly good import the moment the same file explained itself, and a
  # pointer file that documents its own mechanism is the normal case here. The
  # veto was redundant as well as wrong: a line that is nothing but
  # `@AGENTS.md` in a code span cannot match the anchored regex anyway.
  #
  # What it could never catch, and this does, is the import sitting inside a
  # ``` fence. That line matches the anchored regex exactly and is still inert.
  if import_line_outside_code "$root/$file" "$re"; then
    ok "${label}: ${file} reaches AGENTS.md (${mech})"
  else
    fail "${label}: ${file} does not import AGENTS.md (${mech}). A markdown link is NOT followed — the invariants would be invisible."
  fi
  local size; size="$(bytes_of "$root/$file")"
  if [ "$size" -gt "$POINTER_MAX_BYTES" ]; then
    fail "${label}: ${file} is ${size} bytes, over the ${POINTER_MAX_BYTES}-byte pointer budget — it should carry ${label}-specific mechanics only, not a second copy of the rules"
  fi
}

check_import "Claude Code" "CLAUDE.md" '^@\.?/?AGENTS\.md[[:space:]]*$' '@path import, inlined at launch'
check_import "Gemini CLI"  "GEMINI.md" '^@\.?/?AGENTS\.md[[:space:]]*$' '@path.md memory import'

# Copilot reads root AGENTS.md natively, so its own file only has to point
# there — but it must exist, or `/init`-style flows and Copilot-specific
# configuration have nowhere to look.
COPILOT=".github/copilot-instructions.md"
if [ ! -f "$root/$COPILOT" ]; then
  fail "Copilot: ${COPILOT} is missing"
elif ! grep -q 'AGENTS\.md' "$root/$COPILOT"; then
  fail "Copilot: ${COPILOT} does not point at AGENTS.md"
else
  ok "Copilot: ${COPILOT} points at AGENTS.md (Copilot also reads root AGENTS.md natively)"
  csize="$(bytes_of "$root/$COPILOT")"
  [ "$csize" -gt "$POINTER_MAX_BYTES" ] && fail "Copilot: ${COPILOT} is ${csize} bytes, over the ${POINTER_MAX_BYTES}-byte pointer budget"
fi

# ------------------------------------------------------------------------- report
if [ "$report" = 1 ]; then
  printf '\n%-14s %-44s %10s %10s\n' AGENT ENTRYPOINT BYTES '~TOKENS'
  printf '%-14s %-44s %10s %10s\n' -------------- -------------------------------------------- ---------- ----------
  emit() { # emit <agent> <path...>
    local agent="$1"; shift
    local total=0 shown=""
    for f in "$@"; do
      [ -f "$root/$f" ] || continue
      total=$((total + $(bytes_of "$root/$f")))
      shown="${shown:+$shown + }$f"
    done
    printf '%-14s %-44s %10s %10s\n' "$agent" "$shown" "$total" "~$((total / 4))"
  }
  emit "Claude Code" CLAUDE.md AGENTS.md
  emit "Codex CLI"   AGENTS.md
  emit "Gemini CLI"  GEMINI.md AGENTS.md
  emit "Copilot"     .github/copilot-instructions.md AGENTS.md
  printf '\n~TOKENS is bytes/4, the usual rough estimate. Loaded every turn.\n'
  printf 'docs/AGENTS.md (%s bytes) is read on demand and is not in this table.\n' "$( [ -f "$root/docs/AGENTS.md" ] && bytes_of "$root/docs/AGENTS.md" || echo 0 )"
fi

printf '\n'
if [ "$problems" -eq 0 ]; then
  printf 'agent entrypoints: all invariants reachable from every agent.\n'
  exit 0
fi
printf '%s problem(s). See docs/AGENT_ENTRYPOINTS.md for how each agent resolves its instructions.\n' "$problems" >&2
exit 1
