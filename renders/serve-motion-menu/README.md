# The Motion lane: a menu for the captures, a transport for the frames

Two problems with one lane, both of them "the reader cannot tell what they are
looking at".

The **picker** was a segmented button group carrying the annotation's caption
verbatim. That reads fine for "Toggle on" and falls apart on the captions
catalogs actually publish — a line of instruction followed by a paragraph of what
to watch for. Two of those side by side is a block of text above the stage,
**wider than the render it introduces**, and the reader still has to compare two
near-identical paragraphs word by word to tell which button selects which
recording.

The **playback** was whatever the file said to do. Captures are written with an
infinite loop count, so a recording that toggles a switch on and then off runs
on → off → on → off with no seam: there is no telling a transition from its own
reverse, no stopping on the frame that matters, and no slowing a 300ms spring
down far enough to see its overshoot.

| file | what it is |
| --- | --- |
| `picker-before-light.png` | the segmented group, both captions printed in full across the row, and a capture looping with no controls |
| `menu-after-light.png` | the same two captures as a menu — one brief title — with the picked capture's caption beside it |
| `menu-second-capture-light.png` | the other capture picked: title and detail line both follow the selection |
| `transport-ended-light.png` | the pass finished: playhead at the end, timeline full, "0.5s / 0.5s · frame 14/14", and the play button offering it again |
| `transport-scrubbed-light.png` | paused mid-capture on a chosen frame — the switch caught mid-travel, the bar half filled, "0.2s / 0.5s · frame 7/14" |
| `picker-before-dark.png` / `menu-after-dark.png` / `transport-scrubbed-dark.png` | the same, dark |

Every shot is a full capture from the preview harness's own path —
`serve-viewer-motion` — whose states now cover the lane opening, the other
capture being picked, and a scrub to a frame. The **before** pair is HEAD's
markup with this branch's fixture captions patched in, because the fixture used
to carry two-word labels ("Toggle on", "Thumb settle") that made the old picker
look fine at every width and hid the reason it needed replacing.

## The picker: a menu and a detail line

Truncating the caption on the button would have cut the words out of the page
entirely — they are what names the property the recording exists to show — so
this splits them instead:

- The **menu** shows the caption's first clause, where it stops being a name and
  starts being an explanation (`MotionCaptureLabels`, unit-tested on its own).
  Closed, it is one title at one width however many captures a preview
  published.
- The **detail line** prints that caption in full, for the picked capture only.
  One at a time is what makes a whole row affordable to spend on it; N at once
  was the problem.

Unlike the renderer combo beside it, this is a **state field**, not a command
menu: nothing else on the page names which recording is playing, so the control
has to keep showing it rather than returning to a placeholder.

## The transport: the viewer drives playback now

An animated `<img>` exposes no frames, so none of what was asked for was
reachable through it. The lane now fetches the capture, decodes it with
`ImageDecoder` (WebCodecs) and paints frame N onto a canvas on its own clock:

- **plays once and stops on the last frame** — the resting state the interaction
  ended in, which is the answer to "what did that do?";
- **↻ replays** from the top, and pressing play from the end does the same;
- **a timeline bar** whose fill is the playhead — one `<input type=range>`, so
  ← / → step a frame and Home / End jump the ends, from the platform rather than
  from a hand-rolled pointer handler;
- **scrubbing pauses**, because dragging the timeline is an act of inspection;
- **0.25× / 0.5× / 1× / 2×**, changed mid-pass without moving the playhead.

Measured against a real published capture rather than the harness stub
(`m3-catalog/motion/togglebutton-filled__ideal__default__light.apng`: 163 frames,
249×126, 60fps, `num_plays = 0`): decode is 0.1ms median / 0.2ms p95 per frame
and a random seek is 0.1–0.2ms, so both playback and scrubbing are comfortably
realtime in Chromium.

Where `ImageDecoder` is missing or declines the file, the old `<img>` still gets
the capture — looping, uncontrollable, and better than nothing. The transport is
revealed only once a decode has actually succeeded, so that fallback is never
dressed with controls it cannot honour. A reader who asked for
`prefers-reduced-motion` gets the capture ready to play rather than playing.

The encoders were left alone: the published APNG still loops, which is right for
one embedded in a README where nobody is going to press anything, and the loop
count is not what this lane reads any more.

Two side-effects of cutting captions at the first clause, both handled: titles
that collide once cut ("Toggle repeatedly. Baseline swaps…" / "Toggle repeatedly.
Expressive travels…") are numbered in the menu, with the detail line showing what
differs; and a caption already short enough to be a title carries no detail at
all, so it prints once rather than twice beside itself.

```
./gradlew :cli:test --tests '*Serve*' --tests '*MotionCaptureLabelsTest*' \
  :cli:ktfmtCheck                                                           # green
cd cli/serve-web && npm run verify                                          # 835 passing
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot  # 133 passed
```
