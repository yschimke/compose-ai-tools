package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
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
 * A held (live) Android session must compose the `previewOverride*` seeds its spec carries.
 *
 * `applyOverrides` already layered the live bag over the preview's own `@OverrideVariant` seed onto
 * `RenderSpec.overrides` (yschimke/wear-m3-catalog#33), but the held-rule `setContent` planned its
 * extension chain from a **synthetic** bag carrying only `touchOverlay` / `localeTag` — so
 * `PreviewOverridesOverrideExtension` was planned with no seed and every knob composed at its
 * author default. Enabling Live on `buttongroup__ideal__three` drew the two-button base state
 * instead of the variant's three (yschimke/wear-m3-catalog#83), and editing a knob in the viewer's
 * Live lane did nothing at all.
 *
 * The seeds cross the sandbox boundary as strings ([HeldNamedOverrides]); this is the test that the
 * whole path — encode, bridge, decode, plan, compose — actually lands on the pixels. It is the
 * Android counterpart of `:daemon:desktop`'s `OverrideIntegrationTest`, which covers the same
 * ground on a host with no sandbox to cross.
 */
class AndroidInteractiveNamedOverrideTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun seeded_knob_reaches_the_held_composition() {
    val outputDir = tempFolder.newFolder("interactive-named-override-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = AndroidInteractiveNamedOverrideTest::class.java.classLoader!!,
          overrides =
            PreviewOverrides(
              namedOverrides = mapOf("fill" to PreviewOverrideValue.ColorValue(SEEDED_FILL_HEX))
            ),
        )
      try {
        val frame = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("held render must produce a PNG path", frame.pngPath)
        val img = decode(File(frame.pngPath!!))

        val seeded = pixelMatchPct(img, expectedRgb = SEEDED_FILL_RGB)
        val authorDefault = pixelMatchPct(img, expectedRgb = DEFAULT_FILL_RGB)
        assertTrue(
          "the held composition must fill with the SEEDED colour, not the author default — " +
            "seeded ${"%.1f".format(seeded * 100)}%, default " +
            "${"%.1f".format(authorDefault * 100)}%. A default-coloured frame means the seed " +
            "never reached the sandbox's PreviewOverrideController (see HeldNamedOverrides).",
          seeded > 0.9 && authorDefault < 0.01,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    when (previewId) {
      PREVIEW_ID ->
        RenderSpec(
          previewId = PREVIEW_ID,
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "OverridableSquare",
          widthPx = FRAME_PX,
          heightPx = FRAME_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-named-override",
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
    const val PREVIEW_ID = "android-named-override-interactive"
    const val FRAME_PX = 64

    /** `RedFixturePreviews.OverridableSquare`'s author default (`Color(0xFFEF5350)`). */
    const val DEFAULT_FILL_RGB = 0xEF5350

    const val SEEDED_FILL_HEX = "#FF2196F3"
    const val SEEDED_FILL_RGB = 0x2196F3
  }
}
