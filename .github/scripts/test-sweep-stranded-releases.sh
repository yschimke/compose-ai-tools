#!/usr/bin/env bash
# Self-test for sweep-stranded-releases.sh, run by CI on every PR.
#
# The sweeper only ever executes against a release that has already gone wrong, and both of its
# failure directions are expensive: too eager and it publishes a release whose artifacts are not
# there (the "release not found" install race `finalize-release` exists to prevent, or worse, a
# `/releases/latest` whose Gradle plugin never reached Maven Central); too shy and a draft sits
# unpublished until somebody notices — which is the bug it was written for. So the rules are
# pinned here against a stub `gh`/`curl` rather than discovered during the next stuck release:
#   1. a release in flight is never touched;
#   2. neither is a draft young enough to still be publishing itself;
#   3. a stranded draft with every asset and the plugin on Central IS published;
#   4. …but not while Central has not got the plugin yet;
#   5. a draft whose assets never built gets release.yml dispatched for its tag, not published;
#   6. …at most once per cooldown, so one broken release is not a wall of red runs;
#   7. a missing tag is created only at an explicit commit SHA, never at a branch;
#   8. anything that is not v<major>.<minor>.<patch> is left alone; and
#   9. a draft stuck past the escalation window fails the job, which is the only way a scheduled
#      run nobody watches can ask for a human.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/sweep-stranded-releases.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# --- stubs ---------------------------------------------------------------------------------
# `gh` serves fixtures/<path, non-alphanumerics collapsed to _>.json for reads and records every
# call in calls.log; a missing fixture exits non-zero, which is how a 404 is expressed (an absent
# tag ref, in particular). Writes are logged and answered blandly. `--jq` runs for real, so the
# script's own jq expressions are exercised rather than mocked away. `curl` stands in for Maven
# Central: CURL_OK decides whether the plugin POM is there.
mkdir -p "${tmp}/bin" "${tmp}/fixtures"
cat > "${tmp}/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
[[ "${1:-}" == "api" ]] || { echo "stub gh: unexpected command $*" >&2; exit 64; }
shift
method=GET path="" jq_expr="" read_stdin=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --method) method="$2"; shift 2 ;;
    --jq) jq_expr="$2"; shift 2 ;;
    -H) shift 2 ;;
    -f) printf 'FIELD %s\n' "$2" >> "${GH_STUB_LOG}"; shift 2 ;;
    --input) read_stdin=1; shift 2 ;;
    --silent) shift ;;
    *) path="$1"; shift ;;
  esac
done
[[ "${read_stdin}" == 1 ]] && printf 'BODY %s\n' "$(tr '\n' ' ' | tr -s ' ')" >> "${GH_STUB_LOG}"
printf '%s %s\n' "${method}" "${path}" >> "${GH_STUB_LOG}"
[[ "${method}" == "GET" ]] || { echo '{"id":1}'; exit 0; }
fixture="${GH_STUB_FIXTURES}/$(printf '%s' "${path}" | tr -c '[:alnum:]._-' '_').json"
[[ -f "${fixture}" ]] || { echo "stub gh: 404 ${path}" >&2; exit 1; }
if [[ -n "${jq_expr}" ]]; then jq -r "${jq_expr}" < "${fixture}"; else cat "${fixture}"; fi
STUB
cat > "${tmp}/bin/curl" <<'STUB'
#!/usr/bin/env bash
printf 'CURL %s\n' "${*: -1}" >> "${GH_STUB_LOG}"
[[ "${CURL_OK:-true}" == true ]]
STUB
chmod +x "${tmp}/bin/gh" "${tmp}/bin/curl"

export PATH="${tmp}/bin:${PATH}"
export GH_STUB_FIXTURES="${tmp}/fixtures"
export GITHUB_REPOSITORY="yschimke/compose-ai-tools"

fixture() { printf '%s' "$2" > "${GH_STUB_FIXTURES}/$(printf '%s' "$1" | tr -c '[:alnum:]._-' '_').json"; }
unfixture() { rm -f "${GH_STUB_FIXTURES}/$(printf '%s' "$1" | tr -c '[:alnum:]._-' '_').json"; }
ago() { date -u -d "$1 minutes ago" +%Y-%m-%dT%H:%M:%SZ; }

RELEASES="repos/yschimke/compose-ai-tools/releases?per_page=100"
ASSETS="repos/yschimke/compose-ai-tools/releases/9001/assets?per_page=100"
TAG_REF="repos/yschimke/compose-ai-tools/git/ref/tags/v1.78.0"
DISPATCHES="repos/yschimke/compose-ai-tools/actions/workflows/release.yml/runs?event=workflow_dispatch&per_page=1"
SHA="0123456789abcdef0123456789abcdef01234567"

ALL_ASSETS='[{"name":"compose-preview-1.78.0.tar.gz"},{"name":"compose-preview-1.78.0.zip"},
             {"name":"compose-preview-android-daemon-1.78.0.zip"}]'

# `draft <age-in-minutes> [target]` — the one stranded v1.78.0 draft, cut <age> minutes ago.
draft() {
  fixture "${RELEASES}" \
    "[{\"id\":9001,\"draft\":true,\"tag_name\":\"v1.78.0\",\"created_at\":\"$(ago "$1")\",\"target_commitish\":\"${2:-${SHA}}\"}]"
}

# `run <case>` — fresh call log per case, left in $log; the script's stdout lands in <case>.stdout.
run() {
  log="${tmp}/$1.log"; : > "${log}"
  GH_STUB_LOG="${log}" "${UNDER_TEST}" > "${tmp}/$1.stdout" 2>&1
  status=$?
}
logged() { grep -qF "$2" "${tmp}/$1.log"; }

# Quiet workflows by default: `total_count: 0` for every in-flight query.
for wf in release-please.yml release.yml; do
  for status in queued in_progress; do
    fixture "repos/yschimke/compose-ai-tools/actions/workflows/${wf}/runs?status=${status}&per_page=1" '{"total_count": 0}'
  done
done
fixture "${TAG_REF}" '{"object":{"sha":"'"${SHA}"'"}}'
fixture "${ASSETS}" "${ALL_ASSETS}"

# 1. A release in flight owns the draft it just cut — the sweeper must not race finalize-release.
draft 90
fixture "repos/yschimke/compose-ai-tools/actions/workflows/release.yml/runs?status=in_progress&per_page=1" '{"total_count": 1}'
run inflight
[[ $status -eq 0 ]] || fail "an in-flight release should not fail the sweep"
logged inflight "PATCH" && fail "an in-flight release must not be published by the sweeper"
logged inflight "${RELEASES}" && fail "an in-flight release should short-circuit before listing drafts"
fixture "repos/yschimke/compose-ai-tools/actions/workflows/release.yml/runs?status=in_progress&per_page=1" '{"total_count": 0}'

# 2. A draft cut minutes ago is a release publishing normally, not a stranded one.
draft 10
run young
[[ $status -eq 0 ]] || fail "a young draft should not fail the sweep"
logged young "PATCH" && fail "a draft inside the release window must be left alone"

# 3. The bug this exists for: v1.78.0's run died holding the draft. Assets are all there and the
#    plugin is on Central, so the sweeper finishes what finalize-release never got to.
draft 90
run publish
[[ $status -eq 0 ]] || fail "publishing a stranded draft should exit 0"
logged publish "PATCH repos/yschimke/compose-ai-tools/releases/9001" || fail "a stranded, complete draft should be published"
logged publish '"draft": false' || fail "the publish must un-draft the release"
logged publish 'make_latest' || fail "the publish must mark the release latest"

# 4. Assets present but the plugin is not on Central: publishing would point /releases/latest at a
#    version no Gradle build can resolve. finalize-release gets this from the release job's result.
CURL_OK=false run central
[[ $status -eq 0 ]] || fail "a draft waiting on Central propagation should not fail the sweep"
logged central "PATCH" && fail "a release whose plugin is not on Central must stay a draft"

# 5. Assets missing means the build never finished — rebuild it for the tag rather than publish.
fixture "${ASSETS}" '[{"name":"compose-preview-1.78.0.zip"}]'
run rebuild
[[ $status -eq 0 ]] || fail "dispatching a rebuild should exit 0"
logged rebuild "POST repos/yschimke/compose-ai-tools/actions/workflows/release.yml/dispatches" \
  || fail "a draft missing assets should dispatch release.yml"
logged rebuild '"tag": "v1.78.0"' || fail "the dispatch must name the stranded tag"
logged rebuild "PATCH" && fail "a draft missing assets must never be published"

# 6. …but a dispatched run that failed will fail again; hourly re-dispatch is a wall of red runs.
fixture "${DISPATCHES}" "{\"workflow_runs\":[{\"created_at\":\"$(ago 30)\"}]}"
run cooldown
logged cooldown "dispatches" && fail "a recovery dispatched 30m ago should suppress another"
unfixture "${DISPATCHES}"
fixture "${ASSETS}" "${ALL_ASSETS}"

# 7. A missing tag: release.yml checks out `ref: <tag>`, so nothing can be rebuilt without it.
#    Created at the draft's commit — and only when that is a commit, never a branch tip, which
#    would ship a release built from commits it never contained.
unfixture "${TAG_REF}"
run tagged
logged tagged "POST repos/yschimke/compose-ai-tools/git/refs" || fail "a missing tag should be created at the draft's SHA"
logged tagged "\"sha\": \"${SHA}\"" || fail "the tag must be created at the draft's own commit"

draft 90 main
run branch_target
logged branch_target "POST repos/yschimke/compose-ai-tools/git/refs" && fail "a branch target must not be tagged"
logged branch_target "PATCH" && fail "a draft that could not be tagged must not be published"
fixture "${TAG_REF}" '{"object":{"sha":"'"${SHA}"'"}}'

# 8. GitHub's own `untagged-…` drafts, and anything else not release-shaped, are not ours.
fixture "${RELEASES}" "[{\"id\":9001,\"draft\":true,\"tag_name\":\"nightly\",\"created_at\":\"$(ago 90)\",\"target_commitish\":\"${SHA}\"}]"
run foreign
[[ $status -eq 0 ]] || fail "a non-release draft should not fail the sweep"
logged foreign "PATCH" && fail "a draft that is not v<major>.<minor>.<patch> must be left alone"

# 9. Nothing here can fix a release stuck for a day — say so loudly, since a scheduled job's only
#    way to ask for a human is to go red.
draft 4000
CURL_OK=false run escalate
[[ $status -eq 1 ]] || fail "a draft stuck past the escalation window should fail the job"
grep -q "::error::" "${tmp}/escalate.stdout" || fail "escalation should print an error annotation"

# …and a draft the sweep DOES finish is not an escalation, however long it sat.
draft 4000
run late_publish
[[ $status -eq 0 ]] || fail "publishing a long-stuck draft should exit 0, not escalate"

# 10. No drafts at all is the steady state, and must stay silent and green.
fixture "${RELEASES}" '[{"id":1,"draft":false,"tag_name":"v1.78.0","created_at":"'"$(ago 90)"'","target_commitish":"'"${SHA}"'"}]'
run steady
[[ $status -eq 0 ]] || fail "the steady state should exit 0"
logged steady "PATCH" && fail "a published release must not be touched"

if (( failures > 0 )); then
  echo "${failures} sweep-stranded-releases.sh test(s) failed." >&2
  exit 1
fi
echo "sweep-stranded-releases.sh: all tests passed."
