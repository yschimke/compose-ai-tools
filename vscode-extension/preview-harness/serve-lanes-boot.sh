#!/usr/bin/env bash
# Boot a daemon-backed `compose-preview serve` for the serve-lanes e2e spec, wait
# until it can actually LIVE-RENDER (not merely answer /version), and print its
# base URL. Same shape the public server (preview.coo.ee) runs: a Trusted
# `--catalogs` system live-rendered from its carried liveBundle, so every render
# lane (PNG / SVG / Live WS) exercises a real Compose render daemon.
#
# Env:
#   SERVE_CLI          path to the compose-preview launcher (default: the
#                      :cli:installDist output under cli/build/install)
#   SERVE_PORT         bind port (default 8725)
#   SERVE_SYSTEM       catalog system to serve (default compose-m3)
#   SERVE_TRUST_STORE  producer trust store (default deploy/image/trust/producers.json)
#   SERVE_LOG          serve log file (default serve-lanes.log)
#
# Writes the pid to serve-lanes.pid so the caller can tear it down. Needs xvfb +
# software GL on the PATH (the workflow installs them).
set -euo pipefail

CLI="${SERVE_CLI:-cli/build/install/compose-preview/bin/compose-preview}"
PORT="${SERVE_PORT:-8725}"
SYSTEM="${SERVE_SYSTEM:-compose-m3}"
TRUST="${SERVE_TRUST_STORE:-deploy/image/trust/producers.json}"
LOG="${SERVE_LOG:-serve-lanes.log}"
BASE="http://127.0.0.1:${PORT}"

echo "serve-lanes: booting daemon-backed serve ($SYSTEM) on $BASE" >&2
LIBGL_ALWAYS_SOFTWARE=1 nohup xvfb-run -a "$CLI" serve \
  --catalogs "$SYSTEM" --public --host 127.0.0.1 --port "$PORT" \
  --trust-store "$TRUST" --allow-render-trusted --live-seats 1 \
  > "$LOG" 2>&1 &
echo $! > serve-lanes.pid

# 1) Wait for the HTTP server to answer.
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null --max-time 4 "$BASE/version"; then break; fi
  sleep 2
done
if ! curl -sf -o /dev/null --max-time 4 "$BASE/version"; then
  echo "::error::serve did not answer /version in time" >&2
  tail -40 "$LOG" >&2 || true
  exit 1
fi

# 2) Warm the render daemon: hit one live PNG render and wait for a 200. A cold
#    daemon's first render pays the JVM + Skia warm-up; do it here so the spec's
#    per-test budget isn't spent on warm-up.
PID="$(curl -s --max-time 8 "$BASE/$SYSTEM/api/previews" \
  | tr ',' '\n' | grep -oE '"id":"[^"]+"' | head -1 | sed 's/"id":"//;s/"//')"
if [ -n "${PID:-}" ]; then
  echo "serve-lanes: warming render daemon on $PID" >&2
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE/$SYSTEM/render/$PID.png" || true)"
    if [ "$code" = "200" ]; then echo "serve-lanes: daemon warm (render 200)" >&2; break; fi
    sleep 3
  done
fi

echo "$BASE"
