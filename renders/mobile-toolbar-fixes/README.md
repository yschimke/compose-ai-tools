# Mobile toolbar — the count out of the way, and four review fixes

Follow-up to #3906. "Before" is `main` at that merge; everything here is a phone
viewport (412 × 800, and 320 × 800 for the theme menu).

| file | what it is |
| --- | --- |
| `catalog-before.png` / `-after.png` | the summary line moves below the grid, so the bar is followed by the toolbar and then the previews |
| `theme-menu-320-before.png` / `-after.png` | the Theme menu on a 320px screen: right-anchored to a ~150px pill it grew off the left edge (measured at `left: -9px`), and the filter field's 260px basis put a horizontal scrollbar under the page (`scrollWidth` 349 against a 320 viewport) |
| `actions-menu-no-theme-before.png` / `-after.png` | the `⋯` menu on a catalog with no Theme control — in "before" it is open and **not on screen**: with no positioned ancestor the panel measured from the initial containing block and landed at `top: 806px`, past the bottom of an 800px viewport |

Measured on a 412 × 800 viewport, page top to the first preview card:

| catalog | #3906 | with the count moved |
| --- | --- | --- |
| flat | 172 px | **142 px** |
| sectioned | 260 px | **230 px** |
| grouped | 262 px | **232 px** |

The count is moved, not dropped: the live-session hint it carries ("hold a card
for a live session") is the only place a phone is told that gesture exists, and a
phone is where the gesture is.

Captured from the committed page fixtures:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```
