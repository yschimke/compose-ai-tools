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

# Install the vendored docker-rollout CLI plugin so `sudo docker rollout preview`
# works from the host shell for a manual zero-downtime update. The `rollout`
# service does this automatically on a poll; this is just for hands-on ops.
echo "==> Installing the docker-rollout CLI plugin"
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo install -m 0755 ./docker-rollout /usr/local/lib/docker/cli-plugins/docker-rollout

echo "==> Pulling the prebuilt images and starting the stack"
# Unauthenticated pull: both GHCR packages (compose-preview-host AND
# compose-preview-caddy, which carries the baked Caddyfile) must be PUBLIC, or this
# box needs registry creds. A private package fails here before Caddy starts — see
# README "First publish is private".
sudo docker compose pull
sudo docker compose up -d

TOKEN="$(grep '^SERVE_TOKEN=' .env | cut -d= -f2-)"
# Match the compose default (SERVE_PUBLIC defaults to 1) so we print the URL this
# box actually serves: open in public mode, the ?token= gate only when SERVE_PUBLIC=0.
SERVE_PUBLIC="$( (grep '^SERVE_PUBLIC=' .env || true) | cut -d= -f2- )"
SERVE_PUBLIC="${SERVE_PUBLIC:-1}"
echo
echo "==> Up. Once DNS for ${DOMAIN} resolves here and Caddy has a cert:"
if [[ "${SERVE_PUBLIC}" == "1" ]]; then
  echo "    https://${DOMAIN}/        (public mode — open, no token needed)"
  echo "    To lock it down: set SERVE_PUBLIC=0 in .env, re-run, and use the ?token= URL it prints."
else
  echo "    https://${DOMAIN}/?token=${TOKEN}   (token-gated — SERVE_PUBLIC=0)"
fi
echo "    Logs: sudo docker compose logs -f preview"
