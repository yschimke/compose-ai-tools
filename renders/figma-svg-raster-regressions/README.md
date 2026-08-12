# figma-svg-raster-regressions

`compose/figma-svg` exports of two fixtures, before and after the fix for the raster fallbacks that
#3685 and #3686 widened — `FigmaSvgWearPickerTest`'s picker and `FigmaSvgVectorIconRenderTest`'s
`Icon`, straight out of `renderers/android`'s test outputs.

**These are annotated overlays, not the literal exports.** The regression does not change a single
pixel: an opaque frame crop of an icon looks exactly like the icon. What it changes is whether the
SVG a designer opens contains editable geometry or a flattened bitmap — so the overlay stylesheet
outlines every `<image>` in red and paints every `<text>`/`<path>` green, which is the only way to
see the difference in a picture. Rendered by loading each SVG in headless Chromium with that
stylesheet; the raster sidecars are inlined as data URIs so the crops themselves still show.

- `wear-before.png` / `wear-after.png` — three raster crops become two. The extra one is the `:`
  separator, a `Text` under `clearAndSetSemantics`: the export cropped it from the frame *and*
  emitted it as live text, shipping the same glyph twice.
- `icon-before.png` / `icon-after.png` — one opaque `<image>` becomes one editable `<path>`.

To regenerate: run `:renderer-android:testDebugUnitTest --tests '*FigmaSvgWearPickerTest*' --tests
'*FigmaSvgVectorIconRenderTest*'` and read `renderers/android/build/figma-svg-wear-picker/` and
`renderers/android/build/figma-svg-vector-icon/`. Pass `--rerun-tasks`: with the task up to date
Gradle leaves the previous run's SVGs in place, which is an easy way to compare a build against
itself and conclude nothing changed.
