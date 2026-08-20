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
| `home-index-card-{rest,hover}.png` | the chip moved ONTO the card (so every card in a section is the same size without an empty row reserved for it), and the hero lost its backing plate and now breaks out of the card's top edge on hover — `home-index-card-hover.png` is the first tile mid-hover |

Two of the three design systems in the fixture publish Figma-backed references and carry the chip;
`remote-m3` publishes none and carries no chip. That mix is the point of the pair: the third tile
still ends level with its neighbours, because the cell reserves the action row for every card in a
section where any card has one. Reserving it **per section** rather than per page is why the
`yschimke` and `joreilly` sections below (unchanged, outside the crop) carry no empty rows.

`serve-home-index` is already a preview-harness fixture, so the CI visual-diff bot renders and
diffs this surface on every subsequent PR without any further wiring.
