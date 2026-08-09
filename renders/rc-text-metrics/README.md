# Text-metric guide lines across three player lanes — issue #3595

Remote Compose documents that measure their own text with `TextMeasure` and draw the answer as guide
lines, so each lane renders *its own* metrics. Design notes:
[`docs/design/RC_TEXT_METRICS.md`](../../docs/design/RC_TEXT_METRICS.md).

Blue is the **font** box (typographic ascent/descent), green is the **ink** box, magenta is the
**advance**, red is the baseline. Each value is printed by the player itself via `TextFromFloat`, so
the image carries the numbers without a sidecar.

## The metric card

![text-metrics-card on java, cmp-android and cmp-jvm](card-three-lanes.png)

Two readings, immediately:

- **`TextMeasure` writes nothing on the two embedded lanes.** Every guide on `cmp-android` and
  `cmp-jvm` reads `0.0` and every rule collapses onto the origin. The vendored embedded player's
  canvas-operation walker has branches for `DrawTextAnchored` and friends and none for
  `TextMeasure`.
- **The same 48px string is 12.8% wider on `cmp-jvm`.** Measured ink extents: `java` 92..623 (532px),
  `cmp-android` 92..623 (532px), `cmp-jvm` 93..692 (600px). The two Android-backed lanes agree to the
  pixel; the Skiko lane does not. No font is pinned in these fixtures, so this points at typeface
  resolution rather than at layout — which is the useful kind of answer.

## The weight sweep

![text-metrics-weight-sweep on java, cmp-android and cmp-jvm](weight-sweep-three-lanes.png)

Each row reports **two** numbers: the advance (magenta) and the ink width (green), measured off
different code paths. On `java`: 361.0 / 358.0 at wght 400, then 362.0 / 359.0 at 500, 550, 599 and
700 alike — while 700 is plainly heavier than 500 in the same image.

Equal advances alone would prove nothing; families are routinely drawn duplexed, keeping advances
fixed across weights on purpose, which is why each row carries a second number off a different code
path. Both flat while the glyphs visibly differ means 550 and 599 are **metrically
indistinguishable** from 500 here — nothing downstream of layout can tell them apart.

It does *not* establish that the weight was synthesised rather than resolved: `getTextBounds` reports
the outer rectangle, so a real bold that thickens its stems inward, preserving the advance and the
extrema, gives the same signature. Telling those apart needs the resolved face, which this harness
cannot yet report. The ink box is integer-quantised too, so read it as corroboration rather than as a
precise instrument.

## Start and end alignment, in both directions

![align start/end, LTR and RTL, on three lanes](alignment-ltr-vs-rtl-three-lanes.png)

`ALIGN_START` and `ALIGN_END` are the only alignments whose meaning depends on direction, and on
English text they land exactly where `ALIGN_LEFT` and `ALIGN_RIGHT` do — so an LTR-only matrix cannot
tell a correct lane from one that hard-coded start→left. Against a Hebrew paragraph, all three lanes
keep start at the left edge and end at the right.

That makes the question askable, not answered. `CoreText` carries **no layout direction** — AOSP
derives its only flags word from `textAlign >>> 16` — so a document cannot state that its container
is RTL, and all three harnesses run their default LTR one. Resolving `START` against an LTR container
and resolving it to `LEFT` outright look identical here. Separating them needs a harness running a
lane under an RTL layout direction.

## The layout-tree modes

![single-line ellipsis on three lanes](layout-single-ellipsis-three-lanes.png)

![three-line ellipsis on three lanes](layout-wrap-ellipsis-three-lanes.png)

The dark rectangle is the box the text was handed; the magenta rule is where the *player's* own
measurement says a single unwrapped line of the same string ends. On the single-line fixture the
advance (316px) lands just outside the 300px box, which is why it ellipsised; on the wrapping fixture
the one-line advance is 1098px and runs off the frame.

## Regenerating

```bash
scripts/rc-text-metrics/render-strips.sh
```

That builds the fixtures, renders all three lanes, and rewrites the five `*-three-lanes.png` files
in this directory — the images above, not just the per-fixture PNGs. It is one script rather than a
block of commands to copy because the underlying invocation has **three** ways to look like it
worked while producing nothing or something stale, and the strips are what the design doc argues
from:

- `rc.embedded.input` must be an **absolute** path. The harness resolves it against the *test*
  working directory, not the repo root, and an unresolvable path fails an `assumeTrue` — which
  Gradle reports as a skipped test inside a green build. A relative path prints `BUILD SUCCESSFUL`
  and writes no PNGs whatsoever.
- `--rerun` (a *task* option, so it follows the task name) is needed because the fixture directory
  arrives as a system property, not as a declared task input. Without it a second run is
  `UP-TO-DATE` and quietly leaves the previous run's PNGs in place.
- `--tests` is needed because `rc.embedded.input` reaches **every** test in the module, not just the
  render harnesses. Unfiltered, `RcSemanticsExtractionTest` and `RcFigmaSvgExportTest` pick the
  first staged document up as if it were a catalog capture and fail against it — after the PNGs have
  already been written, so the regeneration looks broken when it isn't.

The script fails loudly if a lane produced no renders, since a missing lane would otherwise compose
into a narrower strip that still looks like a picture of three lanes.

Pass a directory to keep the per-lane PNGs somewhere predictable
(`scripts/rc-text-metrics/render-strips.sh /tmp/rc-metrics`); otherwise they go to a temporary
directory whose path is printed at the end. Which fixtures become strips is
[`compose_strips.py`](../../scripts/rc-text-metrics/compose_strips.py)'s `STRIPS` table — all 24
fixtures render, and only the five cited here are composed.
