#!/usr/bin/env bash
# Runtime entrypoint for the prebuilt preview-host image. Serves the baked-in
# project; maps the platform's $PORT + $SERVE_TOKEN onto serve flags.
set -euo pipefail
cd /project

# Seed the downloadable-font cache from the faces baked into the image (see the Dockerfile's
# `fonts` stage). `~/.cache/composeai/fonts` is a named volume, and a volume only inherits image
# content when it is FIRST created, so a box whose volume predates this change would otherwise
# never get them — copying on every boot covers both cases.
#
# The BAKED bytes win on a mismatch. The volume's copy has no authority: the catalog PNGs are
# rendered in CI from ITS font cache, never from this host, so an entry left here by an earlier
# runtime fetch is of unknown provenance and may already differ from what the PNG was rendered
# with. The baked set is the deterministic, release-pinned one, so on a long-lived box an upgrade
# has to be able to correct a drifted entry — otherwise the first stale fetch sticks forever.
# Replacement is via temp + `mv` so a torn copy is never visible to a render, and it happens before
# serve starts, so no daemon is holding the old typeface.
#
# Faces the image doesn't ship (e.g. a runtime-fetched Google Sans Flex) are left alone.
# Best-effort throughout — the renderer still fetches on a miss, exactly as it did before.
FONT_CACHE_SRC="${FONT_CACHE_SRC:-/opt/font-cache/fonts}"
FONT_CACHE_DST="${XDG_CACHE_HOME:-${HOME:-/root}/.cache}/composeai/fonts"
if [[ -d "${FONT_CACHE_SRC}" ]]; then
  if mkdir -p "${FONT_CACHE_DST}" 2>/dev/null; then
    seeded=0
    refreshed=0
    for f in "${FONT_CACHE_SRC}"/*.ttf; do
      [[ -e "$f" ]] || continue
      dst="${FONT_CACHE_DST}/$(basename "$f")"
      if [[ -s "${dst}" ]]; then
        cmp -s "$f" "${dst}" && continue
        action=refreshed
      else
        action=seeded
      fi
      if cp -p "$f" "${dst}.tmp" 2>/dev/null && mv -f "${dst}.tmp" "${dst}" 2>/dev/null; then
        [[ "${action}" == "seeded" ]] && seeded=$((seeded + 1)) || refreshed=$((refreshed + 1))
      else
        rm -f "${dst}.tmp" 2>/dev/null || true
        echo "entrypoint: warn: could not install baked font $(basename "$f")" >&2
      fi
    done
    if [[ "${seeded}" -gt 0 || "${refreshed}" -gt 0 ]]; then
      echo "entrypoint: baked fonts → ${FONT_CACHE_DST} (${seeded} new, ${refreshed} refreshed)" >&2
    fi
  else
    echo "entrypoint: warn: could not create ${FONT_CACHE_DST}; fonts will be fetched at runtime" >&2
  fi
fi

PORT="${PORT:-8080}"

args=(serve --host 0.0.0.0 --port "${PORT}")
[[ -n "${SERVE_CATALOG_MAX_IMAGES:-}" ]] &&
  args+=(--catalog-max-images "${SERVE_CATALOG_MAX_IMAGES}")

# The release tarball carries the matching CMP/Wasm Remote Compose player as a static sidecar.
# Older releases simply omit it and retain the existing player set.
if [[ -f /opt/compose-preview/rc-player-wasm/index.html ]]; then
  args+=(--rc-player-wasm-dir /opt/compose-preview/rc-player-wasm)
fi

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
# The published catalog set is CONFIG, NOT IMAGE CONTENT. It lives in a catalogs.json
# on the mounted /config volume: which catalogs to serve, the repo each one's
# design-artifacts branch lives in, whether it's on the front door, and the front-page
# section it's published under. Editing that file (or POSTing to /admin/catalogs) is
# how a catalog is added — no image rebuild, no CLI release, no compose edit.
#
# The image carries only a SEED (/etc/compose-preview/catalogs.default.json), copied in
# on first boot when the config file doesn't exist yet — so a bare `docker run` with an
# empty volume still comes up serving the standard set, while an operator's existing
# config is never overwritten by an image pull.
: "${SERVE_CATALOGS_FILE:=/config/catalogs.json}"
if [[ "${SERVE_CATALOGS_FILE}" != "none" ]]; then
  if [[ ! -f "${SERVE_CATALOGS_FILE}" && -f /etc/compose-preview/catalogs.default.json ]]; then
    if mkdir -p "$(dirname "${SERVE_CATALOGS_FILE}")" 2>/dev/null &&
      cp /etc/compose-preview/catalogs.default.json "${SERVE_CATALOGS_FILE}" 2>/dev/null; then
      echo "entrypoint: seeded ${SERVE_CATALOGS_FILE} from the image default" >&2
    else
      # Read-only / unwritable config dir: serve the baked seed directly rather than
      # coming up with no catalogs at all. Admin writes will report they can't persist.
      SERVE_CATALOGS_FILE=/etc/compose-preview/catalogs.default.json
      echo "entrypoint: ${SERVE_CATALOGS_FILE} not writable — serving the baked default" >&2
    fi
  fi
  args+=(--catalogs-file "${SERVE_CATALOGS_FILE}")
fi
# Optional ADDITIONS to the config file, for a box that wants one extra catalog without
# editing its config: <system>@<owner>/<repo>, comma-separated. Unset by default — the
# config file is the source of truth. A system named in both keeps its config entry.
[[ -n "${SERVE_CATALOGS:-}" && "${SERVE_CATALOGS}" != "none" ]] && args+=(--catalogs "${SERVE_CATALOGS}")
[[ -n "${SERVE_CATALOGS_UNLISTED:-}" && "${SERVE_CATALOGS_UNLISTED}" != "none" ]] &&
  args+=(--catalogs-unlisted "${SERVE_CATALOGS_UNLISTED}")
# Top-level sites: <host>=<system>, comma-separated. A catalog this box already serves is ALSO
# reachable on a hostname of its own, where it presents as the only thing here (its landing at /,
# links inside the domain, no front door, /status scoped to it). Same sessions and same baked
# pixels — a site is a view of this server, not a second one, so it costs no extra memory or
# render. The reverse proxy in front must route the hostname here and hold a certificate for it;
# catalogs.json's "sites" says the same thing as durable config.
[[ -n "${SERVE_SITES:-}" && "${SERVE_SITES}" != "none" ]] && args+=(--sites "${SERVE_SITES}")
# Runtime catalog administration (GET/POST /admin/catalogs, DELETE /admin/catalogs/<system>),
# gated by its own secret — never the browse token, which a public box hands to every visitor.
# Unset (the default) means the admin routes don't exist at all.
[[ -n "${SERVE_ADMIN_TOKEN:-}" ]] && args+=(--admin-token "${SERVE_ADMIN_TOKEN}")
# Aggregate view counts live beside catalog/trust config so container restarts and image updates do
# not erase engagement. Set to `none` to keep counters process-local.
: "${SERVE_ENGAGEMENT_FILE:=/config/engagement.json}"
[[ "${SERVE_ENGAGEMENT_FILE}" != "none" ]] && args+=(--engagement-file "${SERVE_ENGAGEMENT_FILE}")
if [[ -n "${SERVE_GITHUB_AUTH_CLIENT_ID:-}" ||
  -n "${SERVE_GITHUB_AUTH_CLIENT_SECRET:-}" ||
  -n "${SERVE_GITHUB_AUTH_COOKIE_SECRET:-}" ]]; then
  args+=(--github-auth-client-id "${SERVE_GITHUB_AUTH_CLIENT_ID:-}")
  args+=(--github-auth-client-secret "${SERVE_GITHUB_AUTH_CLIENT_SECRET:-}")
  args+=(--github-auth-cookie-secret "${SERVE_GITHUB_AUTH_COOKIE_SECRET:-}")
  args+=(--github-auth-repo "${SERVE_GITHUB_AUTH_REPO:-yschimke/compose-ai-tools}")
  github_auth_callback_base_url="${SERVE_GITHUB_AUTH_CALLBACK_BASE_URL:-}"
  if [[ -z "${github_auth_callback_base_url}" && -n "${DOMAIN:-}" ]]; then
    github_auth_callback_base_url="https://${DOMAIN}"
  fi
  [[ -n "${github_auth_callback_base_url}" ]] &&
    args+=(--github-auth-callback-base-url "${github_auth_callback_base_url}")
  # Scope the auth cookies to the parent domain so ONE sign-in covers this host and every
  # SERVE_SITES hostname under it. Without it the cookies are host-only, the state cookie written on
  # a site host never reaches the pinned callback origin, and the server withholds the sign-in
  # affordance on every site (live + playground stay snapshot-only there).
  #
  # Derived from DOMAIN, but only when sites are actually configured — a single-hostname box has
  # nothing to widen for, and a cookie domain is the blast radius of a session. Set it explicitly to
  # override, or to `none` to keep cookies host-only. Note the derivation is only as narrow as
  # DOMAIN itself: on a box whose DOMAIN is an apex, set this to the subdomain the sites live under
  # rather than letting one session span the whole zone.
  #
  # "Configured" means SERVE_SITES **or** a `sites` entry in the catalogs file. Reading only the env
  # var was right when that was the only way to declare a site; now that the committed config
  # delivers them (and /admin/sites publishes them), a box with sites and no SERVE_SITES would have
  # kept host-only cookies and silently withheld sign-in on every site host.
  github_auth_cookie_domain="${SERVE_GITHUB_AUTH_COOKIE_DOMAIN:-}"
  if [[ -z "${github_auth_cookie_domain}" && -n "${DOMAIN:-}" ]]; then
    # `none` is this file's documented "explicitly off" spelling for SERVE_SITES, so it must not
    # read as a configured site here either.
    sites_configured="${SERVE_SITES:-}"
    [[ "${sites_configured}" == "none" ]] && sites_configured=""
    if [[ -z "${sites_configured}" && "${SERVE_CATALOGS_FILE}" != "none" ]]; then
      sites_configured="$(sh /usr/local/bin/site-domains.sh "${SERVE_CATALOGS_FILE}" 2>/dev/null || true)"
    fi
    [[ -n "${sites_configured}" ]] && github_auth_cookie_domain="${DOMAIN}"
  fi
  [[ -n "${github_auth_cookie_domain}" && "${github_auth_cookie_domain}" != "none" ]] &&
    args+=(--github-auth-cookie-domain "${github_auth_cookie_domain}")
  [[ -n "${SERVE_GITHUB_AUTH_USERS:-}" ]] && args+=(--github-auth-users "${SERVE_GITHUB_AUTH_USERS}")
  # Unset derives the scope from the gating repo's visibility (public -> read:user, private ->
  # read:user repo). Only set this when a GitHub App or org policy demands a specific scope.
  [[ -n "${SERVE_GITHUB_AUTH_SCOPE:-}" ]] && args+=(--github-auth-scope "${SERVE_GITHUB_AUTH_SCOPE}")
fi
# The producer-trust store is CONFIG, on the same /config volume as catalogs.json — for the
# same reason. It used to live only in the image, which meant trusting a new producer needed a
# code change, a release and an image publish, while a *catalog* could be published at runtime in
# one HTTP call. That asymmetry made runtime catalog registration close to useless: the catalog
# served, but badged `unverified` until the image caught up.
#
# The image still carries the seed (/trust/producers.json) and it is copied to the volume on
# first boot only, never overwritten — so an operator edit, or a POST to /admin/trust (which
# rewrites this same file), survives every subsequent image roll. Falls back to serving the
# baked file read-only when /config isn't writable, exactly like the catalogs seed.
# `:=` fills it when SERVE_TRUST_STORE is unset OR empty (an older host compose passes ""), so a
# bare image pull self-heals a box without editing compose. Override with your own path to pin
# different producers, or the literal `none` to run trustless (catalogs then show Unverified).
# NB opt-out is `none`, not empty — empty deliberately falls back to the default.
#
# Seeding applies ONLY to the default path. An operator who names their own SERVE_TRUST_STORE and
# whose file is missing — a typo, an unmounted secret, a broken deploy — must NOT silently get the
# image's allowlist instead: with SERVE_ALLOW_RENDER_TRUSTED=1 that would execute producers they
# never configured. For an explicit override the file has to already be there, and a missing one
# keeps the old hard failure (the CLI exits non-zero on an absent --trust-store).
serve_trust_store_defaulted=0
[[ -z "${SERVE_TRUST_STORE:-}" ]] && serve_trust_store_defaulted=1
: "${SERVE_TRUST_STORE:=/config/producers.json}"
if [[ "${SERVE_TRUST_STORE}" != "none" ]]; then
  if [[ "${serve_trust_store_defaulted}" == 1 && ! -f "${SERVE_TRUST_STORE}" &&
    -f /trust/producers.json ]]; then
    if mkdir -p "$(dirname "${SERVE_TRUST_STORE}")" 2>/dev/null &&
      cp /trust/producers.json "${SERVE_TRUST_STORE}" 2>/dev/null; then
      echo "entrypoint: seeded ${SERVE_TRUST_STORE} from the image default" >&2
    else
      # Read-only /config (or a bind mount pointing somewhere unwritable): serve the baked store
      # rather than refusing to start. Admin trust writes will report themselves unpersisted.
      echo "entrypoint: ${SERVE_TRUST_STORE} not writable — using the baked trust store" >&2
      SERVE_TRUST_STORE=/trust/producers.json
    fi
  fi
  args+=(--trust-store "${SERVE_TRUST_STORE}")
fi
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

# Playground compile lane. Off by default because it compiles and runs visitor-supplied Kotlin.
# SERVE_PLAYGROUND=1 enables it with NOTHING pinned: the editor offers a runtime selector over the
# catalogs this host already serves, and the chosen catalog's bundle backend picks the renderer and
# the dependencies. SERVE_PLAYGROUND_BUNDLE still pins a default (a served catalog system id like
# `compose-m3`, or a local .bundle path) and the two compose — a pinned bundle becomes the
# selector's preselected "Server default" entry.
# A public server must be admitted by one of the two postures the CLI gate accepts — GitHub auth
# configured (repo-access-gated), or a sandbox profile that passes the preflight — otherwise serve
# refuses the lane and `/playground` shows an explanatory disabled page.
[[ -n "${SERVE_PLAYGROUND:-}" && "${SERVE_PLAYGROUND}" != "0" ]] &&
  args+=(--playground)
[[ -n "${SERVE_PLAYGROUND_CATALOG_LIMIT:-}" ]] &&
  args+=(--playground-catalog-limit "${SERVE_PLAYGROUND_CATALOG_LIMIT}")
[[ -n "${SERVE_PLAYGROUND_BUNDLE:-}" ]] &&
  args+=(--playground-bundle "${SERVE_PLAYGROUND_BUNDLE}")
[[ -n "${SERVE_PLAYGROUND_ANDROID_BUNDLE:-}" ]] &&
  args+=(--playground-android-bundle "${SERVE_PLAYGROUND_ANDROID_BUNDLE}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX:-}" ]] &&
  args+=(--playground-sandbox "${SERVE_PLAYGROUND_SANDBOX}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX_MEMORY_MB:-}" ]] &&
  args+=(--playground-sandbox-memory-mb "${SERVE_PLAYGROUND_SANDBOX_MEMORY_MB}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX_CPUS:-}" ]] &&
  args+=(--playground-sandbox-cpus "${SERVE_PLAYGROUND_SANDBOX_CPUS}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX_PIDS:-}" ]] &&
  args+=(--playground-sandbox-pids "${SERVE_PLAYGROUND_SANDBOX_PIDS}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX_TTL:-}" ]] &&
  args+=(--playground-sandbox-ttl "${SERVE_PLAYGROUND_SANDBOX_TTL}")
[[ -n "${SERVE_PLAYGROUND_SANDBOX_RO:-}" ]] &&
  args+=(--playground-sandbox-ro "${SERVE_PLAYGROUND_SANDBOX_RO}")
[[ -n "${SERVE_PLAYGROUND_COMPILE_SLOTS:-}" ]] &&
  args+=(--playground-compile-slots "${SERVE_PLAYGROUND_COMPILE_SLOTS}")
# Per-caller compile budget (issue #3214). Every other playground bound is a whole-host one, so
# without this one caller can hold every compile slot. Default 10/min, 1 concurrent; set
# SERVE_PLAYGROUND_RATE_LIMIT=0 to turn the limiter off. SERVE_TRUST_FORWARDED_FOR is only safe
# behind a reverse proxy that APPENDS the peer address it saw — see the CLI flag's docs.
[[ -n "${SERVE_PLAYGROUND_RATE_LIMIT:-}" ]] &&
  args+=(--playground-rate-limit "${SERVE_PLAYGROUND_RATE_LIMIT}")
[[ -n "${SERVE_PLAYGROUND_CALLER_CONCURRENCY:-}" ]] &&
  args+=(--playground-caller-concurrency "${SERVE_PLAYGROUND_CALLER_CONCURRENCY}")
# Experimental stateful BTA editing: exactly one GitHub-authenticated lease across the host.
[[ "${SERVE_PLAYGROUND_EDITING:-0}" == "1" ]] && args+=(--playground-editing)
[[ -n "${SERVE_PLAYGROUND_EDIT_LEASE_TTL:-}" ]] &&
  args+=(--playground-edit-lease-ttl "${SERVE_PLAYGROUND_EDIT_LEASE_TTL}")
[[ -n "${SERVE_TRUST_FORWARDED_FOR:-}" && "${SERVE_TRUST_FORWARDED_FOR}" != "0" ]] &&
  args+=(--trust-forwarded-for)

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

# Document lane: accept a generated Remote Compose / Lottie document and hand back an expiring
# permalink (GET /docs, POST /docs, GET /d/<id>). Data-only — the document is played back by a
# player in the visitor's browser, so nothing runs on the box. Off unless asked for.
if [[ "${SERVE_ACCEPT_DOCS:-}" == "1" || "${SERVE_ACCEPT_DOCS:-}" == "true" ]]; then
  args+=(--accept-docs)
  [[ -n "${SERVE_DOC_TTL:-}" ]] && args+=(--doc-ttl "${SERVE_DOC_TTL}")
  [[ -n "${SERVE_ACCEPT_DOCS_FROM:-}" ]] &&
    args+=(--accept-docs-from "${SERVE_ACCEPT_DOCS_FROM}")
fi

# Extra Maven repositories the live-daemon classpath resolver may fetch from, beyond Maven Central +
# Google Maven. A served catalog whose module pulls deps from a non-default repo (e.g.
# meshcore-mobile's jitpack.io deps like usb-serial-for-android) otherwise has those coordinates
# skipped, so its live daemon can't build its classpath and the catalog falls back to baked PNGs.
# Defaults to the repos every baked live catalog needs: jitpack.io (meshcore-mobile's
# usb-serial-for-android etc.), the Apollo snapshots repo (Confetti's mapped Apollo artifacts), and
# Automattic's a8c-libs S3 repo (Pocket Casts' `com.automattic:eventhorizon`, which its
# `Theme.ThemeType` links against — without it the /pocketcasts live daemon dies on
# `NoClassDefFoundError: com/automattic/eventhorizon/AppThemeType` the moment a themed preview
# renders). Override with your own comma list to add another catalog's repo, or set `none` to send
# only Central + Google. Empty inherits this baked default.
: "${SERVE_EXTRA_MAVEN_REPOS:=https://jitpack.io,https://storage.googleapis.com/apollo-snapshots/m2,https://a8c-libs.s3.amazonaws.com/android}"
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
# RSS history is demand-activated: each feed request renews this inactivity lease. Once it expires,
# its branch worker sleeps while retaining the generated XML + shallow Git cache under /config.
[[ -n "${SERVE_CATALOG_FEED_IDLE:-}" ]] &&
  args+=(--catalog-feed-idle-timeout "${SERVE_CATALOG_FEED_IDLE}")

# Warmed theme renders survive a container recreation when this points at a mounted volume.
# Defaults beside catalogs.json (/config/theme-cache) — the server declines to persist at all
# rather than fall back to a temp dir, since a theme cache thrown away with the container costs
# disk and render time to buy nothing.
[[ -n "${SERVE_THEME_CACHE_DIR:-}" ]] && args+=(--theme-cache-dir "${SERVE_THEME_CACHE_DIR}")
[[ -n "${SERVE_THEME_CACHE_MAX_BYTES:-}" ]] &&
  args+=(--theme-cache-max-bytes "${SERVE_THEME_CACHE_MAX_BYTES}")

# The heavy bytes a catalog fetches — its executable liveBundle, the per-preview splits and the
# externalised resource pool — kept on a volume so a rolled replica reads them instead of pulling
# ~100 MB per live catalog again. Unlike the theme cache, unset is NOT off: the server falls back
# to a temp-dir pool, which is what it always had. Only commit-pinned reads are cached.
[[ -n "${SERVE_CATALOG_CACHE_DIR:-}" ]] && args+=(--catalog-cache-dir "${SERVE_CATALOG_CACHE_DIR}")
[[ -n "${SERVE_CATALOG_CACHE_MAX_BYTES:-}" ]] &&
  args+=(--catalog-cache-max-bytes "${SERVE_CATALOG_CACHE_MAX_BYTES}")

echo "entrypoint: compose-preview serve on 0.0.0.0:${PORT}" >&2
exec compose-preview "${args[@]}"
