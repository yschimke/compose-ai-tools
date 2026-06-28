# Prebuilt preview-host image (instant deploy)

A **prebuilt** Docker image that runs `compose-preview serve` with **no build on
the host**. It's the fast alternative to [`deploy/cloudrun/Dockerfile`](../cloudrun/Dockerfile),
which compiles the whole tool from source (~8 min) — instead this installs the
**released** `compose-preview` CLI + a tiny self-contained Compose Desktop project
and renders it with the **published** plugin from Maven Central. Built once in CI
and pushed to GHCR, so hosts just pull it.

- **Image:** `ghcr.io/yschimke/compose-preview-host:<version>` (and `:latest`)
- **Render target:** Compose **Desktop** only (Skiko software GL).
- **Bundle uploads:** disabled. **Auth:** shared token. **TLS:** via Caddy.

## How it's fast

The from-source image compiles `build-logic` + the Gradle plugin + the CLI + ~50
data modules. This one skips all of that:

| | From source (`deploy/cloudrun`) | Prebuilt (`deploy/image`) |
|---|---|---|
| Tool | compiled in the image (~8 min) | **released CLI tarball, downloaded** |
| Plugin | built locally | **published, auto-injected from Maven Central** |
| Project served | the whole repo's `:samples:cmp` | a tiny standalone `sample-project/` |
| Host build | yes, every deploy | **none — `docker pull`** |

The image's warm render (in CI) bakes the Gradle cache + module build outputs, so
the first runtime render is incremental.

## Publishing the image (one-time / per release)

The [`preview-host-image.yml`](../../.github/workflows/preview-host-image.yml)
workflow builds and pushes to GHCR. Trigger it either way:

- **On a CLI release** (`v*` tag) — automatic; bundles that version + tags `latest`.
- **Manually** — Actions → *Publish preview-host image* → run with a `cli_version`
  (e.g. `0.16.1`).

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

Pin a version with `IMAGE_TAG=0.16.1` in `.env` (defaults to `:latest`).

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
| `Dockerfile` | Downloads the released CLI, warm-renders `sample-project/`. |
| `sample-project/` | Self-contained Compose Desktop module (foundation-only previews) + Gradle wrapper. Applies the published plugin via auto-inject. |
| `entrypoint.sh` | Maps `$PORT`/`$SERVE_TOKEN` onto serve flags; generous `--timeout`. |
| `docker-compose.yml` + `Caddyfile` | Pull the image + Caddy auto-HTTPS. |
| `setup.sh` | Install Docker, write `.env`, pull + start. |

## Serving a different project

Replace `sample-project/` with your own Compose project (any module the released
CLI can render — it auto-injects the plugin), keep a Gradle wrapper, and republish
the image. The CLI needs only the project + network to Maven; no part of this repo.

## Notes / caveats

- The runtime server still uses Gradle + Maven to render, but the baked warm cache
  makes it incremental. `SERVE_TIMEOUT` (default 1800s) guards slow first renders.
- The standalone project pins Kotlin `2.3.21` / Compose Multiplatform `1.10.3` to
  match the published plugin's tested stack — bump in lockstep with the CLI version.
