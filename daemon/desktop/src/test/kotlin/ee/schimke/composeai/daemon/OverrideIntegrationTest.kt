package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop counterpart of `:daemon:android`'s `OverrideIntegrationTest`. Drives
 * [PreviewManifestRouter] directly with override-bearing payloads to prove the desktop renderer
 * actually applies `widthPx`, `uiMode`, and `fontScale` (PROTOCOL.md § 5, INTERACTIVE.md § 8a).
 *
 * `localeTag` is applied only when the Compose UI runtime exposes a providable locale list;
 * `orientation` is a no-op on desktop (`ImageComposeScene` has no rotation concept).
 */
class OverrideIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun widthPxOverrideChangesRenderedDimensions() {
    val outputDir = tempFolder.newFolder("renders-width")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
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
      val small = renderAndDecode(host, "previewId=red-square", "small")
      assertEquals("manifest default width should be honoured", 64, small.width)

      val large = renderAndDecode(host, "previewId=red-square;widthPx=128;heightPx=128", "large")
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
      val light = renderAndDecode(host, "previewId=dark-aware;uiMode=light", "uimode-light")
      val dark = renderAndDecode(host, "previewId=dark-aware;uiMode=dark", "uimode-dark")

      // `LocalSystemTheme provides SystemTheme.Light/Dark` is what flips
      // `isSystemInDarkTheme()` on Compose Desktop. Without the override reaching the
      // CompositionLocalProvider both renders would fall through to `SystemTheme.Unknown` and
      // pick the same colour.
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
  fun wallpaperOverrideDrivesAmbientPrimaryColor() {
    val outputDir = tempFolder.newFolder("renders-wallpaper")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wallpaper-aware",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "WallpaperAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "wallpaper-aware",
            )
          )
      )
    val host =
      PreviewManifestRouter(
        manifest = manifest,
        engine =
          RenderEngine(
            previewOverrideExtensions =
              PreviewOverrideExtensions(listOf(WallpaperPreviewOverrideExtension()))
          ),
      )
    host.start()
    try {
      val baseline = renderAndDecode(host, "previewId=wallpaper-aware", "wallpaper-baseline")
      val red =
        renderAndDecode(
          host,
          "previewId=wallpaper-aware;overrides=${encodeWallpaperBag("#FFFF0000")}",
          "wallpaper-red",
        )
      val blue =
        renderAndDecode(
          host,
          "previewId=wallpaper-aware;overrides=${encodeWallpaperBag("#FF0000FF")}",
          "wallpaper-blue",
        )

      // The ambient primary should differ from the seedless baseline AND between two distinct
      // seeds. The exact derived primary depends on `WallpaperColorScheme`; sampling a single
      // pixel is enough — the fixture paints a solid fill.
      val basePrimary = baseline.getRGB(baseline.width / 2, baseline.height / 2) and 0xFFFFFF
      val redPrimary = red.getRGB(red.width / 2, red.height / 2) and 0xFFFFFF
      val bluePrimary = blue.getRGB(blue.width / 2, blue.height / 2) and 0xFFFFFF
      assertNotEquals(
        "wallpaper override should change primary vs the seedless baseline",
        basePrimary,
        redPrimary,
      )
      assertNotEquals("different seeds should yield different primaries", redPrimary, bluePrimary)
      // The derived primary for a pure-red seed should still be predominantly red.
      assertTrue(
        "red-seed primary expected red-dominant, got 0x%06X".format(redPrimary),
        ((redPrimary shr 16) and 0xFF) > ((redPrimary shr 8) and 0xFF) &&
          ((redPrimary shr 16) and 0xFF) > (redPrimary and 0xFF),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun fontScaleOverrideReachesLocalDensity() {
    val outputDir = tempFolder.newFolder("renders-fontscale")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "font-scale",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "FontScaleAwareSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "font-scale",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val unscaled = renderAndDecode(host, "previewId=font-scale;fontScale=1.0", "fontscale-1")
      val scaled = renderAndDecode(host, "previewId=font-scale;fontScale=2.0", "fontscale-2")

      // FontScaleAwareSquare paints black at fontScale<1.5, white at fontScale>=1.5. The override
      // reaches the composition iff `LocalDensity.current.fontScale` reflects the spec's value.
      val unscaledBlackPct =
        pixelMatchPct(unscaled, expectedRgb = 0x000000, perChannelTolerance = 8)
      val scaledWhitePct = pixelMatchPct(scaled, expectedRgb = 0xFFFFFF, perChannelTolerance = 8)
      assertTrue(
        "fontScale=1.0 render should be mostly black; got ${"%.2f".format(unscaledBlackPct * 100)}%",
        unscaledBlackPct >= 0.95,
      )
      assertTrue(
        "fontScale=2.0 render should be mostly white; got ${"%.2f".format(scaledWhitePct * 100)}%",
        scaledWhitePct >= 0.95,
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
    val result = host.submit(request, timeoutMs = 30_000)
    assertNotNull("$label: pngPath must be populated", result.pngPath)
    val pngFile = File(result.pngPath!!)
    assertTrue("$label: rendered PNG must exist", pngFile.exists())
    return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      ?: error("$label: PNG failed to decode")
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance] of
   * the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
   * `PixelDiff` to avoid the same circular dep that [RenderEngineTest]'s helper sidesteps.
   */
  private fun encodeWallpaperBag(seedColor: String): String {
    val json = Json { encodeDefaults = false }
    val bag = PreviewOverrides(wallpaper = WallpaperOverride(seedColor = seedColor))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
      )
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
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }
}
