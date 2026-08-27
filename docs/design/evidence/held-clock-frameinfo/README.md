# A held press animates on the simulated clock — before / after

Evidence for [#4549](https://github.com/yschimke/compose-ai-tools/issues/4549): from Robolectric
`4.17-beta-3`, `ShadowNativeHardwareRenderer.syncAndDrawFrame` rewrites every `FrameInfo` timestamp
into the host's monotonic clock domain before handing a frame to hwui. Under a paused clock that
offset is "however long this JVM has been up" and it grows between frames, so the native
render-thread animations Material's `RippleDrawable` runs (`RenderNodeAnimator`) end up paced by
wall-clock time while the held session advances only simulated time.

Captured by `AndroidRippleFrameTest` (`:daemon:android`), which presses and holds the centre of the
`RippleOnlySquare` fixture and then films eight frames, advancing the held clocks one frame budget
(50 ms) between each. The fixture's click handler is inert, so press feedback is the only thing that
can move a pixel. Re-collect with:

```
./gradlew :daemon:android:testDebugUnitTest --tests '*AndroidRippleFrameTest*' --rerun-tasks
# frames land in daemon/android/build/ripple-frames/
```

Both filmstrips below were shot on `robolectric = "4.17-beta-4"`. The only difference is whether
`ShadowPausedClockHardwareRenderer` is registered in the daemon sandbox.

## Before — the ripple is out of step with the clock

| rest | 50 ms | 150 ms | 250 ms | 400 ms |
| --- | --- | --- | --- | --- |
| ![rest](before-00-rest.png) | ![50ms](before-01-press-050ms.png) | ![150ms](before-03-press-150ms.png) | ![250ms](before-05-press-250ms.png) | ![400ms](before-08-press-400ms.png) |

The early frames are ~identical to rest and the animation is still visibly accelerating when the
window ends — frame-to-frame change runs `0.00% → 0.14% → 0.59% → 2.22% → 7.13% → 19.13% → 39.40% →
52.95%`. 400 ms of simulated time buys a fraction of the enter animation, and the test fails on both
"must animate across successive frames" and "must settle within 400ms".

## After — it enters and settles inside the filmed window

| rest | 50 ms | 150 ms | 250 ms | 400 ms |
| --- | --- | --- | --- | --- |
| ![rest](after-00-rest.png) | ![50ms](after-01-press-050ms.png) | ![150ms](after-03-press-150ms.png) | ![250ms](after-05-press-250ms.png) | ![400ms](after-08-press-400ms.png) |

With the frame timestamps left untranslated, the same press runs `0.00% → 92.80% → 78.55% → 94.66%
→ 90.21% → 4.43% → 0.00% → 0.00%`: motion across several frames, then convergence — the shape
`4.17-beta-2` produced, and the property both live-lane oracles assert.
