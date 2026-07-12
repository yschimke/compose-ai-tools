# syntax=docker/dockerfile:1.25

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
FROM caddy:2
COPY Caddyfile /etc/caddy/Caddyfile
