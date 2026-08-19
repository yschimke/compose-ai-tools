#!/bin/sh
# Print the TOP-LEVEL SITE hostnames declared by a catalogs.json, one per line.
#
# Why this exists: Caddy has to be told a hostname before it will match it or ask Let's Encrypt for
# a certificate for it, and that list used to be `SITE_DOMAINS` in the box's untracked `.env` —
# hand-maintained, invisible to review, and drifting from `catalogs.json`'s own `sites` the moment
# anyone forgot one of the two. The file already says which hostnames this deployment serves, so
# the proxy reads it from there and the env var stops being a second source of truth.
#
# Usage: site-domains.sh [<catalogs.json>]   (default /srv/preview-config/catalogs.json)
#
# An absent or site-less file prints nothing and exits 0 — a box with no sites is the common case,
# not an error, and it must leave Caddy's site-address line exactly as it was.
#
# POSIX sh + awk on purpose: this runs inside the caddy container, which is a stock Alpine image
# with no jq and nothing this can install. The scan is deliberately narrow rather than a real JSON
# parser — it takes the `"sites"` array (matched as a quoted key followed by `:` and `[`, so prose
# in a neighbouring `_sites_comment` can't be mistaken for it), stops at the first `]`, and reads
# every `"host"` value inside. Site objects are flat `{host, system}` pairs and a hostname can't
# contain `]`, so there is nothing in that range for the short-cut to trip over.
set -eu

FILE="${1:-/srv/preview-config/catalogs.json}"
[ -f "${FILE}" ] || exit 0

awk '
  # Slurp the document: the array we want spans lines in a pretty-printed file and none in a
  # minified one, so scanning line by line would only work for one of the two.
  { doc = doc $0 "\n" }
  END {
    rest = doc
    # The quoted key, then optional whitespace, a colon, more whitespace, and the opening bracket.
    # match() is POSIX ERE here, which is all busybox awk offers — hence the explicit [ \t\r\n]*.
    if (match(rest, /"sites"[ \t\r\n]*:[ \t\r\n]*\[/) == 0) exit 0
    rest = substr(rest, RSTART + RLENGTH)
    end = index(rest, "]")
    if (end == 0) exit 0
    sites = substr(rest, 1, end - 1)
    while (match(sites, /"host"[ \t\r\n]*:[ \t\r\n]*"[^"]*"/) > 0) {
      pair = substr(sites, RSTART, RLENGTH)
      sites = substr(sites, RSTART + RLENGTH)
      # The value is what follows the LAST quote-pair opener in the match.
      sub(/^"host"[ \t\r\n]*:[ \t\r\n]*"/, "", pair)
      sub(/"$/, "", pair)
      if (pair != "") print tolower(pair)
    }
  }
' "${FILE}"
