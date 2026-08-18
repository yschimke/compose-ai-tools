# Named values no longer reset the document — render evidence for #4059

A 300 ms layout animation, started by a click, while a host writes a **new** named-float value on
every frame — a slider bound to a named value, which is the case #4059 is about. Frames are 75 ms
apart. The named value drives nothing the animation reads; changing it should be invisible to it.

Captured on the CMP desktop lane with a manual frame clock, on this branch ("after") and on its base
branch ("before"), with a harness differing only in how the values are handed over: a fresh
`Map<String, RcNamedValue>` per recomposition before, a `SnapshotStateMap` after.

| t | before | after |
|---|---|---|
| 75 ms | ![](before-0.png) | ![](after-0.png) |
| 150 ms | ![](before-1.png) | ![](after-1.png) |
| 225 ms | ![](before-2.png) | ![](after-2.png) |
| 300 ms | ![](before-3.png) | ![](after-3.png) |
| 375 ms | ![](before-4.png) | ![](after-4.png) |

**Before**, the red panel never grows. Each value write rebuilt `RcPlayerState`, which reset the
float a document action had set and discarded the running timeline, so the animation restarted from
zero on every frame and never got anywhere. **After**, it animates to completion while the same
writes land.

Only `before-0` and `after-0` match: at the first captured frame the animation has not yet had time
to diverge.
