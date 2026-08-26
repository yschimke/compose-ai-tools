# Front door: the hero lifts off the card

Before/after crops of one hovered card on the `serve` front door (`/`), captured from the committed
page fixture (`preview-server/preview-harness/fixtures/pages/serve-home-index.html`) at
`deviceScaleFactor: 2` with transitions disabled, so the shot lands on the settled hover state
rather than racing the ease.

Three things changed in the hover treatment, and only the first is visible without looking twice:

1. **The break-out zoom grew** from `scale(1.12)` to `scale(1.15)` — a quarter more travel out of
   the card, still anchored at bottom-centre so the artwork only ever grows up and outward.
2. **The state layer dropped under the card's content.** `.cp-card::after` is M3's `primary` tint
   at the hover opacity, and it used to paint over the artwork: a design system's own screenshot
   came out washed purple at exactly the moment a visitor was looking at it. On a `.cp-sys` card it
   is now `z-index: -1` — above the card's background, below everything else, which is where M3
   draws a state layer anyway. That is what the phone pair below is for: the whites in the render
   are white again.

   Lowering the layer, rather than raising the hero above it, is the whole point. Raising the hero
   also lifts it above `.cp-sys-open::after`, the stretched overlay that makes the tile one link,
   so the hero has to refuse pointer events to keep the card clickable — and then pointing at the
   part of the scaled hero hanging *outside* the card hits the page (or the neighbouring tile)
   instead of this card, so the artwork retracts from under the pointer standing on it. Measured
   in the harness: with the pointer 13px above the card's top edge and over the hero's own pixels,
   `card.matches(":hover")` was `false`. The contract test named below now pins both ends.
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

…and one contract test, `contract · the front door's state layer stays under the hero, and the
break-out keeps its hover`, because neither half is a picture: it reads the layer's computed
`z-index`, hit-tests the middle of the hero (which must still resolve to the tile link), and then
walks the pointer onto the break-out strip above the card and onto the side overhang over the
neighbouring column, asserting the card stays hovered and the hero does not retract.

Reproduce with:

```sh
npm --prefix preview-server/preview-harness run harness:pages   # or, from that directory: npx playwright test pages-snapshot
```
