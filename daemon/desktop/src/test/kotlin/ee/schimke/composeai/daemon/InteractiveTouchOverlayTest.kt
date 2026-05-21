package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies that opening an interactive session with `overrides.touchOverlay = true` actually paints
 * the visualization rings over the live frames — the symmetric counterpart to
 * `TouchOverlayPinchRecordingTest` for recording sessions.
 *
 * Flow:
 * 1. Acquire an interactive session against [PinchableSquare] with `PreviewOverrides(touchOverlay =
 *    true)`. `DesktopHost.acquireInteractiveSession` runs the same `applyOverrides` pass recording
 *    uses, which installs the `TouchOverlayExtension` `AroundComposable` against the held
 *    composition.
 * 2. Dispatch a `POINTER_DOWN`, render, then assert the rendered frame contains cyan ring pixels
 *    (the overlay actually painted).
 * 3. Dispatch a `POINTER_UP` and render — overlay is allowed to fade but the press confirmed it was
 *    wired.
 *
 * Pre-existing behaviour (without overlay) is covered by `DesktopInteractiveSessionTest` —
 * verifying renders work, clicks flip state, `remember{}` survives. This test focuses solely on the
 * opt-in overlay enablement.
 */
class InteractiveTouchOverlayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun overlay_enabled_session_paints_cyan_ring_on_press() {
    val outputDir = tempFolder.newFolder("interactive-overlay-renders")
    val engine =
      RenderEngine(
        outputDir = outputDir,
        previewOverrideExtensions =
          PreviewOverrideExtensions(listOf(TouchOverlayPreviewOverrideExtension())),
      )
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
              outputBaseName = "interactive-overlay",
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
            InteractiveTouchOverlayTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        // Push a single pointer down at the centre. The TouchOverlayExtension paints a cyan ring at
        // every currently-pressed pointer; rendering immediately after the press samples that ring.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-overlay-stream",
            kind = InteractiveInputKind.POINTER_DOWN,
            pixelX = CANVAS_PX / 2,
            pixelY = CANVAS_PX / 2,
            pointerId = 1,
          )
        )
        val pressed = session.render(requestId = RenderHost.nextRequestId())
        val pressedImage = TouchOverlayTestSupport.readPng(File(pressed.pngPath!!))
        val cyanMatch =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            pressedImage,
            expectedRgb = 0x00BCD4,
            perChannelTolerance = 60,
          )
        assertTrue(
          "pressed frame must contain cyan overlay-ring pixels (interactive overlay enabled); " +
            "got ${"%.4f".format(cyanMatch * 100)}% — if 0, the overrides didn't reach " +
            "TouchOverlayExtension via `DesktopHost.acquireInteractiveSession.applyOverrides`",
          cyanMatch > 0.0005,
        )

        // Lift the finger so the composition returns to its un-pressed state; this confirms the
        // dispatch path stays usable end-to-end (overlay sessions still get standard pointer
        // dispatch, the AroundComposable only adds visualization on top).
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-overlay-stream",
            kind = InteractiveInputKind.POINTER_UP,
            pixelX = CANVAS_PX / 2,
            pixelY = CANVAS_PX / 2,
            pointerId = 1,
          )
        )
        session.render(requestId = RenderHost.nextRequestId())
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  companion object {
    private const val PINCH_PREVIEW_ID = "interactive-overlay-pinch"
    private const val CANVAS_PX = 240
  }
}
