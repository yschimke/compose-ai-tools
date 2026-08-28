#!/usr/bin/env bash
# Self-test for the agent-entrypoint reachability gate.
#
# The gate's failure mode is the same silent one it exists to catch: a matcher
# that stops matching still prints "all invariants reachable" and still exits 0.
# So this drives the real script against synthetic trees and asserts BOTH
# directions — what must be rejected, and what must be left alone.
#
#   scripts/test-check-agent-entrypoints.sh
set -uo pipefail

repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel 2>/dev/null)" \
  || repo_root="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="$repo_root/scripts/check-agent-entrypoints.sh"

if [ ! -x "$CHECK" ]; then
  printf 'missing or non-executable: %s\n' "$CHECK" >&2
  exit 2
fi

pass=0; fail=0
check() { # check <label> <expected-exit> <actual-exit>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1)); printf 'ok   %-62s (exit %s)\n' "$1" "$3"
  else
    fail=$((fail + 1)); printf 'FAIL %-62s (want %s, got %s)\n' "$1" "$2" "$3"
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# A minimal tree that must PASS, built from scratch rather than copied, so the
# test states what the gate actually requires rather than inheriting it.
make_good() { # make_good <dir>
  local d="$1"
  mkdir -p "$d/.github"
  {
    printf '# AGENTS.md\n\n'
    printf '<!-- invariant: agent-attribution -->\n### No agent attribution\nrule.\n\n'
    printf '<!-- invariant: branch-prefix -->\n### agent/ prefix\nrule.\n\n'
    printf '<!-- invariant: conventional-commits -->\n### Conventional commits\nrule.\n\n'
    printf '<!-- invariant: format-before-commit -->\n### Format first\nrule.\n\n'
    printf '<!-- invariant: pr-state-recheck -->\n### Re-check PR state\nrule.\n'
  } > "$d/AGENTS.md"
  printf '@AGENTS.md\n\n## Claude Code\nharness mechanics.\n' > "$d/CLAUDE.md"
  printf '@./AGENTS.md\n\npointer.\n' > "$d/GEMINI.md"
  printf '# Copilot\n\nRead AGENTS.md at the repository root.\n' > "$d/.github/copilot-instructions.md"
}

run() { "$CHECK" --root "$1" >/dev/null 2>&1; echo $?; }

# ---------------------------------------------------------------- must pass
good="$tmp/good"; make_good "$good"
check "a complete tree passes" 0 "$(run "$good")"

# The real repository must pass too — otherwise the gate is only ever green on
# a synthetic tree, which is worth nothing.
check "the real repository passes" 0 "$(run "$repo_root")"

# --------------------------------------------------------------- must fail
d="$tmp/no-agents"; make_good "$d"; rm "$d/AGENTS.md"
check "missing root AGENTS.md is rejected" 1 "$(run "$d")"

d="$tmp/oversized"; make_good "$d"
head -c 40000 /dev/zero | tr '\0' 'x' >> "$d/AGENTS.md"
check "AGENTS.md over Codex's 32 KiB budget is rejected" 1 "$(run "$d")"

d="$tmp/missing-anchor"; make_good "$d"
grep -v 'invariant: branch-prefix' "$d/AGENTS.md" > "$d/tmp" && mv "$d/tmp" "$d/AGENTS.md"
check "an unanchored invariant is rejected" 1 "$(run "$d")"

d="$tmp/dup-anchor"; make_good "$d"
printf '<!-- invariant: branch-prefix -->\n' >> "$d/AGENTS.md"
check "an invariant anchored twice is rejected" 1 "$(run "$d")"

d="$tmp/stray-anchor"; make_good "$d"; mkdir -p "$d/docs"
printf '<!-- invariant: branch-prefix -->\nsecond copy of the rule.\n' > "$d/docs/AGENT_GUIDE.md"
check "an invariant anchor outside AGENTS.md is rejected" 1 "$(run "$d")"

# The whole point: a markdown link is not an import for any of these agents.
d="$tmp/link-not-import"; make_good "$d"
printf 'See [AGENTS.md](AGENTS.md) for the rules.\n' > "$d/CLAUDE.md"
check "a markdown link in CLAUDE.md is not accepted as an import" 1 "$(run "$d")"

d="$tmp/gemini-link"; make_good "$d"
printf 'See [AGENTS.md](AGENTS.md) for the rules.\n' > "$d/GEMINI.md"
check "a markdown link in GEMINI.md is not accepted as an import" 1 "$(run "$d")"

# A backticked @AGENTS.md is documentation, not an import: both CLIs skip code
# spans. A file whose only reference is backticked must fail.
d="$tmp/backticked"; make_good "$d"
printf 'Import it with `@AGENTS.md` at the top.\n' > "$d/CLAUDE.md"
check "a backticked @AGENTS.md is not accepted as an import" 1 "$(run "$d")"

# ...but a file that carries a REAL import and also explains itself is fine. The
# check used to veto the whole file on any backticked mention, so a pointer that
# documented its own mechanism — the normal shape for these files — failed the
# gate with a live import sitting on line 1.
d="$tmp/import-and-prose"; make_good "$d"
printf '@AGENTS.md\n\nThe line above is an `@AGENTS.md` import, inlined at launch.\n' > "$d/CLAUDE.md"
check "a real import survives prose that mentions it in backticks" 0 "$(run "$d")"

# The case the file-wide veto could never see: an import inside a fenced block
# matches the anchored regex exactly and is still inert.
d="$tmp/fenced-import"; make_good "$d"
printf 'Put this at the top of the file:\n\n```\n@AGENTS.md\n```\n' > "$d/CLAUDE.md"
check "an @AGENTS.md inside a fence is not accepted as an import" 1 "$(run "$d")"

# A literal ~~~ inside a backtick fence does NOT close it. Toggling on either
# marker without remembering the opener re-opened the file mid-block, so the
# `@AGENTS.md` on the next line — still fenced, still inert — certified as an
# import.
d="$tmp/mixed-fence"; make_good "$d"
printf 'Example:\n\n```\n~~~\n@AGENTS.md\n```\n' > "$d/CLAUDE.md"
check "a ~~~ inside a backtick fence does not re-open the file" 1 "$(run "$d")"

# The import regex must keep its literal dots. `awk -v re=...` consumes C string
# escapes, so `\.` arrives as a wildcard and a pointer that names no real file
# passes the gate. The regex goes to grep -E and nowhere else.
d="$tmp/wildcard-dots"; make_good "$d"
printf '@xAGENTSymd\n' > "$d/CLAUDE.md"
printf '@xAGENTSymd\n' > "$d/GEMINI.md"
check "a pointer matching only via wildcarded dots is rejected" 1 "$(run "$d")"

# CommonMark caps fence indentation at three spaces: at four, a same-delimiter
# line is an indented code block — content — not a closer. Accepting unlimited
# leading whitespace re-opened the document at that line and certified the still
# fenced @AGENTS.md below it.
d="$tmp/overindented-fence"; make_good "$d"
printf '```\n    ```\n@AGENTS.md\n```\n' > "$d/CLAUDE.md"
check "a four-space-indented fence marker does not close the block" 1 "$(run "$d")"

# ...and three spaces still open and close one. Both cases below are shaped so that
# RECOGNISING the indented marker is what decides the exit status — an earlier
# version put a live `@AGENTS.md` on line 1 and then checked for exit 0, which the
# first line already guaranteed, so it would have passed with every trace of
# optional-space handling deleted.
#
# Opener: the file's ONLY `@AGENTS.md` is inside a three-space-indented fence. Miss
# the opener and the line is unfenced, reads as a live import, and the gate passes.
d="$tmp/indented-opener"; make_good "$d"
printf 'Put this at the top:\n\n   ```\n@AGENTS.md\n   ```\n' > "$d/CLAUDE.md"
check "a three-space-indented fence opens, hiding the import inside it" 1 "$(run "$d")"

# Closer: the import is AFTER a three-space-indented closing fence. Miss the closer
# and the rest of the file stays fenced, the import is invisible, and the gate fails.
d="$tmp/indented-closer"; make_good "$d"
printf 'Example:\n\n   ```\nfenced\n   ```\n\n@AGENTS.md\n' > "$d/CLAUDE.md"
check "a three-space-indented fence closes, so the import after it is live" 0 "$(run "$d")"

# A nested AGENTS.md is a second entrypoint, not a second document: Codex loads
# every one from the root down to its working directory, so one in `docs/` is
# inlined into any session started there — under the same 32 KiB budget, silently
# truncated past it. `docs/AGENTS.md` really was the 70 KiB contributor guide.
d="$tmp/nested-agents"; make_good "$d"; mkdir -p "$d/docs"
printf '# Contributor guide\n\nlong architecture notes.\n' > "$d/docs/AGENTS.md"
check "a nested AGENTS.md is rejected, whatever its size" 1 "$(run "$d")"

# ...and the same file under any other name is fine, which is the whole remedy.
d="$tmp/nested-renamed"; make_good "$d"; mkdir -p "$d/docs"
printf '# Contributor guide\n\nlong architecture notes.\n' > "$d/docs/AGENT_GUIDE.md"
check "the same guide under another name is fine" 0 "$(run "$d")"

d="$tmp/no-copilot"; make_good "$d"; rm "$d/.github/copilot-instructions.md"
check "missing copilot-instructions.md is rejected" 1 "$(run "$d")"

d="$tmp/copilot-silent"; make_good "$d"
printf '# Copilot\n\nUse two-space indentation.\n' > "$d/.github/copilot-instructions.md"
check "copilot-instructions.md that never names AGENTS.md is rejected" 1 "$(run "$d")"

# Pointer files that grow big enough to hold a second copy of the rules.
d="$tmp/fat-pointer"; make_good "$d"
{ printf '@AGENTS.md\n'; head -c 5000 /dev/zero | tr '\0' 'y'; } > "$d/CLAUDE.md"
check "an oversized CLAUDE.md pointer is rejected" 1 "$(run "$d")"

# ------------------------------------------------------------------ usage
"$CHECK" --root "$tmp/does-not-exist" >/dev/null 2>&1
check "a missing root is a usage error, not a pass" 2 "$?"
"$CHECK" --bogus >/dev/null 2>&1
check "an unknown argument is a usage error" 2 "$?"

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
