# Mobile — the catalog's one toolbar row

Committed evidence for the catalog landing's phone layout: the `max-width: 640px`
block in `serve.css`, the disclosure-plus-sibling-panel menus in
`ServeWeb.themePickerHtml` / the catalog actions, and the reflow in
[`<cp-catalog-toolbar>`](../../cli/serve-web/src/components/CatalogToolbar.ts).

Four blocks used to stand between the heading and the first card: the catalog's
actions, the Theme group (one chip per declared theme), the filter field, and —
on a sectioned or grouped catalog — the whole navigation tree, which below 960px
is a full-width outline above the grid. They become one row,
`[Theme ▾] [Filter previews…] [⋯]`, over a strip of section chips that scrolls
sideways. The `<h1>` goes with them: the bar above it already names the catalog,
so the heading is clipped into the accessibility tree rather than dropped.

| file | what it is |
| --- | --- |
| `flat-before.png` / `-after.png` | a catalog with no sections (`serve-landing-declared-themes`), five declared themes |
| `sectioned-before.png` / `-after.png` | a sectioned catalog (`serve-landing-sections`) — the tree becomes the strip |
| `grouped-before.png` / `-after.png` | a grouped catalog (`serve-landing-grouped`), whose outline was the tallest of the three |

Measured on a 412 × 800 phone viewport, top of the page to the first preview
card:

| catalog | before | after |
| --- | --- | --- |
| flat | 306 px | **172 px** |
| sectioned | 526 px | **260 px** |
| grouped | 709 px | **262 px** |

The "before" column is `main` as of #3898, which had already taken the flat
catalog from 444 px.

Neither menu needs script: each is a `<details>` holding only its button, with
its panel as the next sibling, so `[open] + panel` is the whole mechanism and it
behaves the same whether the bundle loaded or not. What `<cp-catalog-toolbar>`
does is the part CSS cannot — moving the filter field out of the tree's sidebar
and the actions out of their own block, into the toolbar, and back out again
above the breakpoint.

Captured from the committed page fixtures at phone size. The harness carries the
equivalents as `serve-landing-declared-themes-mobile`, `-mobile-menu`,
`-mobile-theme` and `serve-landing-sections-mobile`:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```
