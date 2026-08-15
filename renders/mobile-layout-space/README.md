# Mobile layout — what a phone screen is spent on

Committed evidence for the `@media (max-width: 640px)` changes in
`cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css`.

The complaint these answer: opening a catalog on a phone shows a screen of
chrome and no components, and the component page shows a screen of controls and
no render. Nothing here re-orders the page — the DOM order is unchanged, so tab
order still matches what is on screen. Rows that grew a line per chip are
capped at one scrolling line instead, which is the rule the viewer's theme bar
already followed, and the vertical rhythm between them is tightened to phone
scale.

| file | what it is |
| --- | --- |
| `catalog-before.png` / `-after.png` | the catalog landing (`serve-landing-declared-themes`). The Theme group is one chip per declared theme; five of them wrapped to two rows, and this fixture is a small one — `preview.coo.ee/m3` declares a dozen and wraps to five |
| `component-before.png` / `-after.png` | the component page (`serve-viewer-variants`). The disclosure pills — every control the viewer has — fell twelve pixels short of one row, and the renderer row wrapped below them |

Measured on a 412 × 800 phone viewport, top of the page to the top of the thing
the page is for:

| page | anchor | before | after |
| --- | --- | --- | --- |
| catalog landing | first preview card | 444 px | 382 px |
| component page | the render stage | 334 px | 262 px |

Captured from the committed page fixtures at phone size, the same ones the
snapshot harness uses. The equivalent shots are now part of that harness as
`serve-landing-declared-themes-mobile` and `serve-viewer-variants-mobile`
(`viewport` states in `pages-snapshot.spec.mjs`), so every later PR gets this
surface diffed without anyone remembering to look:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

Every existing capture is byte-identical — the changes live entirely inside a
`max-width: 640px` block and the harness's other shots are taken at 1024.
