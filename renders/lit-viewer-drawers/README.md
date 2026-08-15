# A pressed chip under the pointer

Committed evidence for the CSS fix that came out of porting `viewer-drawers.js`
to `<cp-viewer-drawers>`.

`.cp-spec-chip:hover:not(:disabled)` is (0,3,0); `.cp-spec-chip[aria-pressed="true"]`
is (0,2,0). Hover therefore outranked pressed, so resting the pointer on the
active chip replaced its `secondary-container` fill with the neutral hover wash —
the chip stopped looking selected while you pointed at the thing that selected
it. `.cp-fmt-toggle` was only accidentally safe, both its rules being (0,2,0)
with pressed declared second.

It stayed invisible because the old `viewer-drawers.js` re-inserted
`.cp-preview-primary` on every load. Detaching a node drops `:hover` with it, so
every page capture accidentally shot a pointer-free page. `<cp-viewer-drawers>`
moves a row only when it needs moving, which let the pointer's real resting
position show through — and with it, the bug.

| file | what it is |
| --- | --- |
| `chip-before.png` / `chip-after.png` | the viewer's **Spec** segment, hovered. Before, the grey hover wash sits over the pressed fill; after, the pressed treatment survives the pointer |
| `landing-chip-before.png` / `landing-chip-after.png` | the same fix on a catalog landing's **Brand Light** theme chip, which was wearing the wash over its selected outline |

Cropped and nearest-neighbour enlarged from the `pages-snapshot` captures of
`serve-viewer-path-spec-lane.light` and
`serve-landing-declared-themes-theme-render-terminal.light`, taken on this branch
and on `main`:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

Those two are the **only** 2 of 216 captures that differ between the branch and
`main` — the port itself moves nothing.
