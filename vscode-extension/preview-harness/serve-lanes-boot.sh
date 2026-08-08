#!/usr/bin/env bash
# Boot a daemon-backed `compose-preview serve` for the serve-lanes e2e spec, wait
# until it can actually LIVE-RENDER (not merely bind /version), and print its
# base URL. Same shape the public server (preview.coo.ee) runs: a Trusted
# `--catalogs` system live-rendered from its carried liveBundle, so every render
# lane (PNG / SVG / Live WS) exercises a real Compose render daemon.
#
# `--accept-docs` is on too, so the suite can drive the **client-side** Remote Compose
# lane from a `.rc` file it uploads: that lane needs no catalog `ir/` sidecar, so its
# typeface-registration check runs on every boot rather than skipping wherever the
# served catalog happens to carry no documents.
#
# IMPORTANT: serve must run MODULE-LESS (hosting only the fetched --catalogs). It
# decides that by NOT being inside a Gradle project — so this launches it from a
# scratch dir OUTSIDE the repo. Run from the repo root, serve instead tries to
# discover + build the ~20 local preview modules and never answers /version. All
# input paths are resolved to absolute BEFORE we cd out.
#
# Env:
#   SERVE_CLI          path to the compose-preview launcher (default: the
#                      :cli:installDist output under cli/build/install)
#   SERVE_PORT         bind port (default 8725)
#   SERVE_SYSTEM       catalog system to serve (default compose-m3)
#   SERVE_TRUST_STORE  producer trust store (default deploy/image/trust/producers.json)
#   SERVE_LOG          serve log file, relative to the repo root (default serve-lanes.log)
#
# Writes the pid to serve-lanes.pid (repo root) so the caller can tear it down, and
# the log where the caller can upload it. Needs xvfb + software GL on the PATH (the
# workflow installs them).
set -euo pipefail

REPO_ROOT="$(pwd)"
CLI="$(readlink -f "${SERVE_CLI:-cli/build/install/compose-preview/bin/compose-preview}")"
TRUST="$(readlink -f "${SERVE_TRUST_STORE:-deploy/image/trust/producers.json}")"
PORT="${SERVE_PORT:-8725}"
SYSTEM="${SERVE_SYSTEM:-compose-m3}"
LOG="${REPO_ROOT}/${SERVE_LOG:-serve-lanes.log}"
PID_FILE="${REPO_ROOT}/serve-lanes.pid"
BASE="http://127.0.0.1:${PORT}"

if [ ! -x "$CLI" ]; then
  echo "::error::serve CLI not found or not executable at $CLI — run :cli:installDist first" >&2
  exit 1
fi

# Launch from a scratch dir OUTSIDE the Gradle project so serve runs module-less.
WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/serve-lanes.XXXXXX")"
echo "serve-lanes: booting daemon-backed serve ($SYSTEM) on $BASE (module-less, cwd=$WORK)" >&2
cd "$WORK"
LIBGL_ALWAYS_SOFTWARE=1 nohup xvfb-run -a "$CLI" serve \
  --catalogs "$SYSTEM" --public --host 127.0.0.1 --port "$PORT" \
  --trust-store "$TRUST" --allow-render-trusted --live-seats 1 --accept-docs \
  > "$LOG" 2>&1 &
echo $! > "$PID_FILE"

# 1) Wait for the serve readiness gate: in catalog mode the listener binds before
#    the async catalog load starts, so /version can answer while /api/previews is
#    still empty. /readyz retries until a representative preview renders.
up=""
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null --max-time 4 "$BASE/readyz"; then up=1; break; fi
  sleep 2
done
if [ -z "$up" ]; then
  echo "::error::serve did not become ready in time" >&2
  tail -60 "$LOG" >&2 || true
  exit 1
fi

# 2) Warm the render daemon: /readyz already rendered one representative preview,
#    but keep this explicit render wait so the test sees the same clear log line
#    and we verify the path the spec exercises.
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
