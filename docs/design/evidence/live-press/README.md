# A live click paints press feedback — before / after

Evidence for [wear-m3-catalog#32](https://github.com/yschimke/wear-m3-catalog/issues/32): a click
on the preview server's live lane showed no ripple, so the only thing that appeared to happen was
whatever the handler wrote.

Captured by `LivePressRippleTest` (`:daemon:android`), which drives one `interactive/input` click
into a held Robolectric session against the `RippleOnlySquare` fixture and renders the frames a
post-input burst would put on the wire. The fixture's handler is inert and it remembers no state,
so **press feedback is the only thing that can move a pixel** — identical frames are proof the
press never landed, not a threshold that was not met. Re-collect with:

```
COMPOSEAI_EVIDENCE_DIR=docs/design/evidence/live-press \
  ./gradlew :daemon:android:testDebugUnitTest --tests '*LivePressRippleTest*' --rerun-tasks
```

## Before — every frame is the resting frame

| resting | frame 1 | frame 2 |
| --- | --- | --- |
| ![resting](resting.png) | ![before frame 1](before-frame-1.png) | ![before frame 2](before-frame-2.png) |

The Android live lane dispatched a pixel click by invoking the smallest containing node's
`SemanticsActions.OnClick` lambda. That runs the handler and nothing else — no `PressInteraction`
reaches the component's interaction source — so all six frames come back byte-identical to the
resting one and the test reports `mean per-channel diffs: 0.00, 0.00, 0.00, 0.00, 0.00, 0.00`.

## After — the ripple is on the wire

| frame 0 | frame 1 | frame 2 | frame 3 | frame 4 | frame 5 |
| --- | --- | --- | --- | --- | --- |
| ![after frame 0](press-frame-0.png) | ![after frame 1](press-frame-1.png) | ![after frame 2](press-frame-2.png) | ![after frame 3](press-frame-3.png) | ![after frame 4](press-frame-4.png) | ![after frame 5](press-frame-5.png) |

Mean per-channel difference from the resting frame, 0–255:

| frame | 0 | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- | --- |
| before | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| after | 0.00 | 3.96 | 9.94 | 13.99 | 15.47 | 15.99 |

Three things the numbers say that the images alone do not:

- **Frame 0 is still the resting frame.** That is the render the input itself triggers, at t≈0,
  before the ripple has drawn anything — which is why sampling only on input was never going to
  show press feedback, and why the fix needs the burst as well as the real press.
- **The ripple grows across the burst rather than appearing whole.** Each held-session render
  advances the paused clock one frame, so the frames are the animation, not six copies of its end
  state. At the production idle cadence of 250ms the next frame after the click would have landed
  past all six of these.
- **The magnitude is a ripple, not encoder noise.** Material's pressed state layer is ~10% alpha,
  and this fixture ripples blue over red, so a real ripple lands in the double digits while a
  difference of zero stays exactly zero.

See [INTERACTIVE.md § 9.7.1](../../daemon/INTERACTIVE.md) for what changed: the click is injected
as a real down/up gesture (with a `waitForIdle()` between the halves, without which
`Modifier.clickable` drops the tap under the held session's paused clock), a click no longer
advances that clock 100ms past its own release, and every input runs its stream's frame loop at
~60fps for 600ms before falling back to the idle cadence.
