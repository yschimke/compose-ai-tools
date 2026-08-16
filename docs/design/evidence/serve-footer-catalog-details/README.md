# The about box is gone; catalog details moved into the footer, expanded

Before/after crops of the bottom of the `serve` web surfaces, captured from the committed page
fixtures (`vscode-extension/preview-harness/fixtures/pages/*.html`) served statically and shot with
Playwright (light theme, 1000px viewport, 2× DPR; the dark shots are the same markup).

| Pair | Fixture | What changed |
| --- | --- | --- |
| `catalog-footer-{before,after}.png` | `serve-landing-public` | the "About this preview server" disclosure is gone, and the "Catalog details" disclosure moved out of the page body into the site footer, rendered expanded beside the source/`/version`/build links |
| `home-footer-{before,after}.png` | `serve-home-index` | the front door drops the same about disclosure; its footer is otherwise unchanged |
