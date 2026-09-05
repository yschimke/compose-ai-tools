---
title: Scroll captures
parent: Reference
nav_order: 11
permalink: /reference/scroll/
---

# Scroll captures

Long-form scrolling captures (one tall PNG that stitches the whole
scrollable region) and animated GIFs of the scroll itself, driven
through the renderer extension pipeline.

## At a glance

| | |
|---|---|
| Kinds | `render/scroll/long`, `render/scroll/gif` |
| Schema version | n/a (image-only) |
| Modules | `:data-scroll-core` (pure JVM — planners, stitcher, GIF encoder, axis), `:data-scroll-android` (the Android `AndroidComposeTestRule`-bound scroll drivers used by `:renderer-android`) |
| Render mode | default |
| Cost | medium (extra renders per scroll step) |
| Token usage | Image-only — ~1.5 k tok per `render/scroll/*` PNG read; payload itself is a `path`. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | path (PNG / GIF) |
| Platforms | Android · Desktop · shared |

## What it answers

- What does the entire scrollable region look like, end to end, not just the viewport at rest?
- What does the scroll motion *look like* over time (entry animations, sticky headers settling, `LazyColumn` item placement)?
- Does a `nestedScroll` collapse / expand land in the right state at the end of a fling?

`data/scroll/core` ships the pure-JVM scroll primitives (`ScrollAxis`,
`ScrollLongFramePlan` / `ScrollGifFramePlan` planners,
`ScrollSliceStitcher`, `ScrollGifEncoder`, `ScrollPreviewExtension`).
`data/scroll/android` ships the `AndroidComposeTestRule`-bound
`ScrollDriver` (`driveScrollByViewport`, `driveScrollBy`,
`driveScrollToStart`, `driveScrollToEnd`, `remainingScrollPx`) — the
Android renderer composes both. The Compose Desktop renderer pulls
just `data-scroll-core` and drives the scrollable through
`runComposeUiTest` directly, sharing the pure-JVM planners and
stitcher with the Android path.

## What it does NOT answer

- Scroll is **renderer-side only** — it produces image artifacts, not a JSON payload, so it has no `kind` on the daemon's `initialize.capabilities.dataProducts` list. There is no `data-scroll-connector`. It never round-trips through `data/fetch` or `data/subscribe`; instead the renderer drives it directly via `PreviewPipelineStep` / scenario-driver hooks.
- It does not measure scroll performance — for that, instrument [`compose/recomposition`](../recomposition) over the same scrolled frames.

## Use cases

- Render a tall settings screen as one PNG for design review.
- GIF a `LazyColumn` to verify item-key stability across data changes.
- Capture the full scroll of a Wear `ScalingLazyColumn` to confirm the curvature target.

## Payload shape

Image-only artifacts. Produced via
[`:data-scroll-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/scroll/core)
extensions. Output paths under
`build/compose-previews/renders/<id>-long.png` and
`build/compose-previews/renders/<id>.gif`.

## Enabling

Annotate the preview with the matching multi-preview annotation (e.g.
`@ScrollingPreview`) — see
[`skills/compose-preview/references/capture-modes.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/capture-modes.md)
for the multi-preview / scenario annotations the scroll extension
recognises.

## How a LONG capture is verified

Every stride of the walk and every seam of the stitch is checked against the
pixels, and what could not be checked is written next to the PNG.

**The driver measures each stride.** `ScrollBy` on a lazy list animates, and a
lazy list's `ScrollAxisRange.value` is not a pixel position (`LazyColumn` and
Wear's `ScalingLazyColumn` publish `index × 500 + offset`), so neither "has the
spring landed" nor "how far did the content move" can be read off the
semantics range. `driveScrollByViewport` instead snapshots the on-screen
positions of the scrollable's descendant semantics nodes before each stride,
advances the paused clock frame by frame until they stop moving, measures the
median displacement of the nodes seen on both sides, and dispatches a
corrective `ScrollBy` (up to three) when the landing misses the planned stride
by more than a pixel while the scroller still has room. The measured travel is
the offset the stitcher receives, flagged as measured so the search window
around it is a few pixels rather than a third-to-triple of the hint.

**The stitcher only aligns on signal.** Rows are weighted by their horizontal
luminance stddev, so black background and the body of a button between its text
lines contribute nothing and a row through text or an icon decides the match.
A candidate shift is only eligible when its overlap is at least a tenth of the
viewport *and* its informative rows sum to enough signal to be a feature rather
than a stray edge; candidates within a small margin of the best score are a tie
and the one nearest the hint wins (identical chip bodies one pitch apart). This
is what a pinned Wear `TimeText` used to defeat: with the time drawn over the
head of every slice, a 12-row all-black overlap at the far end of the window
out-scored the true shift and the next slice was painted from its top row,
`10:10` and all.

**Chrome that appears at the foot of a slice is set aside.** The last stride of
a Wear walk lands at the content end, and landing there reveals the
`ScreenScaffold`'s `EdgeButton`: the last slice's bottom fifth is a bright
button where the previous slice had background. At the true shift every row
above it agrees exactly and every row of it disagrees violently, which
misalignment never looks like (it spreads disagreement across the overlap). A
candidate's score therefore walks up from the bottom of the overlap past a
contiguous run of disagreeing rows, caps it at a third of the overlap, and
scores only the rows above; the run is reported as `revealedRows` on the seam.
`RevealedBottomChromeSeamTest` pins this on the real slice pair.

**What is reported.** Each seam gets a verdict — `verified`, `low_signal` (too
little varied content in the overlap to decide on) or `mismatch` (no shift made
the two slices agree) — and each stride a landed/not-landed outcome. The stitch
is always written, but anything unverified rides in `<png>.warnings.json`:

```json
{
  "unlandedScrollSteps": [{ "role": "…", "step": 2, "requestedPx": 307.2, "measuredPx": 225.0, "corrections": 0, "settled": true, "message": "…" }],
  "unverifiedScrollSeams": [{ "role": "…", "seam": 1, "verdict": "low_signal", "hintPx": 307, "shiftPx": 372, "overlapRows": 12, "informativeRows": 0, "residualPerPixel": 0.3, "message": "…" }]
}
```

A clean capture writes neither array. A consumer that wants to refuse an
untrustworthy tall PNG fails on either being non-empty; the renderer's
`WearPinnedTimeTextLongScrollTest` and `LongScrollPreviewPixelTest` do exactly
that.

To iterate on the matcher without a Robolectric round-trip, render with
`COMPOSEAI_KEEP_SCROLL_SLICES=1` (the per-slice PNGs, the settled final frame
and the reported offsets stay in `<id>_slices/` next to the output), then point
`COMPOSEAI_STITCH_HARNESS_DIR` at that directory and run
`:data-scroll-core:test` — `ScrollSliceStitcherHarnessTest` re-stitches every
slice set it finds and prints each seam's verdict.

## Companion products

- [Recomposition](../recomposition) — `compose/recomposition` to attribute scroll cost to specific composables.
- [History diff](../history) — `history/diff/regions` against a long-PNG baseline to catch list-item layout regressions.
