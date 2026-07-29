#!/usr/bin/env bash
# Populate a compose-preview downloadable-font cache at image BUILD time.
#
# Why this exists
# ---------------
# The Android renderer resolves `Font(DeviceFontFamilyName("roboto-flex"))` — the shape Wear
# Material3's type scale uses — by downloading the matching Google Fonts TTF and seeding it into
# Robolectric's `Typeface.sSystemFontMap` (`PixelSystemFontAliases`). A slug it can't resolve falls
# back to plain Roboto *silently*. In the preview host that made the live daemon's text differ from
# the baked catalog PNG (which renders in CI, where the font cache is warm), so the same preview
# showed two different typefaces depending on which lane you were looking at.
#
# Baking the faces into the image makes the live lane's fonts deterministic: no first-render
# download, no dependency on runtime egress to fonts.googleapis.com, and the same bytes CI used.
#
# Byte-for-byte parity requirement
# --------------------------------
# The files must be exactly what `GoogleFontInterceptor.downloadFromGoogleFonts` would have written,
# or the two tiers still disagree. This script therefore mirrors that function's contract exactly:
#   * filename  — `<slugify(display name)>-<weight>[-italic].ttf` (`GoogleFontKey.fileName`)
#   * stage 1   — `css2?family=<enc>:wght@<weight>&display=swap`
#   * stage 2   — retried with `wght@100..1000` ONLY when stage 1 carried no TTF url (purely
#                 variable families like Roboto Flex reject single-weight queries), then the
#                 declared weight closest to the request wins
#   * User-Agent — a pre-KitKat Android UA, the only kind the CSS2 endpoint answers with
#                 `format('truetype')` rather than WOFF2
# Keep this in lockstep with `renderers/android/.../GoogleFontInterceptor.kt`; the self-test in
# `test-prewarm-fonts.sh` pins the naming and URL shapes.
#
# Usage: prewarm-fonts.sh <target-dir> [family ...]
# Exits non-zero if any requested family fails to resolve — a silently font-less image would
# reintroduce exactly the bug this guards against, and a failed build is the visible failure mode.
set -euo pipefail

TTF_USER_AGENT="Mozilla/5.0 (Linux; U; Android 2.3.3; en-us) AppleWebKit/533.1 (KHTML, like Gecko)"

# Mirrors `GoogleFontKey.slugify`: lowercase, every non-alphanumeric run collapsed to one `-`, no
# leading/trailing `-`.
slugify() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//'
}

# Percent-encode a family name for the `family=` query parameter (spaces → %20).
urlencode_family() {
  printf '%s' "$1" | sed -E 's/ /%20/g'
}

css_url() {
  printf 'https://fonts.googleapis.com/css2?family=%s:%s&display=swap' \
    "$(urlencode_family "$1")" "$2"
}

fetch() {
  curl -fsSL --retry 10 --retry-delay 6 --retry-all-errors \
    --connect-timeout 10 --max-time 120 -H "User-Agent: ${TTF_USER_AGENT}" "$1"
}

# Print the `url(...)` whose `format('truetype')` block declares the weight closest to $2.
# A single block (the variable-font response) is returned as-is.
pick_truetype_url() {
  local css="$1" want="$2"
  printf '%s' "$css" | tr '}' '\n' | awk -v want="$want" '
    /format\((.)truetype\1?\)/ || /format\(.truetype.\)/ {
      weight = 400
      if (match($0, /font-weight:[ \t]*[0-9]+/)) {
        w = substr($0, RSTART, RLENGTH); sub(/[^0-9]+/, "", w); weight = w + 0
      }
      if (match($0, /url\(https:\/\/[^)]+\)/)) {
        u = substr($0, RSTART + 4, RLENGTH - 5)
        d = weight - want; if (d < 0) d = -d
        if (best == "" || d < bestd) { best = u; bestd = d }
      }
    }
    END { if (best != "") print best }
  '
}

main() {
  local dir="${1:?usage: prewarm-fonts.sh <target-dir> [family ...]}"
  shift
  [ "$#" -gt 0 ] || { echo "prewarm-fonts: no families requested" >&2; exit 2; }
  mkdir -p "$dir"

  local weight=400 failed=0 family slug dest css url
  for family in "$@"; do
    slug="$(slugify "$family")"
    dest="${dir}/${slug}-${weight}.ttf"
    if [ -s "$dest" ]; then
      echo "prewarm-fonts: ${family} → ${slug}-${weight}.ttf (already present)"
      continue
    fi

    # Stage 1: exact weight. A *failed request* must not fall through to stage 2 — the two stages
    # can resolve to differently-metricked faces, and caching the wrong one under this filename
    # would stick for every later render (see the KDoc on downloadFromGoogleFonts).
    if ! css="$(fetch "$(css_url "$family" "wght@${weight}")")"; then
      echo "prewarm-fonts: ERROR ${family}: stage-1 CSS request failed" >&2
      failed=1
      continue
    fi
    url="$(pick_truetype_url "$css" "$weight")"

    # Stage 2: purely-variable families answer stage 1 with no TTF url; retry across the axis.
    if [ -z "$url" ]; then
      if ! css="$(fetch "$(css_url "$family" 'wght@100..1000')")"; then
        echo "prewarm-fonts: ERROR ${family}: stage-2 CSS request failed" >&2
        failed=1
        continue
      fi
      url="$(pick_truetype_url "$css" "$weight")"
    fi

    if [ -z "$url" ]; then
      echo "prewarm-fonts: ERROR ${family}: no TrueType url in either CSS response" >&2
      failed=1
      continue
    fi

    if ! fetch "$url" > "${dest}.tmp"; then
      rm -f "${dest}.tmp"
      echo "prewarm-fonts: ERROR ${family}: TTF download failed" >&2
      failed=1
      continue
    fi
    if [ ! -s "${dest}.tmp" ]; then
      rm -f "${dest}.tmp"
      echo "prewarm-fonts: ERROR ${family}: empty TTF" >&2
      failed=1
      continue
    fi
    mv "${dest}.tmp" "$dest"
    echo "prewarm-fonts: ${family} → ${slug}-${weight}.ttf ($(wc -c < "$dest") bytes)"
  done

  [ "$failed" -eq 0 ] || { echo "prewarm-fonts: one or more families failed" >&2; exit 1; }
}

main "$@"
