#!/usr/bin/env bash
#
# Self-test for the `changes` → `scope` STEP in .github/workflows/design-artifacts.yml.
#
# scope-systems.sh (and test-scope-systems.sh) cover the path→system mapping. This
# covers everything wrapped around it that only exists in the workflow YAML:
#
#   • force-all — a reusable workflow inherits the CALLER's github context, so on
#     the release chain `event_name` is `push` and the range is the release merge.
#     Without the explicit input, scoping would skip every system on the one run
#     that must republish them all.
#   • compare truncation — the compare endpoint caps `files` at 300 and paginates
#     commits, not files. A partial list is worse than none: a catalog past the cap
#     reads as "unchanged" and its branch rots while the run reports success.
#   • the fail-safe paths — branch creation, API failure — must regenerate
#     everything rather than nothing.
#
# The step body is extracted from the workflow at runtime, so this can't drift from
# what CI actually executes. `gh` is stubbed; nothing here touches the network.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/design-artifacts.yml"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
step="$work/scope-step.sh"
failures=0

# Extract the `scope` step's `run:` block by indentation — deliberately no PyYAML,
# which isn't a dependency this repo's CI scripts otherwise carry. The sentinel
# assertion below turns any formatting change that breaks the extraction into a
# loud failure rather than a test that silently exercises nothing.
awk '
  /^      - id: scope$/       { in_step = 1; next }
  in_step && /^        run: \|/ { in_run = 1; next }
  in_run {
    # The block ends at the first non-blank line indented less than its body.
    if ($0 !~ /^[[:space:]]*$/ && $0 !~ /^          /) exit
    sub(/^          /, "")
    print
  }
' "$workflow" > "$step"

if ! grep -q 'scope-systems.sh' "$step"; then
  echo "could not extract the 'scope' step from $workflow — did its indentation change?" >&2
  exit 2
fi

# run_case <name> <want> <event> <force-all> <before> [changed files…]
#   want: comma-separated systems, or "none"
#   a literal "TRUNCATED" file list stands in for a 300-file (capped) response;
#   "APIFAIL" makes the stubbed gh exit non-zero.
run_case() {
  local name="$1" want="$2" event="$3" force="$4" before="$5"; shift 5
  local dir; dir="$(mktemp -d "$work/case.XXXXXX")"
  mkdir -p "$dir/bin"

  case "${1:-}" in
    APIFAIL) printf '#!/usr/bin/env bash\nexit 1\n' > "$dir/bin/gh" ;;
    TRUNCATED)
      python3 -c "
import json
print(json.dumps({'files': [{'filename': 'src/f%d.kt' % i} for i in range(300)]}))" > "$dir/compare.json"
      printf '#!/usr/bin/env bash\ncat %q\n' "$dir/compare.json" > "$dir/bin/gh" ;;
    *)
      python3 -c "
import json, sys
print(json.dumps({'files': [{'filename': n} for n in sys.argv[1:]]}))" "$@" > "$dir/compare.json"
      printf '#!/usr/bin/env bash\ncat %q\n' "$dir/compare.json" > "$dir/bin/gh" ;;
  esac
  chmod +x "$dir/bin/gh"

  local got rc
  (
    cd "$repo_root"
    export PATH="$dir/bin:$PATH"
    export EVENT="$event" FORCE_ALL="$force" BEFORE="$before" AFTER=headsha \
           REPO=yschimke/compose-ai-tools GH_TOKEN=stub \
           GITHUB_OUTPUT="$dir/out" GITHUB_STEP_SUMMARY="$dir/summary"
    : > "$GITHUB_OUTPUT"; : > "$GITHUB_STEP_SUMMARY"
    bash "$step" >/dev/null 2>&1
  )
  rc=$?
  if [ "$rc" -ne 0 ]; then
    printf 'FAIL  %s -> step exited %d\n' "$name" "$rc"
    failures=$((failures + 1))
    return
  fi
  got="$(grep '=true$' "$dir/out" | cut -d= -f1 | paste -sd, -)"
  : "${got:=none}"
  if [ "$got" = "$want" ]; then
    printf 'PASS  %s -> %s\n' "$name" "$got"
  else
    printf 'FAIL  %s -> got "%s", want "%s"\n' "$name" "$got" "$want"
    failures=$((failures + 1))
  fi
}

ALL='compose-m3,wear-m3'

# --- ordinary merge scoping -------------------------------------------------
run_case 'push: wear catalog only'  'wear-m3'   push '' before samples/design-catalog-wear-m3/A.kt
run_case 'push: two catalogs'       'compose-m3,wear-m3' push '' before \
  samples/design-catalog-m3/A.kt samples/design-catalog-wear-m3/B.kt
run_case 'push: renderer only'      'none'      push '' before gradle-plugin/src/main/kotlin/A.kt

# --- release chain: workflow_call inherits the caller's `push` event ---------
run_case 'release chain (force-all, event=push)' "$ALL" push true before \
  CHANGELOG.md gradle.properties version.txt
# The same shape WITHOUT force-all scopes to nothing — this is the regression the
# input exists to prevent, asserted so a future refactor can't quietly undo it.
run_case 'release-shaped push, no force-all' 'none' push '' before \
  CHANGELOG.md gradle.properties version.txt

# --- compare-endpoint 300-file cap ------------------------------------------
run_case 'compare truncated at cap' "$ALL" push '' before TRUNCATED

# --- fail-safe paths --------------------------------------------------------
run_case 'compare API failure' "$ALL" push '' before APIFAIL
run_case 'branch creation (all-zero before)' "$ALL" push '' \
  0000000000000000000000000000000000000000 ignored.txt

# --- non-push events regenerate everything ----------------------------------
run_case 'cron'     "$ALL" schedule          '' before ignored.txt
run_case 'dispatch' "$ALL" workflow_dispatch '' before ignored.txt

if [ "$failures" -ne 0 ]; then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
printf '\nall checks passed\n'
