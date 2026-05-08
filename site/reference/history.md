---
title: History diff
parent: Reference
nav_order: 5
permalink: /reference/history/
---

# History diff

Per-pixel bounding boxes of regions that changed between two history
entries (e.g. base render vs. PR head, or before / after a code edit).

## At a glance

| | |
|---|---|
| Kind | `history/diff/regions` |
| Schema version | 1 |
| Modules | `:data-history-core` (published) · `:data-history-connector` |
| Render mode | default |
| Cost | low |
| Transport | inline |
| Platforms | Android · Desktop · shared |

## What it answers

- Where in the frame did the pixels change between two renders of the same `previewId`?
- How many distinct change regions are there, and how big is each (in pixels and percentage)?
- Is a "tiny visual change" PR actually tiny, or did the author break a screen further down the layout?

## What it does NOT answer

- It does not tell you *why* a region changed (theme tweak vs. layout regression vs. anti-aliasing) — pair it with [`compose/theme`](../theme) or [`layout/inspector`](../layout-inspector) for that.
- It does not produce a perceptual / SSIM diff — it operates on raw pixel inequality, so font hinting differences and platform GPU drift can show up as regions.

## Use cases

- PR comments: post a single image with the changed regions outlined, plus a one-line "12 regions, ~3.4% of pixels".
- Refactor verification: prove a state-hoisting refactor produced an empty diff against `compose-preview/main`.
- Bisect: walk backwards through baselines to find the commit that changed pixels in a particular bounding box.

## Payload shape

`HistoryDiffPayload`, `HistoryDiffRegion` in
[`:data-history-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/history/core).

```jsonc
// history/diff/regions
{
  "leftEntryId": "compose-preview/main@7a1c…",
  "rightEntryId": "<head>",
  "regions": [
    { "boundsInScreen": "48,200,144,232",
      "changedPixels": 412, "totalPixels": 3072,
      "deltaSummary": "color-shift" }
  ],
  "totalChangedPixels": 9184,
  "totalPixels": 1080000
}
```

## Enabling

The producer attaches when the History extension is publicly enabled
and `data/fetch?kind=history/diff/regions` is called with a left and
right entry id. The companion CI workflow is
[`preview-comment`](https://github.com/yschimke/compose-ai-tools/tree/main/.github/actions/preview-comment),
which posts before / after PR comments using the same machinery.

## Companion products

- [Layout inspector](../layout-inspector) — `layout/inspector` to attribute a changed region to a specific composable.
- [Theme](../theme) — `compose/theme` to confirm a colour-shift region is from a token change rather than a regression.
