# Padding applied twice at density ≠ 1, CMP/Wasm player (#4749)

The companion to [`../padding-density/`](../padding-density/README.md), which recorded the same
defect in the embedded Android player (#4727), and the second half of the doubling
[`../rounded-clip-density-cmp/`](../rounded-clip-density-cmp/README.md) opened: on
`CardRemote_width_227dp_height_200dp_dpi_320` the CMP render came out 454 × 205 against `rc-baked`'s
454 × 158, and the corner half of that 47px gap was #4744.

`RcComposePlayer` ran a `PaddingModifierOperation`'s four edges through `dpTypedPixels`, multiplying
each by the display density under `DENSITY_BEHAVIOR_DP`. The edge is already in pixels by then, so
at density 2.0 a card was inset by its whole padding a second time.

`before.png` / `after.png` — `AppCardRemote-640x480` (the fixture committed at
`rc-player/compose/src/jvmTest/resources/rc-fixtures/`), rendered through `RcComposePlayer` at
`Density(2f)`, with and without the fix. Measuring the card's own ink down the centre column:

| render | card box | inset used | wanted |
| --- | --- | --- | --- |
| `before.png` | 640 × **219** | 48px ✗ | 24px |
| `after.png` | 640 × **171** | 24px ✓ | 24px |

48px of height — exactly `2 × 24`, the card's whole vertical padding — and the content moves in to
meet the corner the document asks for. Nothing else in the frame moves.

The measurement, off the same document's bytes: header `DENSITY_BEHAVIOR_DP`, generation density
2.0, and four literal `24f` padding edges beside the four literal `52f` clip corners of the same
26dp card. `remote-creation-compose` folds the density in at capture (`RemoteDp.toPx()`), so the
wire value already scales with density — the split #4731 recorded, and the same reason the corners
had to stop scaling.

`spacedBy` moved in the same pass, on its own measurement rather than by analogy: across the
published `design-artifacts/remote-m3` corpus at generation density 2.0, `WatchScreenRemote`'s
`RemoteArrangement.spacedBy(8.rdp)` is on the wire as `16` and `ButtonGroupRemote`'s 4dp gap as `8`
— the doubling #4731 recorded as "RemoteButtonGroup's 4dp gap rendering at 8dp". It carries no
render here because no committed fixture pairs a gap with a DP header; `RcCapturedPixelsDensityTest`
pins it instead.

Regenerate: render the fixture through `RcComposePlayer` at `Density(2f)` and encode the frame —
`RcCapturedPixelsDensityTest` builds the same scene, minus the PNG. No Android SDK needed; this
player renders on the JVM through Skiko.
