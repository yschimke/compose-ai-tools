# The Theme dropdown, before and after

Committed evidence for the `.cp-theme-menu-panel` rows in `serve.css`.

The report was `wear.preview.coo.ee`: open the Theme menu and the six
declared themes are near-black text on a near-black panel, with a 2px
sliver where each swatch should be. Both halves are cascade accidents, and
both are visible in the pairs below.

## The reported surface — `wear.preview.coo.ee/p/appcard__ideal__default`

| file | what it is |
| --- | --- |
| `wear-viewer-before.png` | the menu as deployed: `AndroidMakers` … `KotlinConf` sunk into the panel, each preceded by a vertical hairline |
| `wear-viewer-after.png` | the same menu with this branch's stylesheet: labels at `on-surface`, a 16px swatch per row, one left edge |

Captured against the live catalog with only the stylesheet swapped, so the
markup, the palette and the six themes are the deployed ones and the pair
differs by this change alone:

```
# a local proxy to wear.preview.coo.ee that answers /…/serve.css from a file,
# then: open /p/appcard__ideal__default, click #cp-theme-toggle, shoot .cp-theme-menu
#   before → git show origin/main:…/assets/serve.css
#   after  → the working tree
```

`Dark` reads in both shots because it is a baked `data-theme-choice`, not a
declared theme — it never matched the rule that broke, which is why the fault
survived a control that looks fine at a glance.

## The harness fixture — `serve-viewer-theme-overflow`

Eight choices, which is the shape that put them behind a dropdown in the
first place, and the fixture committed for exactly this panel.

| file | what it is |
| --- | --- |
| `theme-overflow-before.dark.png` / `theme-overflow-after.dark.png` | the dark page, where `color-scheme: normal` resolved the labels' `light-dark()` to its LIGHT value and painted #1d1b20 on `surface-container-high` |
| `theme-overflow-before.light.png` / `theme-overflow-after.light.png` | the light page, where that fault is invisible — `normal` and the page agreed — so what the pair shows is the other one: six swatches that were hairlines becoming light and dark circles that actually taste the theme they name |

The light pair is the argument for capturing both schemes: on its own it
would have said the menu was fine.

Shot from the fixture at 1280px. The same two captures are now taken by the
harness on every PR — `serve-viewer-theme-overflow` gains a `theme-menu`
state in `pages-snapshot.spec.mjs`, so the panel's paint is diffed rather
than left to a report:

```
cd vscode-extension
HARNESS_FIXTURE=serve-viewer-theme-overflow \
  npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
# → out/serve-viewer-theme-overflow-theme-menu.{light,dark}.png
```

Before this change that fixture was captured only SHUT. The stylesheet was
never the missing half — `preview-harness/_server.mjs` serves
`/assets/serve/*` to every page fixture, so the page was already painted —
but with the menu closed the panel is off-screen, and both faults live
entirely inside it. Neither would have moved a baseline. (That is also why
this branch's diff leaves the fixture's own two captures untouched and adds
only the new state: the page at rest is unchanged.)
