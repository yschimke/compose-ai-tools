# syntax=docker/dockerfile:1.26

# Bakes deploy/image/Caddyfile into a caddy:2 image so a Caddyfile change ships to
# the running host over the SAME Watchtower auto-update path as the preview server.
#
# Why bake it: Watchtower watches image *digests*, not the bind-mounted config file,
# so a plain `caddy:2` + `./Caddyfile` volume never auto-updates when the Caddyfile
# changes — an edit has to be copied onto the box and Caddy reloaded by hand. With
# the config in a watched image, a Caddyfile change → preview-caddy-image.yml pushes
# a new `:latest` → Watchtower pulls + recreates caddy → new config live. Certs
# survive (they live in the caddy_data volume), so a recreate doesn't re-provision.
#
# `{$DOMAIN}` stays an env placeholder — Caddy substitutes it from the container's
# DOMAIN at runtime, so the same image serves any host. Published to
# ghcr.io/<owner>/compose-preview-caddy by .github/workflows/preview-caddy-image.yml.
#
# The image also carries the entrypoint that resolves `{$SITE_DOMAINS}` from the deployment's
# catalogs.json (mounted read-only at /srv/preview-config) instead of the box's untracked `.env`.
# Same reason as the Caddyfile itself: a top-level site's hostname has to reach the edge to be
# reachable at all, and a hand-maintained env var beside a committed `sites` list is two sources of
# truth that silently disagree.
FROM caddy:2
COPY Caddyfile /etc/caddy/Caddyfile
COPY site-domains.sh /usr/local/bin/site-domains.sh
COPY caddy-entrypoint.sh /usr/local/bin/caddy-entrypoint.sh
RUN chmod +x /usr/local/bin/site-domains.sh /usr/local/bin/caddy-entrypoint.sh
# ENTRYPOINT + CMD, and the CMD is NOT optional: Docker **resets an inherited CMD to empty** when a
# Dockerfile declares ENTRYPOINT, so `caddy:2`'s own `run --config … --adapter caddyfile` does not
# survive the line below. Without this, the entrypoint's `exec caddy "$@"` runs `caddy` with no
# arguments — which prints usage and exits, so the container dies and takes ports 80/443 with it.
# That is the whole box offline, not a degraded site: it is what happened when this image first
# shipped without the CMD. Restated verbatim from caddy:2 so the entrypoint passes it straight
# through and changes what Caddy is *told*, never how it is run.
ENTRYPOINT ["/usr/local/bin/caddy-entrypoint.sh"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
