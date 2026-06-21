#!/usr/bin/env bash
# Cloud Run entrypoint for `compose-preview serve`.
#
# Translates Cloud Run's environment contract (the $PORT it injects, a token from
# Secret Manager) into `compose-preview serve` flags. Runs from the repo root so
# the CLI's Gradle Tooling API build finds settings.gradle.kts.
set -euo pipefail

cd /app

PORT="${PORT:-8080}"
SERVE_MODULE="${SERVE_MODULE:-:samples:cmp}"
BIN=/app/cli/build/install/compose-preview/bin/compose-preview

# A public endpoint must be token-gated. Cloud Run surfaces the secret as $SERVE_TOKEN.
# Fail closed rather than expose an unauthenticated renderer.
if [[ -z "${SERVE_TOKEN:-}" ]]; then
  echo "entrypoint: SERVE_TOKEN is unset — refusing to start an unauthenticated public server." >&2
  echo "            Set it (e.g. from Secret Manager) before deploying. See deploy/cloudrun/README.md." >&2
  exit 64
fi

args=(
  serve
  --module "${SERVE_MODULE}"
  --host 0.0.0.0
  --port "${PORT}"
  --token "${SERVE_TOKEN}"
)

# Optional: exit after N idle seconds so the Cloud Run instance scales to zero.
# Set SERVE_IDLE_EXIT=0 (or unset) to run until terminated.
if [[ -n "${SERVE_IDLE_EXIT:-}" && "${SERVE_IDLE_EXIT}" != "0" ]]; then
  args+=("--exit-when-idle=${SERVE_IDLE_EXIT}")
fi

echo "entrypoint: starting compose-preview serve on 0.0.0.0:${PORT} (module ${SERVE_MODULE})" >&2
exec "${BIN}" "${args[@]}"
