package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end verification that `renderNow.overrides` actually changes rendered pixels (PROTOCOL.md
 * § 5, INTERACTIVE.md § 8a). Drives [PreviewManifestRouter] directly with override-bearing
 * payloads so we exercise the same path `JsonRpcServer.encodeRenderPayload` produces — the
 * manifest router rewrites `previewId=…;widthPx=…;uiMode=…` into a full `RenderSpec` payload, and
 * the engine consumes the override-merged spec.
 *
 * This isn't a wire-level test (no JSON-RPC plumbing) — that's already covered by the protocol
 * round-trip in [`MessagesTest`][ee.schimke.composeai.daemon.protocol.MessagesTest]. What we add
 * here is the missing rung: confirm the override fields actually reach `setQualifiers` /
 * `setFontScale` / the `RenderSpec` dimensions, by rendering the same fixture twice with
 * different overrides and asserting the bytes differ in the expected way. Without this, a
 * refactor on either side of the router could silently break overrides while the unit tests stay
 * green.
 */
class OverrideIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun widthPxOverrideChangesRenderedDimensions() {
    val outputDir = tempFolder.newFolder("renders-width")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "red-square",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "red-square-default",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      // Default (manifest's 64×64).
      val small = renderAndDecode(host, "previewId=red-square", "small")
      assertEquals("manifest default width should be honoured", 64, small.width)
      assertEquals("manifest default height should be honoured", 64, small.height)

      // Override pushes width and height to 128.
      val large =
        renderAndDecode(
          host,
          "previewId=red-square;widthPx=128;heightPx=128",
          "large",
        )
      assertEquals("widthPx override should reach the RenderSpec", 128, large.width)
      assertEquals("heightPx override should reach the RenderSpec", 128, large.height)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun uiModeOverrideFlipsDarkAwareComposable() {
    val outputDir = tempFolder.newFolder("renders-uimode")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "dark-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "DarkAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "dark-aware",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val light =
        renderAndDecode(host, "previewId=dark-aware;uiMode=light", "uimode-light")
      val dark = renderAndDecode(host, "previewId=dark-aware;uiMode=dark", "uimode-dark")

      // DarkAwareSquare paints white (#FFFFFF) in light mode, black (#000000) in dark mode.
      // `setQualifiers("+notnight")` / `+night` is what flips `isSystemInDarkTheme()`; if the
      // override didn't reach the qualifier builder both renders would land on the same colour.
      val lightWhitePct = pixelMatchPct(light, expectedRgb = 0xFFFFFF, perChannelTolerance = 8)
      val darkBlackPct = pixelMatchPct(dark, expectedRgb = 0x000000, perChannelTolerance = 8)
      assertTrue(
        "light render should be mostly white; got ${"%.2f".format(lightWhitePct * 100)}%",
        lightWhitePct >= 0.95,
      )
      assertTrue(
        "dark render should be mostly black; got ${"%.2f".format(darkBlackPct * 100)}%",
        darkBlackPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  private fun renderAndDecode(
    host: PreviewManifestRouter,
    payload: String,
    label: String,
  ): java.awt.image.BufferedImage {
    val request = RenderRequest.Render(payload = payload)
    val result = host.submit(request, timeoutMs = 120_000)
    assertNotNull("$label: pngPath must be populated", result.pngPath)
    val pngFile = File(result.pngPath!!)
    assertTrue("$label: rendered PNG must exist", pngFile.exists())
    return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      ?: error("$label: PNG failed to decode")
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance]
   * of the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
   * `PixelDiff` to avoid the same circular dep that [RenderEngineTest]'s helper sidesteps.
   */
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
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }
}
