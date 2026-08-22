# Front door: the hero lifts off the card

Before/after crops of one hovered card on the `serve` front door (`/`), captured from the committed
page fixture (`vscode-extension/preview-harness/fixtures/pages/serve-home-index.html`) at
`deviceScaleFactor: 2` with transitions disabled, so the shot lands on the settled hover state
rather than racing the ease.

Three things changed in the hover treatment, and only the first is visible without looking twice:

1. **The break-out zoom grew** from `scale(1.12)` to `scale(1.15)` — a quarter more travel out of
   the card, still anchored at bottom-centre so the artwork only ever grows up and outward.
2. **The hero moved above the card's state layer.** `.cp-card::after` is M3's `primary` tint at the
   hover opacity, and it used to paint over the artwork: a design system's own screenshot came out
   washed purple at exactly the moment a visitor was looking at it. The hero now sits at
   `z-index: 4`, above the layer, so the tint stays on the card *around and behind* the picture.
   That is what the phone pair below is for — the whites in the render are white again.
3. **The hero casts a shadow shaped to itself**, via `filter: drop-shadow` rather than
   `box-shadow`. The filter traces the image's own alpha, so a round watch face throws a round
   shadow and a phone screenshot throws a rectangular one. A `box-shadow` would draw the 220px
   layout box instead, whose corners are empty for most of these previews — and that mismatch is
   what reads as "sticker pasted on" rather than "object lifted off".

| Pair | What it shows |
| --- | --- |
| `hero-lift-phone-{before,after}.light.png` | an opaque phone screenshot. The tint is off the artwork, the zoom is larger, and the cast shadow is a soft rectangle following the render's own edge |
| `hero-lift-round-{before,after}.light.png` | the Wear card, stubbed with a round render whose corners are transparent. The shadow is a circle, not a 220px square — the whole claim of the `drop-shadow` approach in one picture |
| `hero-lift-round-{before,after}.dark.png` | the same card in dark mode, which is why the filter has a third layer. A black watch face casting a black shadow onto a near-black card is nothing at all, so the first layer is a `primary`-tinted **bloom** — transparent in light mode — and it is what lifts a dark silhouette off a dark card |

## The Wear catalog's own hero

| Pair | What it shows |
| --- | --- |
| `wear-catalog-hero-{before,after}.png` | `wear-m3-catalog`'s `display.hero`, before and after it moved from `Shape/MaterialShapes` to `Media/PlayerScreen`. Both are that catalog's **real published renders**, pulled from `design-artifacts/wear-m3-catalog` and served into the fixture's Wear card |

The shape specimen is a flat lilac cookie: it is a legitimate component of that catalog and it is
also 176px square, so it never even reaches the card's edge — the break-out gesture had nothing to
break out of. The media player is a whole 384px round watch face with a clock, a track, and a
transport row, and it is the picture that says "watch design system" before anyone reads the title.

## How these are kept

`serve-home-index` is a preview-harness fixture, so the CI visual-diff bot renders and diffs this
surface on every subsequent PR. Two additions here keep the *hover* covered rather than just the
resting page:

- `serve-home-index.card-hover-round` — a second hover capture, on the card whose hero is round.
  The existing `card-hover` shot is an opaque rectangle, which cannot tell a shaped shadow from a
  boxed one.
- The home-index image stub now serves the round placeholder to the Wear lanes, so that difference
  exists in the baseline at all.

Reproduce with:

```sh
npm --prefix vscode-extension run harness:snapshot   # or: npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```
