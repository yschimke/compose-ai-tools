# Representing scrolling screens in the layered SVG export

Status: **mobile validated (experiment landed); Wear designed, not yet implemented.**

## Problem

The `compose/figma-svg` export (and its sibling `compose/semantics-wireframe`) is built from the
layout-inspector + semantics tree captured for **one rendered frame** at the preview's viewport size
(see [`ComposeFigmaSvgDataProduct`](../../data/layoutinspector/connector/src/main/kotlin/ee/schimke/composeai/daemon/ComposeFigmaSvgDataProduct.kt)
and [`FigmaSvgModel`](../../data/layoutinspector/core/src/main/kotlin/ee/schimke/composeai/data/layoutinspector/FigmaSvgModel.kt)).

A `LazyColumn` / `LazyRow` / Wear `TransformingLazyColumn` is **virtualised**: only the items in (or
just adjacent to) the visible viewport are composed, so only those have a `LayoutNode`. The capture
walks the live `LayoutNode` tree, so today a scrolling screen exports **only its on-screen rows** —
everything scrolled off is absent.

PNG already has a scrolling story — `@ScrollingPreview(modes = [LONG, GIF])` drives
`SemanticsActions.ScrollBy` and stitches per-viewport raster slices into one tall PNG / animated GIF
(`ScrollSliceStitcher`, `renderScrollPreview`). That path is **raster**: all of its reference points
(overlap-shift alignment, EdgeButton detection, pill clip) operate on pixel rows, so it can't produce
a *layered, editable* SVG. **This document is about the SVG path only. PNG keeps LONG/GIF.**

## Mobile: expand the device vertically ✅ validated

For a phone-style `LazyColumn`, the fix is exactly the intuition "just make the device taller":
render the preview at an **expanded viewport height** so every item lays out in a single composition,
then run the *existing* figma-svg export over that tree. No scroll-and-stitch, no re-tree-merging —
the layered SVG carries the whole list, each row an editable `<g>` at full fidelity.

### Evidence

Experiment: [`RenderEngineFigmaSvgScrollTest`](../../daemon/desktop/src/test/kotlin/ee/schimke/composeai/daemon/RenderEngineFigmaSvgScrollTest.kt)
renders a realistic mobile screen — a Material 3 `Scaffold` with a pinned top app bar ("status bar")
and a bottom navigation bar ("bottom buttons") around a 30-row `LazyColumn`
(`LazyColumnListPreview`) — through the real desktop `RenderEngine` and counts the `Row N` layers in
the emitted `compose-figma.svg`:

| Render | Viewport | Rows in SVG | SVG height |
| --- | --- | --- | --- |
| Viewport-only (today) | 200 × 520 | **9** (visible + prefetch) | 552 px |
| Expanded (overshoot) | 200 × 4000 | **30** | 4032 px |
| Expanded, sized-to-content | 200 × ~1517 | **30** | ~1647 px |

| Viewport-only (`compose/figma-svg` today) | Full page (expanded, sized-to-content) |
| --- | --- |
| ![viewport only](../renders/scrolling-svg/mobile-viewport-only.png) | ![full page](../renders/scrolling-svg/mobile-full-list.png) |

The full-page export bookends the whole list with the pinned top app bar and the bottom navigation
bar — a designer sees the entire screen as one editable layer tree.

### The one nuance: size the height to the content

Overshooting the height works for content coverage but leaves a **trailing band**: a
`Surface`/`Scaffold` that `fillMaxSize()`s paints its background across the *entire* tall viewport,
and (for a `Scaffold`) the bottom bar is *pinned to the bottom of the frame*, so an over-tall frame
leaves a big gap between the last row and the bottom bar. The export must render at a height that
matches the content.

**Grow-by-remaining (preferred).** The scroll container's `VerticalScrollAxisRange` reports
`remaining = maxValue − value` — the pixels of content below the fold, which is *exactly* the extra
content-area height needed to show the rest. So:

1. Render at the base viewport height `H`; read `remaining` from the captured scroll node.
2. If `remaining > ε`, set `H += ceil(remaining)` and re-render.
3. Repeat until `remaining ≤ ε`.

Because the top/bottom chrome are fixed height, adding `remaining` to the frame height adds exactly
`remaining` to the content area — so the list now fits, `remaining → 0`, and a pinned bottom bar
tucks directly under the last row with no gap. It converges in one step for fixed-height rows (a
second pass covers content whose height changes as it reflows at the new size). Bounded by
`@ScrollingPreview.maxScrollPx` and a small iteration cap for infinite/very-tall lists.

This needs no scroll dispatch and no structural analysis of the Scaffold — just the one scalar the
scroll semantics already expose, read after each measured render.

### Implementation (mobile) — landed for the desktop backend, exposed in the preview server

- **Data product** `compose/figma-svg-long` — a `requiresRerender = true` kind
  ([`ComposeFigmaSvgLongDataProductRegistry`](../../data/layoutinspector/connector/src/main/kotlin/ee/schimke/composeai/daemon/ComposeFigmaSvgDataProduct.kt)),
  mirroring `render/scroll/long`. A `data/fetch` for it returns `RequiresRerender("figma-svg-long")`,
  so the daemon queues a per-preview re-render in that mode; the file lands next to the viewport SVG
  as `compose-figma-long.svg`.
- **Producer** — `RenderEngine.render` dispatches `renderMode == "figma-svg-long"` to
  `runScrollSvgScenario`, which runs the growth loop (sizing by **measured geometry** — the deepest
  composed descendant of the scroll node — because the LazyList scroll-range estimate is unreliable),
  renders once at the settled height into an **isolated** output base so the tall render never
  overwrites the preview's normal-size `compose/semantics` / wireframe / PNG, and copies out the
  layered SVG. Reuses `ComposeFigmaSvgDataProducer.writeSvg` unchanged; no change to `FigmaSvgModel`
  / `FigmaLayeredSvg`.
- **Preview server** — [`ServeRenderHost.renderScrollSvg`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRenderHost.kt)
  fetches `compose/figma-svg-long` (inlining any hybrid rasters, cached like the viewport SVG), and
  the HTTP server serves it at **`GET /render/<id>.svg?scroll=long`** (`full` / `page` accepted too)
  alongside the existing `.png` / `.svg` lanes.
- **Remaining**: the interactive web-viewer "Full-page SVG" link (a fast-follow — the viewer's SVG
  links are built by a client-side state machine that needs the Electron preview-harness for visual
  evidence), the **Android** backend (`runScrollSvgScenario` mirror), and the **Wear** geometry
  below.

## Wear: split the scaffold, stack the items unscaled (proposed)

"Just make it taller" does **not** work for Wear, for three reasons:

1. **Round device crop.** A round Wear preview is masked to the inscribed circle (`roundClip`).
   A tall frame has no meaningful circle — the mask would clip the list to a lens.
2. **`ScreenScaffold` chrome is pinned, not scrolled.** `TimeText` sits at the top, the `EdgeButton`
   is revealed at the bottom only when the list reaches its end, the `ScrollIndicator` hugs the right
   edge. None of these belong "stacked" with the list; they frame it.
3. **`TransformingLazyColumn` scales items toward the edges.** Items curve/shrink near the top and
   bottom of the round face (`SurfaceTransformation`). Captured in place they'd be at varying,
   wrong-for-a-flat-list scales.

### Tactical approach

Treat the Wear scroll SVG as a **capsule**, mirroring the existing raster `applyWearPillClip` shape
(top half-circle + rectangle + bottom half-circle):

- **Top chrome group** — the top half of the round face captured with the list scrolled to the
  **start** (so `TimeText` and the first `ListHeader` are shown), clipped to the top arc.
- **Bottom chrome group** — the bottom half captured with the list scrolled to the **end** (so the
  revealed `EdgeButton` is shown), clipped to the bottom arc.
- **Middle list group** — the `TransformingLazyColumn` items captured **unscaled** (with
  `LocalReduceMotion(true)` to flatten the edge transforms, the same knob the PNG LONG path uses) and
  **stacked vertically at natural size** between the two chrome halves, in a full-width rectangle.
  Because items are captured without the edge scaling, they can be laid out sequentially exactly like
  the mobile list.

Split at the vertical middle of the round face. The result is a layered SVG whose top and bottom keep
the round-watch framing while the middle is a clean, unscaled, editable list — a designer can read
and edit the whole screen without the fisheye.

Wear is Android-only (`androidx.wear.compose.*`), so this is implemented in the Android backend
(`ComposeFigmaSvgExtension` / `RobolectricRenderTest`) where the scroll driver, `reduceMotion`, and
`isRound` already live. The item-tree stacking is the new work: capture each item's layout/semantics
subtree at its unscaled size (e.g. from an expanded, chrome-less render of the list content) and
translate them into a single stacked column between the two captured chrome arcs.

## Non-goals

- No change to the PNG scroll path (`render/scroll/long`, `render/scroll/gif`).
- No merging of per-scroll-offset trees for mobile — the expanded single-pass render makes that
  unnecessary. (Tree-merging would only be needed if a list genuinely can't be given a viewport tall
  enough to compose it all; that's the safety-cap tail, out of scope here.)
