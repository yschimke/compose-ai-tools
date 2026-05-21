package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Multi-pointer interactive-dispatch test — verifies that `interactive/input` notifications
 * carrying `pointerId` route through `DesktopInteractiveSession`'s new multi-pointer path so an
 * external panel can drive pinch / two-finger rotate / multi-touch gestures live.
 *
 * Driver: drives [PinchableSquare] (the `Modifier.transformable {}`-backed blue square fixture
 * shared with [TouchOverlayPinchRecordingTest]) through:
 * 1. `pointerDown` id=1 near the upper-left
 * 2. `pointerDown` id=2 near the lower-right
 * 3. Three `pointerMove` pairs that walk both fingers outward toward opposite corners
 * 4. `pointerUp` for both fingers
 *
 * Each move dispatch packs both currently-pressed pointers into a single multi-pointer
 * `sendPointerEvent` (the gating signal `Modifier.transformable {}`'s zoom detector needs). The
 * second render's blue-pixel coverage must exceed the bootstrap render's — without multi-pointer
 * dispatch the two `pointerDown`s would arrive as independent single-pointer gestures and the
 * transformable callback would never fire, so the square wouldn't grow.
 *
 * **What this does NOT test** — overlay rings on interactive sessions. That requires plumbing
 * `PreviewOverrides` through `InteractiveStartParams` → `RenderHost.acquireInteractiveSession`,
 * which is the *next* slice. See the third commit on this branch.
 */
class DesktopInteractiveSessionMultiPointerTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun two_pointer_drag_visibly_zooms_the_transformable_square() {
    val outputDir = tempFolder.newFolder("interactive-multipointer-renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PINCH_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.TouchOverlayPinchRecordingTestKt",
              functionName = "PinchableSquare",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "interactive-pinch",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = PINCH_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionMultiPointerTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        // 0. Bootstrap render — establish the un-zoomed baseline blue coverage.
        val first = session.render(requestId = RenderHost.nextRequestId())
        val firstImage = TouchOverlayTestSupport.readPng(File(first.pngPath!!))
        val initialBlue =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            firstImage,
            expectedRgb = 0x1976D2,
            perChannelTolerance = 32,
          )

        val centre = CANVAS_PX / 2
        val startSpread = (CANVAS_PX * 0.15f).toInt() // fingers near centre
        val endSpread = (CANVAS_PX * 0.40f).toInt() // fingers near corners

        // 1+2. Two pointerDowns — id=1 upper-left of centre, id=2 lower-right of centre.
        session.dispatch(
          input(InteractiveInputKind.POINTER_DOWN, centre - startSpread, centre - startSpread, 1)
        )
        session.dispatch(
          input(InteractiveInputKind.POINTER_DOWN, centre + startSpread, centre + startSpread, 2)
        )

        // 3. Three move pairs walking both fingers symmetrically outward. We dispatch them in
        //    pairs (same logical timestamp from the recording-session perspective; here the
        //    multi-pointer event packs them naturally because they both live in `activePointers`).
        for (step in 1..3) {
          val spread = (startSpread + (endSpread - startSpread) * step.toFloat() / 3f).toInt()
          session.dispatch(
            input(InteractiveInputKind.POINTER_MOVE, centre - spread, centre - spread, 1)
          )
          session.dispatch(
            input(InteractiveInputKind.POINTER_MOVE, centre + spread, centre + spread, 2)
          )
        }

        // 4. Lift both fingers.
        session.dispatch(
          input(InteractiveInputKind.POINTER_UP, centre - endSpread, centre - endSpread, 1)
        )
        session.dispatch(
          input(InteractiveInputKind.POINTER_UP, centre + endSpread, centre + endSpread, 2)
        )

        // Render again — the held scene's `mutableStateOf` should have absorbed the zoom callback
        // updates so the blue square's now scaled up.
        val second = session.render(requestId = RenderHost.nextRequestId())
        val secondImage = TouchOverlayTestSupport.readPng(File(second.pngPath!!))
        val finalBlue =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            secondImage,
            expectedRgb = 0x1976D2,
            perChannelTolerance = 32,
          )

        assertTrue(
          "post-pinch blue coverage ($finalBlue) must exceed initial ($initialBlue) — " +
            "if equal, the multi-pointer dispatch didn't reach `Modifier.transformable`'s zoom " +
            "callback (most likely cause: each POINTER_DOWN arrived as an independent " +
            "single-pointer event)",
          finalBlue > initialBlue * 1.3,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun input(
    kind: InteractiveInputKind,
    pixelX: Int,
    pixelY: Int,
    pointerId: Int,
  ): InteractiveInputParams =
    InteractiveInputParams(
      frameStreamId = "test-multipointer-stream",
      kind = kind,
      pixelX = pixelX,
      pixelY = pixelY,
      pointerId = pointerId,
    )

  companion object {
    private const val PINCH_PREVIEW_ID = "interactive-pinch-square"
    private const val CANVAS_PX = 240
  }
}
