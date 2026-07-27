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

log() { echo "rollout: $*"; }

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
  docker compose pull "$SERVICE" >/dev/null 2>&1 || log "pull failed (using cached image)"
  before="$(running_image_id)"
  after="$(pulled_image_id)"
  if [ -z "$after" ]; then
    log "could not resolve pulled image for '$SERVICE' — skipping"
    return 0
  fi
  if [ -n "$before" ] && [ "$before" = "$after" ]; then
    log "'$SERVICE' already up to date"
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
  if docker rollout --timeout "$HEALTH_TIMEOUT" "$SERVICE"; then
    log "'$SERVICE' rolled"
    return 0
  else
    rc=$?
    log "docker rollout failed (exit $rc)"
    return "$rc"
  fi
}

if [ "${1:-}" = "--loop" ]; then
  log "polling '$SERVICE' every ${INTERVAL}s for zero-downtime updates"
  while true; do
    roll_once || log "roll attempt failed — will retry next cycle"
    sleep "$INTERVAL"
  done
else
  roll_once
fi
