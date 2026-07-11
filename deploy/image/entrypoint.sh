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
: "${SERVE_CATALOGS_UNLISTED:=meshcore-mobile@yschimke/meshcore-mobile,homeassistant-remotecompose@yschimke/homeassistant-remotecompose,cadence@yschimke/cadence}"
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
# Trusted server-side re-render — ON by default, and cheap: for a Trusted catalog
# that carries an executable `liveBundle` (the desktop CMP `compose-m3` does), serve
# fetches that bundle from the trusted branch and launches a render daemon straight
# from it — NO source checkout, NO Gradle build. So a bare image pull "just works"
# with live CMP; set SERVE_ALLOW_RENDER_TRUSTED=0 to opt out (Wasm still carries CMP).
# Safe/fail-closed: only Trusted catalogs execute, and a catalog with no runnable
# bundle (the Android wear/remote) simply falls back to baked PNG.
: "${SERVE_ALLOW_RENDER_TRUSTED:=1}"
[[ -n "${SERVE_REVISIONS_ALLOW:-}" ]] && args+=(--revisions-allow "${SERVE_REVISIONS_ALLOW}")
if [[ "${SERVE_ALLOW_RENDER_TRUSTED}" == "1" || "${SERVE_ALLOW_RENDER_TRUSTED}" == "true" ]]; then
  args+=(--allow-render-trusted)
  # Optional SOURCE-BUILD FALLBACK (not needed for the bundle path above). For a
  # catalog that declares a Gradle `source` but no `liveBundle`, the prebuilt image
  # has no checkout to worktree from; set SERVE_CATALOG_SOURCE_REPO to clone one and
  # point serve at it with --catalog-source-root. This DOES pay a one-time cold Gradle
  # build at startup — leave it unset (the default) unless you specifically need the
  # source path; the bundle path covers the published catalogs with no build.
  if [[ -n "${SERVE_CATALOG_SOURCE_REPO:-}" ]]; then
    src_root="${SERVE_CATALOG_SOURCE_ROOT:-/catalog-src}"
    src_ref="${SERVE_CATALOG_SOURCE_REF:-main}"
    if [[ ! -d "${src_root}/.git" ]]; then
      echo "entrypoint: cloning ${SERVE_CATALOG_SOURCE_REPO}@${src_ref} → ${src_root} for trusted live render" >&2
      git clone --branch "${src_ref}" "https://github.com/${SERVE_CATALOG_SOURCE_REPO}.git" "${src_root}"
    else
      git -C "${src_root}" fetch --quiet origin "${src_ref}" && \
        git -C "${src_root}" checkout --quiet -B "${src_ref}" "origin/${src_ref}" || \
        echo "entrypoint: refresh of ${src_root} failed — building from the existing checkout" >&2
    fi
    args+=(--catalog-source-root "${src_root}")
  fi
fi
# Bound concurrent live (daemon-backed) stream seats — each seat holds a JVM Compose daemon, so with
# the live tier on by default we default the cap to 1 for the reference 4 GB / 2 vCPU box
# (preview.coo.ee): an over-cap viewer is refused (WS 1013) rather than OOM-ing the box. Raise it on
# a beefier box (SERVE_LIVE_SEATS=4), or set 0 for unbounded.
: "${SERVE_LIVE_SEATS:=1}"
[[ -n "${SERVE_LIVE_SEATS}" ]] && args+=(--live-seats "${SERVE_LIVE_SEATS}")
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
