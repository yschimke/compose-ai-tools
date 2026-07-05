# Raw-pixel corner radii in the figma-svg export

`RoundedCornerShape(20f)` (and any `PxCornerSize`) sets a corner radius in **raw pixels**, which the
dp-only `cornerRadius` token can't express — so the figma-svg export used to drop it and render a
**sharp** rectangle. Pixels are the export's native space, so there's no reason to lose them: the
capture now records the radius on a `cornerRadiusPx` token and the export rounds the corner with no
density round-trip.

| Before — px corner dropped (sharp) | After — `cornerRadiusPx` → rounded |
|---|---|
| ![before](raw-px-corner-before.png) | ![after](raw-px-corner-after.png) |

Both panels are the exported `compose/figma-svg` for the same filled box; the only difference is that
the raw-pixel corner now survives capture (`ModifierTokenResolver.cornerRadiusPxWire` reflects the
`PxCornerSize`) and maps straight to the layer's radii (`rx="20"`). The dp path is unchanged and still
wins when both are present; the dp-only token-compliance consumer ignores the new field.

## Cut corners (`CutCornerShape`)

`CutCornerShape` reports its corner size on `cornerRadius` (like a rounded shape) plus a
`shape="cut"` descriptor. The export used to ignore the descriptor and *round* the corner, so a
bevelled component rendered wrong. The renderer now draws straight chamfer segments (a `<path>` with
line commands, not arcs) when the shape is cut.

| Before — `shape="cut"` ignored (rounded) | After — chamfered |
|---|---|
| ![before](cut-corner-before.png) | ![after](cut-corner-after.png) |

Same corner size, same box; the only difference is that a cut corner now bevels instead of rounding
(`…H96 L120,24 V96 L96,120…` — straight cuts). Rounded and circle shapes are unchanged.
