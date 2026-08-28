# preview-server harness

Playwright specs for `compose-preview serve`. Two kinds live here:

**Page captures** — `pages-snapshot.spec.mjs` renders the committed `fixtures/pages/*.html` (written
by `:cli`'s `ServeWebFixtureTest` / `ExplodedSvgFixtureTest`) and writes `<name>.<theme>.png` into
`out/`. `format-compare-scorer.spec.mjs` runs the served viewer's real
`serve/assets/format-compare.js` in a browser and scores synthetic pairs. Both feed the visual-diff
bot; see below.

**End-to-end lanes** — `serve-lanes`, `playground` and `bundle-upload` each boot a real server
(`*-boot.sh`) and drive it. They need a JVM, xvfb and a built CLI, which is why they have their own
configs and run in `serve-lanes-e2e.yml` rather than on every PR.

```
npm ci
npx playwright install --with-deps chromium

npm run harness:pages           # the page captures the diff bot compares
npm run harness:compare         # the compare scorer, in a real browser
npm run harness:serve-lanes     # needs SERVE_URL from serve-lanes-boot.sh
npm run harness:playground
npm run harness:bundle-upload
```

`HARNESS_CHROMIUM=/path/to/chrome` points Playwright at an existing browser when the matching
download isn't present. `HARNESS_THEME=dark` narrows the captures.

## Why it lives here

It used to sit in [`preview-harness/`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/), which made it look like the VS Code
extension's. It never was: these specs drive the server's web surfaces. The measurement in
[#3824](https://github.com/yschimke/compose-ai-tools/issues/3824) put that misfiling at 28% of the
apparent traffic across the serve boundary, and counted from the other side it was worse — of 72
PRs touching `compose-preview-vscode/`, 60 touched only this harness. `harness:snapshot` ran 205 tests,
**167 of them these**.

Two consequences worth knowing:

- **The captures feed one diff surface, from two producers.** The extension's harness and this one
  both write `<name>.<theme>.png`; `vscode-preview-comment` merges the two `out/` directories and
  diffs the result against `vscode-preview/main`. Capture names are unique across the two, so the
  merge is safe and the baselines did not need regenerating when this moved.
- **Both must render on the same Chromium.** They share a baseline set, so a Playwright version skew
  between the two `package.json`s would move pixels for reasons no PR explains. Keep the
  `@playwright/test` range here and in [`package.json`](https://github.com/yschimke/compose-preview-vscode/blob/main/package.json) in step.

`playwright.config.mjs` explains which settings are pixel-load-bearing. Change viewport or the
Chromium raster flags and every capture rebaselines.

## What stayed behind

[`preview-harness/`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/) keeps the panel's own webview fixtures, `snapshot.spec.mjs`,
`contract.spec.mjs` and their helpers. The only thing this directory borrowed from it was a
four-line `listThemes()`, now copied into `_themes.mjs` — see that file for why it is a copy rather
than an import.
