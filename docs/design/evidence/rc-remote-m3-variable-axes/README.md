# remote-m3 CMP/Wasm parity — the three font previews (#3469)

Baked reference | CMP/Wasm render | `pixelmatch` diff, for the three previews that were failing the
strict 1% CMP/Wasm gate on `design-artifacts → remote-m3`. All three are reproduced locally from
this repo at the same numbers CI reported on run
[31209547232](https://github.com/yschimke/compose-ai-tools/actions/runs/31209547232) — 1.08% / 1.82%
/ 2.80%, matching to two decimals — so these are the actual pixels the gate is scoring, not a
stand-in.

The baked reference is the **AOSP view player**: `RemoteSticker` captures through
`RemoteOverridablePreview`, whose `player` defaults to `RemoteComposePlayerKind.VIEW`.

## `VariableWidthRemote` — 2.80%

![baked, wasm, diff](variable-width-baked-wasm-diff.png)

The reference draws `wdth` 25 / 100 / 151 at **one** set width; the wasm player instances Roboto Flex
at each. The diff is the horizontal displacement that follows, on every glyph of every line — a
reference-lane gap, not a wasm fault. See
[`RC_PLAYER_TYPEFACES.md`](../../RC_PLAYER_TYPEFACES.md): the AOSP CoreText renderer does not route a
style-carried font-variation axis into the paint's variation settings.

## `VariableWeightRemote` — 1.82%

![baked, wasm, diff](variable-weight-baked-wasm-diff.png)

The same gap on the `wght` axis: four identical weights in the reference, a real 100 → 1000 ramp in
the wasm render. Smaller than the `wdth` case because weight thickens stems in place while width
moves whole runs.

## `TypefaceSpecimenRemote` — 1.08%

![baked, wasm, diff](typeface-specimen-baked-wasm-diff.png)

A different finding, and the one worth stating explicitly because the grouping invited the opposite
conclusion: this preview carries **no axes**, and both lanes resolve all four named families to the
same faces. Orbitron, Lobster Two, Space Grotesk and JetBrains Mono are each recognisably themselves
on both sides, at the same baselines and the same advances. The diff is a pure outline halo — glyph
*edges* only, with no displacement, no shape change and no substituted face anywhere in it. Contrast
the `wdth` diff above, where the text is visibly doubled and shifted. 1.08% is high for that class of
residual only because the preview is four lines of 22sp display text on an otherwise empty 640×480,
so glyph edges are nearly all of its ink.

## Reproducing

```
export ANDROID_HOME=…                                     # any SDK with platform 36
./gradlew :samples:design-catalog-remote-m3:composePreviewRenderAll   # baked PNGs + .rc sidecars
./gradlew :rc-player-wasm:wasmPlayerDist                              # the lane's player
```

then render each `.rc` sidecar through `rc-player/wasm/build/wasmDist` and `pixelmatch` it against
the baked PNG beside it, at `threshold: 0.1` and `deviceScaleFactor` = the preview's density —
`cmpWasmFor` in [`rc-compare.mjs`](../../../../scripts/design-artifacts/rc-compare.mjs) is the
authority on the details. That path needs no catalog bundle and no `bundle.png`, which is what makes
it a practical local check when the CI evidence artifact is out of reach.
