# Hosting the Compose preview server on a VPS (Hetzner reference)

This deploys `compose-preview serve` on a plain VPS — the reliable, predictable,
fixed-price path. The reference target is a **Hetzner Cloud CAX21** (4 Ampere/ARM
vCPU, 8 GB, ~€7/mo), but the same files work on any Ubuntu/Debian VPS
(DigitalOcean, Linode, Vultr, OVH…).

It reuses the desktop-only render image from the Cloud Run deployment
([`deploy/cloudrun/Dockerfile`](../cloudrun/Dockerfile)) behind Docker Compose +
[Caddy](https://caddyserver.com/) for automatic HTTPS — the same stack as
[`deploy/oracle/`](../oracle/), minus the Oracle-specific host-firewall step.

- **Render target:** Compose **Desktop** only (Skiko software rendering).
- **Bundle uploads:** disabled — renders only the baked-in module, no untrusted
  code execution.
- **Auth:** a shared token (a bad/missing token returns `404`); TLS via Caddy.
- **Always warm:** always-on box, so renders are fast (no cold start). A fixed
  monthly bill is its own usage cap — no metering surprises.

## Why Hetzner CAX21 (8 GB), not 4 GB

The image build runs a full Gradle build *inside* Docker, which is
memory-hungry. **8 GB clears it with no fuss.** On a 4 GB VPS you must add swap
first (see below) and lower `mem_limit` — doable, but 8 GB is the no-surprises
choice. ARM is a first-class target here (same as Oracle A1); the base image,
JDK, and Skiko all ship arm64 builds.

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | `preview` (built from the Cloud Run Dockerfile) + `caddy` (TLS reverse proxy). |
| `Caddyfile` | Terminates TLS for `$DOMAIN`, proxies to the internal server. |
| `setup.sh` | Run on the VM: installs Docker, writes `.env` with a generated token, builds + starts. RAM-checks and prints swap guidance under ~7 GB. |
| `.gitignore` | Keeps the generated `.env` (token) out of git. |

## One-time: create the server

1. **Hetzner Cloud Console → Add Server.** Location: pick one near you. Image:
   **Ubuntu 24.04**. Type: **Arm64 (Ampere) → `CAX21`** (4 vCPU / 8 GB). Add your
   SSH key. Create, and note the **public IPv4**.
2. **Firewall (optional).** A stock Hetzner image has the OS firewall open, so
   nothing to do by default. If you attach a **Hetzner Cloud Firewall**, add
   inbound rules for **TCP 22, 80, 443**. *(This is the one place Hetzner is
   simpler than Oracle — no default-deny host firewall to unblock.)*
3. **Point DNS at the server.** Create an `A` record for your hostname (e.g.
   `preview.example.com`) → the public IP. Let it resolve before setup — Caddy
   needs it to issue a certificate. Check with `dig +short preview.example.com`.

## Deploy

SSH in, get the repo, and run setup from this directory:

```bash
ssh root@<SERVER_IP>
git clone https://github.com/yschimke/compose-ai-tools.git
cd compose-ai-tools/deploy/vps
DOMAIN=preview.example.com ./setup.sh
```

The first build runs a full Gradle build inside the image (several minutes);
subsequent starts reuse warm layers. When it finishes it prints:

```
https://preview.example.com/?token=<TOKEN>
```

Endpoints (token via `?token=` or `X-Compose-Preview-Token`):
`GET /` index · `GET /p/{id}` viewer · `GET /render/{id}.png` PNG · `GET /healthz`
liveness · `GET /readyz` readiness (both unauthenticated).

## Operating it

```bash
sudo docker compose logs -f preview      # server logs
sudo docker compose restart preview      # restart the renderer
sudo docker compose up -d --build        # rebuild + recreate after a git pull
sudo docker compose pull caddy           # refresh just the proxy image (optional)
sudo docker compose down                 # stop everything
cat .env                                  # recover the token
```

`restart: always` plus Docker's systemd integration brings the stack back after
a reboot.

## Cost and control

A CAX21 is a flat **~€7/mo** (confirm current pricing — Hetzner adjusts it).
Billing is hourly, so you can spin one up, test for a day for cents, and destroy
it before committing. Usage control is inherent: a fixed-price box has no meter
to surprise you, and the **token gate** plus the app's session/concurrency caps
keep load bounded. Put **Cloudflare (free)** in front for TLS edge caching, basic
DDoS protection, and to hide the origin IP. Enable Hetzner **snapshots/backups**
for quick recovery.

## Security notes

- **The token rides in the URL** (`?token=`), so treat render links as secrets.
  Caddy provides TLS so they aren't sent in the clear.
- **No untrusted code:** bundle uploads stay disabled. Don't enable
  `--accept-bundles` on a public box without per-session sandbox isolation.
- `.env` holds the token (mode `600`, git-ignored). Rotate by editing it and
  `sudo docker compose up -d`.

## 4 GB VPS (add swap)

If you must use a 4 GB box, give the build headroom before running `setup.sh`:

```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile \
  && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # persist
```

Then lower `mem_limit` to `~3g` in `docker-compose.yml`. `setup.sh` warns and
prints these commands automatically when it sees < ~7 GB.

## HTTP-only (quick test, no domain/TLS)

Skips Caddy/TLS — the token travels **in the clear**, so throwaway only:

```bash
SERVE_TOKEN=$(openssl rand -hex 24)
sudo docker run -d --restart always -p 8080:8080 \
  -e SERVE_TOKEN="$SERVE_TOKEN" -e SERVE_IDLE_EXIT=0 \
  $(sudo docker build -q -f ../cloudrun/Dockerfile ../..)
echo "http://<SERVER_IP>:8080/?token=$SERVE_TOKEN"
```

Open TCP **8080** in the cloud firewall if you attached one.
