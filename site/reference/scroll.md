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
| Modules | `:data-scroll-core` |
| Render mode | default |
| Cost | medium (extra renders per scroll step) |
| Token usage | Image-only — ~1.5 k tok per `render/scroll/*` PNG read; payload itself is a `path`. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | path (PNG / GIF) |
| Platforms | Android · Desktop · shared |

## What it answers

- What does the entire scrollable region look like, end to end, not just the viewport at rest?
- What does the scroll motion *look like* over time (entry animations, sticky headers settling, `LazyColumn` item placement)?
- Does a `nestedScroll` collapse / expand land in the right state at the end of a fling?

`data/scroll/core` ships scroll-scenario drivers (`ScrollDriver`,
`ScrollGifEncoder`, `ScrollPreviewExtension`) that the renderer
composes through the regular extension pipeline.

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

## Companion products

- [Recomposition](../recomposition) — `compose/recomposition` to attribute scroll cost to specific composables.
- [History diff](../history) — `history/diff/regions` against a long-PNG baseline to catch list-item layout regressions.
