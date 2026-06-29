#!/usr/bin/env bash
# One-shot setup for hosting compose-preview on a generic VPS (reference target:
# a Hetzner Cloud CAX21 — 4 Ampere/ARM vCPU, 8 GB). Run it ON the server, from
# this directory, e.g.:
#
#   DOMAIN=preview.example.com ./setup.sh
#
# It is idempotent: installs Docker (if missing), writes .env with a generated
# token (kept if it already exists), then builds and starts the stack.
#
# Unlike the Oracle path, this does NOT touch a host firewall: a stock Hetzner
# (or DigitalOcean / Linode / Vultr) image leaves the OS firewall open. If you
# attached a provider "cloud firewall", allow inbound TCP 22/80/443 there.
set -euo pipefail
cd "$(dirname "$0")"

DOMAIN="${DOMAIN:-}"
if [[ -z "${DOMAIN}" ]]; then
  echo "Set DOMAIN to the hostname whose DNS A record points at this VM, e.g.:" >&2
  echo "  DOMAIN=preview.example.com ./setup.sh" >&2
  echo "(Caddy needs a real domain to issue a Let's Encrypt cert. For a quick" >&2
  echo " HTTP-only test without TLS, see the README.)" >&2
  exit 64
fi

echo "==> Host: $(uname -m), $(nproc) CPU(s)"

# The image build runs a full Gradle build, which is memory-hungry. Warn (don't
# block) under ~7 GB so 4 GB boxes can add swap before the OOM killer strikes.
mem_mb="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
if [[ "${mem_mb}" -gt 0 && "${mem_mb}" -lt 7000 ]]; then
  echo "WARN: only ${mem_mb} MB RAM — the in-image Gradle build may OOM under ~7 GB." >&2
  echo "      On an 8 GB box (e.g. Hetzner CAX21) you're fine. On 4 GB, add swap first:" >&2
  echo "        sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile \\" >&2
  echo "          && sudo mkswap /swapfile && sudo swapon /swapfile" >&2
  echo "      and lower 'mem_limit' to ~3g in docker-compose.yml." >&2
fi

# --- Docker -----------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker (get.docker.com handles arm64 + amd64, Ubuntu/Debian)"
  # The installer does package-manager writes, so it needs root. On Hetzner you
  # are usually root already; `sudo` is a no-op then and required on non-root VMs.
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "ERROR: 'docker compose' plugin not available after install." >&2
  exit 1
fi

# --- .env: token + domain ----------------------------------------------------
if [[ ! -f .env ]]; then
  TOKEN="$(openssl rand -hex 24)"
  printf 'DOMAIN=%s\nSERVE_TOKEN=%s\n' "${DOMAIN}" "${TOKEN}" > .env
  chmod 600 .env
  echo "==> Wrote .env with a freshly generated token"
else
  if grep -q '^DOMAIN=' .env; then
    sed -i "s#^DOMAIN=.*#DOMAIN=${DOMAIN}#" .env
  else
    printf 'DOMAIN=%s\n' "${DOMAIN}" >> .env
  fi
  echo "==> Reusing existing .env (token preserved)"
fi

# --- Build + start -----------------------------------------------------------
echo "==> Building image and starting the stack (first build runs a full Gradle build — minutes)"
sudo docker compose up -d --build

TOKEN="$(grep '^SERVE_TOKEN=' .env | cut -d= -f2-)"
echo
echo "==> Up. Once DNS for ${DOMAIN} points at this VM and Caddy has a cert:"
echo "    https://${DOMAIN}/        (public mode — open; SERVE_PUBLIC defaults to 1)"
echo "    For a token-gated box, set SERVE_PUBLIC=0 in .env; the gate is then:"
echo "    https://${DOMAIN}/?token=${TOKEN}"
echo "    Logs: sudo docker compose logs -f preview"
