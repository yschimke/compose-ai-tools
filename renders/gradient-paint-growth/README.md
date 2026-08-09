# Gradient container pinned to its padded content rect — issue #3569

Pocket Casts' [`GradientRowButton`][src] is
`background(brush, RoundedCornerShape(12.dp)).clickable().padding(16.dp)` — the brush covers the
whole node, the padding insets only the label. The figma-svg export drew the gradient at the
node's placed (post-padding) `bounds` instead of growing it to the measured box, so the SVG showed
a pill floating inside the button the PNG paints edge to edge.

| file | how it was produced | gradient rect |
| --- | --- | --- |
| `before.png` | `FigmaLayeredSvg.render` over the button's layout payload, before the fix | `42,42 966×56` |
| `after.png` | the same render with the fix | `0,0 1050×140` |

Both are the export's own SVG output rasterised at 1:1 (the 16px margin around the frame is the
export's own). The payload is the one `FigmaSvgPaintInsetTest.gradientButton` models: a node placed
at `42,42 966×56` inside a `1050×140` parent, measured at `1050×140`, carrying a
`backgroundGradient` and a `12.0dp` corner — the shape the live catalog render has at density
2.625. `after` matches the catalog PNG at
`/pocketcasts/render/buttons-gradient-row-button__ideal__default__dark.png`.

Regenerate by rendering that payload through `FigmaLayeredSvg.render` and rasterising the SVG;
`FigmaSvgPaintInsetTest` asserts the same geometry without the images.

[src]: https://github.com/yschimke/pocket-casts-android/blob/previews/modules/services/compose/src/main/java/au/com/shiftyjelly/pocketcasts/compose/buttons/GradientRowButton.kt
