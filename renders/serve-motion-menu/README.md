# The capture picker: a wall of prose, then a menu and a detail line

The Motion lane's per-capture picker was a segmented button group carrying the
annotation's caption verbatim. That reads fine for "Toggle on" and falls apart on
the captions catalogs actually publish — a line of instruction followed by a
paragraph of what to watch for. Two of those side by side is a block of text
above the stage, **wider than the render it introduces**, and the reader still
has to compare two near-identical paragraphs word by word to tell which button
selects which recording.

| file | what it is |
| --- | --- |
| `picker-before-light.png` | the segmented group, both captions printed in full across the row |
| `menu-after-light.png` | the same two captures as a menu — one brief title — with the picked capture's caption beside it |
| `menu-second-capture-light.png` | the other capture picked: the title and the detail line both follow the selection |
| `picker-before-dark.png` / `menu-after-dark.png` | the before/after pair, dark |

Every shot is a full capture from the preview harness's own path —
`serve-viewer-motion`, whose `motion-lane` state clicks the chip over a stubbed
capture, followed by a new `motion-second-capture` state that picks the other
recording. The **before** pair is HEAD's markup with this branch's fixture
captions patched in, because the fixture used to carry two-word labels ("Toggle
on", "Thumb settle") that made the old picker look fine at every width and hid
the reason it needed replacing. The fixture now carries prose, so the harness
keeps covering the case from here on — and the new state means a picker that
stops switching, or a readout that keeps describing the previous recording,
moves a baseline instead of passing quietly.

## Why a menu and not a shorter label

Truncating the caption on the button would have cut the words out of the page
entirely: they are what names the property the recording exists to show. So this
splits them rather than dropping them.

- The **menu** shows the caption's first clause — where it stops being a name and
  starts being an explanation (`MotionCaptureLabels`, unit-tested on its own).
  Closed, it is one title at one width however many captures a preview
  published.
- The **detail line** prints that caption in full, for the picked capture only.
  One at a time is what makes a whole row affordable to spend on it; N at once
  was the problem.

Unlike the renderer combo beside it, this is a **state field**, not a command
menu: nothing else on the page names which recording is playing, so the control
has to keep showing it rather than returning to a placeholder.

Two side-effects of cutting at the first clause, both handled: captions that
collide once cut ("Toggle repeatedly. Baseline swaps…" / "Toggle repeatedly.
Expressive travels…") are numbered in the menu, and the detail line is where the
difference actually shows; and a caption already short enough to be a title
carries no detail at all, so it prints once rather than twice beside itself.

```
./gradlew :cli:test --tests '*MotionCaptureLabelsTest*' --tests '*ServeWebFixtureTest*' \
  --tests '*ServeComponentBrowserTest*' --tests '*ServeTopLevelSiteTest*'   # green
cd cli/serve-web && npm run verify                                          # 815 passing
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot \
  -g "serve-viewer-motion"                                                  # 2 passed
```
