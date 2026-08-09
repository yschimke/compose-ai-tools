# Wear scaling-list item alpha — issue #3579 finding 3

**Render PNG (left) | exported SVG (right)**, 1:1, both composited on white. The preview is wear-m3's
`ScalingListSticker_Large Round`.

| file | the second card's background | pixel difference vs its own render |
| --- | --- | --- |
| `scaling-list-before.png` | full strength, while its label fades | 7.46% |
| `scaling-list-after.png` | fades with the label | 4.11% |

A `TransformingLazyColumn` item fills through `Modifier.paint` (Wear's `surface()`) and carries the
morph's alpha *behind* it in the chain. The export treated a trailing alpha as content-only — correct
for `Modifier.background`, which draws its rect before delegating — so each card's own fill stayed
opaque while its text faded.

That the fill really does fade is measured, not assumed: at the item whose alpha is 0.555 the render
draws its `#332E3C` card at `(28,25,33)`, i.e. `0.555 ×` the base colour.

The residual 4.11% is the text-metric gap (finding 4), which is untouched.

Regenerate with `compose-preview bundle pack --module :samples:design-catalog-wear-m3
--with-semantics`, then compare `build/compose-previews/data/<previewId>/compose-figma.svg` against
`build/compose-previews/renders/<previewId>.png`. Rasterise the SVG through `<object>`, not `<img>` —
`<img>` blocks the external `<image>` children a raster-fallback SVG carries.

**Measurement note.** Difference is the fraction of pixels differing by more than 8/255 in luma. Give
the headless rasterisation a generous virtual-time budget: at 10s two previews in this catalog came
back blank and scored ~70%, which is the harness timing out rather than a real difference.
