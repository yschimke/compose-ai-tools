#!/bin/sh
# Zero-downtime image updates for the `preview` service via docker-rollout.
#
# This replaces Watchtower's in-place stop→recreate of `preview` (which 502s for
# the whole ~1 min the new container spends fetching catalogs + readiness-rendering) with a
# rolling swap: pull the new image, start a SECOND preview replica alongside the
# live one, wait for its /readyz healthcheck to pass (green only once a preview
# actually renders — not merely once the port binds), let Caddy drain traffic
# onto it (see Caddyfile — dynamic upstreams + passive health), then retire the
# old replica. Existing traffic is served the entire time.
#
# Two ways to run it:
#   ./rollout.sh          one-shot: pull + roll if the image changed (manual op)
#   ./rollout.sh --loop   poll forever (used by the `rollout` compose service)
#
# Only `preview` is rolled this way — `caddy` publishes fixed 80/443 ports so it
# can't be scaled, and stays on Watchtower's recreate (a ~1s proxy blip, and only
# when the Caddyfile image itself changes).
set -eu

SERVICE="${ROLLOUT_SERVICE:-preview}"
INTERVAL="${ROLLOUT_INTERVAL:-1200}"
# docker-rollout's default healthcheck timeout is 60s; preview's cold start
# (catalog fetch from the design-artifacts branches + first readiness render) can exceed
# that, so give it room before rollout would wrongly declare the new replica
# unhealthy and roll back.
HEALTH_TIMEOUT="${ROLLOUT_HEALTH_TIMEOUT:-300}"
LOCK_NAME="${ROLLOUT_LOCK_NAME:-compose-preview-rollout-lock}"
# The lock is a tiny Docker container because the polling and webhook runners are separate
# containers and their only already-shared coordination primitive is the Docker daemon. Docker
# reserves container names atomically. A crashed caller leaves the marker running only until this
# TTL; the next attempt then removes the exited marker and can proceed.
# A rollout can legitimately take several health-timeout windows while replicas start and drain.
# Keep the crash-recovery TTL comfortably above that so a slow rollout cannot lose exclusivity.
LOCK_TTL="${ROLLOUT_LOCK_TTL:-$((HEALTH_TIMEOUT * 4 + 600))}"

log() { echo "rollout: $*"; }

release_rollout_lock() {
  docker rm -f "$LOCK_NAME" >/dev/null 2>&1 || true
}

# A POSIX shell RESUMES after a trap handler returns unless the handler exits. Releasing the lock
# from a plain `trap ... INT TERM` therefore advertised the lock as free while this runner carried
# on with the rest of the rollout — another runner could acquire it and overlap the remaining work,
# which is the exact concurrency the lock exists to prevent. Signals get handlers that clean up and
# terminate; EXIT keeps the plain cleanup, and `trap - EXIT` stops it running twice (harmless, since
# the release is idempotent, but it would log a second removal attempt).
on_rollout_signal() {
  # Kill whatever this runner is blocked on first. See [run_interruptible].
  if [ -n "${rollout_child:-}" ]; then
    kill -TERM "$rollout_child" 2>/dev/null || true
    wait "$rollout_child" 2>/dev/null || true
    rollout_child=""
  fi
  release_rollout_lock
  trap - EXIT
  # 128 + signal number: the conventional status for a signal-terminated process, so a supervisor
  # can tell an interrupted rollout from a failed one.
  exit "$((128 + $1))"
}

# Run a command so a trapped signal reaches the handler NOW rather than after it finishes.
#
# A POSIX shell defers a trapped signal until the running FOREGROUND child returns. This script is
# PID 1 in the `rollout` compose service, where the two things it blocks on are exactly the long
# ones: `docker rollout` can sit in its health timeout (default 300s) and the poll loop sleeps for
# `$ROLLOUT_INTERVAL` (default 1200s). A plain foreground call therefore meant `docker stop` on the
# service would hit its grace period and SIGKILL the shell before the handler ever ran — leaving the
# lock CONTAINER alive for its whole TTL and blocking both the poller and the webhook runner from
# deploying, which is the failure the lock exists to avoid, arrived at from the other side.
#
# Backgrounding and `wait`ing fixes it: `wait` is interruptible, so the handler runs immediately and
# can take the child down with it.
run_interruptible() {
  "$@" &
  rollout_child=$!
  rc=0
  wait "$rollout_child" || rc=$?
  rollout_child=""
  return "$rc"
}

arm_rollout_lock_traps() {
  trap release_rollout_lock EXIT
  trap 'on_rollout_signal 2' INT
  trap 'on_rollout_signal 15' TERM
}

acquire_rollout_lock() {
  if docker run -d --name "$LOCK_NAME" --entrypoint sleep docker:29-cli "$LOCK_TTL" \
    >/dev/null 2>&1; then
    arm_rollout_lock_traps
    return 0
  fi
  state="$(docker inspect --format '{{.State.Status}}' "$LOCK_NAME" 2>/dev/null || true)"
  if [ "$state" = "exited" ] || [ "$state" = "dead" ]; then
    docker rm -f "$LOCK_NAME" >/dev/null 2>&1 || true
    if docker run -d --name "$LOCK_NAME" --entrypoint sleep docker:29-cli "$LOCK_TTL" \
      >/dev/null 2>&1; then
      arm_rollout_lock_traps
      return 0
    fi
  fi
  log "another rollout is active — skipping"
  return 1
}

# The `rollout` service runs on docker:*-cli, which bundles the compose plugin on
# current images; fall back to installing it (Alpine) if a slimmer base is used.
# On a Debian/Ubuntu host (manual `./rollout.sh`) compose is already present, so
# this is a no-op there.
ensure_compose() {
  if docker compose version >/dev/null 2>&1; then
    return 0
  fi
  if command -v apk >/dev/null 2>&1; then
    log "installing docker compose plugin"
    apk add --no-cache docker-cli-compose >/dev/null
  fi
}

# The image id the running container is on, vs. the id the pulled tag now points
# at — roll only when they differ, so an unchanged poll is a cheap no-op instead
# of churning a replica every interval.
running_image_id() {
  cid="$(docker compose ps -q "$SERVICE" 2>/dev/null | head -n1 || true)"
  [ -n "$cid" ] && docker inspect --format '{{.Image}}' "$cid" 2>/dev/null || true
}

pulled_image_id() {
  ref="$(docker compose config --images "$SERVICE" 2>/dev/null | head -n1 || true)"
  [ -n "$ref" ] && docker image inspect --format '{{.Id}}' "$ref" 2>/dev/null || true
}

roll_once() {
  ensure_compose
  # The hook and poller can notice the same image seconds apart. Without one host-wide lock both
  # call docker-rollout, each treats the other's new replica as an old replica, and the steady-state
  # count doubles. A skipped contender is success: the lock holder is already deploying that image.
  if ! acquire_rollout_lock; then
    return 0
  fi
  docker compose pull "$SERVICE" >/dev/null 2>&1 || log "pull failed (using cached image)"
  before="$(running_image_id)"
  after="$(pulled_image_id)"
  if [ -z "$after" ]; then
    log "could not resolve pulled image for '$SERVICE' — skipping"
    release_rollout_lock
    trap - EXIT INT TERM
    return 0
  fi
  if [ -n "$before" ] && [ "$before" = "$after" ]; then
    log "'$SERVICE' already up to date"
    release_rollout_lock
    trap - EXIT INT TERM
    return 0
  fi
  if [ -z "$before" ]; then
    log "'$SERVICE' not running — starting via rollout"
  else
    log "new image for '$SERVICE' — rolling (health timeout ${HEALTH_TIMEOUT}s)"
  fi
  # Capture and return the rollout's own exit status. Don't rely on `set -e` here:
  # errexit is suppressed whenever roll_once runs on the left of `||` (the poll
  # loop below) or in an `if` condition, so a failed `docker rollout` would
  # otherwise fall through to the success log and a 0 return — reporting a failed
  # rollout as rolled. Inside the `else`, `$?` still holds the rollout exit code.
  if run_interruptible docker rollout --timeout "$HEALTH_TIMEOUT" "$SERVICE"; then
    log "'$SERVICE' rolled"
    release_rollout_lock
    trap - EXIT INT TERM
    return 0
  else
    rc=$?
    log "docker rollout failed (exit $rc)"
    release_rollout_lock
    trap - EXIT INT TERM
    return "$rc"
  fi
}

if [ "${1:-}" = "--loop" ]; then
  log "polling '$SERVICE' every ${INTERVAL}s for zero-downtime updates"
  while true; do
    roll_once || log "roll attempt failed — will retry next cycle"
    # Interruptible for the same reason the rollout is: at the default interval this shell spends
    # almost all of its life right here, so a foreground `sleep` is where a shutdown signal would
    # most often be swallowed.
    run_interruptible sleep "$INTERVAL" || true
  done
else
  roll_once
fi
