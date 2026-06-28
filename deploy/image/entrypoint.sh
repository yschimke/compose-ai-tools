#!/usr/bin/env bash
# Runtime entrypoint for the prebuilt preview-host image. Serves the baked-in
# project; maps the platform's $PORT + $SERVE_TOKEN onto serve flags.
set -euo pipefail
cd /project

PORT="${PORT:-8080}"

if [[ -z "${SERVE_TOKEN:-}" ]]; then
  echo "entrypoint: SERVE_TOKEN unset — refusing to start an unauthenticated public server." >&2
  exit 64
fi

args=(serve --host 0.0.0.0 --port "${PORT}" --token "${SERVE_TOKEN}")

# Generous render/build timeout so a slow host's first render doesn't trip the
# CLI's 300s default (the warm cache is baked in, so it's normally fast anyway).
args+=(--timeout "${SERVE_TIMEOUT:-1800}")

# Optional: exit after N idle seconds (set SERVE_IDLE_EXIT>0) so a scale-to-zero
# platform can reclaim the instance. Default 0 = stay up.
if [[ -n "${SERVE_IDLE_EXIT:-}" && "${SERVE_IDLE_EXIT}" != "0" ]]; then
  args+=("--exit-when-idle=${SERVE_IDLE_EXIT}")
fi

echo "entrypoint: compose-preview serve on 0.0.0.0:${PORT}" >&2
exec compose-preview "${args[@]}"
