#!/usr/bin/env bash
# Self-test for site-domains.sh + caddy-entrypoint.sh — the edge half of a top-level site.
#
# What's worth pinning here is the pair of failures that are SILENT in production. A hostname the
# scanner misses is a site configured in the app and unreachable at the edge: Caddy never matches
# the name, so it never asks for a certificate, and the only symptom is a TLS error from a browser
# nobody is pointing at it yet. A hostname the scanner *invents* — prose from the neighbouring
# `_sites_comment` read as config, say — is worse: Caddy blocks on an ACME challenge for a name
# that doesn't resolve, and the whole site-address line can fail to come up with it.
#
# Both scripts are POSIX sh + awk because they run inside the stock caddy container, so this drives
# them with `sh`, not bash. Run by ci.yml.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
scan="${here}/site-domains.sh"
entrypoint="${here}/caddy-entrypoint.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

pass=0
fail=0
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $1" >&2
    echo "  expected: $2" >&2
    echo "  actual:   $3" >&2
  fi
}

scan_of() { # scan_of <json> -> hostnames, space-separated
  local file="${work}/catalogs.json"
  printf '%s' "$1" > "${file}"
  sh "${scan}" "${file}" | tr '\n' ' ' | sed 's/ *$//'
}

# --- the scanner -------------------------------------------------------------------------------

check "no sites key ⇒ nothing" "" \
  "$(scan_of '{"catalogs":[{"system":"m3-catalog","repo":"a/b"}]}')"

check "empty sites array ⇒ nothing" "" "$(scan_of '{"sites":[]}')"

check "one site" "m3.preview.coo.ee" \
  "$(scan_of '{"sites":[{"host":"m3.preview.coo.ee","system":"m3-catalog"}]}')"

check "several sites, in file order" "m3.preview.coo.ee wear.preview.coo.ee" \
  "$(scan_of '{"sites":[{"host":"m3.preview.coo.ee","system":"m3"},{"host":"wear.preview.coo.ee","system":"w"}]}')"

# Pretty-printed across lines is the form the file is actually committed in — a line-by-line scan
# would find the key and lose the array.
check "pretty-printed across lines" "m3.preview.coo.ee wear.preview.coo.ee" "$(scan_of '{
  "catalogs": [
    { "system": "m3-catalog", "repo": "yschimke/m3-catalog" }
  ],
  "sites": [
    {
      "host": "m3.preview.coo.ee",
      "system": "m3-catalog"
    },
    {
      "host": "wear.preview.coo.ee",
      "system": "wear-m3-catalog"
    }
  ]
}')"

# Key order inside a site object is not guaranteed by anything — kotlinx.serialization writes
# `host` first today, and a hand-edit needn't.
check "system before host" "wear.preview.coo.ee" \
  "$(scan_of '{"sites":[{"system":"wear-m3-catalog","host":"wear.preview.coo.ee"}]}')"

# The real file carries a long `_sites_comment` next to the array. Its prose mentions sites, hosts
# and hostnames throughout — none of which is config.
check "a _sites_comment is not the sites array" "m3.preview.coo.ee" "$(scan_of '{
  "_sites_comment": "TOP-LEVEL SITES — a catalog served on a hostname of its own. DNS for the name must point at the box.",
  "sites": [{ "host": "m3.preview.coo.ee", "system": "m3-catalog" }]
}')"

# A `host` key elsewhere in the document is not a site: the scan is bounded by the array.
check "a host key outside the array is ignored" "m3.preview.coo.ee" "$(scan_of '{
  "sites": [{ "host": "m3.preview.coo.ee", "system": "m3-catalog" }],
  "other": { "host": "not-a-site.example.com" }
}')"

check "hostnames are lowercased, as the Host header is compared" "wear.preview.coo.ee" \
  "$(scan_of '{"sites":[{"host":"WEAR.Preview.Coo.EE","system":"w"}]}')"

check "an absent file is not an error" "" "$(sh "${scan}" "${work}/nope.json"; echo -n)"

# --- the entrypoint ----------------------------------------------------------------------------
# `caddy` is stubbed so the resolved value can be observed without running the real thing. What is
# under test is what SITE_DOMAINS ends up as, and that the image's CMD is passed straight through.

mkdir -p "${work}/bin"
cat > "${work}/bin/caddy" <<'STUB'
#!/bin/sh
echo "SITE_DOMAINS=[${SITE_DOMAINS}]"
echo "ARGS=[$*]"
STUB
chmod +x "${work}/bin/caddy"

entry_of() { # entry_of <json> [SITE_DOMAINS] -> the stub's report
  printf '%s' "$1" > "${work}/catalogs.json"
  PATH="${work}/bin:${PATH}" \
    CATALOGS_FILE="${work}/catalogs.json" \
    SITE_DOMAINS="${2:-}" \
    sh "${entrypoint}" run --config /etc/caddy/Caddyfile --adapter caddyfile 2>/dev/null
}

check "derives the hostnames from the config" "SITE_DOMAINS=[m3.preview.coo.ee wear.preview.coo.ee]" \
  "$(entry_of '{"sites":[{"host":"m3.preview.coo.ee","system":"m3"},{"host":"wear.preview.coo.ee","system":"w"}]}' | head -1)"

# Empty must stay EMPTY, not whitespace: `{$SITE_DOMAINS:}` expanding to a blank token puts a stray
# address on the site line, and Caddy fails to parse its config — the box loses TLS entirely.
check "no sites ⇒ empty, not whitespace" "SITE_DOMAINS=[]" \
  "$(entry_of '{"catalogs":[]}' | head -1)"

check "an explicit SITE_DOMAINS still works on its own" "SITE_DOMAINS=[legacy.example.com]" \
  "$(entry_of '{"catalogs":[]}' 'legacy.example.com' | head -1)"

check "explicit and derived are unioned" "SITE_DOMAINS=[legacy.example.com m3.preview.coo.ee]" \
  "$(entry_of '{"sites":[{"host":"m3.preview.coo.ee","system":"m3"}]}' 'legacy.example.com' | head -1)"

check "a name in both is listed once" "SITE_DOMAINS=[m3.preview.coo.ee]" \
  "$(entry_of '{"sites":[{"host":"m3.preview.coo.ee","system":"m3"}]}' 'm3.preview.coo.ee' | head -1)"

check "comma-separated SITE_DOMAINS is accepted, as documented" \
  "SITE_DOMAINS=[a.example.com b.example.com]" \
  "$(entry_of '{"catalogs":[]}' 'a.example.com,b.example.com' | head -1)"

# The image's CMD has to survive: the entrypoint replaces `caddy` as PID 1's program, so dropping
# the arguments would start Caddy with no config at all.
check "the caddy command line is passed through" \
  "ARGS=[run --config /etc/caddy/Caddyfile --adapter caddyfile]" \
  "$(entry_of '{"catalogs":[]}' | sed -n 2p)"

# An entrypoint that is handed nothing must still start Caddy. `exec caddy` with no arguments
# prints usage and exits; a caddy container that exits releases ports 80/443, so EVERY hostname on
# the box goes dark, not just the sites. This is the shape of the outage the missing CMD caused.
check "no arguments still starts Caddy with the standard config" \
  "ARGS=[run --config /etc/caddy/Caddyfile --adapter caddyfile]" \
  "$(printf '%s' '{"catalogs":[]}' > "${work}/catalogs.json"
     PATH="${work}/bin:${PATH}" CATALOGS_FILE="${work}/catalogs.json" SITE_DOMAINS="" \
       sh "${entrypoint}" 2>/dev/null | sed -n 2p)"

# …and the image must not rely on that fallback: Docker RESETS an inherited CMD to empty when a
# Dockerfile declares ENTRYPOINT, so caddy:2's own command line does not survive our ENTRYPOINT
# line. Shipping the pair without a CMD is what took the box offline; this pins that they stay
# together.
dockerfile="${here}/caddy.Dockerfile"
if grep -q '^ENTRYPOINT' "${dockerfile}"; then
  if grep -q '^CMD' "${dockerfile}"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: caddy.Dockerfile declares ENTRYPOINT without a CMD — Docker resets the inherited one to empty, so Caddy would start with no arguments and exit." >&2
  fi
fi

# --- the real deployment config ----------------------------------------------------------------
# The committed file is the one input that must never surprise this. Its hostnames are what the box
# routes, so a scan that disagrees with the file is a site that silently doesn't come up.
deploy_config="${here}/../preview.coo.ee/catalogs.json"
if [ -f "${deploy_config}" ] && command -v jq > /dev/null 2>&1; then
  expected="$(jq -r '.sites // [] | .[] | .host | ascii_downcase' "${deploy_config}" | tr '\n' ' ' | sed 's/ *$//')"
  check "the deployment's own catalogs.json scans exactly as jq reads it" \
    "${expected}" "$(sh "${scan}" "${deploy_config}" | tr '\n' ' ' | sed 's/ *$//')"
fi

if [ "${fail}" -gt 0 ]; then
  echo "${fail} check(s) failed, ${pass} passed." >&2
  exit 1
fi
echo "OK: ${pass} site-domain check(s) passed."
