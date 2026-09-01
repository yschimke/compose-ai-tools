package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FocusOverride
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

/** Pixel-level regression for #4937: focused variants must remain focused in Android Live mode. */
class AndroidInteractiveFocusOverrideTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun focus_override_reaches_the_held_composition() {
    val outputDir = tempFolder.newFolder("interactive-focus-override-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolvePreview)
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = AndroidInteractiveFocusOverrideTest::class.java.classLoader!!,
          overrides = PreviewOverrides(focus = FocusOverride(tabIndex = 0)),
        )
      try {
        val frame = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("held render must produce a PNG path", frame.pngPath)
        val img = decode(File(frame.pngPath!!))

        val focused = pixelMatchPct(img, expectedRgb = FOCUSED_FILL_RGB)
        val resting = pixelMatchPct(img, expectedRgb = RESTING_FILL_RGB)
        assertTrue(
          "the held composition must receive real focus — focused " +
            "${"%.1f".format(focused * 100)}%, resting ${"%.1f".format(resting * 100)}%",
          focused > 0.9 && resting < 0.01,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun resolvePreview(previewId: String): RenderSpec? =
    if (previewId == PREVIEW_ID) {
      RenderSpec(
        previewId = PREVIEW_ID,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "InteractionStateSquare",
        widthPx = FRAME_PX,
        heightPx = FRAME_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "interactive-focus-override",
      )
    } else null

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int = 16,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        if (
          abs(((rgb shr 16) and 0xFF) - expR) <= perChannelTolerance &&
            abs(((rgb shr 8) and 0xFF) - expG) <= perChannelTolerance &&
            abs((rgb and 0xFF) - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  private companion object {
    const val PREVIEW_ID = "android-focus-override-interactive"
    const val FRAME_PX = 64
    const val RESTING_FILL_RGB = 0xEF5350
    const val FOCUSED_FILL_RGB = 0xFFA726
  }
}
