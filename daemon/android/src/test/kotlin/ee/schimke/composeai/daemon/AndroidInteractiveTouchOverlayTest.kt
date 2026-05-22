package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end touch-overlay verification on Robolectric — the Android counterpart of
 * `:daemon:desktop`'s `InteractiveTouchOverlayTest`.
 *
 * Pins the structural fix from this PR: the held-rule `setContent` now wraps
 * `InvokeHeldComposable` with `ComposeDataExtensionPipeline.Apply(extensions =
 * engine.previewOverrideExtensions.plan(...))`, so `TouchOverlayExtension`'s `AroundComposable`
 * actually paints the visualization rings on the held composition. Pre-fix, the extension was
 * registered on the engine and the override propagated correctly through #1317's plumbing, but
 * the held-rule `setContent` bypassed the extension chain entirely and the rings never appeared.
 *
 * The single-pointer `pointerDown` (no matching `pointerUp`) keeps `activePointers` non-empty so
 * the overlay paints a ring at the press point. The dispatch loop's existing 100 ms `mainClock`
 * advance after every dispatch (`RobolectricHost.kt:1882`) is what flushes the press through
 * Compose's pointer pipeline before the next render captures.
 */
class AndroidInteractiveTouchOverlayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun overlay_enabled_session_paints_cyan_ring_on_press() {
    val outputDir = tempFolder.newFolder("interactive-overlay-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = OVERLAY_PREVIEW_ID,
          classLoader = AndroidInteractiveTouchOverlayTest::class.java.classLoader!!,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "android-overlay-test-stream",
            kind = InteractiveInputKind.POINTER_DOWN,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
            pointerId = 1,
          )
        )
        val pressed = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("pressed render must produce a PNG path", pressed.pngPath)
        val pressedImg = decode(File(pressed.pngPath!!))
        val cyanMatch =
          pixelMatchPct(pressedImg, expectedRgb = 0x00BCD4, perChannelTolerance = 60)
        assertTrue(
          "pressed frame must contain cyan overlay-ring pixels (interactive overlay enabled); " +
            "got ${"%.4f".format(cyanMatch * 100)}% — if 0, either the override didn't reach " +
            "the held composition (this PR's `ComposeDataExtensionPipeline.Apply` wrap) or the " +
            "press wasn't observed by `Modifier.pointerInput` on the Initial pass",
          cyanMatch > 0.0005,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "android-overlay-test-stream",
            kind = InteractiveInputKind.POINTER_UP,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
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

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    when (previewId) {
      OVERLAY_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "ClickToggleSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-touch-overlay",
        )
      else -> null
    }
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    val bytes = file.readBytes()
    require(bytes.isNotEmpty()) { "capture is empty: ${file.absolutePath}" }
    return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  companion object {
    private const val OVERLAY_PREVIEW_ID = "android-touch-overlay-interactive"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
  }
}
