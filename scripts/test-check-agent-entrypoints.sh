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
printf '<!-- invariant: branch-prefix -->\nsecond copy of the rule.\n' > "$d/docs/AGENTS.md"
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
