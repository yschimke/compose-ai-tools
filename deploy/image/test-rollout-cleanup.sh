#!/usr/bin/env bash
# Self-test for the two rollout failure modes that are INVISIBLE in production — runs OFFLINE
# against a stub `docker` on PATH, so it touches no daemon.
#
# Both are "reports success while leaving the system in the state the script exists to prevent":
#
#  1. `docker-rollout` swallows the exit status of the old-container `stop`/`rm` with `|| true`, so
#     a failure other than "already gone" left the old replicas running BESIDE the healthy new ones
#     — the doubled replica count and memory pressure the rolling swap exists to avoid — and the
#     caller logged the rollout as complete.
#  2. `rollout.sh` released its cross-container lock from a plain `trap ... INT TERM`. A POSIX shell
#     resumes after a trap handler returns, so an interrupted runner advertised the lock as free and
#     kept rolling; a second runner could acquire it and overlap the remaining work.
#
# Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

pass=0
fail=0
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1" >&2
    echo "  expected: $2" >&2
    echo "  actual:   $3" >&2
  fi
}

# --- stub docker -----------------------------------------------------------------------------
# `inspect` answers from $SURVIVORS: an id listed there still exists (exit 0), anything else is
# gone (exit 1) — which is exactly what the real `docker inspect` reports after a successful `rm`.
# `stop`/`rm` always "fail", standing in for the case `|| true` used to swallow wholesale.
mkdir -p "${work}/bin"
cat > "${work}/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  inspect)
    id="${!#}"
    for survivor in ${SURVIVORS:-}; do
      if [ "$survivor" = "$id" ]; then echo "$id"; exit 0; fi
    done
    echo "Error: No such object: $id" >&2
    exit 1
    ;;
  stop|rm)
    echo "Error: cannot ${1} container" >&2
    exit 1
    ;;
esac
exit 0
STUB
chmod +x "${work}/bin/docker"
export PATH="${work}/bin:${PATH}"

# --- 1. docker-rollout's survivor check ------------------------------------------------------
# Exercise only the cleanup tail: the surrounding script needs a compose project. Sourcing the
# whole file would run its argument parsing, so the tail is replayed here against the same stub —
# and the assertion below pins that the real script still contains this check.
cleanup_tail() {
  docker stop $OLD_CONTAINER_IDS || true
  docker rm $OLD_CONTAINER_IDS || true

  SURVIVING_CONTAINER_IDS=""
  for OLD_CONTAINER_ID in $OLD_CONTAINER_IDS; do
    if docker inspect --format='{{.Id}}' "$OLD_CONTAINER_ID" >/dev/null 2>&1; then
      SURVIVING_CONTAINER_IDS="$SURVIVING_CONTAINER_IDS $OLD_CONTAINER_ID"
    fi
  done
  if [ -n "$SURVIVING_CONTAINER_IDS" ]; then
    echo "==> ERROR: old containers survived cleanup:$SURVIVING_CONTAINER_IDS" >&2
    exit 1
  fi
}

OLD_CONTAINER_IDS="old1 old2"

# Both gone despite `stop`/`rm` reporting failure: that IS the benign already-reaped race, and it
# must stay a success — this is what `|| true` is for.
export SURVIVORS=""
status=0
( cleanup_tail ) >/dev/null 2>&1 || status=$?
check "already-gone containers are still a successful cleanup" "0" "$status"

# One survivor: the state the caller must not be told is a completed rollout.
export SURVIVORS="old2"
status=0
( cleanup_tail ) >/dev/null 2>&1 || status=$?
check "a surviving old container fails the cleanup" "1" "$status"

grep -q 'SURVIVING_CONTAINER_IDS' "${here}/docker-rollout" &&
  survivor_check=present || survivor_check=missing
check "docker-rollout still carries the survivor check" "present" "$survivor_check"

# --- 2. rollout.sh's signal traps ------------------------------------------------------------
# A shell that installs the same traps, then signals itself mid-"rollout". The marker file stands
# in for the work after the signal: if the handler only released the lock and returned, the shell
# would resume and write it.
cat > "${work}/signal-case.sh" <<'CASE'
#!/bin/sh
set -eu
release_rollout_lock() { echo released >> "$LOG"; }
on_rollout_signal() {
  release_rollout_lock
  trap - EXIT
  exit "$((128 + $1))"
}
trap release_rollout_lock EXIT
trap 'on_rollout_signal 2' INT
trap 'on_rollout_signal 15' TERM

kill -TERM $$
# Only reached if the handler returned instead of exiting.
echo resumed >> "$LOG"
CASE
chmod +x "${work}/signal-case.sh"

LOG="${work}/signal.log" status=0
LOG="$LOG" "${work}/signal-case.sh" || status=$?
check "a signalled runner exits rather than resuming" "released" "$(cat "${work}/signal.log")"
check "and reports 128 + SIGTERM" "143" "$status"

# The real script must arm the traps this way, and never re-introduce the resuming form.
grep -q "trap 'on_rollout_signal 15' TERM" "${here}/rollout.sh" && armed=present || armed=missing
check "rollout.sh arms terminating signal handlers" "present" "$armed"
grep -q 'trap release_rollout_lock EXIT INT TERM' "${here}/rollout.sh" && resuming=present ||
  resuming=absent
check "rollout.sh no longer uses the resuming trap form" "absent" "$resuming"

echo "rollout-cleanup self-test: ${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
