# Catalog nav: one action per comparison, and the design spec at top level

Before/after crops of the `serve` web surfaces whose navigation changed, captured from the
committed page fixtures (`vscode-extension/preview-harness/fixtures/pages/*.html`) with

```sh
npm --prefix vscode-extension run harness:snapshot   # or: npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

and cropped to the row that moved. Light theme; the dark shots are the same markup.

| Pair | Fixture | What changed |
| --- | --- | --- |
| `catalog-actions-{before,after}.png` | `serve-landing-themed` | the summary line's run of grey text links became a row of assist chips, and "compare formats" split into "compare SVG" + "compare RC players", each deep-linking its own `?format=` |
| `catalog-design-tool-{before,after}.png` | `serve-landing-path` | "design parity" → "compare to Figma", named after the tool the catalog's references came from |
| `parity-compare-all-{before,after}.png` | `serve-parity` | the parity page gained a chip back out to the whole-catalog `?format=reference` table |
| `compare-reference-tab-{before,after}.png` | `serve-format-compare` | the comparison page's "PNG ↔ Design reference" tab → "PNG ↔ Figma" |
| `viewer-figma-chip-{before,after}.png` | `serve-viewer-path` | the design-spec lane left the renderer combo and became a top-level "Figma" chip |
| `viewer-figma-chip-active-{before,after}.png` | `serve-viewer-path` (`spec-lane` variant) | the same row with the spec on the stage: the Figma chip is lit and the renderer chip names the lane it returns to |
