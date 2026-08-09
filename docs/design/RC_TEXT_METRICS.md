# Text metrics as guide lines, across the Remote Compose player lanes

A set of Remote Compose documents that **measure their own text and draw the answer as guide lines**,
so every player lane renders *its own* metrics. Issue
[#3595](https://github.com/yschimke/compose-ai-tools/issues/3595); split out of the text residual on
[#3579](https://github.com/yschimke/compose-ai-tools/issues/3579).

Companion to [`RC_PLAYER_TYPEFACES.md`](RC_PLAYER_TYPEFACES.md), which covers *which face* a lane
resolves. This document is about *how that face is laid out once chosen*. They interact — a
substituted face has different metrics, so a resolution bug masquerades as a metrics bug — so read
them together.

## Why

Text has been the dominant residual on nearly every preview that carries any, and every round of
chasing it has ended at a pixel percentage: wear-m3 renders "heavier", compose-m3 renders "narrower".
Both are true and neither is actionable. A pixel diff cannot separate a substituted face from a wrong
weight instance from a wrong advance from a wrong ascent from leading placed on the other side of the
line — and those have completely different fixes.

So: stop diffing the glyphs, and diff the numbers the glyphs were laid out with.

## How it works

No player needed teaching. The mechanism is three facts that were already true:

1. `TextMeasure` (opcode 155) measures the current paint's text and writes **one number into a float
   id**. It is marked implemented on every lane.
2. A float id is a legal **draw coordinate** — `DrawLine` takes NaN-boxed references like any other
   operation.
3. Therefore a line drawn *at* that float is the lane's own measurement, rendered by the lane itself,
   in the same frame, with nothing to reconcile afterwards.

The fixtures translate the canvas to the text origin before drawing, which keeps the whole thing
arithmetic-free: `getTextBounds` reports origin-relative horizontals and baseline-relative verticals,
so after the translate every measured value *is* the coordinate to draw at. No float expression sits
between the measurement and the line, so there is no question about evaluation order.

The measured values are also printed into the image with `TextFromFloat`. That is deliberately not a
JSON sidecar: a number rendered by the player reaches all five lanes — including the two that run in
a browser — with no harness change at all.

### The metric vocabulary

`TextMeasure`'s `type` word is a selector in the low byte plus flags above it. The authority is
`AndroidPaintContext.getTextBounds` in `remote-player-core`, whose entire behaviour is:

```
left   = flags&4 ? 0            : rect.left
right  = flags&4 ? measureText  : flags&1 ? measureText - rect.left : rect.right
top    = flags&2 ? round(fontMetrics.ascent)  : rect.top
bottom = flags&2 ? round(fontMetrics.descent) : rect.bottom
```

`rect` is `Paint.getTextBounds` — the **ink** box; `fontMetrics` is the **font** box. Two of the
three flags are named upstream (`MEASURE_MONOSPACE_FLAG` = 0x100, `MEASURE_MAX_HEIGHT_FLAG` = 0x200);
the advance flag (0x400) is not, though both the AOSP context above and this repo's CMP player read
it.

`RcTextGuide` turns that into nine guides. Blue is the font box, green is the ink box, magenta is the
advance — and the colours are a contract, not decoration, because the images are read side by side.

Cap height and x-height have no selector of their own and need none: the ink top of `H` **is** the
cap height and the ink top of `x` **is** the x-height, measured by the lane with the same paint. Two
extra strings buy two more metrics, and the values are the lane's own rather than a font table this
repo read and asserted at it.

### The two text paths

RC measures text in two unrelated places, and this is the fault line most likely to explain lane
disagreement. Canvas-drawn text (`DrawTextRun`) is measured by the *player*, and is the only path
`TextMeasure` can see. Layout-tree text (`CoreText`) hands the string to the host stack, which owns
measurement, shaping, wrapping and ellipsis and opens no seam to ask.

Both are covered for the same string and style: the mode fixtures lay text out through `CoreText`
inside a box of known size, and draw over it the advance the *player* measured for the same string on
the canvas path. Where the host stack actually broke, clipped or ellipsised the line, against where
the player thinks the line ends, is then a picture rather than an adjective.

## The fixtures

`./gradlew :rc-player-metrics:rcTextMetricFixtures` writes `<id>.rc` plus `manifest.json` and
`fixtures.json` to `rc-player/metrics/build/fixtures`.

| fixture | what it answers |
| --- | --- |
| `text-metrics-card` | Every metric of one 48px canvas specimen, drawn and labelled. |
| `text-metrics-weight-{400,500,550,599,700}` | Advance **and** ink width, per requested weight. |
| `text-metrics-weight-sweep` | The same five, stacked, for reading rather than diffing. |
| `text-metrics-layout-single-*` | `maxLines = 1` against clip / visible / all three ellipses. |
| `text-metrics-layout-wrap-*` | `maxLines = 3` against clip / ellipsis / justify. |
| `text-metrics-layout-align-*` | All six `CoreText` alignments, plus start/end again in RTL. |
| `text-metrics-layout-line-height-*` | Leading, as an add and as a multiplier (properties 13/14). |

The manifest shape is not new: it is exactly what `rc-compare --stage-embedded` produces and what
`RcViewPlayerRenderHarness`, `RcEmbeddedRenderHarness` and `RcJvmRenderHarness` already read, so the
fixtures reach three lanes with no harness change:

```bash
./gradlew :rc-player-metrics:rcTextMetricFixtures
./gradlew :third-party-rc-embedded-player:testDebugUnitTest --rerun \
  -Prc.embedded.input=rc-player/metrics/build/fixtures \
  -Prc.view.output=/tmp/rc-metrics/java \
  -Prc.embedded.output=/tmp/rc-metrics/cmp-android
./gradlew :third-party-rc-embedded-player-jvm:test --rerun \
  -Prc.jvm.input=rc-player/metrics/build/fixtures \
  -Prc.jvm.output=/tmp/rc-metrics/cmp-jvm
```

The two browser lanes (`js`, `cmp-wasm`) are not wired yet — see [Not done yet](#not-done-yet).

## What authoring these documents cost

Four traps, all of which produce a *plausible-looking* frame rather than an error, and all of which
would read as a text bug if you met them from the other end. They are pinned by tests in
`RcTextMetricDocumentsTest` so nobody has to rediscover them.

- **A layout root must be followed directly by its component.** Putting a `LayoutContent` between
  `RootLayoutComponent` and the first component makes the AOSP view player build a tree it then never
  paints. Background modifiers still land, so the frame looks right and simply has no text in it.
  (`RcProfileDocuments` has this shape; its documents are only ever played by the CMP player, where
  it is harmless.)
- **`CoreText` (239) needs the profiled header.** AndroidX keeps several operation registries, and
  header property 27 decides which are installed. With a legacy header the reader raises `Unknown
  operation encountered 239` and **abandons the rest of the buffer** — so the document renders
  truncated, not merely plainer. Every fixture therefore carries the header shape the connector emits
  for real previews.
- **`PaddingModifier` is dp; `WidthModifier(EXACT)` is px.** An inset authored as 80 becomes 160px on
  the xhdpi harness and 80 in a density-1 lane. The box moves, an overlay drawn at the authored
  coordinate does not, and the guides end up measuring the fixture's own bug. The mode fixtures place
  their box at the frame's top-left corner instead — the one origin that means the same thing on
  every lane.
- **A text component must be told to fill its box.** Left to its intrinsic width it is exactly as wide
  as its text, and an alignment inside a component that tight is a no-op: all six alignment fixtures
  render identically and look like six lanes agreeing.

## First readings

From the three server-side lanes, at `xhdpi`, with no embedded font (so each lane resolves its own
default face). These are observations from this harness in this environment, not verdicts about the
players.

**`TextMeasure` writes nothing on the two embedded lanes.** Every guide on `cmp-android` and
`cmp-jvm` reads `0.0` and every rule collapses onto the origin, while `java` reports a full set. The
vendored embedded player's canvas-operation walker has explicit branches for `DrawTextAnchored` and
friends and none for `TextMeasure`, which matches. This is the first thing to fix — until it is, the
guide lines only exist on the reference lane, and `rc:measureText` is unavailable to anything else
that might want it.

**The same string is 12.8% wider on `cmp-jvm`.** Measured ink extents of the 48px specimen:
`java` 92..623 (532px), `cmp-android` 92..623 (532px), `cmp-jvm` 93..692 (600px). The Android-backed
lanes agree to the pixel; the Skiko lane does not. With no font pinned this is far more likely to be
a different *face* than a metrics fault — which is exactly the useful outcome: it points at
[`RC_PLAYER_TYPEFACES.md`](RC_PLAYER_TYPEFACES.md) rather than at layout, and it is a place to look
rather than a percentage.

**Weight moves neither number on `java`, but does move the glyphs.** The sweep reports advance
361.0 / ink 358.0 at wght 400, then 362.0 / 359.0 at 500, 550, 599 and 700 alike — while 700 is
plainly heavier than 500 in the same image.

Read that carefully, because the obvious reading is wrong. Equal advances **do not** prove a reused
face: families are routinely drawn duplexed, keeping advances fixed across weights on purpose. That
is why every row reports a second, independent number off a different code path
(`Paint.getTextBounds` rather than `measureText`). The pair plus the glyphs is a signature:

| advance | ink width | glyphs | reading |
| --- | --- | --- | --- |
| moves | moves | differ | the weight reached a metric-distinct instance |
| flat | flat | identical | the weight changed nothing at all |
| flat | flat | differ | the weight is **synthesised**, not resolved to a face |

The reference lane lands on the third row, which is a much more specific answer than "550 and 599
look the same": in the Robolectric sandbox there is no `/system/fonts/`, so the platform default is
being emboldened rather than swapped. The variable-font question from #3579 now has a measurement
attached instead of an inference from two identical file sizes; answering it properly needs the same
sweep with a real variable face pinned. Note the ink box is integer-quantised, so it is corroboration
rather than a precise instrument.

**`ALIGN_START` and `ALIGN_END` do not follow paragraph direction on any of the three lanes.** They
are the only two alignments whose meaning is direction-dependent, and on English text they land
exactly where `ALIGN_LEFT` and `ALIGN_RIGHT` do — so a matrix built only from LTR text cannot tell a
correct lane from one that hard-coded start→left. Drawing the pair a second time against a Hebrew
paragraph shows start still at the left edge and end still at the right, identically on `java`,
`cmp-android` and `cmp-jvm`. The fixtures carry no explicit layout direction, so the expected
behaviour is the content-derived one both stacks normally implement (Compose's `TextDirection.Content`,
Android's `ALIGN_NORMAL` against an RTL paragraph) — which makes this worth chasing rather than
dismissing as unspecified.

**`maxLines = 3` rendered four lines on `java`**, while `maxLines = 1` was honoured exactly. Recorded
as an observation, not a diagnosis — it wants checking across lanes before anyone calls it a bug,
which is what the fixture is for.

## Not done yet

- **The browser lanes.** `js` and `cmp-wasm` render from a catalog bundle through `rc-compare`, not
  from a staged fixture directory, so wiring these in means teaching that driver about a fixture
  source. Until then the JS lane's own `getTextBounds` is worth knowing about: it always reports
  `left = 0` and `right = advance`, so `ink L` / `ink R` there are not the ink box at all.
- **A pinned face.** The fixtures use the lane's default family, which leaves every reading entangled
  with typeface resolution. Embedding the face as `FontData` fixes that for `java` and `cmp-wasm`;
  the JS lane doesn't have opcode 189 in its registry and truncates the document rather than
  substituting, so it needs either that decoder or a lane-specific variant.
- **A machine-readable metric dump.** The numbers are currently rendered into the image. A per-lane
  JSON table would let the comparison be asserted rather than read, which is what turns this from a
  diagnostic into a gate.
- **An explicit layout direction.** The RTL alignment fixtures rely on the paragraph direction being
  derived from content. A variant that states the direction outright would separate "the lane ignores
  content direction" from "the lane ignores direction entirely".
- **Font-variation axes.** Weight travels as the paint's typeface style, not as a `wght` axis,
  because the axis path is canvas-unimplemented on some lanes and a fixture meant to be comparable
  across all five must not use an operation two of them decline. An axis-carrying variant belongs
  next to the sweep once the lanes above are wired.
