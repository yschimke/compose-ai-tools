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

args=(
  serve
  --module "${SERVE_MODULE}"
  --host 0.0.0.0
  --port "${PORT}"
)

# Two auth postures, chosen by SERVE_PUBLIC:
#   - SERVE_PUBLIC=1  → the open public preview server (preview.coo.ee): every
#     route is unauthenticated. Safe by construction — no server-side code exec,
#     untrusted re-render refused, uploads (if enabled) capped + SSRF-gated.
#   - otherwise       → token-gated; SERVE_TOKEN is required (fail closed rather
#     than expose an unauthenticated renderer). Cloud Run surfaces it as a secret.
if [[ "${SERVE_PUBLIC:-}" == "1" || "${SERVE_PUBLIC:-}" == "true" ]]; then
  args+=(--public)
else
  if [[ -z "${SERVE_TOKEN:-}" ]]; then
    echo "entrypoint: SERVE_TOKEN is unset and SERVE_PUBLIC is off — refusing to start an" >&2
    echo "            unauthenticated server. Set SERVE_TOKEN, or SERVE_PUBLIC=1 for the open" >&2
    echo "            public profile. See deploy/cloudrun/README.md." >&2
    exit 64
  fi
  args+=(--token "${SERVE_TOKEN}")
fi

# The published design systems, their producer-trust store, and the in-browser
# CMP Wasm app — the public-server pillars. All optional: unset = off.
#   SERVE_CATALOGS=compose-m3,wear-m3
#   SERVE_TRUST_STORE=trust/producers.json
#   SERVE_WASM_DIR=compose-m3=samples/cmp-wasm-catalog/build/wasmDist
[[ -n "${SERVE_CATALOGS:-}" ]] && args+=(--catalogs "${SERVE_CATALOGS}")
# Design systems served but hidden from the front-page nav — reachable at /<system>/
# (and ?session=<system>). Each entry may carry a per-repo source as <system>@<owner>/<repo>
# (e.g. meshcore-mobile@yschimke/meshcore-mobile).
[[ -n "${SERVE_CATALOGS_UNLISTED:-}" ]] && args+=(--catalogs-unlisted "${SERVE_CATALOGS_UNLISTED}")
[[ -n "${SERVE_TRUST_STORE:-}" ]] && args+=(--trust-store "${SERVE_TRUST_STORE}")
[[ -n "${SERVE_WASM_DIR:-}" ]] && args+=(--wasm-dir "${SERVE_WASM_DIR}")
# Trusted server-side re-render (opt-in, OFF by default). Only enable on a box that
# can BUILD the catalog source (the Android catalogs need the Android toolchain).
# Reuses SERVE_REVISIONS_ALLOW as the trusted ref allowlist (fail-closed).
[[ -n "${SERVE_REVISIONS_ALLOW:-}" ]] && args+=(--revisions-allow "${SERVE_REVISIONS_ALLOW}")
if [[ "${SERVE_ALLOW_RENDER_TRUSTED:-}" == "1" || "${SERVE_ALLOW_RENDER_TRUSTED:-}" == "true" ]]; then
  args+=(--allow-render-trusted)
fi
# Client uploads (POST /bundles) — off unless SERVE_ACCEPT_BUNDLES=1; a host
# allowlist for ?url= fetches is opt-in on top (SSRF stays fail-closed).
if [[ "${SERVE_ACCEPT_BUNDLES:-}" == "1" || "${SERVE_ACCEPT_BUNDLES:-}" == "true" ]]; then
  args+=(--accept-bundles)
  [[ -n "${SERVE_ACCEPT_BUNDLES_FROM:-}" ]] &&
    args+=(--accept-bundles-from "${SERVE_ACCEPT_BUNDLES_FROM}")
fi

# Optional: exit after N idle seconds so the Cloud Run instance scales to zero.
# Set SERVE_IDLE_EXIT=0 (or unset) to run until terminated.
if [[ -n "${SERVE_IDLE_EXIT:-}" && "${SERVE_IDLE_EXIT}" != "0" ]]; then
  args+=("--exit-when-idle=${SERVE_IDLE_EXIT}")
fi

echo "entrypoint: starting compose-preview serve on 0.0.0.0:${PORT} (module ${SERVE_MODULE})" >&2
exec "${BIN}" "${args[@]}"
