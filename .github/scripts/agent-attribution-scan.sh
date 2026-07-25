#!/usr/bin/env bash
# Shared agent-attribution detector for the "No Agent Attribution" gate.
#
# Two callers, one implementation — a PR-layer check that blocks the merge and a
# post-merge drift check on `main`. They MUST agree, so the matching lives here
# rather than being copy-pasted into each job: a gate that disagrees with itself
# is worse than no gate.
#
# Usage:
#   agent-attribution-scan.sh --range <base>..<head> [--text-file <file>]
#
#   --range      commit range to scan for author/committer identity and for
#                Co-authored-by trailers in the commit messages.
#   --text-file  extra text to scan for trailers (the PR title + body, which the
#                squash commit is built from). Optional — the drift check has no
#                PR to read.
#
# Exits 1 and prints the offending records when anything is found, 0 otherwise.
#
# Keep AGENT_NAME / AGENT_EMAIL in sync with .githooks/commit-msg and the
# workflow's documentation block.
set -uo pipefail

AGENT_NAME='(Claude|Codex|ChatGPT|Copilot|Gemini)'
AGENT_EMAIL='@(anthropic|openai)[.]com'

range=""
text_file=""
while [ $# -gt 0 ]; do
  case "$1" in
    --range) range="${2:-}"; shift 2 ;;
    --text-file) text_file="${2:-}"; shift 2 ;;
    *) printf 'agent-attribution-scan: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[ -n "$range" ] || { printf 'agent-attribution-scan: --range is required\n' >&2; exit 2; }

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

problems=""

# 1. Author / committer identity of every commit in the range.
#
#    This is also what produces a `Co-authored-by:` trailer on `main`: GitHub's
#    squash credits every distinct commit author of the branch as a co-author,
#    so an agent identity here lands a trailer even when the PR title and body
#    are spotless. That is not hypothetical — it is how commit 5aacb786 got its
#    `Co-authored-by: Claude` line.
#
#    One record per identity: <sha>\t<role>\t<name>\t<email>.
git log \
  --format='%H%x09author%x09%an%x09%ae%x0a%H%x09committer%x09%cn%x09%ce' \
  "$range" > "$workdir/idents.txt"
# tolower() rather than IGNORECASE so this holds on mawk (the default awk on
# ubuntu-latest) as well as gawk. The email match is a substring (any
# …@anthropic.com / …@openai.com address), but the NAME match is anchored to the
# whole identity ("^…$") so it catches an identity set literally to
# "Claude"/"Codex"/… without snagging a human whose name merely contains the
# token ("Claude Martin", "Alice Copilotson") — and it naturally exempts GitHub
# App bots, whose name is "claude[bot]", not "claude".
ident_hits="$(awk -F'\t' -v nre="$AGENT_NAME" -v ere="$AGENT_EMAIL" '
  BEGIN { nre = tolower(nre); ere = tolower(ere) }
  tolower($4) ~ ere            { print; next }
  tolower($3) ~ ("^" nre "$")  { print }
' "$workdir/idents.txt" || true)"
if [ -n "$ident_hits" ]; then
  problems="${problems}Commit author/committer identity is an agent:
${ident_hits}
"
fi

# 2. Co-authored-by trailers (start-of-line only) in commit messages, and in the
#    supplied text (PR title + body) when given — flagged when the name or email
#    is an agent. Trailer position only, so prose that merely *mentions* a
#    trailer does not trip the gate.
{
  git log --format='%B' "$range"
  [ -n "$text_file" ] && cat "$text_file"
} > "$workdir/all_text.txt"

coauthor_hits="$(grep -iE '^[[:space:]]*Co-authored-by:' "$workdir/all_text.txt" \
                 | grep -Ei "Co-authored-by:[[:space:]]*${AGENT_NAME}|${AGENT_EMAIL}" \
                 || true)"
if [ -n "$coauthor_hits" ]; then
  problems="${problems}Agent Co-authored-by trailer(s):
${coauthor_hits}
"
fi

if [ -n "$problems" ]; then
  printf '%s\n' "$problems" >&2
  exit 1
fi

echo "No agent co-author trailers or agent commit identities found."
