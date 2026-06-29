#!/usr/bin/env bash
# Runtime entrypoint for the prebuilt preview-host image. Serves the baked-in
# project; maps the platform's $PORT + $SERVE_TOKEN onto serve flags.
set -euo pipefail
cd /project

PORT="${PORT:-8080}"

args=(serve --host 0.0.0.0 --port "${PORT}")

# Auth posture (see deploy/cloudrun/entrypoint.sh): SERVE_PUBLIC=1 → the open
# public preview server (preview.coo.ee); otherwise token-gated (SERVE_TOKEN
# required, fail closed).
if [[ "${SERVE_PUBLIC:-}" == "1" || "${SERVE_PUBLIC:-}" == "true" ]]; then
  args+=(--public)
else
  if [[ -z "${SERVE_TOKEN:-}" ]]; then
    echo "entrypoint: SERVE_TOKEN unset and SERVE_PUBLIC off — refusing to start an" >&2
    echo "            unauthenticated server. Set SERVE_TOKEN, or SERVE_PUBLIC=1." >&2
    exit 64
  fi
  args+=(--token "${SERVE_TOKEN}")
fi

# The public-server pillars (all optional). The prebuilt image has no catalog
# modules to build a Wasm app from, so its in-browser tier rides --catalogs:
# `serve` fetches each system's web/wasm/ from the trusted design-artifacts
# branch. (--wasm-dir is for the from-source image's local build.)
[[ -n "${SERVE_CATALOGS:-}" ]] && args+=(--catalogs "${SERVE_CATALOGS}")
[[ -n "${SERVE_TRUST_STORE:-}" ]] && args+=(--trust-store "${SERVE_TRUST_STORE}")
[[ -n "${SERVE_WASM_DIR:-}" ]] && args+=(--wasm-dir "${SERVE_WASM_DIR}")
if [[ "${SERVE_ACCEPT_BUNDLES:-}" == "1" || "${SERVE_ACCEPT_BUNDLES:-}" == "true" ]]; then
  args+=(--accept-bundles)
  [[ -n "${SERVE_ACCEPT_BUNDLES_FROM:-}" ]] &&
    args+=(--accept-bundles-from "${SERVE_ACCEPT_BUNDLES_FROM}")
fi

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
