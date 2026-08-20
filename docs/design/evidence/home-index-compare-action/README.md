# Front door: the design comparison as a card action

Before/after crops of the `serve` front door (`/`), captured from the committed page fixture
(`vscode-extension/preview-harness/fixtures/pages/serve-home-index.html`) with

```sh
npm --prefix vscode-extension run harness:snapshot   # or: npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

and cropped to the **Design Systems** section, the only part of the page that moved.

| Pair | What changed |
| --- | --- |
| `home-index-light-{before,after}.png` | each card that publishes design references gained a `compare to <tool>` chip under it, deep-linking that catalog's `compare?format=reference` |
| `home-index-dark-{before,after}.png` | the same rows in the dark palette |
| `home-index-neutral-label-{light,dark}.png` | the `yschimke` section after #4349's follow-up: a catalog whose references name no design tool keeps the action and takes the neutral "compare to design references" wording, instead of losing it |
| `home-index-state-layer-corner.png` | the hovered card's top-left corner: before / after / the difference amplified 18×. Losing `overflow: hidden` for the break-out also stopped the card clipping its square-cornered `::after` state layer, so its tint painted the wedge outside the border radius — 143 pixels at a max channel delta of 13, which is why it needs amplifying to see |
| `home-index-card-{rest,hover}.png` | the chip moved ONTO the card (so every card in a section is the same size without an empty row reserved for it), and the hero lost its backing plate and now breaks out of the card's top edge on hover — `home-index-card-hover.png` is the first tile mid-hover |

Two of the three design systems in the fixture publish Figma-backed references and carry the chip;
`remote-m3` publishes none and carries no chip. That mix is the point of the pair: the third tile
still ends level with its neighbours, and its count line still lands on the same baseline.

The earliest shots here (`home-index-*-{before,after}.png`) predate the chip moving onto the card,
so they show it hanging *below* the tile in a wrapper cell. That shape needed an empty row reserved
on every chipless card in a section to stop the tiles going ragged; with the chip inside the card
the grid's own stretch does it, and the reservation is gone.

`serve-home-index` is already a preview-harness fixture, so the CI visual-diff bot renders and
diffs this surface on every subsequent PR without any further wiring.
