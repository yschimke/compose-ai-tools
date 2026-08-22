# Catalog sheet: sub-groups flow into shared rows instead of each claiming one

Before/after full-page captures of the `compose-preview serve` catalog sheet, taken from the
committed page fixtures with the preview-harness at a 1440×1000 viewport — the viewport
[#4423](https://github.com/yschimke/compose-ai-tools/issues/4423) measured at. Light theme; the
dark shots are the same markup and move by the same amount.

```
HARNESS_WIDTH=1440 HARNESS_HEIGHT=1000 \
  npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot \
  --grep "snapshot · serve-landing-grouped"
```

| Pair | Fixture | Page height before → after |
| --- | --- | --- |
| `serve-landing-grouped-{before,after}.png` | `serve-landing-grouped` (4 synthesized families, 14 previews) | 1893px → 1091px (−42%) |
| `serve-landing-sections-{before,after}.png` | `serve-landing-sections` (3 sections, 6 sub-groups, 9 previews) | 2405px → 1599px (−34%) |
| `serve-component-browser-catalog-{before,after}.png` | `serve-component-browser-catalog` | 1750px → 1000px (−43%) |

## What changed

Each `.cp-subgroup` used to open its own `.cp-cards` grid, and a grid declares its full column
count whatever it has to put in it — so a one-card family reserved five columns and painted four
of them blank, one family per row. The sub-groups are now **clusters** in a single wrapping flow
per section: a cluster asks for exactly the width its own cards occupy (`--cp-n`, the card count,
written inline by the server and kept in step by the filter script), so Button (3 cards) and Card
(2) share the first row and FAB and Badge share the second.

The headings still mark every boundary — that was never the thing costing height. A full-width
heading inside one section-level grid (suggestion 1 on the issue, read literally) would not have
helped: `grid-column: 1 / -1` occupies a whole row, so each family would still start a fresh one
and the sheet would have lost only the heading's own line. It is the cluster sizing, not the grid
merge, that lets single-card families sit side by side.

## The id line

Visible in the crops at card-meta height: preview ids now elide from the **middle**
(`button-outlined__id…__light`) rather than the end (`button-outlined__ideal__d…`). What
distinguishes one render from its siblings is the suffix, so an end-clipped id said nothing the
label above it had not already said. The server splits at the last `__` and only the head span
shrinks.
