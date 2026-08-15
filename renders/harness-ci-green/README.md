# The render-history strip, back on the page — and on the right side of it

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

## Which side of the stage, on a phone

Putting the strip back above the stage is right on a laptop, where it reads as
metadata about the render it describes. On a phone it is not: the page there is
**bar → title → preview**, and a strip in between costs 113px of exactly that.
Measured at 412px — `.cp-stage` starts at 126px on every other preview and at
239px on one with a history.

| file | what it is |
| --- | --- |
| `history-strip-mobile-before.png` | 412px with the strip always above the stage: the render is pushed under a fold it does not need |
| `history-strip-mobile-after.png` | the same page with `place()` choosing by width: title, then the render, then History under it, joining the rows `viewer-drawers.js` already moves down |

Desktop is unchanged either way — `.cp-history` at 194px, `.cp-stage` at 278px,
the same numbers as the `after` shot above.

The phone position is now its own committed harness state
(`serve-viewer-history` · `mobile`), so the two positions are diffed
separately and the desktop baseline is no longer the only thing watching a
runtime decision that has two answers.

```
cd vscode-extension
npm run harness:snapshot     # 158 passed
```
