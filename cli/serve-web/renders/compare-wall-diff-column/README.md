# Compare wall: the middle diff column

Before/after for the delta map on `/{system}/compare?format=reference` — the column that sits
between the rendered PNG and the design's own drawing and paints, per row, which pixels moved.

| | |
| --- | --- |
| `before.png` | two panels and a percentage. Six rows scoring 78–99%, and no way to see *where* the two disagree without opening each row's detail page. |
| `after.png` | three panels. The same six rows, the same six percentages, and the magenta map that says the alertdialog's whole face is offset while `button-child — Disabled` differs only in the weight of one label. |

The percentages are identical between the two shots on purpose: this adds a column, it does not
change the measurement. What it does change is that the map and the number now come from **one**
normalisation of the pair (`compare/detail.ts`, the same composition the Reference / Diff / Actual
page measures with) rather than the wall scoring a second, independently-fetched decode.

The reference lane also narrows its three panels from 260px to 200px. Three of them plus the label
and the score do not fit a 1024px viewport at the old width, and the column that falls off the
scroll edge is `Match` — the one the wall is sorted by.

## Reproducing

`fixture/` is the real page, not a mock: `?format=reference` as `ServeWeb` emits it, trimmed to six
rows, beside the twelve PNGs those rows point at (`render/` and `reference/`, fetched from the
published `wear-m3-catalog` on preview.coo.ee). Nothing is drawn by the script — every delta map in
`after.png` is painted by the committed `format-compare.js` from that committed artwork, which is
why this is shot rather than described.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` strips the diff cells and restores the pre-change panel width instead of checking out the
old assets — the component before this change differed only in never painting a canvas that did not
exist. That equivalence is checked rather than assumed: the `before.png` here is byte-identical to a
shot of the same six rows taken against the **deployed** `serve.css` and `serve-components.js` from
preview.coo.ee.

## What keeps this covered from now on

These two are a one-off. The standing coverage is the preview-harness state
`serve-format-compare-reference-lane` (`vscode-extension/preview-harness/pages-snapshot.spec.mjs`),
which drives the committed `serve-format-compare` fixture onto the reference lane and shoots it in
both themes. The harness stubs the two lanes with *different* placeholders, so that baseline carries
a real magenta map over a real mismatch — every future change to this column is diffed by the CI bot
without anyone re-running the above.
