# Clock bookkeeping vs. frame rounding

Evidence for [issue #4247](https://github.com/yschimke/compose-ai-tools/issues/4247), part 1.

`MainTestClock.advanceTimeBy` aligns to the frame duration and rounds **up** — it steps whole
16ms frames until it has covered at least the requested amount. The render loop was recording the
*requested* `target` as the new clock position, so the marker said one thing and the clock sat
somewhere later. Across a multi-capture fan-out that error compounded instead of cancelling,
because each job computed its own delta from the fiction.

`SpinnerTimelinePreview` is the only sample that shows it, and it needs all three of its jobs to
do so — `advanceTimeMillis = 0, 500, 1500`, none of them frame-aligned:

| job | requested | clock, before this change | clock, after |
| --- | --- | --- | --- |
| 1 | 0 | 0 | 0 |
| 2 | 500 | 512 (recorded as 500) | 512 (recorded as 512) |
| 3 | 1500 | 512 + 1008 = **1520** | 512 + 992 = **1504** |

So the third capture was landing 20ms past the coordinate it asked for; it now lands 4ms past —
the closest a 16ms frame clock can sit to 1500. The other two captures are unaffected, and so is
every other preview in the sample module: 162 of 163 renders are byte-identical.

| Before — marker drifts | After — marker tracks the clock |
| --- | --- |
| ![spinner at 1520ms](spinner-1500ms-before.png) | ![spinner at 1504ms](spinner-1500ms-after.png) |

The visible difference is the indeterminate arc one frame further along. Nothing about the
component changed — only which frame the shutter caught.
