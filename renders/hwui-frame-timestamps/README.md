# hwui frame timestamps under a paused clock

Evidence for issue [#4578](https://github.com/yschimke/compose-ai-tools/issues/4578) — the
`SwitchButtonOn` interaction capture that diffed against itself on pushes that never touched it.

## The flake

Three renders of `:samples:design-catalog-wear-m3`'s `SwitchButtonOn` at one commit
(`8d8c989`, no source change between runs) produce three different `.apng`s:

```
0c3be6cb1cb60bc524db972c109394c3  run 1
52c39dae6e5361aa2d1dcd2320a836bf  run 2
fb17f1a79c86758b520515d2cd9780fe  run 3
```

28–31 of the 114 frames differ, by up to 51 per channel. Every differing frame sits inside one of
the two scripted press windows — frames 17–33 and 66–86 — and nowhere else
([`frame-deltas.txt`](frame-deltas.txt)). Frame 22, the first press, amplified ×10 in the third
column:

| main, run 1 | main, run 2 | difference ×10 |
| --- | --- | --- |
| ![](main-run1-frame22.png) | ![](main-run2-frame22.png) | ![](main-run1-vs-run2-frame22-x10.png) |

A uniform tint across the whole toggle surface at slightly different strength: Material's platform
ripple, caught at a different point of its animation by each run.

## Why

Robolectric 4.17-beta-3's `ShadowNativeHardwareRenderer` rewrites hwui's frame timestamps by
`System.nanoTime() - ShadowPausedSystemClock.uptimeNanos()` before each draw. Under a paused clock
that offset is "however long this JVM has been up", and it grows between frames — real time passes
while simulated time only moves when the render advances it. So `RippleDrawable`'s patterned enter
animation, which runs on the render thread through `RenderNodeAnimator`, is paced by host
wall-clock time rather than by the clock the capture controls. A still draws one frame and cannot
notice; a capture that samples a component mid-animation samples it somewhere new each run.

## The fix

[`ShadowPausedClockHardwareRenderer`](../../renderers/android/src/main/kotlin/ee/schimke/composeai/renderer/ShadowPausedClockHardwareRenderer.kt)
hands hwui the framework's own timestamps, which is what 4.17-beta-2 did. The daemon has registered
it since [#4159](https://github.com/yschimke/compose-ai-tools/issues/4159); this registers it for
the static render lane too.

Three renders with it registered:

```
f05a675580b60ca5d1ed975452340b8a  run 1
f05a675580b60ca5d1ed975452340b8a  run 2
f05a675580b60ca5d1ed975452340b8a  run 3
```

The ripple is still the patterned (AGSL) one a device draws — only its clock changed. Against a
main run, the fixed frame 22 differs by no more than two runs of main differ from each other:

| main, run 1 | with the fix | difference ×10 |
| --- | --- | --- |
| ![](main-run1-frame22.png) | ![](fixed-frame22.png) | ![](main-vs-fixed-frame22-x10.png) |

## Blast radius

A full `:samples:design-catalog-wear-m3` render, base vs. fix: **93 products, one changes** — this
`.apng`. Every still and every other motion product is byte-identical.

## Rejected alternative

Forcing the ripple onto the software `ValueAnimator` path — what `settlePressedRipple` does for
pressed *stills* — is equally deterministic here (three byte-identical runs, press amplitude 154 vs
155 peak against frame 0). It was rejected because it publishes the software ripple instead of the
patterned one a device draws, and this shadow gets the determinism without giving that up.
