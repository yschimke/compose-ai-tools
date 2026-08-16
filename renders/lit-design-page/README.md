# The design page, unchanged by the port

Committed evidence for `design-page.js` → `<cp-design-page>`.

**All 24 `serve-design-page` captures are byte-identical** between `origin/main`
(`0713524`) and this branch — twelve states in both themes, nothing added or
dropped. So these are not a before/after: there is no "before" to show, because
nothing moved.

| file | what it is |
| --- | --- |
| `diff-lane.light.png`, `diff-lane.dark.png` | the diff lane, scoring each node against the sheet |
| `render-failed.light.png` | a render the server could not produce |
| `unlinked-only.light.png` | the coverage filter, showing what has no code behind it |

## Why the diff-lane shot is the one that matters

It is the state that proves the port did not quietly break the thing hardest to
notice. `design-page.js` read `window.ComposePreviewCompare` at IIFE time, and
its `<script>` came *after* `format-compare.js`, which publishes that global. The
components bundle is emitted **before** `format-compare.js` on this page — so an
element that cached the handle when it upgraded would cache `null`, and the diff
lane would score nothing at all. Every badge stuck on a dash, no error anywhere,
and a page that still looks like it is working.

So `<cp-design-page>` reads the handle when the lane is *entered* rather than
when it upgrades. That costs one property lookup per entry into the lane and
cannot be got wrong by moving a script tag. The capture is the proof it resolves:
`15.3% ⇲` in the red band, not a dash.

## What the port extracted, and what each rule prevents

**`design/ink.ts` — fitting our drawn pixels onto the design's drawn box.** The
two halves of the swap are boxes of different kinds. A design node's box is the
tight bounds of the shape Figma drew; a render is a fixed canvas with the
component inside it and transparent margin around. `object-fit: contain` fits
CANVAS to INK, so the margin is spent inside the design's slot and our component
comes out smaller — on this catalog's Shape page that ran from 4% (a circle,
which nearly fills its canvas) to 42% (a semicircle, square canvas, 1.6:1 slot).
Read as "everything scales when you flip the lane", which is precisely the
reading the two lanes exist to make impossible. So ink is fitted to ink, and the
transparent margin hangs *outside* the slot — which is why the placement can be
200% wide at a negative offset, and why that case has a test.

The fit is uniform and centred, never stretched: the aspect our render actually
has is a finding about our code, and a fit that squashed it to the design's box
would report every component as the right shape.

**`design/score.ts` — what a badge says.** Two numbers arrive from the scorer and
they answer different questions; both ways of conflating them have already been
made here once:

- `scoreImages` answers with a MATCH percentage — identical images score 100 —
  and this lane reports DRIFT. Getting the inversion backwards prints "100.0%" in
  red for a perfect match and green for a total mismatch: a readout that lies
  rather than one that is merely wrong.
- Taking `max(drift, geometry)` as the headline made every badge on this fixture
  read 52.4% — the aspect difference wearing the label of a pixel difference. So
  the number is the drift alone, and the geometry marks the badge (`⇲`), is
  spelled out in the tooltip, and counts for the BAND. A component rendered at
  the wrong shape still triages red however well its pixels line up once the
  scorer has normalised the two boxes.

**`design/geometry.ts` — measured placement.** Slots are percentages of the ZOOM
LAYER, not the stage: a zoomed canvas is larger than its stage and offset within
it, so measuring against the stage would divide a zoomed distance by an unzoomed
span. Against the canvas both sides share one transform and the ratio is
identical at any zoom, which is why nothing is recomputed when a zoom is applied
— there is a test that asserts exactly that equality. Node ids are matched under
both of Figma's spellings (`58548:7249` and `58548-7249`) by comparing attribute
values rather than building a selector out of one; a node id is text from a
design file, and interpolating it into a selector has the same shape as an HTML
injection.

**`design/lanes.ts` — the three lanes and two filters.** Including the rule that
switching the coverage filter on turns the resting marks on (a filter with
nothing to draw on is a no-op the reader cannot see) while switching it off
leaves them on (it was an explicit state to arrive at, and silently repainting
the sheet plain would read as the filter having broken something).

## What no capture can show

That a node the manifest names but the export does not carry says so
(`[data-cp-missing]`) instead of vanishing; that the design's own drawing is
hidden only once ours has actually *arrived*, and comes back if it never does;
and that the coverage filter takes what it mutes out of the tab order and the
accessibility tree, not just out of sight — CSS alone cannot, so a keyboard user
would otherwise tab onto an invisible rectangle with no focus ring.

```
cd cli/serve-web && npm run verify   # 508 passing
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
./gradlew :cli:test --tests '*ServeWeb*' && ./gradlew ktfmtCheck
cd vscode-extension/preview-harness
HARNESS_FIXTURE=serve-design-page npx playwright test pages-snapshot.spec.mjs
# 10 passed on both refs; 24/24 PNGs byte-identical
```
