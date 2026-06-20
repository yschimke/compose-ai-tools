# Multi-touch drawing canvas

`drawing-canvas-gestures.gif` is a recorded ~3 s session against
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

One command, no daemon / MCP knowledge — drive the **already-compiled**
`MultiTouchDrawingPreview` with the committed [`session.json`](session.json):

```
compose-preview record \
  --module :samples:cmp \
  --preview com.example.samplecmp.MultiTouchDrawingPreviewKt.MultiTouchDrawingPreview \
  --script demos/multi-touch-drawing/session.json \
  --overrides touchOverlay=true \
  --out demos/multi-touch-drawing/drawing-canvas-gestures.gif
```

`session.json` is a JSON array of `RecordingScriptEvent` — the same
tap (0–132 ms) → drag (660–1320 ms) → pinch (1848–2904 ms) timeline the
recording test scripts, in image-natural pixels on the 240×240 canvas.
The `--out` extension selects the encoder (`.gif` here; `.apng`, `.mp4`,
`.webm` also work — `gif`/`apng` are pure-JVM, `mp4`/`webm` need
`ffmpeg`). Same script → same frames, every run.

### Reference recording test

The pixel-coverage regression test captures the same timeline and
asserts on the result:

```
./gradlew :daemon:desktop:test \
  --tests "ee.schimke.composeai.daemon.TouchOverlayDrawingCanvasGesturesRecordingTest"
# → build/touch-overlay-artifacts/drawing-canvas-gestures.gif
```

The test scripts the tap → drag → pinch timeline through
`DesktopRecordingSession.postScript`, encodes the captured frames as both
APNG (primary) and GIF (this artifact), and asserts that:

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
