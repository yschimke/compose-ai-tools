#!/usr/bin/env bash
# Self-test for release-pr-race-guard.sh, run by CI on every PR.
#
# Drives the script against a stub `gh` on PATH serving canned API responses, because every rule
# it encodes only ever executes during a release, where getting it wrong is either invisible or
# expensive:
#   1. the steady state — the version main carries is published — must NOT block the release PR;
#   2. a version that is not published yet IS a release in flight, and the PR half must skip;
#   3. a version left unpublished for hours is a STRANDED release, not one in flight: the guard
#      must fail open rather than freeze the release PR forever (0.16.34's wedge);
#   4. an unreadable manifest fails open for the same reason;
#   5. repair mode closes the duplicate release PR a mid-invocation merge produced (#4645);
#   6. …but only release-please's own open PR for the version being released — never a PR titled
#      for another version, and never a human's PR sitting on a release-please-shaped branch.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/release-pr-race-guard.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# --- stub gh -------------------------------------------------------------------------------
# Serves fixtures/<path, non-alphanumerics collapsed to _>.json for reads and records every call
# in calls.log. A missing fixture exits non-zero, which is how a 404 (an unpublished release, in
# particular) is expressed — the endpoint the script relies on does not resolve drafts. `--jq`
# runs for real so the script's own jq expressions are exercised rather than mocked away.
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
chmod +x "${tmp}/bin/gh"

export PATH="${tmp}/bin:${PATH}"
export GH_STUB_FIXTURES="${tmp}/fixtures"
export GITHUB_REPOSITORY="yschimke/compose-ai-tools"

fixture() { printf '%s' "$2" > "${GH_STUB_FIXTURES}/$(printf '%s' "$1" | tr -c '[:alnum:]._-' '_').json"; }

MANIFEST_PATH="repos/yschimke/compose-ai-tools/contents/.release-please-manifest.json?ref=main"
BUMP_PATH="repos/yschimke/compose-ai-tools/commits?sha=main&path=.release-please-manifest.json&per_page=1"
RELEASE_PATH="repos/yschimke/compose-ai-tools/releases/tags/v1.42.0"
PR_PATH="repos/yschimke/compose-ai-tools/pulls/4645"

# `run <case> <MODE> [RP_PR]` — fresh log and GITHUB_OUTPUT per case; leaves both in $log/$out.
run() {
  log="${tmp}/$1.log"; out="${tmp}/$1.out"; : > "${log}"; : > "${out}"
  GH_STUB_LOG="${log}" GITHUB_OUTPUT="${out}" MODE="$2" RP_PR="${3:-}" \
    "${UNDER_TEST}" > "${tmp}/$1.stdout" 2>&1
}
output() { grep -m1 "^$2=" "${tmp}/$1.out" | cut -d= -f2- ; }

fixture "${MANIFEST_PATH}" '{".": "1.42.0"}'
fixture "${BUMP_PATH}" "[{\"commit\":{\"committer\":{\"date\":\"$(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\"}}}]"

# 1. The manifest version is published: the ordinary steady state between releases, where the PR
#    half has to run or the release PR never reflects anything merged since.
fixture "${RELEASE_PATH}" '{"draft": false}'
run published check
[[ "$(output published in_flight)" == "false" ]] || fail "a published version should not read as in flight"

# 2. No published release for the version main already carries — the 17 minutes between merging
#    the release PR and `finalize-release` un-drafting v1.42.0, when release-please would
#    re-propose 1.42.0 on top of itself.
rm -f "${GH_STUB_FIXTURES}/$(printf '%s' "${RELEASE_PATH}" | tr -c '[:alnum:]._-' '_').json"
run inflight check
[[ "$(output inflight in_flight)" == "true" ]] || fail "an unpublished manifest version should read as in flight"
[[ "$(output inflight version)" == "1.42.0" ]] || fail "check mode should report the version it judged"

# 3. Same state, but the manifest was bumped days ago: that is a release that never finished, and
#    treating it as in flight would stop the release PR from ever updating again.
fixture "${BUMP_PATH}" "[{\"commit\":{\"committer\":{\"date\":\"$(date -u -d '3 days ago' +%Y-%m-%dT%H:%M:%SZ)\"}}}]"
run stranded check
[[ "$(output stranded in_flight)" == "false" ]] || fail "a stranded release should not block the PR half forever"
grep -q '::warning::' "${tmp}/stranded.stdout" || fail "a stranded release should say so"

# 4. Fail open when the manifest cannot be read at all.
mv "${GH_STUB_FIXTURES}/$(printf '%s' "${MANIFEST_PATH}" | tr -c '[:alnum:]._-' '_').json" "${tmp}/manifest.bak"
run unreadable check
[[ "$(output unreadable in_flight)" == "false" ]] || fail "an unreadable manifest should fail open"
mv "${tmp}/manifest.bak" "${GH_STUB_FIXTURES}/$(printf '%s' "${MANIFEST_PATH}" | tr -c '[:alnum:]._-' '_').json"

# --- repair mode ---------------------------------------------------------------------------
fixture "${BUMP_PATH}" "[{\"commit\":{\"committer\":{\"date\":\"$(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\"}}}]"

# 5. The release PR merged while release-please was computing, and it opened #4645 for the very
#    version being released. That PR is the artefact this whole guard exists to prevent.
fixture "${PR_PATH}" '{"state":"open","title":"chore(main): release 1.42.0",
  "user":{"login":"github-actions[bot]"},"head":{"ref":"release-please--branches--main--components--compose-ai-tools"}}'
run duplicate repair '{"number":4645}'
[[ "$(output duplicate raced)" == "true" ]] || fail "repair should report the race"
[[ "$(output duplicate closed)" == "4645" ]] || fail "repair should close the duplicate release PR"
grep -q "^PATCH repos/yschimke/compose-ai-tools/pulls/4645$" "${tmp}/duplicate.log" ||
  fail "repair should PATCH the duplicate closed"
grep -q '^FIELD state=closed$' "${tmp}/duplicate.log" || fail "repair should close, not edit"
grep -q '^POST repos/yschimke/compose-ai-tools/issues/4645/comments$' "${tmp}/duplicate.log" ||
  fail "repair should say on the PR why it closed it"
grep -q 'reconcile-release-pr' "${tmp}/duplicate.log" || fail "the closing comment should name what opens the right PR"

# 6a. A PR for a different version is somebody else's release PR, not this race's.
fixture "${PR_PATH}" '{"state":"open","title":"chore(main): release 1.43.0",
  "user":{"login":"github-actions[bot]"},"head":{"ref":"release-please--branches--main--components--compose-ai-tools"}}'
run other_version repair '{"number":4645}'
[[ -z "$(output other_version closed)" ]] || fail "repair should not close a PR for another version"
grep -q '^PATCH ' "${tmp}/other_version.log" && fail "repair should not write to a PR for another version"

# 6b. The branch name is contributor-controlled; the author is what makes it release-please's.
fixture "${PR_PATH}" '{"state":"open","title":"chore(main): release 1.42.0",
  "user":{"login":"yschimke"},"head":{"ref":"release-please--branches--main--components--compose-ai-tools"}}'
run human_pr repair '{"number":4645}'
[[ -z "$(output human_pr closed)" ]] || fail "repair should not close a human's pull request"
grep -q '^PATCH ' "${tmp}/human_pr.log" && fail "repair should not write to a human's pull request"

# 7. No race: repair must leave everything alone, including the release PR the run just updated.
fixture "${RELEASE_PATH}" '{"draft": false}'
run no_race repair '{"number":4645}'
[[ "$(output no_race raced)" == "false" ]] || fail "repair should report no race when none happened"
grep -qE '^(PATCH|POST) ' "${tmp}/no_race.log" && fail "repair should write nothing when there was no race"

if (( failures > 0 )); then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "release-pr-race-guard.sh: all checks passed"
