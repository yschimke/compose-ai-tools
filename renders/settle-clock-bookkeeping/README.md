# Requested coordinates vs. the rounded clock

Evidence for [issue #4247](https://github.com/yschimke/compose-ai-tools/issues/4247), part 1.

`MainTestClock.advanceTimeBy` aligns to the frame duration and rounds **up** — it steps whole 16ms
frames until it has covered at least the requested amount. So the render loop works in two spaces
that do not coincide:

- the **requested** coordinate a capture asks for (`advanceTimeMillis`, or a `@SettledPreview`
  target), which is what the manifest wrote and what the ascending-order check reasons about;
- the **physical** clock, which lands on the next frame boundary at or after it.

Previously the loop measured each hop as the difference between two *requested* coordinates while
spending it on the *physical* clock. The rounding excess was therefore paid again at every hop
instead of being absorbed, and the error compounded across a fan-out.

The loop now keeps the two apart: the marker stays in requested space (so a job whose coordinate the
clock has already passed is simply owed nothing, rather than looking like time running backwards),
while each advance spends only what the physical clock still owes.

## The one render that moves

`SpinnerTimelinePreview` is the only sample that shows it, and it needs all three of its jobs —
`advanceTimeMillis = 0, 500, 1500`, none frame-aligned:

| job | requested | physical clock, before | physical clock, after |
| --- | --- | --- | --- |
| 1 | 0 | 0 | 0 |
| 2 | 500 | 512 | 512 |
| 3 | 1500 | 512 + 1008 = **1520** | 512 + 992 = **1504** |

The requested marker reads 0 / 500 / 1500 throughout, before and after — it is the *physical* column
that changes. The third capture was landing 20ms past the coordinate it asked for and now lands 4ms
past, the closest a 16ms frame clock can sit to 1500.

| Before — hop measured in requested space | After — hop measured against the clock |
| --- | --- |
| ![spinner at 1520ms](spinner-1500ms-before.png) | ![spinner at 1504ms](spinner-1500ms-after.png) |

The visible difference is the indeterminate arc one frame further along. Nothing about the component
changed — only which frame the shutter caught.

## What is deliberately unchanged

Internal advances — the `@FocusedPreview` settle window, the press settle, reduce-motion flips —
still sit *on top of* the requested timeline rather than consuming it. A capture that asks for its
own delta still gets that delta after them. Making those absorb into the requested space instead is
what broke `WearFocusedPressPixelTest`: the pressed state layer lost the time it needed to crossfade,
so the pressed capture stopped differing from the focused one.

`RedToBlueScrollTimedPreview` covers the other half — a non-frame-aligned timing on a preview that
also emits a scroll data product, so two jobs sit at the same coordinate and the second must be owed
nothing rather than rejected.
