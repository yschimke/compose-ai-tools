# The score rebaseline, before and after

The D3 rebaseline swaps the live scorer's resampler: `drawImage`'s implementation-defined
smoothing out, the portable area average both engines run in. What it primarily moves is a
**number**, so the table in the PR is the evidence that matters — but the same kernel builds the
normalised panels the magenta delta map is drawn from (`boxCanvas`), so it moves pixels too, and
this is what those pixels look like.

Each row is one pair from `renders/lane-parity/`: the two sides normalised into one box, the delta
map between them, and the percentage the page would print. Nothing here is drawn by the script —
every panel comes from `format-compare.js` running in Chromium, which is the whole reason for
shooting this rather than describing it.

```sh
node shoot.mjs after.png

git show origin/main:cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js \
  > before-asset.js
node shoot.mjs before.png --before
rm before-asset.js
```

`--before` loads the published asset from `main` rather than the built one, so both frames come
from real assets and this directory holds no second implementation of the scorer. `before-asset.js`
is a working copy and is deliberately not committed.

Set `CHROMIUM_PATH` when Playwright's own download is not where it expects it (in the container
image here: `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`).
