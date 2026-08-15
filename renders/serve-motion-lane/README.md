# A preview's recorded interaction, on the component page but never by default

A component page could only ever show its component at rest. Whether the
component's own interaction plumbing actually drives its transition — and what
shape that transition has — is exactly what a still cannot answer.

| file | what it is |
| --- | --- |
| `chip-before.png` | the viewer's control row before: `Live preview · Transparent · Fit width` |
| `chip-after.png` | the same row with the **Motion** chip, which is the entire at-rest delta |
| `lane-open-light.png` | the lane open — chip pressed, per-capture picker revealed, `▶ Recording` on the badge, and the capture on the stage in place of the still |
| `lane-open-dark.png` | the same, dark |

The before/after pair is a crop rather than the whole page because the whole
page would bury the one thing that changed. Both open shots are full captures,
straight out of the preview harness's own path — `serve-viewer-motion`, whose
`motion-lane` state clicks the chip over a stubbed capture.

## Why it is a chip and not the frame

Most readers open a component page to look at the component, so a page that
starts animating at them is answering a question nobody asked.

It is also load-bearing for the bytes rather than a matter of taste: an APNG
begins playing the moment its `src` is assigned, so **assigning `src` is
starting playback**. The src is written on the lane's first entry and never at
page load, and leaving the lane drops it again — otherwise a hidden capture
keeps looping and decoding for the rest of the visit.

## What the lane had to tell the rest of the viewer

A recording is neither a render nor current, and four subsystems assumed
otherwise until they were told:

- the **backend badge** credited the still renderer for animated pixels;
- **render overrides** stayed live, re-rendering the hidden snapshot underneath
  while the recording went on saying something else;
- the **inspection layers** stayed drawn, placed from an image no longer on
  screen;
- a **pinned page** would have played today's capture beside an old render,
  which is the one thing a permalink must never do.

## Bounds

Published catalogs carry no captures yet (#3922), so the lane is exercised
against fixtures rather than live data and will show nothing on preview.coo.ee
until that is fixed. The harness stub plays once and rests, unlike a real
capture, because `page.screenshot({animations: "disabled"})` reaches CSS
animations and not an image's own — a looping stub flakes the baseline by the
full width of the switch's travel.

```
./gradlew :cli:test --tests '*ServeWebFixtureTest*' --tests '*ServeCatalogStoreTest*'   # green
cd cli/serve-web && npm run verify                                                      # 151 passing
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot \
  -g "serve-viewer(-motion)? · (light|dark)"                                            # 4 passed
```
