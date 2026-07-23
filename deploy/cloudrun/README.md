# Hosting the Compose preview server on Google Cloud Run

This directory deploys `compose-preview serve` as a public, token-gated HTTP
service on [Cloud Run](https://cloud.google.com/run). It renders the `@Preview`
functions of one Compose **Desktop** module (`:samples:cmp` by default) on demand
and serves them as PNGs with display overrides.

- **Render target:** Desktop / Compose Multiplatform only (Skiko software
  rendering — no Android SDK, no GPU, no X server).
- **Bundle uploads:** disabled. The server renders only the module baked into the
  image; it does **not** accept or execute uploaded code.
- **Auth:** a shared token (Secret Manager). A bad/missing token returns `404`.

## Files

| File | Purpose |
|------|---------|
| `Dockerfile` | Two-stage build: builds the CLI dist and warms the render path, then a runtime image with the warmed Gradle cache + fonts. |
| `entrypoint.sh` | Maps Cloud Run's `$PORT` / `$SERVE_TOKEN` onto `serve` flags; fails closed if no token. |
| `service.yaml` | Knative service manifest (2 vCPU / 4Gi, scale-to-zero, 1h timeout). |
| `cloudbuild.yaml` | Cloud Build: build → push → deploy. |
| `deploy.sh` | One-shot: enables APIs, creates the Artifact Registry repo + token secret, runs the build, prints the URL + link. |
| `Dockerfile.dockerignore` | Trims the build context (BuildKit honors this co-located ignore). |

## Quick start

From the **repo root**, with `gcloud` authenticated and a billing-enabled project:

```bash
PROJECT_ID=your-project REGION=us-central1 deploy/cloudrun/deploy.sh
```

That prints a URL and a ready-to-open link:

```
==> Deployed: https://compose-preview-xxxx.a.run.app
    Open the preview index:   https://compose-preview-xxxx.a.run.app/?token=<TOKEN>
```

Endpoints (all token-gated except `/healthz`), token via `?token=` or the
`X-Compose-Preview-Token` header:

- `GET /?token=…` — landing page / preview index
- `GET /p/{id}?token=…` — viewer page for one preview
- `GET /render/{id}.png?token=…` — PNG bytes (supports overrides, e.g. `&fontScale=1.3`)
- `GET /api/previews?token=…` — preview list as JSON
- `GET /healthz` — liveness (unauthenticated; used by the startup probe)
- `GET /readyz` — readiness: green only once a preview actually renders (unauthenticated; the
  `deploy/image` docker-rollout gate uses this instead of `/healthz`)

## Manual deploy (without deploy.sh)

```bash
# 1. Token secret
printf '%s' "$(openssl rand -hex 24)" | \
  gcloud secrets create compose-preview-token --replication-policy=automatic --data-file=-

# 2. Build + push + deploy
gcloud builds submit --config deploy/cloudrun/cloudbuild.yaml \
  --substitutions=_REGION=us-central1,_REPO=compose-preview

# …or apply the Knative manifest after building the image yourself:
sed "s#IMAGE#us-central1-docker.pkg.dev/$PROJECT_ID/compose-preview/compose-preview:latest#" \
  deploy/cloudrun/service.yaml | gcloud run services replace - --region us-central1
```

## Cost and sizing

The instance is **2 vCPU / 4 GiB** — comfortable for one warm Desktop render
daemon (≈1–2 GiB Gradle + render JVM, plus headroom). Cost is bounded two ways:

- **`min-instances=0`** — scales to zero when idle, so you pay nothing between
  sessions. The trade-off is **cold starts**: the first request after idle runs an
  incremental (warmed) Gradle build + daemon launch — seconds to a couple of
  minutes, not the multi-minute cold build the warmed image avoids.
- **`max-instances=1`** — a traffic spike or abuse can't fan out a surprise bill.

With request-based billing (the default here), Cloud Run's monthly free tier
(180k vCPU-s + 360k GiB-s + 2M requests) covers roughly **25 hours of active
2 vCPU / 4 GiB compute** — plenty for a low-traffic public demo that's otherwise
scaled to zero.

### Free-with-limits knobs

- **`SERVE_IDLE_EXIT`** (default `900`) — the server exits after this many idle
  seconds, letting the instance be reclaimed. Set `0` to disable.
- **`timeoutSeconds: 3600`** — hard ceiling on any one request / WebSocket session
  (Cloud Run's max). Lower it to cap session length more aggressively.
- **`containerConcurrency: 4`** — simultaneous renders per instance.

### If you want snappy (no cold starts)

Set `min-instances=1` and flip `run.googleapis.com/cpu-throttling` to `"false"`
(instance-based billing — CPU always allocated, so the warm daemon stays
responsive between requests). This leaves the free tier and costs roughly the
price of one always-on 2 vCPU / 4 GiB instance.

## Security notes

- **Untrusted code:** disabled here. Public bundle uploads (`--accept-bundles`)
  would mean executing arbitrary uploaded JVM code — do **not** enable that on a
  shared endpoint without per-session sandbox isolation. Cloud Run runs containers
  under gVisor, but the safe default is to render only the baked-in module.
- **The token is the only gate.** It rides in the URL (`?token=`), so treat render
  links like secrets. For stronger auth, drop `--allow-unauthenticated` and put
  Cloud Run IAM / IAP in front instead.
- **TLS** is terminated by Cloud Run automatically.

## Changing what's served

Set `SERVE_MODULE` (build arg / env / `service.yaml`) to any Desktop
(Compose Multiplatform / JVM) module Gradle path in this repo, e.g.
`:samples:cmp-shared`. To host a *different* repo, change the `COPY . /app` source
and point `SERVE_MODULE` at that project's module.

## A cheaper, fully-static alternative

If you only need to publish a fixed set of renders (no live overrides /
re-rendering), skip the live server: render once at build time with
`compose-preview serve --module … --export <bundle>` and serve the resulting
portable bundle (HTML + PNGs) from any static host or a tiny `nginx` image. That
removes the JDK/Gradle runtime entirely — smallest image, instant cold start,
zero code execution — at the cost of interactivity.
