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
    # $DAEMON_DOWN stands in for the daemon or socket having gone away — the same condition that
    # would have failed the stop/rm a moment earlier. Inspect cannot answer, and must not be read
    # as an answer.
    if [ -n "${DAEMON_DOWN:-}" ]; then
      echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock." >&2
      exit 1
    fi
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
  UNVERIFIED_CONTAINER_IDS=""
  for OLD_CONTAINER_ID in $OLD_CONTAINER_IDS; do
    if INSPECT_ERROR="$(docker inspect --format='{{.Id}}' "$OLD_CONTAINER_ID" 2>&1 >/dev/null)"; then
      SURVIVING_CONTAINER_IDS="$SURVIVING_CONTAINER_IDS $OLD_CONTAINER_ID"
    elif ! echo "$INSPECT_ERROR" | grep -qiE 'no such (object|container)'; then
      UNVERIFIED_CONTAINER_IDS="$UNVERIFIED_CONTAINER_IDS $OLD_CONTAINER_ID"
    fi
  done
  if [ -n "$SURVIVING_CONTAINER_IDS" ]; then
    echo "==> ERROR: old containers survived cleanup:$SURVIVING_CONTAINER_IDS" >&2
    exit 1
  fi
  if [ -n "$UNVERIFIED_CONTAINER_IDS" ]; then
    echo "==> ERROR: could not confirm removal:$UNVERIFIED_CONTAINER_IDS" >&2
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

# A daemon that cannot answer is not a daemon saying "gone". Reading a failed inspect as absence
# reinstates exactly the clean bill of health the survivor check exists to withhold.
export SURVIVORS="" DAEMON_DOWN=1
status=0
( cleanup_tail ) >/dev/null 2>&1 || status=$?
check "an unverifiable inspect fails rather than passing" "1" "$status"
unset DAEMON_DOWN

grep -q 'SURVIVING_CONTAINER_IDS' "${here}/docker-rollout" &&
  survivor_check=present || survivor_check=missing
check "docker-rollout still carries the survivor check" "present" "$survivor_check"

grep -q 'UNVERIFIED_CONTAINER_IDS' "${here}/docker-rollout" &&
  unverified_check=present || unverified_check=missing
check "docker-rollout distinguishes unverifiable from gone" "present" "$unverified_check"

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

# The PID-1 case the plain self-signal above does NOT cover: a POSIX shell defers a trapped signal
# until the running FOREGROUND child returns, and this script blocks on `docker rollout` (up to its
# 300s health timeout) and on the poll `sleep` (1200s by default). Backgrounding and `wait`ing is
# what lets the handler run at once — otherwise `docker stop` on the rollout service SIGKILLs the
# shell before cleanup, stranding the lock container for its whole TTL.
cat > "${work}/interruptible-case.sh" <<'CASE'
#!/bin/sh
set -eu
rollout_child=""
release_rollout_lock() { echo released >> "$LOG"; }
on_rollout_signal() {
  if [ -n "${rollout_child:-}" ]; then
    kill -TERM "$rollout_child" 2>/dev/null || true
    wait "$rollout_child" 2>/dev/null || true
    rollout_child=""
  fi
  release_rollout_lock
  trap - EXIT
  exit "$((128 + $1))"
}
run_interruptible() {
  "$@" &
  rollout_child=$!
  rc=0
  wait "$rollout_child" || rc=$?
  rollout_child=""
  return "$rc"
}
trap release_rollout_lock EXIT
trap 'on_rollout_signal 2' INT
trap 'on_rollout_signal 15' TERM

# Signal ourselves from a detached child, then block on a long "rollout". `self` is captured
# before backgrounding: in a POSIX subshell `$PPID` still names THIS script's parent, so using it
# would signal the test harness instead.
self=$$
(sleep 0.3; kill -TERM "$self") &
run_interruptible sleep 30 || true
echo resumed >> "$LOG"
CASE
chmod +x "${work}/interruptible-case.sh"

LOG="${work}/interruptible.log" status=0
start=$(date +%s)
LOG="$LOG" "${work}/interruptible-case.sh" || status=$?
elapsed=$(( $(date +%s) - start ))
check "a signal during a long child is handled at once" "released" "$(cat "${work}/interruptible.log")"
check "and still reports 128 + SIGTERM" "143" "$status"
[ "$elapsed" -lt 10 ] && promptly=yes || promptly=no
check "without waiting out the child (${elapsed}s)" "yes" "$promptly"

grep -q 'run_interruptible docker rollout' "${here}/rollout.sh" && rolls=present || rolls=missing
check "rollout.sh runs the rollout interruptibly" "present" "$rolls"
grep -q 'run_interruptible sleep' "${here}/rollout.sh" && sleeps=present || sleeps=missing
check "rollout.sh sleeps interruptibly" "present" "$sleeps"

# The real script must arm the traps this way, and never re-introduce the resuming form.
grep -q "trap 'on_rollout_signal 15' TERM" "${here}/rollout.sh" && armed=present || armed=missing
check "rollout.sh arms terminating signal handlers" "present" "$armed"
grep -q 'trap release_rollout_lock EXIT INT TERM' "${here}/rollout.sh" && resuming=present ||
  resuming=absent
check "rollout.sh no longer uses the resuming trap form" "absent" "$resuming"

echo "rollout-cleanup self-test: ${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
