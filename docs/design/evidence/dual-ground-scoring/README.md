# A comparison is scored on two grounds, not one

The fidelity scorer composited both sides of every comparison onto a single fixed ground
(`COMPARISON_BACKDROP = "#ffffff"`) before measuring. One opaque ground is not a neutral choice: it
**annihilates ink that matches it**, and the metric cannot tell that apart from "nothing was drawn".

`scorePlanes` is explicit about what it does with that:

```ts
if (measured === 0) return 100;
```

— *"Two blank planes have no content and no disagreement, so they are a match by definition rather
than a division by zero."* That is the right call for two genuinely empty frames, and a confident lie
for two frames a white ground flattened.

## It is not a corner case

Measured off `samples/design-catalog-wear-m3`, from each PNG's own alpha channel:

| Sticker | Opaque | Ink luminance |
| --- | --- | --- |
| `TextMaxLinesTruncated` | 8% | 255 |
| `IconSticker` | 15% | 255 |
| `OutlinedButtonSticker` | 5% | 188 |

A dark-first design system draws light ink, and a component sticker is mostly alpha by design. Those
two facts together mean the white ground was erasing exactly the catalogs this tool exists to check.

## What it cost, measured

Real renders, real metric, scored in Chromium. `before` is one ground (`#ffffff`); `after` is the
worse of two (`#ffffff`, `#000000`). Every pair below except the last is a pair of **different
components**, so a correct metric should score them low:

| Pair | Before | After |
| --- | --- | --- |
| `TextMaxLinesTruncated` vs `IconSticker` — both white ink | **100.0** | 51.4 |
| `TextMaxLinesTruncated` vs `OutlinedButtonSticker` | 79.4 | 63.6 |
| `IconSticker` vs `OutlinedButtonSticker` | 79.4 | 34.0 |
| `CardSticker` vs `FilledButton` — darker ink, control | 10.3 | 10.3 |
| `FilledButton` vs itself — identity guard | 100.0 | 100.0 |

The first row is the headline: two components sharing nothing but the colour of their ink scored a
**perfect match**. Not "high" — 100.0, the same number an image scores against itself.

All five were re-measured after the opacity gate below was added, and none of them moved: every
sticker in this catalog carries alpha on both sides, so all five still earn the black pass.

The last two rows are the reassurance. The dark-ink control is **unchanged**, so the second ground
costs nothing where the first was not destroying anything; and an image against itself still scores
100, so taking the worse of two grounds introduces no noise-driven pessimism on an honest pair.

## Why two fixed grounds rather than a themed one

A theme-derived ground — the `PreviewBackdrop` this repo already resolves for *display* — makes the
collision rarer rather than impossible: a white specimen on a white stage still vanishes. It also
makes every score a function of `display.surface`, so a catalog re-declaring its stage silently moves
its numbers.

White and black together make the failure **structural** instead: a pixel can only vanish on both
grounds when its alpha is zero on both sides, which is the one case where "no evidence" is the honest
answer. No per-catalog configuration, and nothing to keep in sync.

## A second ground is only scored when both sides can show one

Taking the minimum is only safe when the extra ground measures the *artwork*. On a **mixed** pair —
an opaque reference against a render with a transparent surround — it does not: the reference sits
still while the render's whole surround swings from white to black, so the black pass reports a
difference that lives in the grounds, and the minimum would hand that back as the verdict.

`groundsWorthScoring` drops the extra grounds in exactly that case, and it detects opacity for free:
an opaque frame composites identically onto every ground, so its planes come back equal. Nothing is
decoded or rasterised to find out.

That case is the normal one for the design lane. `DesignPage.sheetImage` still flattens its crop onto
`#fff`, because the crop is not an isolated node — `rasteriseSheet` rasterises a clone of the *whole*
sheet, so the crop carries the opaque sheet furniture drawn behind and around the target. The gate
would catch it either way; the fill keeps the two lanes agreeing about what the reference *is*
instead of relying on that.

## Every ground is rasterised before the first score

`scorePlanes` yields to the event loop every eighth row, and not every source is a still: the RC
lane's candidate is a live canvas the Remote Compose player schedules its own frames on. Scoring
ground-by-ground would let it repaint between passes, so the two grounds would measure two different
frames and the minimum of those is neither — a single-shot score that changes when nothing changed.
All planes are therefore composited up front, synchronously, and only then scored.

## Cost

Two passes instead of one, over a plane capped at `MAX_SIDE = 192` — so under 37k pixels either way.

## Reproducing

```sh
./gradlew :samples:design-catalog-wear-m3:composePreviewRenderAll
# Bundle src/scorer/{frames,planes,tuning}.ts into a page and serve it over http — a file:// image
# taints the canvas, so getImageData throws. Score each pair twice: with ["#ffffff"] and with
# ["#ffffff", "#000000"], taking the minimum across grounds.
```

## Known limit

`scripts/design-artifacts/render-compare-html.mjs` carries its **own** inline copy of `grayFromDraw`,
also compositing on `#ffffff`, for the publish-time PNG↔figma-svg report. It has the same flaw and is
not fixed here — unifying the two scorer implementations is its own change. Until then that lane's
numbers keep the old behaviour.
