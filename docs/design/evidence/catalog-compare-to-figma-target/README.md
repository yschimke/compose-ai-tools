# Catalog nav: "compare to Figma" points at the comparison, not the parity dashboard

Before/after crops of the catalog landing's action chips, captured from the committed page fixture
(`vscode-extension/preview-harness/fixtures/pages/serve-landing-path.html`) and cropped to the chip
row. Light theme; the dark shots are the same markup.

| Pair | Fixture | What changed |
| --- | --- | --- |
| `catalog-actions-{before,after}.png` | `serve-landing-path` | "compare to Figma" now deep-links `/<system>/compare?format=reference` — the side-by-side table it names — instead of `/<system>/parity`, and the parity dashboard gets its own chip back under its own name |

The pixel difference is the second chip; the load-bearing difference is the first chip's `href`,
which the crop can't show:

| Chip | Before | After |
| --- | --- | --- |
| `compare to Figma` | `/meshcore-mobile/parity` | `/meshcore-mobile/compare?format=reference` |
| `design parity` | — | `/meshcore-mobile/parity` |
