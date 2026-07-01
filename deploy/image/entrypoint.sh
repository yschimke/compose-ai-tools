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

# The public-server pillars. The prebuilt image has no catalog modules to build a
# Wasm app from, so its in-browser tier rides --catalogs: `serve` fetches each
# system's web/wasm/ from the trusted design-artifacts branch. (--wasm-dir is for
# the from-source image's local build.)
#
# The published catalog set is BAKED INTO THE IMAGE (same `:=` + `none` convention
# as SERVE_TRUST_STORE below), so a bare `docker pull` / Watchtower auto-update
# self-heals without editing the box's compose. Front-page systems:
: "${SERVE_CATALOGS:=compose-m3,wear-m3,remote-m3}"
[[ "${SERVE_CATALOGS}" != "none" ]] && args+=(--catalogs "${SERVE_CATALOGS}")
# …and the app design systems we publish UNLISTED from their own repos — reachable at
# /<system>/ (and ?session=<system>) but off the front-page nav. <system>@<owner>/<repo>
# points at a per-repo design-artifacts branch, which must be trusted (see the store
# below) to badge Trusted. Override with your own list, or `none` to serve none.
: "${SERVE_CATALOGS_UNLISTED:=meshcore-mobile@yschimke/meshcore-mobile,homeassistant-remotecompose@yschimke/homeassistant-remotecompose}"
[[ "${SERVE_CATALOGS_UNLISTED}" != "none" ]] && args+=(--catalogs-unlisted "${SERVE_CATALOGS_UNLISTED}")
# Default to the baked branch-trust store so the published design-artifacts catalogs
# badge as Trusted(Branch) out of the box. `:=` fills it when SERVE_TRUST_STORE is
# unset OR empty (an older host compose passes ""), so a bare image pull self-heals a
# box without editing compose. Override with your own path to pin different
# producers, or the literal `none` to run trustless (catalogs then show Unverified).
# NB opt-out is `none`, not empty — empty deliberately falls back to the default.
: "${SERVE_TRUST_STORE:=/trust/producers.json}"
[[ "${SERVE_TRUST_STORE}" != "none" ]] && args+=(--trust-store "${SERVE_TRUST_STORE}")
[[ -n "${SERVE_WASM_DIR:-}" ]] && args+=(--wasm-dir "${SERVE_WASM_DIR}")
# Trusted server-side re-render (opt-in, OFF by default). Only enable on a box that
# can BUILD the catalog source — this desktop image CANNOT build the Android
# catalogs, so leave SERVE_ALLOW_RENDER_TRUSTED unset on the public preview server.
# Needs SERVE_REVISIONS_ALLOW (the trusted ref allowlist) to build anything.
[[ -n "${SERVE_REVISIONS_ALLOW:-}" ]] && args+=(--revisions-allow "${SERVE_REVISIONS_ALLOW}")
if [[ "${SERVE_ALLOW_RENDER_TRUSTED:-}" == "1" || "${SERVE_ALLOW_RENDER_TRUSTED:-}" == "true" ]]; then
  args+=(--allow-render-trusted)
fi
# Bound concurrent live (daemon-backed) stream seats. Only meaningful once the live tier is enabled
# (SERVE_ALLOW_RENDER_TRUSTED) — each seat holds a JVM Compose daemon, so a small box (preview.coo.ee
# is 4 GB / 2 vCPU) should cap it (e.g. SERVE_LIVE_SEATS=1) so an over-cap viewer is refused rather
# than OOM-ing the box. Unset ⇒ unbounded (fine for a beefy box or the snapshot/Wasm-only default).
[[ -n "${SERVE_LIVE_SEATS:-}" ]] && args+=(--live-seats "${SERVE_LIVE_SEATS}")
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
