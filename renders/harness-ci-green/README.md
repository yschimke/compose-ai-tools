# The render-history strip, back on the page

Evidence for the one *product* fix in the CI-green change: `viewer-history.js`
anchored its timeline to `.cp-viewer-bar` and returned early when that element
was missing. #3893 folded that bar's controls into the title row and stopped
emitting it, so the script has been bailing at its second line ever since and
the strip has not drawn on any viewer page.

| file | what it is |
| --- | --- |
| `history-strip-before.png` | `serve-viewer-history` on `main`: title, renderer row, stage. No timeline |
| `history-strip-after.png` | the same page with the strip anchored to the stage instead: **History · 3 versions over 7 publishes · unstable**, and the three dated stops |

Both are the top 300px of the harness capture
`serve-viewer-history.light.png`, which moves with this change — it is the only
baseline that ever held the strip, and it silently rebaselined without it.

The regression was invisible to every other check. What caught it is the
contract test `the fit cap re-measures when the history strip lands`, which
waits for `.cp-history` and had been timing out on every branch since; it passes
now, and it is the guard that keeps this fixed.

```
cd vscode-extension
npm run harness:snapshot     # 158 passed
```
