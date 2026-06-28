#!/usr/bin/env bash
# Instant deploy of the prebuilt preview-host image on any Ubuntu/Debian host.
# No image build — just pulls ghcr.io/yschimke/compose-preview-host. Run from this
# directory:
#
#   DOMAIN=preview.example.com ./setup.sh
#
# Installs Docker if missing, writes .env (generated token, kept if present),
# then pulls + starts the stack behind Caddy (auto-HTTPS).
set -euo pipefail
cd "$(dirname "$0")"

DOMAIN="${DOMAIN:-}"
if [[ -z "${DOMAIN}" ]]; then
  echo "Set DOMAIN to the hostname whose DNS A record points at this host, e.g.:" >&2
  echo "  DOMAIN=preview.example.com ./setup.sh" >&2
  exit 64
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker"
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
fi

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

echo "==> Pulling the prebuilt image and starting the stack"
sudo docker compose pull
sudo docker compose up -d

TOKEN="$(grep '^SERVE_TOKEN=' .env | cut -d= -f2-)"
echo
echo "==> Up. Once DNS for ${DOMAIN} resolves here and Caddy has a cert:"
echo "    https://${DOMAIN}/?token=${TOKEN}"
echo "    Logs: sudo docker compose logs -f preview"
