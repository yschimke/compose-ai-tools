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
: "${SERVE_CATALOGS:=compose-m3,wear-m3,remote-m3,meshcore-mobile@yschimke/meshcore-mobile,homeassistant-remotecompose@yschimke/homeassistant-remotecompose,confetti-wear@joreilly/Confetti,confetti-mobile@joreilly/Confetti}"
[[ "${SERVE_CATALOGS}" != "none" ]] && args+=(--catalogs "${SERVE_CATALOGS}")
# …and cadence, served UNLISTED from its own repo — reachable at /cadence/ (and ?session=cadence)
# but kept OFF the front-page index. (meshcore-mobile / homeassistant-remotecompose are LISTED above
# so they show on the front page.) <system>@<owner>/<repo> points at a per-repo design-artifacts
# branch, which must be trusted (see the store below) to badge Trusted. `none` serves none.
: "${SERVE_CATALOGS_UNLISTED:=cadence@yschimke/cadence}"
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
# Bound concurrent live (daemon-backed) stream sessions by a PERMIT BUDGET — each live session
# charges permits by backend weight (a desktop CMP daemon = 1, a heavier Robolectric Android one = 2,
# see LiveSeatLimiter), so one heavy catalog can't hog a flat seat count and starve the cheap CMP
# lanes. An over-budget viewer is refused (WS 1013) rather than OOM-ing the box.
#
# When SERVE_LIVE_SEATS is unset we AUTO-DERIVE the budget from the container's memory limit so a
# bigger box scales up on its own (no compose edit, no rebuild): reserve ~1 GB for the serve host +
# OS, budget ~1.2 GB of headroom per permit, and clamp to [2, 8]. The floor of 2 means even the
# reference 4 GB box always runs at least two cheap CMP sessions concurrently (4 GB → 2; 8 GB → 5).
# Set SERVE_LIVE_SEATS explicitly to override, or 0 for unbounded.
if [[ -z "${SERVE_LIVE_SEATS:-}" ]]; then
  # Detect the cgroup memory limit (v2 then v1), capped by physical RAM so an "unlimited" sentinel
  # (a huge number or the literal "max") falls back to the real total instead of overshooting.
  mem_total_mb=0
  if [[ -r /proc/meminfo ]]; then
    mem_total_mb=$(awk '/^MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
  fi
  limit_bytes=""
  if [[ -r /sys/fs/cgroup/memory.max ]]; then
    limit_bytes=$(cat /sys/fs/cgroup/memory.max 2>/dev/null)          # cgroup v2
  elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then
    limit_bytes=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)  # cgroup v1
  fi
  mem_limit_mb=0
  if [[ "${limit_bytes}" =~ ^[0-9]+$ ]]; then
    mem_limit_mb=$(( limit_bytes / 1024 / 1024 ))
  fi
  # Effective memory = the tighter of the cgroup limit and physical RAM (0 = unknown → ignore).
  eff_mb=0
  if (( mem_limit_mb > 0 && mem_total_mb > 0 )); then
    eff_mb=$(( mem_limit_mb < mem_total_mb ? mem_limit_mb : mem_total_mb ))
  elif (( mem_limit_mb > 0 )); then
    eff_mb=${mem_limit_mb}
  else
    eff_mb=${mem_total_mb}
  fi
  seats=2
  if (( eff_mb > 0 )); then
    seats=$(( (eff_mb - 1024) / 1200 ))
    (( seats < 2 )) && seats=2
    (( seats > 8 )) && seats=8
  fi
  SERVE_LIVE_SEATS="${seats}"
  echo "entrypoint: auto live-seat budget ${SERVE_LIVE_SEATS} (effective mem ${eff_mb} MB)" >&2
fi
[[ -n "${SERVE_LIVE_SEATS}" ]] && args+=(--live-seats "${SERVE_LIVE_SEATS}")
if [[ "${SERVE_ACCEPT_BUNDLES:-}" == "1" || "${SERVE_ACCEPT_BUNDLES:-}" == "true" ]]; then
  args+=(--accept-bundles)
  [[ -n "${SERVE_ACCEPT_BUNDLES_FROM:-}" ]] &&
    args+=(--accept-bundles-from "${SERVE_ACCEPT_BUNDLES_FROM}")
fi

# Extra Maven repositories the live-daemon classpath resolver may fetch from, beyond Maven Central +
# Google Maven. A served catalog whose module pulls deps from a non-default repo (e.g.
# meshcore-mobile's jitpack.io deps like usb-serial-for-android) otherwise has those coordinates
# skipped, so its live daemon can't build its classpath and the catalog falls back to baked PNGs.
# Defaults to the repos every baked live catalog needs: jitpack.io (meshcore-mobile's
# usb-serial-for-android etc.) and the Apollo snapshots repo (Confetti's mapped Apollo artifacts).
# Override with your own comma list to add another catalog's repo, or set `none` to send only
# Central + Google. Empty inherits this baked default.
: "${SERVE_EXTRA_MAVEN_REPOS:=https://jitpack.io,https://storage.googleapis.com/apollo-snapshots/m2}"
[[ "${SERVE_EXTRA_MAVEN_REPOS}" != "none" && -n "${SERVE_EXTRA_MAVEN_REPOS}" ]] &&
  args+=(--extra-maven-repos "${SERVE_EXTRA_MAVEN_REPOS}")

# Generous render/build timeout so a slow host's first render doesn't trip the
# CLI's 300s default (the warm cache is baked in, so it's normally fast anyway).
args+=(--timeout "${SERVE_TIMEOUT:-1800}")

# Optional: exit after N idle seconds (set SERVE_IDLE_EXIT>0) so a scale-to-zero
# platform can reclaim the instance. Default 0 = stay up.
if [[ -n "${SERVE_IDLE_EXIT:-}" && "${SERVE_IDLE_EXIT}" != "0" ]]; then
  args+=("--exit-when-idle=${SERVE_IDLE_EXIT}")
fi

# Keep the published catalogs fresh against their `design-artifacts/<system>` branches WITHOUT a
# restart: re-check each branch's head every SERVE_CATALOG_REFRESH seconds and re-fetch on change
# (via `git ls-remote`, no API rate limit). Defaults to the CLI's 600s; set 0 to disable (serve the
# boot snapshot until the container recycles). This is what lets a `design-artifacts.yml` regen
# reach preview.coo.ee on its own — Watchtower only rolls the *image*, never the branch content.
[[ -n "${SERVE_CATALOG_REFRESH:-}" ]] && args+=(--catalog-refresh-interval "${SERVE_CATALOG_REFRESH}")

echo "entrypoint: compose-preview serve on 0.0.0.0:${PORT}" >&2
exec compose-preview "${args[@]}"
