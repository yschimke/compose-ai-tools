#!/usr/bin/env bash
#
# Self-test for scripts/design-artifacts/scope-step.sh — the whole `Scope` decision.
#
# scope-systems.sh (and test-scope-systems.sh) cover the path→system mapping. This
# covers everything wrapped around it:
#
#   • the PER-LANE baseline (issue #5438). Each lane is diffed from the commit it
#     was last rendered from, not from the one push that started the run, so a lane
#     whose run was cancelled while pending is picked up by the next push whatever
#     that push touched. The first case below is that exact failure.
#   • force-all — a reusable workflow inherits the CALLER's github context, so on
#     the release chain `event_name` is `push` and the range is the release merge.
#     Without the explicit input, scoping would skip every system on the one run
#     that must republish them all.
#   • compare truncation — the compare endpoint caps `files` at 300 and paginates
#     commits, not files. A partial list is worse than none: a catalog past the cap
#     reads as "unchanged" and its branch rots while the run reports success.
#   • the fail-safe paths — branch creation, API failure, a baseline that is not an
#     ancestor of HEAD — must regenerate everything rather than nothing.
#   • the dispatch lane selector, so the manual remedy for one stale lane costs one
#     render rather than every catalog in the repository.
#
# `gh` is stubbed; nothing here touches the network. The step body used to be
# extracted from the workflow YAML at runtime to keep the two from drifting; it is a
# script now, which both callers and this test run directly — so the drift check is
# the assertion below that the workflow still invokes it.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/design-artifacts.yml"
step="$repo_root/scripts/design-artifacts/scope-step.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

if ! grep -q 'scope-step.sh' "$workflow"; then
  echo "$workflow no longer runs scope-step.sh — this test would be exercising nothing." >&2
  exit 2
fi

# A `gh api` stub. It answers the three endpoints the step uses, off a fixture the
# case writes: the source marker ref, the delivery branch tip, and compare.
cat > "$work/gh" <<'STUB'
#!/usr/bin/env python3
import json, os, re, sys

fixture = json.load(open(os.environ["GH_FIXTURE"]))
args = sys.argv[1:]
if not args or args[0] != "api":
    sys.exit(1)
endpoint = args[1]
jq = args[args.index("--jq") + 1] if "--jq" in args else None

def answer(value):
    if value is None:
        sys.exit(1)
    print(value if jq else json.dumps(value))
    sys.exit(0)

m = re.search(r"/git/ref/design-artifacts/source/(.+)$", endpoint)
if m:
    sha = fixture.get("refs", {}).get(m.group(1))
    answer(sha if jq else ({"object": {"sha": sha}} if sha else None))

m = re.search(r"/commits/design-artifacts/(.+)$", endpoint)
if m:
    msg = fixture.get("subjects", {}).get(m.group(1))
    answer(msg if jq else ({"commit": {"message": msg}} if msg else None))

m = re.search(r"/compare/(.+?)\.\.\.(.+)$", endpoint)
if m:
    if fixture.get("apifail"):
        sys.exit(1)
    base = m.group(1)
    body = fixture.get("compare", {}).get(base, fixture.get("compare", {}).get("*"))
    if body is None:
        sys.exit(1)
    files = body.get("files", [])
    if files == ["TRUNCATED"]:
        files = ["src/f%d.kt" % i for i in range(300)]
    answer({"status": body.get("status", "ahead"),
            "files": [{"filename": n} for n in files]})

sys.exit(1)
STUB
chmod +x "$work/gh"

# run_case <name> <want> <fixture-json> [env assignments…]
#   want: comma-separated systems, or "none"
run_case() {
  local name="$1" want="$2" fixture="$3"; shift 3
  local dir; dir="$(mktemp -d "$work/case.XXXXXX")"
  mkdir -p "$dir/bin"
  cp "$work/gh" "$dir/bin/gh"
  printf '%s' "$fixture" > "$dir/fixture.json"

  local got rc
  got="$(
    cd "$repo_root" || exit 1
    export PATH="$dir/bin:$PATH" GH_FIXTURE="$dir/fixture.json"
    export EVENT=push FORCE_ALL='' ONLY='' BEFORE=before AFTER=headsha \
           REPO=yschimke/compose-ai-tools GH_TOKEN=stub \
           GITHUB_STEP_SUMMARY="$dir/summary"
    # The function's positional parameters survive into this subshell, so the
    # per-case `KEY=value` overrides arrive here without a second transport.
    for kv in "$@"; do export "${kv?}"; done
    bash "$step" 2>"$dir/err"
  )"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    printf 'FAIL  %s -> step exited %d\n%s\n' "$name" "$rc" "$(cat "$dir/err")"
    failures=$((failures + 1))
    return
  fi
  got="$(grep '=true$' <<<"$got" | cut -d= -f1 | paste -sd, -)"
  : "${got:=none}"
  if [ "$got" = "$want" ]; then
    printf 'PASS  %s -> %s\n' "$name" "$got"
  else
    printf 'FAIL  %s -> got "%s", want "%s"\n' "$name" "$got" "$want"
    failures=$((failures + 1))
  fi
}

ALL='compose-m3,wear-m3'
PUB_WEAR='"refs":{"wear-m3":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'

# --- the defect issue #5438 describes ---------------------------------------
# wear-m3's own run was cancelled while pending, so its published source still
# predates the wear change. THIS push touched only remote config — `before...head`
# is empty of catalog files, which is what used to scope the lane away for good.
# Diffing from the lane's baseline instead still sees the unrendered change.
run_case 'cancelled run is picked up by the next push' 'wear-m3' \
  "{$PUB_WEAR,\"compare\":{
      \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\":{\"files\":[\"samples/design-catalog-wear-m3/A.kt\"]},
      \"*\":{\"files\":[\"remote-catalog/x.json\"]}}}"

# --- a lane already rendered from HEAD is not re-rendered -------------------
run_case 'baseline identical to HEAD' 'none' \
  "{$PUB_WEAR,\"compare\":{\"*\":{\"status\":\"identical\",\"files\":[]}}}"

# --- baseline resolution order ----------------------------------------------
# The marker ref wins over the delivery branch subject: the subject lags by every
# render whose output was byte-identical (SKIP_IF_UNCHANGED commits nothing).
run_case 'marker ref preferred over branch subject' 'none' \
  "{$PUB_WEAR,
    \"subjects\":{\"wear-m3\":\"chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12, bbbbbbbb)\"},
    \"compare\":{\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\":{\"files\":[\"docs/x.md\"]},
                 \"bbbbbbbb\":{\"files\":[\"samples/design-catalog-wear-m3/A.kt\"]},
                 \"*\":{\"files\":[\"docs/x.md\"]}}}"
# With no ref, the subject is the baseline — this is a branch published before the
# marker existed.
run_case 'branch subject used when no marker ref' 'wear-m3' \
  "{\"subjects\":{\"wear-m3\":\"chore(design-artifacts): regenerate wear-m3 catalog (2026-09-12, bbbbbbbb)\"},
    \"compare\":{\"bbbbbbbb\":{\"files\":[\"samples/design-catalog-wear-m3/A.kt\"]},
                 \"*\":{\"files\":[\"docs/x.md\"]}}}"
# A lane that has never published has neither, and falls back to this push's range —
# the behaviour that shipped.
run_case 'falls back to the push range' 'compose-m3' \
  "{\"compare\":{\"before\":{\"files\":[\"samples/design-catalog-m3/A.kt\"]}}}"

# --- ordinary merge scoping -------------------------------------------------
run_case 'push: renderer only' 'none' \
  '{"compare":{"*":{"files":["gradle-plugin/src/main/kotlin/A.kt"]}}}'
run_case 'push: two catalogs' "$ALL" \
  '{"compare":{"*":{"files":["samples/design-catalog-m3/A.kt","samples/design-catalog-wear-m3/B.kt"]}}}'

# --- fail-safe paths --------------------------------------------------------
# A baseline that is not an ancestor of HEAD (force-push, or a marker written from
# another branch) describes nothing this run can catch up on.
run_case 'baseline diverged from HEAD' "$ALL" \
  '{"compare":{"*":{"status":"diverged","files":["docs/x.md"]}}}'
run_case 'compare truncated at cap' "$ALL" '{"compare":{"*":{"files":["TRUNCATED"]}}}'
run_case 'compare API failure'      "$ALL" '{"apifail":true}'
run_case 'branch creation (all-zero before)' "$ALL" '{}' \
  BEFORE=0000000000000000000000000000000000000000

# --- release chain: workflow_call inherits the caller's `push` event ---------
run_case 'release chain (force-all, event=push)' "$ALL" \
  '{"compare":{"*":{"files":["CHANGELOG.md","gradle.properties"]}}}' FORCE_ALL=true
# The same shape WITHOUT force-all scopes to nothing — this is the regression the
# input exists to prevent, asserted so a future refactor can't quietly undo it.
run_case 'release-shaped push, no force-all' 'none' \
  '{"compare":{"*":{"files":["CHANGELOG.md","gradle.properties"]}}}'

# --- non-push events regenerate everything ----------------------------------
run_case 'cron'     "$ALL" '{}' EVENT=schedule
run_case 'dispatch' "$ALL" '{}' EVENT=workflow_dispatch

# --- the dispatch lane selector ---------------------------------------------
run_case 'dispatch: one lane'  'wear-m3' '{}' EVENT=workflow_dispatch ONLY=wear-m3
run_case 'dispatch: two lanes' "$ALL"    '{}' EVENT=workflow_dispatch ONLY=compose-m3,wear-m3
# The form a human types, and the one the workflow input's own description shows.
# It used to scope away every lane after the first, silently.
run_case 'dispatch: comma-space' "$ALL" '{}' EVENT=workflow_dispatch 'ONLY=compose-m3, wear-m3'
# The selector outranks the everything-regenerates rule a dispatch would otherwise
# hit, which is the entire reason it exists.
run_case 'selector beats force-all' 'compose-m3' '{}' FORCE_ALL=true ONLY=compose-m3

if [ "$failures" -ne 0 ]; then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
printf '\nall checks passed\n'
