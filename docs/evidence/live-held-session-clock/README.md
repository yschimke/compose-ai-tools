# Held-session clock evidence (#4282, and the #4159 measurement that rescoped it)

## `postdelayed-*.png` — what this fix changes

Two consecutive held renders of `PostDelayedSquare`, which flips red→green from a
`Handler.postDelayed` at 100 ms. Frame 1 covers the 32 ms bootstrap settle; frame 2
advances 200 ms.

- **before** — red, red. The looper clock never moved, so the callback never came due.
  A live preview ran no `Handler.postDelayed` work at all, however long it was open.
- **after** — red, green. Frame 1 is still correctly red: the clock is stepped in real
  time, not settled to its end state in one jump.

## `ripple-filmstrip-*.png` — the #4159 measurement

The same press filmed at two cadences, six frames each (at rest, then five renders).
Captured before `RippleOnlySquare` landed on main, so these frames are of a Material 3
filled button rather than that fixture; the timings are what matter and they are
reproducible against either.

- **50 ms** — the ripple is visible mid-expansion in frame 3, then settles.
- **250 ms**, the flat idle cadence `INTERACTIVE_FRAME_INTERVAL_MS` still falls back to —
  the ripple never appears in any frame. The animation completes between two ticks.

This is what corrected the diagnosis of #4159 from "the ripple is frozen" to "the live
loop samples it at most once", and it is the same gap #4274 closed from the other side by
running a burst cadence for 600 ms after an input. Regenerate with `AndroidRippleFrameTest`
(it writes its frames to `build/ripple-frames/`) with `STEP_MS` retimed.
