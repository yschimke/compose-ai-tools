# Embedded vs view player A/B — issue #3936 step 2

Full re-run after the `ColorAttribute` fix (#3977): all 164 `.rc` documents in the
`homeassistant-remotecompose` catalog, both lanes, same commit, same Robolectric harness
(`RcEmbeddedRenderHarness` and `RcViewPlayerRenderHarness`), so the document is a controlled
variable and every difference is the interpreter.

Scored with pixelmatch at `threshold: 0.1` and its default anti-aliasing detection — the same
library and settings `rc-compare.mjs` already uses, rather than a fresh ad-hoc metric.

| result | documents |
| --- | --- |
| no differing pixels | 16 |
| differing, under 1% of pixels | 86 |
| differing, 1–5% | 59 |
| differing, over 5% | 1 |
| **unrendered on the embedded lane** | **2** |

## What the differences actually are

**Text rasterization dominates and is not a defect.** The largest single result,
`WeatherForecast_Light` at 5.57%, is visually indistinguishable — Compose's Skia text stack and the
Android canvas hint and fill glyphs differently. `weather-forecast-text.png` is that document at
5.57%; the two panels read identically.

![weather forecast](weather-forecast-text.png)

An exact-inequality pixel count cannot separate this from a real defect, and an erosion filter
cannot either, because glyph strokes are several pixels wide and high contrast. That is why the
numbers above use pixelmatch's AA detection.

**Two real defects came out of the sweep**, both filed separately:

### Switch thumb renders square

![toggle thumb](toggle-thumb.png)

The view player slides a circular thumb; the embedded player draws a square one. The thumb carries
`RoundedClipRectModifierOperation [46][46] [46][46] [46][46] [46][46]` — radii by *variable
reference*, where `FloatExpression[46] = ([44] [45] min 2.0 /)` and `[44]`/`[45]` are
`ComponentValue`s of the track. The track's own clip uses literal radii (`28.875`) and is correct in
both lanes. The apparent rounding on the thumb's trailing edge is the track's clip, not the thumb's.

### An unresolvable image aborts the whole document

![picture entity](picture-entity-app-mode.png)

`PictureEntity_AppMode` (light and dark) throws `IllegalArgumentException: URI is not absolute` out
of `AndroidBitmapLoader.loadBitmap`, reached from `BitmapData.apply` during
`CoreDocument.initializeContext`. Both players fail to load the image — the view player renders its
own error card, the embedded player propagates the exception and renders nothing.

## Regenerating

```
./gradlew :third-party-rc-embedded-player:testDebugUnitTest \
  --tests '*RcEmbeddedRenderHarness*' --tests '*RcViewPlayerRenderHarness*' \
  -Prc.embedded.input=<dir of <id>.rc + manifest.json> \
  -Prc.embedded.output=<embedded dir> -Prc.view.output=<view dir>
```
