# Multi-touch drawing canvas

`drawing-canvas-gestures.gif` is a recorded ~1.5 s session against
`MultiTouchDrawingPreview` showing all three of its gestures:

| Phase   | What's dispatched                                   | What the canvas does               |
|---------|-----------------------------------------------------|------------------------------------|
| Tap     | single `pointerDown` + `pointerUp`, no travel       | pink circle dropped at tap point   |
| Drag    | single pointer walks along a zigzag                 | polyline stroke committed on lift  |
| Pinch   | two pointers walk outward symmetrically             | canvas scales about its centre     |

Cyan rings under the synthetic fingers come from the
`LiveTouchOverlay` data extension (`overrides.touchOverlay = true`).
Lining up the rings with the resulting circle / stroke / scale change
is the visual proof that the gesture pipeline is wired end-to-end —
not just the daemon's dispatch but the composition's reception.

## How this was captured

```
./gradlew :daemon:desktop:test \
  --tests "ee.schimke.composeai.daemon.TouchOverlayDrawingCanvasGesturesRecordingTest"
# → build/touch-overlay-artifacts/drawing-canvas-gestures.gif
```

The test scripts a tap (0–66 ms) → drag (330–660 ms) → pinch
(924–1452 ms) timeline through `DesktopRecordingSession.postScript`,
encodes the captured frames as both APNG (primary) and GIF (this
artifact), and asserts that:

1. At least one mid-script frame contains cyan overlay pixels (the
   touch overlay actually painted).
2. The post-tap frame contains pink-circle pixels (tap reached the FSM
   and committed a circle).
3. The final frame has more dark-stroke pixels than the post-tap
   frame (drag committed a stroke).

The recording-test fixture (`MultiGestureCanvasFixture`) inlines the
canvas shape so the test doesn't need a cross-module classpath onto
`:samples:cmp` — same convention as `PinchableSquare` /
`DrawingCanvasFixture` for the other touch-overlay tests.

## Source

- Public sample: [`samples/cmp/.../MultiTouchDrawingPreview.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/MultiTouchDrawingPreview.kt)
- Recording test: [`daemon/desktop/.../TouchOverlayDrawingCanvasGesturesRecordingTest.kt`](../../daemon/desktop/src/test/kotlin/ee/schimke/composeai/daemon/TouchOverlayDrawingCanvasGesturesRecordingTest.kt)
- Touch overlay: `data/touch-overlay/`
