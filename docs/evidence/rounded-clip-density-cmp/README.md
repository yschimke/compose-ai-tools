# Rounded clip radius doubled at density ≠ 1, CMP/Wasm player (#4712)

The companion to [`../rounded-clip-density/`](../rounded-clip-density/README.md), which recorded the
same defect in the embedded Android player (#4710). `RcComposePlayer` ran a
`RoundedClipRectModifierOperation`'s four corners through `dpTypedPixels`, multiplying each by the
display density under `DENSITY_BEHAVIOR_DP`. The corner is already in pixels by then, so at density
2.0 a 26dp card corner clipped as 104px instead of 52px.

`before.png` / `after.png` — `AppCardRemote-640x480` (the fixture now committed at
`rc-player/compose/src/jvmTest/resources/rc-fixtures/`), rendered through `RcComposePlayer` at
`Density(2f)`, with and without the fix. The card goes from a lozenge to the 26dp corner the
document asks for; nothing else in the frame moves.

The measurement, off the same document's bytes: header `DENSITY_BEHAVIOR_DP`, generation density
2.0, and opcode 54 followed by four literal `52f` corners. `remote-creation-compose` folds the
density in at capture (`RemoteDp.toPx()`) and remote-core's `RoundedClipRectModifierOperation` never
rescales it, so the wire value already scales with density.

Regenerate: render the fixture through `RcComposePlayer` at `Density(2f)` and encode the frame —
`RcRoundedClipDensityTest` builds the same scene, minus the PNG. No Android SDK needed; this player
renders on the JVM through Skiko.
