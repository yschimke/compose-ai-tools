#!/bin/sh
# Token-gated deploy webhook: roll `preview` the INSTANT a new image is published,
# instead of waiting up to ROLLOUT_INTERVAL (default 1200s) for the `rollout`
# service's poll loop to notice.
#
# The image-publish CI (preview-host-image.yml) POSTs here once the new
# ghcr.io/…/compose-preview-host:latest is pushed; this triggers a one-shot
# `rollout.sh`, which pulls the tag and rolls ONLY if the digest actually changed
# (so a duplicate / replayed / racing-with-the-poll call is a cheap no-op). The
# poll loop stays on as the fallback, so a missed webhook still self-heals within
# one interval.
#
# WHY it's safe to expose (behind Caddy TLS):
#   * A bearer token (DEPLOY_HOOK_TOKEN) is required; fail-closed if unset — the
#     service stays up but inert (never an unauthenticated exec endpoint).
#   * The ONLY effect is `rollout.sh` on the ALREADY-CONFIGURED image tag. The
#     caller can't parameterise what image runs, so a leaked/replayed token forces
#     at most a rollout CHECK of the tag the box is already pinned to — a bounded
#     no-op, not an RCE lever.
#   * Single-flight lock: concurrent/rapid calls don't launch parallel rollouts.
#
# Runs on the same docker:*-cli base + mounts as the `rollout` service (Docker
# socket, this dir at /workspace:ro, the docker-rollout plugin), so it reuses
# rollout.sh verbatim. socat is apk-added at start, mirroring how rollout.sh
# apk-adds the compose plugin — runtime apk-add is the established pattern here.
#
# Modes:
#   deploy-hook.sh --serve    listen on $HOOK_PORT (default 9000), fork a handler
#                             per connection (used by the `hook` compose service)
#   deploy-hook.sh --handle   process ONE HTTP request on stdin/stdout (socat EXEC)
#   deploy-hook.sh --roll     run the single-flight rollout (spawned detached)
set -eu

HOOK_PORT="${HOOK_PORT:-9000}"
LOCK="/tmp/deploy-hook-rollout.lock"
TOKEN="${DEPLOY_HOOK_TOKEN:-}"

# ALWAYS log to stderr, never stdout. In --handle mode stdout IS the client
# socket (socat wires the connection to the handler's stdin/stdout), so a log line
# on stdout would land on the wire BEFORE the HTTP status line and corrupt the
# response — the CI POST would see a malformed reply / 502. socat leaves the
# handler's stderr on the container log (no `stderr` EXEC option), so stderr logs
# stay visible via `docker compose logs hook` in every mode.
log() { echo "deploy-hook: $*" >&2; }

# ---- one HTTP request, on stdin → response on stdout -----------------------
handle() {
  # Request line: "POST /__hooks/rollout HTTP/1.1". Split on whitespace.
  IFS=' ' read -r method path _rest || return 0
  : "${path:=/}"

  # Headers until the blank line; capture Authorization + Content-Length. Use tr
  # (not bash `${//}`, which busybox ash lacks) to strip CR and surrounding space.
  auth=""
  clen=0
  while IFS= read -r header; do
    header=$(printf '%s' "$header" | tr -d '\r')
    [ -z "$header" ] && break
    case "$header" in
      [Aa]uthorization:*) auth=$(printf '%s' "${header#*:}" | sed 's/^[[:space:]]*//') ;;
      [Cc]ontent-[Ll]ength:*) clen=$(printf '%s' "${header#*:}" | tr -cd '0-9') ;;
    esac
  done

  # Drain any request body so the read side finishes cleanly (our CI POST sends
  # none, so clen is normally 0 and this is a no-op).
  case "$clen" in
    ''|*[!0-9]*) clen=0 ;;
  esac
  [ "$clen" -gt 0 ] && dd bs=1 count="$clen" >/dev/null 2>&1 || true

  if [ "$method" != "POST" ]; then
    respond "405 Method Not Allowed" "POST only"
    return 0
  fi
  if [ -z "$TOKEN" ] || [ "$auth" != "Bearer $TOKEN" ]; then
    log "rejected ${method} ${path} (bad or missing token)"
    respond "401 Unauthorized" "bad token"
    return 0
  fi

  # Single-flight: mkdir is atomic. Got it → start a detached rollout; else a
  # rollout is already running and this call folds into it.
  if mkdir "$LOCK" 2>/dev/null; then
    log "authorized — triggering rollout"
    respond "202 Accepted" "rolling"
    # Detach into its own session so the multi-minute rollout survives this
    # short-lived connection handler being reaped by socat when the socket closes.
    # Log to the service's stdout (pid 1) so `docker compose logs hook` shows it.
    setsid "$0" --roll </dev/null >/proc/1/fd/1 2>&1 &
  else
    log "authorized — rollout already in progress"
    respond "200 OK" "rollout already in progress"
  fi
}

# HTTP/1.1 response with an explicit Content-Length + Connection: close, so the
# client (curl) sees a complete message and the socket closes deterministically.
respond() {
  _status="$1"
  _body="$2"
  _len=$(printf '%s' "$_body" | wc -c | tr -d ' ')
  printf 'HTTP/1.1 %s\r\nContent-Type: text/plain\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
    "$_status" "$_len" "$_body"
}

# ---- the actual rollout (detached; always frees the lock) ------------------
roll() {
  # rmdir on ANY exit so a crash can't wedge the single-flight lock permanently.
  trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT INT TERM
  log "rollout.sh start"
  # Reuse the vendored one-shot rollout verbatim (pull + roll only if changed).
  ROLLOUT_SERVICE="${ROLLOUT_SERVICE:-preview}" sh /workspace/rollout.sh || log "rollout.sh exited non-zero"
  log "rollout.sh done"
}

# ---- listener --------------------------------------------------------------
serve() {
  if ! command -v socat >/dev/null 2>&1; then
    log "installing socat"
    apk add --no-cache socat >/dev/null 2>&1 || {
      log "could not install socat — deploy hook unavailable"; exec sleep infinity; }
  fi
  if [ -z "$TOKEN" ]; then
    # Fail-closed: without a token we will NEVER authorize a roll, so don't even
    # open the port. Stay up (idle) rather than crash-loop under restart:always.
    log "DEPLOY_HOOK_TOKEN unset — deploy hook DISABLED (idle). Set it in .env to enable."
    exec sleep infinity
  fi
  # Clear a stale lock left by an unclean previous stop, so the first call works.
  rmdir "$LOCK" 2>/dev/null || true
  log "listening on :${HOOK_PORT} (POST → single-flight rollout of '${ROLLOUT_SERVICE:-preview}')"
  # Invoke via `/bin/sh <script>` (not the bare exec bit) so it works even if the
  # read-only bind mount didn't preserve the file's executable mode. socat splits
  # the EXEC command on spaces into argv; the script path has none.
  exec socat "TCP-LISTEN:${HOOK_PORT},reuseaddr,fork" "EXEC:/bin/sh $0 --handle"
}

case "${1:-}" in
  --serve)  serve ;;
  --handle) handle ;;
  --roll)   roll ;;
  *) echo "usage: $0 --serve|--handle|--roll" >&2; exit 64 ;;
esac
