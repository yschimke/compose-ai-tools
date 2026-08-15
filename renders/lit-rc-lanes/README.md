# The Remote Compose player wall, and the diff path nothing was watching

Committed evidence for `rc-lanes.js` → `<cp-rc-lanes>`.

All four existing `serve-rc-lanes` captures — default and `diff-baked`, light and
dark — are **byte-identical** between this branch and `origin/main`. The port
moves nothing. What is new is a capture of the half that had none.

## The path that was already covered

| file | what it is |
| --- | --- |
| `diff-baked.light.png` | reference = the baked PNG: the marked column, the mismatch chips, and the offline run's own numbers replayed |

This is the cheap path — every number on it was computed by
`scripts/design-artifacts/rc-compare.mjs` with pixelmatch and published on the
delivery branch, so the page just shows them.

## The path that was not

| file | what it is |
| --- | --- |
| `diff-player.light.png` | reference = a **player**: nothing is precomputed, so the two renders are decoded onto a canvas and diffed in the browser |
| `diff-player-images.light.png` | the diff images that produces — the washed-out 10% grey backdrop `diffPixels` paints under its flagged pixels |

Picking a player is the one question the build cannot answer, and it reached
production with no test and no capture: an in-browser pass that threw, or
produced no image at all, would have left every shot in the suite unchanged. The
new `serve-rc-lanes` · `diff-player` state holds the whole pipeline — load →
canvas → `getImageData` → diff → data URL → an `<img>` that renders — plus the
status line that admits an in-browser number is **not** the build's measurement.

The numbers read `0.00%` because the harness serves one placeholder for every
`/rc-compare/` URL, so every lane is genuinely identical. The arithmetic is
covered directly instead, in `cli/serve-web/test/pixelDiff.test.ts`.

The state waits for every started row to **finish**, not for the first diff image
to appear. A row measures its lanes one after another and several rows are in
flight at once, so the first image lands while most of the wall is still
decoding — a baseline taken there would hold whichever subset won that run's race
and re-diff itself forever. `<cp-rc-lanes>` marks each row `data-scored="pending"`
then `"done"` for exactly this, which is also the only way to tell "still
measuring" from "finished, and this is all there is" when looking at the page.
Verified stable: three consecutive runs produce byte-identical captures.

## The metric, finally pinned

`rc/pixelDiff.ts` is pixelmatch's YIQ metric, hand-transcribed into nine magic
constants and a threshold scale, and it produces the only number on this page the
offline run did not compute — so a drift in it makes the page quietly disagree
with the build while looking exactly the same. It had no test of any kind. It now
has 15, including:

- a sweep of the RGB cube confirming the transform still tops out at the **35,215**
  its `threshold` option is expressed against (the maximum is red against cyan,
  **not** black against white — white only maximises the luminance term, 32,857);
- the exact bytes of a flagged pixel (`255, 60, 60, 255`) and of the backdrop
  (`230, 230, 230, 255` for black at 10% over white);
- that the cutoff is `>` and not `>=`, which is one pixel on a boundary but moves
  every number on the page if it flips.

```
cd cli/serve-web && npm run verify   # 297 passing
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```
