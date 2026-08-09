# figma-svg fidelity sweep — issues #3572, #3573

Each image is **render PNG (left) | exported SVG (right)** at 1:1, the SVG rasterised through
`<object>` so its external `<image>` children load. Both panels are composited on white; the export
is background-free by design, so its transparent margin reads as white.

| file | preview | pixel difference vs its own render |
| --- | --- | --- |
| `compact-button-before.png` | wear-m3 `CompactButton` | 24.6% |
| `compact-button-after.png` | same | 5.1% |
| `outlined-textfield-before.png` | compose-m3 `TextField/Outlined` | 3.6% |
| `outlined-textfield-after.png` | same | 0.1% |

`CompactButton` drew its pill at the 48dp touch target instead of the 32dp button; the residual
5.1% is the text-metric gap (the embedded faces are static subsets of a variable family), which is
untouched here. `TextField/Outlined` drew its value twice — once baked into the field's raster
fallback, once as live `<text>`.

Difference is the fraction of pixels differing by more than 8/255 in luma. The threshold matters:
at 32/255 the CompactButton pill — pale lavender on white — reads as only 3.7% and the defect is
invisible to the measurement.

The `before` panels are from `design-artifacts/{wear-m3,compose-m3}` as regenerated on 2026-08-08
from `e6eebcf9`. The `after` panels are from a local
`compose-preview bundle pack --module <catalog> --with-semantics`, which is the same export path
the delivery branches publish. Regenerate with that command and compare
`build/compose-previews/data/<previewId>/compose-figma.svg` against
`build/compose-previews/renders/<previewId>.png`.
