#!/usr/bin/env bash
# Boot an upload-only `compose-preview serve` for the bundle-upload e2e spec and
# print its base URL. The CI job packs a real bundle first, then the spec uploads
# that file through POST /bundles/{name} and follows the returned URL.
#
# Env:
#   SERVE_CLI    path to the compose-preview launcher (default: :cli:installDist)
#   SERVE_PORT   bind port (default 8728)
#   SERVE_TOKEN  token required by POST /bundles (default bundle-upload-e2e)
#   SERVE_LOG    serve log file, relative to the repo root (default bundle-upload.log)
set -euo pipefail

REPO_ROOT="$(pwd)"
CLI="$(readlink -f "${SERVE_CLI:-cli/build/install/compose-preview/bin/compose-preview}")"
PORT="${SERVE_PORT:-8728}"
TOKEN="${SERVE_TOKEN:-bundle-upload-e2e}"
LOG="${REPO_ROOT}/${SERVE_LOG:-bundle-upload.log}"
PID_FILE="${REPO_ROOT}/bundle-upload.pid"
BASE="http://127.0.0.1:${PORT}"

if [ ! -x "$CLI" ]; then
  echo "::error::serve CLI not found or not executable at $CLI — run :cli:installDist first" >&2
  exit 1
fi

WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/bundle-upload.XXXXXX")"
echo "bundle-upload: booting upload-only serve on $BASE (cwd=$WORK)" >&2
cd "$WORK"
nohup "$CLI" serve \
  --accept-bundles --host 127.0.0.1 --port "$PORT" --token "$TOKEN" \
  > "$LOG" 2>&1 &
SERVE_PID=$!
echo "$SERVE_PID" > "$PID_FILE"

up=""
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null --max-time 4 "$BASE/version"; then up=1; break; fi
  if ! kill -0 "$SERVE_PID" 2>/dev/null; then
    echo "::error::serve exited before answering /version" >&2
    tail -80 "$LOG" >&2 || true
    exit 1
  fi
  sleep 2
done
if [ -z "$up" ]; then
  echo "::error::serve did not answer /version in time" >&2
  tail -80 "$LOG" >&2 || true
  exit 1
fi

echo "$BASE"
