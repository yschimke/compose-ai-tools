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

  @Test
  fun deviceOverrideResolvesCatalogDimensions() {
    // PROTOCOL.md § 5 (`renderNow.overrides.device`) — `device=id:pixel_5` should resolve via
    // `DeviceDimensions.resolve` to widthDp=393, heightDp=851, density=2.75, giving widthPx=1080
    // (393 × 2.75) and heightPx=2340 (851 × 2.75). The manifest's per-preview defaults are
    // small (64×64) so the PNG dimension change is the visible signal.
    val outputDir = tempFolder.newFolder("renders-device")
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
              outputBaseName = "red-square-device",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val pixel5 = renderAndDecode(host, "previewId=red-square;device=id:pixel_5", "pixel_5")
      // 393dp × 2.75 density = 1080px nominal, 851dp × 2.75 = 2340px nominal. Robolectric's
      // qualifier round-trip is lossy by a couple of px (px → dp via integer division → px again),
      // so we assert "near nominal" rather than equality. The point is that the device override
      // routed through the catalog and produced ~Pixel 5 dimensions, not the manifest's 64×64.
      assertNearPx("device=id:pixel_5 width should be ~1080px", expected = 1080, pixel5.width)
      assertNearPx("device=id:pixel_5 height should be ~2340px", expected = 2340, pixel5.height)

      // Explicit widthPx still wins over the device-derived value — `device=id:pixel_5;widthPx=600`
      // takes the Pixel 5's density (2.75) but forces a custom width.
      val custom =
        renderAndDecode(host, "previewId=red-square;device=id:pixel_5;widthPx=600", "custom")
      assertNearPx("explicit widthPx should override device dims", expected = 600, custom.width)
      assertNearPx(
        "heightPx still flows from the device when not overridden",
        expected = 2340,
        custom.height,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Asserts [actual] is within ±4px of [expected]. The Android backend's qualifier path round-trips
   * px → dp (via integer division) → px again inside `applyPreviewQualifiers`, so a request for
   * 1080px can come back as 1078px etc. The exact-px assertion isn't what this test is proving —
   * we're proving the device override reached the spec at all.
   */
  private fun assertNearPx(message: String, expected: Int, actual: Int) {
    assertTrue(
      "$message — expected ~$expected, got $actual (drift > 4px)",
      kotlin.math.abs(expected - actual) <= 4,
    )
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
