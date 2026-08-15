# Mobile — bar, title, preview

Committed evidence for the phone app bar (`ServeWeb.siteHeader` + the
`max-width: 640px` block in `serve.css`) and the viewer's row reflow
(`viewer-drawers.js`).

The page order asked for on a phone is **header bar → title → the thing the
page is for**, and nothing else above the fold. Two things stood in the way: a
site header that stacked three or four rows to say where you are, and two
control rows that sat between the component's title and its render.

| file | what it is |
| --- | --- |
| `catalog-before.png` / `-after.png` | the catalog landing (`serve-landing-declared-themes`) |
| `component-before.png` / `-after.png` | the component page (`serve-viewer-variants`) |
| `header-menu-open.png` | the bar's `⋮`, open. Everything the bar used to spell out — Catalogs, Status, GitHub, the page's own action, Settings — is in here |

Measured on a 412 × 800 phone viewport, top of the page to the top of the thing
the page is for:

| page | anchor | before | after |
| --- | --- | --- | --- |
| catalog landing | first preview card | 382 px | 306 px |
| component page | the render stage | 262 px | 126 px |

Both "before" columns already include the previous round
([`renders/mobile-layout-space`](../mobile-layout-space/README.md), #3895),
which took the same two anchors from 444 px and 334 px.

The links in the bar are **one copy** in the markup, in a `<details>` the server
emits open: above 640px the summary is `display: none` and the panel lays out as
the row of actions it has always been, so the desktop bar is unchanged to the
pixel. The viewer's two rows are **moved in the DOM**, not re-ordered in CSS, and
moved back above 640px — so reading order, paint order and tab order stay the
same order at every width.

Captured from the committed page fixtures at phone size. The harness carries the
equivalents as `serve-landing-declared-themes-mobile`,
`serve-landing-declared-themes-mobile-menu` and `serve-viewer-variants-mobile`:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

All 26 desktop captures of the pages this touches are **pixel-identical** to the
base tree's (compared on decompressed IDAT — the PNG container bytes differ
between runs of different shapes, the pixels do not).
