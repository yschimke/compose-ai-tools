# Prebuilt preview-host image (instant deploy)

A **prebuilt** Docker image that runs `compose-preview serve` with **no build on
the host**. It's the fast alternative to [`deploy/cloudrun/Dockerfile`](../cloudrun/Dockerfile),
which compiles the whole tool from source (~8 min) — instead this installs the
**released** `compose-preview` CLI and serves published catalogs/live bundles
without a local Gradle project. Built once in CI and pushed to GHCR, so hosts just
pull it.

- **Image:** `ghcr.io/yschimke/compose-preview-host:<version>` (and `:latest`)
- **Render targets:** Compose **Desktop** live bundles (Skiko software GL), **plus** a baked
  **Android/Robolectric** daemon + minimal Android SDK so a served Android **Wear** catalog
  (`wear-m3`) renders live server-side. Lighting the Android live lane needs the catalog's stickers to
  carry the `previewId` daemon mapping **and** the bundle to carry the app's resource table under
  `android/`; both shipped in **0.16.50** (previewId #2492, app-resource carriage #2498 + missing-
  resource placeholder fallback #2499), so `wear-m3` renders live once the box rolls that image and
  re-fetches the regenerated bundle. See `docs/public-preview-server.md`. This is what
  `preview.coo.ee` runs.
- **Bundle uploads:** disabled. **Auth:** shared token. **TLS:** via Caddy.

## How it's fast

The from-source image compiles `build-logic` + the Gradle plugin + the CLI + ~50
data modules. This one skips all of that:

| | From source (`deploy/cloudrun`) | Prebuilt (`deploy/image`) |
|---|---|---|
| Tool | compiled in the image (~8 min) | **release CLI tarball, staged by CI** |
| Release modules | built locally | **same-tag Maven tree, baked into the image** |
| Content served | the whole repo's `:samples:cmp` | published catalogs + live bundles |
| Host build | yes, every deploy | **none — `docker pull`** |

The image carries the release's Maven modules and both live-render backends, so
catalog bundles can start without building a local project or waiting for the
release to propagate through Maven Central.

## Publishing the image (one-time / per release)

The [`preview-host-image.yml`](../../.github/workflows/preview-host-image.yml)
workflow builds and pushes to GHCR. The automatic path starts as soon as the
release tag exists and builds its inputs directly from that tag, in parallel
with the core release. Trigger it either way:

- **On a CLI release** (`v*` tag) — automatic; bundles that version + tags `latest`.
- **Manually** — Actions → *Publish preview-host image* → run with a `cli_version`
  (e.g. `0.16.33`).

First publish makes the GHCR package; set it **public** (Packages → settings) if
you want hosts to pull without auth.

## Deploying (on any VPS — Hetzner, etc.)

DNS `A` record → host IP, ports 80/443 reachable, then:

```bash
git clone https://github.com/yschimke/compose-ai-tools.git
cd compose-ai-tools/deploy/image
DOMAIN=preview.example.com ./setup.sh
```

`setup.sh` installs Docker (if needed), writes `.env` (generated token), and
`docker compose pull && up -d` — **no build**. It prints your
`https://preview.example.com/?token=<TOKEN>` link once Caddy has a cert.

Pin a version with `IMAGE_TAG=0.16.33` in `.env` (a bare tag; defaults to the
`latest` tag when unset).

## Auto-updates (zero-downtime)

Updates are **rolling** — existing traffic keeps being served on the old
container until the new one is up and healthy, so a deploy never 502s. Two
services split the work:

- **`rollout`** updates the `preview` server with
  [docker-rollout](https://github.com/wowu/docker-rollout). It polls GHCR and,
  when a new `:latest` lands, boots a **second** `preview` replica alongside the
  live one, waits for that replica's `/readyz` healthcheck to pass, lets Caddy
  drain traffic onto it, then retires the old replica. The chain stays hands-off:

  > merge → cut a `v*` tag → core release + `preview-host-image.yml` start in
  > parallel → image publishes `:latest` →
  > `rollout` pulls it → new replica boots + goes healthy → traffic drains over →
  > old replica retired

- **`watchtower`** updates only the `caddy` reverse-proxy (below). Caddy publishes
  fixed `80`/`443` ports, so it can't be scaled/rolled; Watchtower's in-place
  recreate is a ~1s proxy blip, and only when the baked-Caddyfile image changes.

### Instant roll on publish (webhook — skips the poll wait)

The `rollout` poll only notices a new image on its next tick, so a fresh release
sits up to `ROLLOUT_INTERVAL` (default 1200s) before the box even *starts* rolling.
The **`hook`** service closes that gap: it exposes a token-gated
`POST /__hooks/rollout` (routed through Caddy) that runs `rollout.sh` immediately, so
the publish CI can push the roll the moment the image lands:

> merge → release tag → `preview-host-image.yml` builds & pushes `:latest` → **its final
> step POSTs `/__hooks/rollout`** → `hook` runs `rollout.sh` → new replica boots +
> goes healthy → traffic drains over → old replica retired

**Fire on the image, not the release.** The webhook is triggered from the *end of the
image build*, not a `release: published` event — at image-publish time the GHCR image
is fully self-contained (baked CLI + plugin jars + live-render daemons), so
the box needs **only GHCR** to roll and **no Maven propagation can race it** (the
image workflow builds and seeds its local `m2` directly from the release tag).
A `release: published` webhook would fire *before* the image exists and roll the box onto
the *old* `:latest`.

**Safe to expose (behind Caddy TLS):**
- A bearer `DEPLOY_HOOK_TOKEN` is required; **fail-closed** — with none set the `hook`
  service stays up but idle and never opens its port (never an unauthenticated exec
  endpoint).
- The only effect is `rollout.sh` on the **already-configured** image tag. The caller
  can't choose what image runs, so a leaked/replayed token forces at most a rollout
  *check* of the tag the box is already pinned to — a bounded no-op, and idempotent
  (`rollout.sh` rolls only if the pulled digest actually changed).
- Single-flight: overlapping calls fold into the in-progress roll instead of launching
  parallel rollouts.

**Wiring it up.** `setup.sh` generates `DEPLOY_HOOK_TOKEN` into `.env` and prints it;
add the **same value** as the repo's `DEPLOY_HOOK_TOKEN` Actions secret. If the box
isn't `preview.coo.ee`, also set a `DEPLOY_HOOK_URL` repo *variable* to
`https://<your-domain>/__hooks/rollout`. The CI step is **best-effort** — with no
secret it's skipped, and any failure just falls back to the poll loop, which still
rolls within one interval. To disable the webhook entirely, comment out the `hook`
service **and** the Caddyfile `/__hooks/rollout` route; the poll loop keeps working.

**How the swap stays seamless.** `preview` has a Docker `healthcheck` on the
app's ungated `/readyz` **readiness** route — green only once the new replica has
actually rendered a preview, not merely bound its port (that's `/healthz`), so a
replica with a broken render pipeline never gets promoted. docker-rollout won't
retire the old replica until the new one reports `healthy`. Meanwhile the Caddyfile proxies to
`preview` via **dynamic upstreams** (re-resolving the service's Docker DNS every
few seconds) with cross-replica **retry**, so during the brief two-replica
overlap a request that hits the still-booting replica is retried onto the warm
one. Net effect: no dropped requests across an update.

Both `rollout` and `watchtower` poll every `1200`s (set `ROLLOUT_INTERVAL` in
`.env` to change the rollout cadence) and need the Docker socket (root-equivalent
on the host — fine for your own box). `rollout` also mounts this directory
read-only so it can `docker compose pull` + scale `preview`; the vendored
[`docker-rollout`](./docker-rollout) plugin is mounted into the container's CLI
plugins dir (no runtime download).

> **Manual rollout.** `setup.sh` also installs the plugin on the host, so you can
> force a zero-downtime update by hand with `sudo docker rollout preview` (or
> `./rollout.sh`, which pulls first and only rolls if the image changed).

> **Adopting this on a box first started before the project name was pinned.**
> `docker-compose.yml` now sets `name: compose-preview` so the `rollout`
> container's Compose commands target the same project as the host. A box brought
> up before that change ran under the **directory-derived** project name (`image`
> when deployed the documented way, from `deploy/image/`). Because the new `name:`
> takes precedence, a plain `docker compose down` here would target the *new,
> empty* `compose-preview` project and leave the old stack running — colliding on
> ports 80/443. So stop the **old** project by name first, then start the pinned
> one:
>
> ```bash
> docker compose ls                # find the old project name (e.g. `image`)
> docker compose -p image down     # stop the OLD stack explicitly
> docker compose up -d             # start the pinned `compose-preview` project
> ```
>
> One brief restart; rolling from then on.

**The reverse-proxy config auto-deploys too.** The `caddy` service runs
`ghcr.io/…/compose-preview-caddy:latest` — a `caddy:2` image with
`deploy/image/Caddyfile` **baked in** — rather than `caddy:2` + a bind-mounted
Caddyfile. Watchtower watches image digests, not files, so this is what lets a
Caddyfile change roll out on its own:

> edit `deploy/image/Caddyfile` → merge → `preview-caddy-image.yml` publishes
> `compose-preview-caddy:latest` → Watchtower pulls it → caddy recreated with the
> new config

Certs survive a recreate (they live in the `caddy_data` volume, so no
re-provision / rate-limit). `{$DOMAIN}` is still read from `.env` at runtime.
Pin a specific config with `CADDY_IMAGE_TAG=sha-<commit>` in `.env`.

> **Migrating an existing box** from the old `caddy:2` + `./Caddyfile` mount: pull
> this compose and `docker compose up -d` once — it swaps in the baked image. After
> that, Caddyfile edits ride Watchtower with no manual `caddy reload`.

> **First publish is private — make it public once.** GHCR packages default to
> private, so after `preview-caddy-image.yml`'s first run, set the new
> `compose-preview-caddy` package **public** (Packages → settings), exactly like
> `compose-preview-host` above. Otherwise a fresh or migrating box's unauthenticated
> `docker compose pull` fails on the caddy image *before Caddy can start* — no TLS,
> no proxy. (Alternatively, give the box registry creds — see *Private GHCR
> package* below; it now covers the caddy image too, not just the server.)

> **Image:** this uses the maintained
> [`nicholas-fedor/watchtower`](https://github.com/nicholas-fedor/watchtower) fork,
> pinned by tag+digest. The original `containrrr/watchtower` is effectively
> unmaintained and its baked Docker SDK negotiates API 1.25, which modern engines
> reject (`client version 1.25 is too old. Minimum supported API version is 1.40`) —
> so it silently never updates. Bump the tag **and** digest together to adopt a newer
> release.

Requirements / options:
- **Leave `IMAGE_TAG` unset (it defaults to the `latest` tag)** — both pollers only
  track a moving tag. A pinned `IMAGE_TAG=0.16.32` won't auto-update (by design).
  The value is a bare tag like `latest`, not `:latest` — the compose image string
  already supplies the colon (`…host:${IMAGE_TAG:-latest}`).
- **Zero-downtime updates:** the old `preview` keeps serving until the new replica
  is healthy, so there's no 502 window (contrast the old Watchtower recreate, which
  restarted `preview` in place for ~1 min). The swap briefly runs **two** `preview`
  replicas; on a memory-tight shared host cap the transient overlap with
  `PREVIEW_MEM_LIMIT` (which also lowers the derived live-seat budget per replica).
- **Private GHCR package:** mount registry creds so the in-container pull can
  authenticate — add `- ~/.docker/config.json:/root/.docker/config.json:ro` to the
  `rollout` service **and** the `hook` service (both run `rollout.sh` → `docker
  compose pull preview`) and `- ~/.docker/config.json:/config.json:ro` to
  `watchtower` (for `caddy`), after `docker login ghcr.io`. Public packages need
  nothing.
- **Pause auto-rollout:** comment out the `rollout` service and update `preview` by
  hand with `sudo docker rollout preview` (still zero-downtime) or the blunt
  `docker compose pull preview && docker compose up -d preview` (recreates in place).
- **Don't want any of it:** comment out both `rollout` and `watchtower` and update
  by hand with `docker compose pull && docker compose up -d`.

### Even simpler (no Caddy/TLS — quick test)

```bash
docker run -d --restart always -p 8080:8080 \
  -e SERVE_TOKEN="$(openssl rand -hex 24)" \
  ghcr.io/yschimke/compose-preview-host:latest
```

(Token rides in the clear over HTTP — throwaway only.)

## Files

| File | Purpose |
|------|---------|
| `Dockerfile` | Downloads the released CLI and carries the published Maven modules + live-render daemons. |
| `entrypoint.sh` | Maps `$PORT`/`$SERVE_TOKEN` onto serve flags; generous `--timeout`. |
| `docker-compose.yml` + `Caddyfile` | Pull the image + Caddy auto-HTTPS + zero-downtime (`rollout`) / Watchtower auto-updates + the `hook` instant-roll webhook. |
| `rollout.sh` | Poll loop / one-shot that pulls `preview` and rolls it via docker-rollout. |
| `deploy-hook.sh` | Token-gated `POST /__hooks/rollout` webhook (the `hook` service) that runs `rollout.sh` on demand — instant roll on publish. |
| `docker-rollout` | Vendored [docker-rollout](https://github.com/wowu/docker-rollout) CLI plugin (adds `docker rollout`). |
| `setup.sh` | Install Docker + the docker-rollout plugin, write `.env`, pull + start. |
| `env-migrations.sh` + `test-env-migrations.sh` | One-off `.env` rewrites `setup.sh` applies to an already-deployed box (currently: drop the legacy three-app `SERVE_CATALOGS` pin so the baked catalog default applies), and their tests. |

## Notes / caveats

- The default runtime is module-less: it serves fetched catalogs and launches
  their trusted live bundles without running Gradle.
- Live bundles resolve coordinate dependencies from the baked Maven tree first,
  then the configured remote repositories. `SERVE_TIMEOUT` (default 1800s)
  guards slow first renders.
