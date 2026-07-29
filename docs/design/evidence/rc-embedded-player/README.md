# Evidence: the embedded Remote Compose player as a third `rc-compare` lane

Renders from the `remote-m3` catalog (`design-artifacts/remote-m3`, `bundle/bundle.png`, 24
documents), produced by `:third-party-rc-embedded-player`'s Robolectric harness and diffed against
the catalog's baked PNGs with `pixelmatch` at threshold 0.1.

## `rc-compare-three-way.png`

The generated page with both players side by side — `baked PNG | JS player | diff | embedded player |
diff`. Produced end-to-end by the real pipeline, not mocked:

```sh
node rc-compare.mjs --bundle bundle.png --player <rc-player bundle.js> --out <dir> \
  --embedded <harness output dir>
```

The top three rows are the argument for having both lanes, because the two players fail on
*different* documents:

| preview | JS player | embedded player |
| --- | --- | --- |
| ShaderGradientSticker | 0.18% | **89.12%** — loses the gradient's teal end entirely |
| WatchScreenRemote | **75.97%** — renders nothing at all | 2.94% |
| IconRemote | **5.83%** — star drawn far too small | 0.00% |

A single-player page would have reported each of those as simply "broken", with no way to tell
whether the document, the capture, or that one renderer was at fault.

## Per-preview stills

`<preview>-1-baked.png`, `-2-embedded.png`, `-3-view.png`, `-4-diff.png` — the baked reference, the
embedded player's render, the `remote-player-view` control render, and the embedded-vs-baked pixel
diff.

- **`shader-gradient-sticker-*`** — 89.12% embedded. The `-3-view.png` control is what makes this
  attributable: the View player renders the same document at **0.00%** through the identical
  software-canvas harness, so the divergence is the embedded player's AGSL path and not
  software-canvas rasterization. Tracked in #2928.
- **`app-card-remote-*`** — 2.11% embedded, 0.01% view. Representative of the small
  antialiasing/text-metric divergences that cluster on rounded-corner and text-heavy content.

Note the diffs are computed after flattening both sides onto a mid-grey, because the catalog PNGs
are stickers on a transparent background — without that, light content on transparent would score as
a false match.
