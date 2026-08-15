#!/usr/bin/env bash
# Self-test for comment-release-milestone.sh, run by CI on every PR.
#
# Drives the script against a stub `gh` on PATH, which serves canned API responses and records
# every call. That covers the rules whose failure modes are invisible in a release run:
#   1. the milestone lands on the RELEASE PR resolved from the TAG's commit — and a commit whose
#      PRs aren't release-please's (wrong branch, or the right branch from a human) is a silent
#      no-op, not a comment on an unrelated PR;
#   2. a release PR for a DIFFERENT version is refused. This is the `workflow_dispatch` repair
#      path: it runs from `main`, whose head is a release merge commit right after a release, so
#      repairing an older tag would otherwise post that tag's milestone on the newer release PR;
#   3. a re-run PATCHes its own previous comment instead of stacking a duplicate, since both
#      callers are documented re-run repair paths;
#   4. the marker is per-milestone, so the Maven comment doesn't overwrite the deploy one;
#   5. a marker in someone else's comment is ignored. Release PRs are public and the marker is
#      predictable, so matching on body alone lets any commenter be overwritten — or, if the
#      cross-author PATCH is refused, swallow the milestone through the tolerated failure.
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

# v1.7.0 → its merge commit. This is the lookup that keeps a dispatch repair anchored on the tag
# it was asked about rather than on whatever ref the run was launched from.
printf '{"sha":"relsha"}\n' > "${tmp}/fixtures/${REPO_PATH}_commits_v1.7.0.json"

# The release merge commit: release-please's own PR, plus an unrelated PR that also contains the
# commit (the shape a stacked branch produces) to prove the filter picks the right one.
cat > "${tmp}/fixtures/${REPO_PATH}_commits_relsha_pulls.json" <<'JSON'
[
  { "number": 4100, "head": { "ref": "feature/unrelated" }, "user": { "login": "yschimke" },
    "title": "feat(serve): something else" },
  { "number": 3889, "head": { "ref": "release-please--branches--main" },
    "user": { "login": "github-actions[bot]" }, "title": "chore(main): release 1.7.0" }
]
JSON

# A genuine release PR — for the NEXT release. What a `workflow_dispatch` repair of v1.7.0 sees
# when it resolves `main`'s head instead of the tag: right branch, right author, wrong release.
cat > "${tmp}/fixtures/${REPO_PATH}_commits_newer-release_pulls.json" <<'JSON'
[
  { "number": 3999, "head": { "ref": "release-please--branches--main" },
    "user": { "login": "github-actions[bot]" }, "title": "chore(main): release 1.8.0" }
]
JSON

# A human PR whose branch name imitates release-please's. Branch name alone must not qualify.
cat > "${tmp}/fixtures/${REPO_PATH}_commits_impostor_pulls.json" <<'JSON'
[
  { "number": 4200, "head": { "ref": "release-please--branches--main" },
    "user": { "login": "not-the-bot" }, "title": "chore(main): release 1.7.0" }
]
JSON

cat > "${tmp}/fixtures/${REPO_PATH}_commits_ordinary_pulls.json" <<'JSON'
[ { "number": 4300, "head": { "ref": "agent/some-change" }, "user": { "login": "yschimke" },
    "title": "fix(cli): something" } ]
JSON

run_case() {
  # run_case <log file> <sha> <key> [existing-comments fixture json]
  # An empty <sha> leaves RELEASE_SHA unset, exercising the tag→commit lookup.
  local log="$1" sha="$2" key="$3" comments="${4:-[]}"
  printf '%s' "${comments}" > "${tmp}/fixtures/${REPO_PATH}_issues_3889_comments.json"
  : > "${log}"
  # A subshell rather than an assignment prefix: bash fixes assignment prefixes at parse time, so
  # a `${sha:+RELEASE_SHA=…}` that expands to nothing leaves the rest to be read as a command.
  (
    export GH_STUB_LOG="${log}" \
           MILESTONE_KEY="${key}" \
           MILESTONE_TAG="v1.7.0" \
           MILESTONE_BODY="### milestone body for ${key}"
    [[ -n "${sha}" ]] && export RELEASE_SHA="${sha}"
    "${UNDER_TEST}"
  ) > "${log}.out" 2>&1
  echo "$?"
}

# 0. No RELEASE_SHA: the tag resolves to its own merge commit, and from there to the release PR.
status="$(run_case "${tmp}/c0.log" "" server-deployed)"
[[ "${status}" == 0 ]] || fail "tag-anchored run exited ${status}: $(cat "${tmp}/c0.log.out")"
grep -q "GET repos/yschimke/compose-ai-tools/commits/v1.7.0$" "${tmp}/c0.log" \
  || fail "tag-anchored run did not resolve the tag: $(cat "${tmp}/c0.log")"
grep -q "POST repos/yschimke/compose-ai-tools/issues/3889/comments" "${tmp}/c0.log" \
  || fail "tag-anchored run did not reach the release PR: $(cat "${tmp}/c0.log")"

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
existing='[{"id":900,"user":{"login":"yschimke"},"body":"unrelated chatter"},
           {"id":901,"user":{"login":"github-actions[bot]"},
            "body":"<!-- release-milestone:server-deployed:v1.7.0 -->\nolder text"}]'
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

# 4. A comment carrying the marker but written by someone else is not the milestone. Overwriting
# it would let any commenter hijack the notification — or suppress it, since a refused
# cross-author PATCH is swallowed by the caller's continue-on-error.
hijack='[{"id":902,"user":{"login":"drive-by"},
          "body":"nice release! <!-- release-milestone:server-deployed:v1.7.0 -->"}]'
status="$(run_case "${tmp}/c4.log" relsha server-deployed "${hijack}")"
[[ "${status}" == 0 ]] || fail "hijacked marker exited ${status}: $(cat "${tmp}/c4.log.out")"
grep -q "PATCH " "${tmp}/c4.log" \
  && fail "a foreign comment's marker was overwritten: $(cat "${tmp}/c4.log")"
grep -q "POST repos/yschimke/compose-ai-tools/issues/3889/comments" "${tmp}/c4.log" \
  || fail "hijacked marker suppressed the real milestone: $(cat "${tmp}/c4.log")"

# 5. The dispatch-repair hazard: the resolved PR is a genuine release PR, from the release AFTER
# the tag being repaired. Its title names 1.8.0, so it is not this milestone's PR.
status="$(run_case "${tmp}/c5.log" newer-release server-deployed)"
[[ "${status}" == 0 ]] || fail "newer-release commit exited ${status}: $(cat "${tmp}/c5.log.out")"
grep -qE "^(POST|PATCH) " "${tmp}/c5.log" \
  && fail "v1.7.0's milestone landed on the 1.8.0 release PR: $(cat "${tmp}/c5.log")"

# 6/7. Nothing that looks like a release-please PR → no writes at all, and still a clean exit so
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
