# Space in the `compose-preview serve` pages

The viewer's chrome had grown until the render — the one thing the page exists to show — opened
half below the fold, and a catalog with several declared themes pushed it further with every theme
it declared. Captures come from the preview-harness (`pages-snapshot.spec.mjs`) at 1024×720, light
theme, full page.

## A crowded viewer toolbar

The `serve-viewer-theme-overflow` fixture: eight theme chips beside the four fixed toolbar controls,
which is what the published `compose-m3` catalog actually offers. Before, the bar wrapped onto three
lines and the breadcrumb, title, id and provenance links each took a row of their own — the stage
started 440px down:

![Viewer before — breadcrumb, title and id stacked, provenance links, and a theme bar wrapped onto three rows, with the stage starting 440px down the page](theme-overflow-before.png)

After: the trail is in the header, identity is one row, and the toolbar is capped at a single line —
the theme chips are its only elastic member, so they shrink (ellipsising) to a floor that keeps them
tellable apart and the group scrolls within itself past that. The stage starts at 236px:

![Viewer after — breadcrumb in the header beside the brand, title and id on one row, and a single-row toolbar whose theme chips ellipsise, with the stage starting 236px down the page](theme-overflow-after.png)

## The ordinary viewer

Same page without the theme crowd, showing where the provenance links went. Before, `source` /
`playground` / `report an issue` sat between the heading and the render:

![Viewer before — source, playground and report an issue links between the title and the renderer controls](viewer-before.png)

After, they sit directly above the export bar, with the other take-this-away-with-you affordances:

![Viewer after — the same links now below the stage, immediately above the Export PNG row](viewer-after.png)

## The catalog landing

The back button moves out of the body's first line into the header's brand slot, so the catalog's
own heading leads the content column:

![Catalog landing before — an "All design systems" button above the heading](landing-before.png)

![Catalog landing after — the same button in the site header beside the brand, heading first in the body](landing-after.png)

## Kept diffed

`serve-viewer-theme-overflow` is a committed page fixture, so the CI visual-diff bot renders and
diffs the crowded toolbar on every future PR — this page is a snapshot of the change, not the
mechanism that keeps it covered.
