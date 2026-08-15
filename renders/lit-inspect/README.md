# The inspection layers, unchanged by the port

Committed evidence for `inspect.js` → `<cp-inspect-layers>`.

**All four `serve-viewer-inspect` captures are byte-identical** between
`origin/main` (`fdd01dd`) and this branch — including the `layers` runtime
state, which is the one that actually draws the feature. So these are not a
before/after: there is no "before" to show, because nothing moved.

| file | what it is |
| --- | --- |
| `layers.light.png` | all three layers on: accessibility, typography, theme attributes |
| `layers.dark.png` | the same state in dark |

The capture is worth reading closely, because every rule the port extracted is
visible in it and each would be invisible if it were wrong:

- **Four accessibility entries, not seven.** The hierarchy behind this fixture
  has unmerged inner `Text` nodes on exactly the pixels of their focusable
  ancestors. `merged` is *absent* on the wire when it is true — it is the Kotlin
  default — so `isFocusStop` tests `merged !== false`, not `!merged`. Read the
  other way, entries 1 and 2 each get a second rectangle drawn on top of
  themselves and a second legend row saying the same thing.
- **Entry 3 is red and says `SpeakableTextPresentCheck`.** It is an unlabelled
  `ImageView`, so its title falls back to `(unlabelled)` rather than rendering a
  blank row, and its badge takes the severity's colour instead of a palette hue —
  a per-node pastel there would compete with the failure it is reporting.
- **Entry 4 is a warning on `24×16 dp`.** Nothing else flagged that node: it is
  reachable, labelled, and named. It is just too small to hit. Left at `info` —
  which is what a plain "did any finding match?" would give it — it reads as a
  pass.
- **Entries 1 and 2 are different blues.** Un-flagged stops get
  `PALETTE[index % PALETTE.length]`; with one colour for everything, adjacent
  focus targets in a list merge into a block and the legend cannot be matched
  back to a box by eye.
- **Typography and Theme both have rows, from one fetch.** They are two layers
  over a single `annotations` payload — and on an override-bearing frame a second
  fetch is a second daemon render, which can come back describing different
  pixels than the first.
- **The sections read Accessibility → Typography → Theme.** That is the declared
  order, not the order the checkboxes were ticked in, so the panel does not
  shuffle under the reader.

## What the port changed that a capture cannot show

The rule that mattered most has nothing on screen here: a finding whose bounds
line up with no node in the hierarchy is still surfaced, as its own entry titled
from `viewDescription`. Dropping it — the obvious reading of "join findings onto
nodes" — reports the frame as clean on exactly the elements the hierarchy could
not describe. `inspect/entries.ts` has that as a test, alongside the case that
keeps it from double-reporting a finding that *did* land on its node.

The rest of the extraction is addressing, in `inspect/layers.ts`: the data URL is
derived from the frame **on screen** (`data-cp-src`) by swapping only the format
suffix, so the overlay describes the pixels the visitor is looking at — every
knob and display axis included — with no second copy of the viewer's query rules
to keep in step. A `scroll=long` frame has no inspection product of its own and
falls back to the viewport-sized one rather than 500ing. `?inspect=` round-trips
through `replaceState`, because ticking a layer is a reading aid over the same
frame, not a different render.

```
cd cli/serve-web && npm run verify   # 401 passing
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
./gradlew :cli:test --tests '*ServeWeb*' && ./gradlew ktfmtCheck
cd vscode-extension/preview-harness
HARNESS_FIXTURE=serve-viewer-inspect npx playwright test pages-snapshot.spec.mjs
# 10 passed on both refs; 4/4 PNGs byte-identical
```
