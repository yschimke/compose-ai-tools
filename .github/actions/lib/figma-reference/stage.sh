#!/usr/bin/env bash
# Install the rasteriser, then stage a Figma reference image per changed preview.
#
#   stage.sh <design-map.json> <pages.json> <changed-previews.json> <output-dir> <manifest>
#
# Split out of the compose pipeline for one reason: the dependency. `index.mjs` is dependency-free
# apart from `@resvg/resvg-js` — a prebuilt native rasteriser with no system libraries behind it —
# and the install has to land somewhere that ESM resolution can see. `NODE_PATH` cannot do that job
# (ESM ignores it), so the package is installed beside `index.mjs`, into the action's own checkout
# rather than the consumer's working tree, where a stray `node_modules/` would show up in a repo's
# `git status` and in the next `git add -A`.
#
# Exits 0 on every failure. A missing rasteriser, an offline npm, a page the export skipped: all of
# them mean "no Figma column", never "the preview diff failed".
set -uo pipefail

DESIGN_MAP="${1:?design map path}"
PAGES="${2:?pages manifest path}"
PREVIEWS="${3:?changed previews path}"
OUT_DIR="${4:?output dir}"
MANIFEST="${5:?manifest path}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -d "$HERE/node_modules/@resvg/resvg-js" ]; then
  # `--omit=dev --ignore-scripts`: the package ships prebuilt binaries per platform, so there is
  # nothing to build and nothing here should be running install hooks.
  if ! npm install --prefix "$HERE" --no-audit --no-fund --omit=dev --ignore-scripts >/dev/null 2>&1; then
    echo "figma reference: could not install the SVG rasteriser; skipping the Figma column."
    exit 0
  fi
fi

node "$HERE/index.mjs" \
  --design-map "$DESIGN_MAP" \
  --pages "$PAGES" \
  --previews "$PREVIEWS" \
  --output-dir "$OUT_DIR" \
  --manifest "$MANIFEST" || echo "figma reference: stager failed; skipping the Figma column."

exit 0
