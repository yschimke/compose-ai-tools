# Hosting the Compose preview server on Oracle Cloud (Always Free)

This deploys `compose-preview serve` on an **Oracle Cloud Always Free Ampere A1**
VM — genuinely **$0/month** with real always-warm capacity (up to 4 ARM cores +
24 GB RAM in the free tenancy), unlike a scale-to-zero serverless setup.

It reuses the same desktop-only render image as the Cloud Run deployment
([`deploy/cloudrun/Dockerfile`](../cloudrun/Dockerfile)) and wraps it with Docker
Compose + [Caddy](https://caddyserver.com/) for automatic HTTPS.

- **Render target:** Compose **Desktop** only (Skiko software rendering).
- **Bundle uploads:** disabled — renders only the baked-in module, no untrusted
  code execution.
- **Auth:** a shared token (a bad/missing token returns `404`); TLS via Caddy.
- **Always warm:** the box is always on, so the server stays up (no idle exit) —
  renders are fast (no cold start).

> **ARM note:** the A1 shape is `aarch64`. The base image, JDK, and Skiko all
> have arm64 builds, but if you change the served module, sanity-check that its
> dependencies resolve native arm64 artifacts.

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | `preview` (built from the Cloud Run Dockerfile) + `caddy` (TLS reverse proxy). |
| `Caddyfile` | Terminates TLS for `$DOMAIN`, proxies to the internal server. |
| `setup.sh` | Run on the VM: installs Docker, opens the host firewall (80/443), writes `.env` with a generated token, builds + starts. |
| `.gitignore` | Keeps the generated `.env` (token) out of git. |

## One-time: create the instance

1. **Compute → Create instance.** Shape: **`VM.Standard.A1.Flex`** (Ampere),
   e.g. 2 OCPU / 12 GB (or up to 4 OCPU / 24 GB — all within Always Free). Image:
   Ubuntu or Oracle Linux. Add your SSH key.
2. **Open the ports in the virtual network.** VCN → the instance's subnet →
   Security List (or an NSG) → add **Ingress** rules: source `0.0.0.0/0`, IP
   protocol TCP, destination ports **80** and **443**. *This is separate from the
   host firewall `setup.sh` handles — you need both.*
3. **Point DNS at the VM.** Create an `A` record for your hostname (e.g.
   `preview.example.com`) → the instance's public IP. Caddy needs this resolving
   before it can issue a certificate.

## Deploy

SSH in, get the repo, and run setup from this directory:

```bash
git clone https://github.com/yschimke/compose-ai-tools.git
cd compose-ai-tools/deploy/oracle
DOMAIN=preview.example.com ./setup.sh
```

The first build runs a full Gradle build inside the image (several minutes on an
A1); subsequent starts reuse the warmed layers. When it finishes it prints:

```
https://preview.example.com/?token=<TOKEN>
```

Endpoints (token via `?token=` or `X-Compose-Preview-Token`):
`GET /` index · `GET /p/{id}` viewer · `GET /render/{id}.png` PNG · `GET /healthz`
(unauthenticated).

## Operating it

```bash
sudo docker compose logs -f preview      # server logs
sudo docker compose restart preview      # restart the renderer
sudo docker compose up -d --build        # rebuild + recreate after a git pull
sudo docker compose pull caddy           # refresh just the proxy image (optional)
sudo docker compose down                 # stop everything
cat .env                                  # recover the token
```

`restart: always` plus Docker's systemd integration means the stack comes back
after a reboot.

## Cost and limits

The A1 instance and its boot volume sit inside Oracle's Always Free allowances,
so the steady-state cost is **$0**. Because the box is always on, there's no cold
start — the trade-off versus Cloud Run is that you manage a VM instead of a
managed service.

Bound resource use through the app rather than the (free, fixed) hardware:

- **`mem_limit: 6g`** on the `preview` service caps a render storm.
- **`SERVE_IDLE_EXIT`** (set to `0` here to stay warm) — set a positive number of
  seconds in `docker-compose.yml` if you'd rather the server exit when idle and
  let `restart: always` bring it back on the next request.
- For session-length / concurrency caps, run `serve` with the relevant flags (see
  `compose-preview serve --help`) by extending the `preview` service command.

## Security notes

- **The token rides in the URL** (`?token=`), so treat render links as secrets.
  Caddy provides TLS so they aren't sent in the clear.
- **No untrusted code:** bundle uploads stay disabled. Don't enable
  `--accept-bundles` on a public box without per-session sandbox isolation — on a
  single shared VM there is none.
- `.env` holds the token (mode `600`, git-ignored). Rotate by editing it and
  `sudo docker compose up -d`.

## HTTP-only (quick test, no domain/TLS)

If you just want to poke it without a domain, publish the server's port directly
and skip Caddy:

```bash
SERVE_TOKEN=$(openssl rand -hex 24)
sudo docker run -d --restart always -p 8080:8080 \
  -e SERVE_TOKEN="$SERVE_TOKEN" -e SERVE_IDLE_EXIT=0 \
  $(sudo docker build -q -f ../cloudrun/Dockerfile ../..)
echo "http://<VM_PUBLIC_IP>:8080/?token=$SERVE_TOKEN"
```

Open TCP **8080** in both the VCN security list and the host firewall. **The token
travels unencrypted over plain HTTP — use this only for throwaway testing.**
