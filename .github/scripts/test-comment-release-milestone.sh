#!/usr/bin/env bash
# Self-test for comment-release-milestone.sh, run by CI on every PR.
#
# Drives the script against a stub `gh` on PATH, which serves canned API responses and records
# every call. That covers the rules whose failure modes are invisible in a release run:
#   1. the milestone lands on the RELEASE PR resolved from the merge commit — and a commit whose
#      PRs aren't release-please's (wrong branch, or the right branch from a human) is a silent
#      no-op, not a comment on an unrelated PR;
#   2. a re-run PATCHes its own previous comment instead of stacking a duplicate, since both
#      callers are documented re-run repair paths;
#   3. the marker is per-milestone, so the Maven comment doesn't overwrite the deploy one.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/comment-release-milestone.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# --- stub gh -----------------------------------------------------------------------------------
# Serves fixtures/<path with / replaced by _>.json for reads, appends "METHOD path" (plus the
# request body for writes) to calls.log, and applies --jq so the script's own jq expressions are
# exercised rather than mocked away.
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
    --input) read_stdin=1; shift 2 ;;
    --paginate|--silent) shift ;;
    *) path="$1"; shift ;;
  esac
done
body=""
[[ "${read_stdin}" == 1 ]] && body="$(cat)"
printf '%s %s\n' "${method}" "${path}" >> "${GH_STUB_LOG}"
[[ -n "${body}" ]] && printf 'BODY %s\n' "$(printf '%s' "${body}" | tr '\n' '\036')" >> "${GH_STUB_LOG}"
[[ "${method}" == "GET" ]] || { echo '{"id":1}'; exit 0; }
fixture="${GH_STUB_FIXTURES}/${path//\//_}.json"
[[ -f "${fixture}" ]] || { echo "stub gh: no fixture for ${path}" >&2; exit 1; }
if [[ -n "${jq_expr}" ]]; then jq -r "${jq_expr}" < "${fixture}"; else cat "${fixture}"; fi
STUB
chmod +x "${tmp}/bin/gh"

export PATH="${tmp}/bin:${PATH}"
export GH_STUB_FIXTURES="${tmp}/fixtures"
export GITHUB_REPOSITORY="yschimke/compose-ai-tools"

REPO_PATH="repos_yschimke_compose-ai-tools"

# The release merge commit: release-please's own PR, plus an unrelated PR that also contains the
# commit (the shape a stacked branch produces) to prove the filter picks the right one.
cat > "${tmp}/fixtures/${REPO_PATH}_commits_relsha_pulls.json" <<'JSON'
[
  { "number": 4100, "head": { "ref": "feature/unrelated" }, "user": { "login": "yschimke" } },
  { "number": 3889, "head": { "ref": "release-please--branches--main" },
    "user": { "login": "github-actions[bot]" } }
]
JSON

# A human PR whose branch name imitates release-please's. Branch name alone must not qualify.
cat > "${tmp}/fixtures/${REPO_PATH}_commits_impostor_pulls.json" <<'JSON'
[
  { "number": 4200, "head": { "ref": "release-please--branches--main" },
    "user": { "login": "not-the-bot" } }
]
JSON

cat > "${tmp}/fixtures/${REPO_PATH}_commits_ordinary_pulls.json" <<'JSON'
[ { "number": 4300, "head": { "ref": "agent/some-change" }, "user": { "login": "yschimke" } } ]
JSON

run_case() {
  # run_case <log file> <sha> <key> [existing-comments fixture json]
  local log="$1" sha="$2" key="$3" comments="${4:-[]}"
  printf '%s' "${comments}" > "${tmp}/fixtures/${REPO_PATH}_issues_3889_comments.json"
  : > "${log}"
  GH_STUB_LOG="${log}" \
  MILESTONE_KEY="${key}" \
  MILESTONE_TAG="v1.7.0" \
  MILESTONE_BODY="### milestone body for ${key}" \
  RELEASE_SHA="${sha}" \
    "${UNDER_TEST}" > "${log}.out" 2>&1
  echo "$?"
}

# 1. First run on a real release merge: resolves #3889 and POSTs a new comment carrying the marker.
status="$(run_case "${tmp}/c1.log" relsha server-deployed)"
[[ "${status}" == 0 ]] || fail "fresh milestone exited ${status}: $(cat "${tmp}/c1.log.out")"
grep -q "POST repos/yschimke/compose-ai-tools/issues/3889/comments" "${tmp}/c1.log" \
  || fail "fresh milestone did not POST to the release PR: $(cat "${tmp}/c1.log")"
grep -q "release-milestone:server-deployed:v1.7.0" "${tmp}/c1.log" \
  || fail "posted body is missing its marker"
grep -q "milestone body for server-deployed" "${tmp}/c1.log" \
  || fail "posted body is missing the caller-supplied text"
grep -q "PATCH " "${tmp}/c1.log" && fail "fresh milestone should not PATCH anything"

# 2. Re-run with the marker already on the thread: PATCH that comment, never a second POST.
existing='[{"id":900,"body":"unrelated chatter"},
           {"id":901,"body":"<!-- release-milestone:server-deployed:v1.7.0 -->\nolder text"}]'
status="$(run_case "${tmp}/c2.log" relsha server-deployed "${existing}")"
[[ "${status}" == 0 ]] || fail "re-run exited ${status}: $(cat "${tmp}/c2.log.out")"
grep -q "PATCH repos/yschimke/compose-ai-tools/issues/comments/901" "${tmp}/c2.log" \
  || fail "re-run did not update the existing comment: $(cat "${tmp}/c2.log")"
grep -q "POST " "${tmp}/c2.log" && fail "re-run duplicated the milestone comment"

# 3. A different milestone on the same thread is its own comment, not an overwrite.
status="$(run_case "${tmp}/c3.log" relsha maven-published "${existing}")"
[[ "${status}" == 0 ]] || fail "second milestone exited ${status}: $(cat "${tmp}/c3.log.out")"
grep -q "POST repos/yschimke/compose-ai-tools/issues/3889/comments" "${tmp}/c3.log" \
  || fail "second milestone did not post its own comment: $(cat "${tmp}/c3.log")"
grep -q "PATCH " "${tmp}/c3.log" && fail "second milestone overwrote the first one's comment"

# 4/5. Nothing that looks like a release-please PR → no writes at all, and still a clean exit so
# a manual dispatch or tag-push release never fails on the courtesy comment.
for sha in impostor ordinary; do
  status="$(run_case "${tmp}/c-${sha}.log" "${sha}" server-deployed)"
  [[ "${status}" == 0 ]] || fail "${sha} commit exited ${status}: $(cat "${tmp}/c-${sha}.log.out")"
  grep -qE "^(POST|PATCH) " "${tmp}/c-${sha}.log" \
    && fail "${sha} commit wrote a comment it should have skipped: $(cat "${tmp}/c-${sha}.log")"
done

if [[ "${failures}" -gt 0 ]]; then
  echo "comment-release-milestone.sh: ${failures} check(s) failed." >&2
  exit 1
fi
echo "comment-release-milestone.sh: all checks passed."
