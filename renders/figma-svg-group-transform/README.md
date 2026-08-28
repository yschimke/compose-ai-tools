# figma-svg-group-transform

The `compose/figma-svg` export of a **disabled `ElevatedButton` carrying an `ImageVector` icon whose
paths sit under a transforming `group`**, before and after teaching `VectorGraphicExtractor` to carry
a group's placement onto the paths instead of failing the icon to raster
(yschimke/m3-catalog#200).

Unlike `figma-svg-raster-regressions`, these are the literal exports — no annotation overlay is
needed, because here the fallback *is* visible. A raster fallback is cropped out of the composited
frame, so the icon's square carries the button's own container with it; the export then draws that
square on top of the `<rect>` it already emitted for the same container. An opaque container hides
the double-draw. A disabled button's 10%-black one does not.

- `disabled-button-before.png` — `<image href="figma-raster/12.png">`: a visibly darker square
  behind the glyph, and the glyph itself resampled from an 18px crop.
- `disabled-button-after.png` — `<path … transform="translate(2 2) scale(0.5 0.5)"
  fill="#49454F" fill-opacity="0.38">`: no square, and the star is resolution-independent.

To regenerate: run `:renderer-android:testDebugUnitTest --tests '*FigmaSvgVectorIconRenderTest*'
--rerun-tasks` and read `renderers/android/build/figma-svg-vector-icon/icon-disabled-button.svg`,
then load it in headless Chromium at `deviceScaleFactor: 6` over a `#FFFBFE` page. For the *before*
side the export also writes `figma-raster/` sidecars next to the SVG; inline them as data URIs first
or the `<image>` resolves to nothing.
