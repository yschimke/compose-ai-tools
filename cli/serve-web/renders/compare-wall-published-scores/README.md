# Compare wall: the published scores, and the Bugs column

Before/after for `/{system}/compare?format=reference` **while it is still measuring** — the state a
visitor actually lands in, which no other shot of this wall can show because every one of them
deliberately waits for the in-browser scorer to settle first.

| | |
| --- | --- |
| `before.png` | six rows in catalog order, one pair of pictures painted and five pairs of empty boxes, one `comparing…` and five `waiting…`. Nothing on screen says which of these six is the one to look at. |
| `after.png` | the same six rows, worst-first on the scores the delivery branch published, every pair painted, every percentage banded — and a **Bugs** column carrying what is already filed against a row plus a `+ file` link on every row. |

Three separate things moved, and the shot shows all three at once because they are all answers to the
same complaint (issue #4624):

- **The scores are published.** `references/index.json` has carried a baked `match` since
  `design-reference-score.mjs` existed; the wall never read it. It does now, per variant, and seeds
  each row from it. The in-browser pass still runs and still replaces the number — that is the
  `data-score-source="published"` dotted rule under a seeded percentage, which goes when the browser
  has measured the pair itself.
- **The order is served, not discovered.** `ServeWeb.comparisonPage` sorts the reference lane
  worst-first on those same numbers, so the document leaves the server in the order it will settle
  into. `<cp-compare-wall>` re-seats on the seeded numbers too, which is what covers a lane or theme
  switch — where the served order is about another pairing entirely.
- **The pictures are no longer gated on the scorer.** Assigning `src` used to happen *inside* the
  serial scoring chain, so the wall painted one row's two panels per completed comparison. That is
  the whole difference between the two shots' picture columns; nothing about pointing an `<img>` at
  a URL needs a scorer.

The percentages in `after.png` are not invented for the shot: the script runs the wall once, reads
what the committed `format-compare.js` scores each pair at, and writes those back as the
`data-match-<variant>` attributes the server now emits — which is exactly how the publisher mints
them, by driving that same asset.

## Reproducing

The fixture is borrowed wholesale from [`../compare-wall-diff-column/fixture`](../compare-wall-diff-column/fixture)
— a real `?format=reference` wall trimmed to six rows, beside the twelve PNGs those rows point at,
fetched from the published `wear-m3-catalog`. Nothing is redrawn here.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` serves `serve-components.js` and `serve.css` out of `HEAD` rather than stripping things in
the page: this change moves behaviour and not only markup, so a `before` shot taken against the new
bundle would already be painting all six rows.

Both shots hold the scorer open — the scoring calls are wrapped to never resolve — so neither races a
pass that has already finished. Everything else runs exactly as it does on the served page.

## What keeps this covered from now on

These two are a one-off. The standing coverage is the preview-harness state
`serve-format-compare-reference-lane` (`preview-server/preview-harness/pages-snapshot.spec.mjs`),
which drives the committed `serve-format-compare` fixture onto the reference lane and shoots it in
both themes — and which now carries a published `match` and a two-issue Bugs column, so the column's
placement, its width budget against `Match`, and the seeded score's banding are diffed by the CI bot
on every future change. What that baseline cannot cover is the mid-measurement moment, which is why
these two exist.
