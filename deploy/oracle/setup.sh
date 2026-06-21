#!/usr/bin/env bash
# One-shot setup for hosting compose-preview on an Oracle Cloud Always Free
# Ampere A1 (aarch64) VM. Run it ON the instance, from this directory, e.g.:
#
#   DOMAIN=preview.example.com ./setup.sh
#
# It is idempotent: installs Docker (if missing), opens the host firewall for
# 80/443 (Oracle's images block these by default — the #1 "why can't I reach my
# server" gotcha), writes .env with a generated token (kept if it already
# exists), then builds and starts the stack.
#
# Still required in the OCI Console (this script can't reach it): a VCN security
# list / NSG ingress rule allowing 0.0.0.0/0 -> TCP 80 and 443. See README.md.
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

ARCH="$(uname -m)"
if [[ "${ARCH}" != "aarch64" && "${ARCH}" != "arm64" ]]; then
  echo "WARN: this VM is ${ARCH}, not aarch64 — fine, but the Always Free A1 shape is arm64." >&2
fi

# --- Docker -----------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker (get.docker.com handles arm64 + Ubuntu/Oracle Linux)"
  curl -fsSL https://get.docker.com | sh
  sudo systemctl enable --now docker
fi
# Compose v2 plugin check.
if ! docker compose version >/dev/null 2>&1; then
  echo "ERROR: 'docker compose' plugin not available after install." >&2
  exit 1
fi

# --- Host firewall: open 80/443 ---------------------------------------------
# Oracle Linux/Ubuntu OCI images ship a default-deny host firewall. Open the
# web ports at the OS level (the VCN security-list rule is separate — console).
if command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --state >/dev/null 2>&1; then
  echo "==> Opening 80/443 via firewalld"
  sudo firewall-cmd --permanent --add-port=80/tcp --add-port=443/tcp
  sudo firewall-cmd --reload
else
  echo "==> Opening 80/443 via iptables"
  for port in 80 443; do
    # Insert ACCEPT at the top so it precedes the images' trailing REJECT rule;
    # -C first so re-runs don't pile up duplicates.
    sudo iptables -C INPUT -p tcp --dport "${port}" -j ACCEPT 2>/dev/null \
      || sudo iptables -I INPUT -p tcp --dport "${port}" -j ACCEPT
  done
  # Persist across reboots.
  if command -v netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save
  elif [[ -d /etc/iptables ]]; then
    sudo sh -c 'iptables-save > /etc/iptables/rules.v4'
  else
    echo "WARN: could not persist iptables rules — they may not survive a reboot." >&2
  fi
fi

# --- .env: token + domain ----------------------------------------------------
if [[ ! -f .env ]]; then
  TOKEN="$(openssl rand -hex 24)"
  printf 'DOMAIN=%s\nSERVE_TOKEN=%s\n' "${DOMAIN}" "${TOKEN}" > .env
  chmod 600 .env
  echo "==> Wrote .env with a freshly generated token"
else
  # Keep the existing token; refresh DOMAIN to the requested value.
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
echo "    https://${DOMAIN}/?token=${TOKEN}"
echo "    (Keep the token secret — it is the only gate on this public endpoint.)"
echo "    Logs: sudo docker compose logs -f preview"
