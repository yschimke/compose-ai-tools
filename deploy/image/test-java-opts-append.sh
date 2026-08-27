#!/usr/bin/env bash
# Guard: SERVE_JAVA_OPTS must be APPENDED to the image's baked JAVA_TOOL_OPTIONS, never replace it.
#
# Why this needs a test. The image bakes JAVA_TOOL_OPTIONS into its ENV — the heap ceiling, the
# daemon library directories, the render timeouts, the sandbox boot settings — and the whole reason
# this variable exists is that setting JAVA_TOOL_OPTIONS from the compose file replaces that string
# instead of adding to it. The container then comes up with no heap ceiling and no daemon paths, for
# the sake of one -D, and nothing in the resulting failure points back at the change.
#
# So the one property that matters is that the baked options SURVIVE. A refactor that rewrote the
# append as an assignment would reintroduce exactly the trap this was added to close, and would look
# entirely reasonable in a diff.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
[[ -f "${entrypoint}" ]] || {
  echo "FAIL: missing ${entrypoint}" >&2
  exit 1
}

# Run just the append stanza, rather than the whole entrypoint (which needs a container).
stanza="$(sed -n '/^if \[\[ -n "\${SERVE_JAVA_OPTS:-}" \]\]; then$/,/^fi$/p' "${entrypoint}")"
[[ -n "${stanza}" ]] || {
  echo "FAIL: no SERVE_JAVA_OPTS stanza found in ${entrypoint} — the detector is broken." >&2
  exit 1
}

baked="-XX:MaxRAMPercentage=70 -Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android"

run_stanza() {
  JAVA_TOOL_OPTIONS="${baked}" SERVE_JAVA_OPTS="${1}" bash -c "
    set -euo pipefail
    ${stanza}
    printf '%s' \"\${JAVA_TOOL_OPTIONS}\"
  " 2>/dev/null
}

# 1. The baked options survive, and the operator's are there too.
result="$(run_stanza '-Dcomposeai.serve.themeOptimizationIdleMillis=10000')"
[[ "${result}" == *"-XX:MaxRAMPercentage=70"* ]] || {
  echo "FAIL: the baked heap ceiling was lost: ${result}" >&2
  exit 1
}
[[ "${result}" == *"libDaemonAndroidDir=/opt/lib-daemon-android"* ]] || {
  echo "FAIL: the baked daemon library dir was lost: ${result}" >&2
  exit 1
}
[[ "${result}" == *"themeOptimizationIdleMillis=10000"* ]] || {
  echo "FAIL: the operator's option did not reach JAVA_TOOL_OPTIONS: ${result}" >&2
  exit 1
}
echo "PASS: SERVE_JAVA_OPTS is appended, and the image's baked options survive"

# 2. The operator's options come LAST, which is what lets them override a baked value rather than
#    only add to it — the JVM takes the last occurrence of a repeated flag.
result="$(run_stanza '-XX:MaxRAMPercentage=50')"
[[ "${result}" == *"MaxRAMPercentage=70"*"MaxRAMPercentage=50" ]] || {
  echo "FAIL: an operator override must come after the baked value: ${result}" >&2
  exit 1
}
echo "PASS: operator options come last, so a baked value can be overridden"

# 3. Unset leaves the baked options exactly as they were — no stray separator, nothing appended.
result="$(JAVA_TOOL_OPTIONS="${baked}" bash -c "
  set -euo pipefail
  ${stanza}
  printf '%s' \"\${JAVA_TOOL_OPTIONS}\"
" 2>/dev/null)"
[[ "${result}" == "${baked}" ]] || {
  echo "FAIL: unset SERVE_JAVA_OPTS must leave JAVA_TOOL_OPTIONS untouched, got: ${result}" >&2
  exit 1
}
echo "PASS: unset changes nothing"

# 4. Several options, space-separated, all arrive.
result="$(run_stanza '-Da=1 -Db=2')"
[[ "${result}" == *"-Da=1 -Db=2" ]] || {
  echo "FAIL: multiple space-separated options must all arrive: ${result}" >&2
  exit 1
}
echo "PASS: several options are passed through together"

# 5. Self-test: an assignment instead of an append must be caught. This is the regression the whole
#    file exists for, so the detector is proved rather than trusted.
bad_stanza='if [[ -n "${SERVE_JAVA_OPTS:-}" ]]; then
  export JAVA_TOOL_OPTIONS="${SERVE_JAVA_OPTS}"
fi'
result="$(JAVA_TOOL_OPTIONS="${baked}" SERVE_JAVA_OPTS="-Dx=1" bash -c "
  set -euo pipefail
  ${bad_stanza}
  printf '%s' \"\${JAVA_TOOL_OPTIONS}\"
" 2>/dev/null)"
[[ "${result}" == *"MaxRAMPercentage"* ]] && {
  echo "FAIL: self-test — a replacing stanza was not caught by the baked-options check" >&2
  exit 1
}
echo "PASS: self-test — a stanza that replaces instead of appending is caught"

echo "PASS: all SERVE_JAVA_OPTS checks"
