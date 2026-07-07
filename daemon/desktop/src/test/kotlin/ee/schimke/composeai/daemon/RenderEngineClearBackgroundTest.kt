package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof for the `clearBackground` ("crisp outline") override on the desktop backend.
 *
 * Renders [SurfaceCardSquare] — a Material 3 `Surface` that reads `LocalPreviewBackgroundCleared`,
 * mirroring the design-catalog `CatalogSticker` — twice through the real [RenderEngine] /
 * [DesktopHost] path:
 *
 * * **default** (no override): the harness paints a background AND the `Surface` paints its opaque
 *   `colorScheme.surface` fill, so the corner pixels are fully opaque.
 * * **cleared** (`clearBackground=true`): the harness background is forced transparent (Layer 1)
 *   and the `Surface` drops its own fill (Layer 2, via the provided local), so the corner pixels
 *   are fully transparent — a component silhouette on transparency.
 *
 * The centre purple box is opaque in both, so it's the stable landmark; only the corners move. Both
 * PNGs are copied to `build/clearbg-evidence/` for the PR's before/after visual evidence.
 */
class RenderEngineClearBackgroundTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun render(host: DesktopHost, clearBackground: Boolean, baseName: String): File {
    val payload = buildString {
      append("className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;")
      append("functionName=SurfaceCardSquare;")
      append("widthPx=220;heightPx=96;density=1.0;")
      if (clearBackground) append("clearBackground=true;")
      append("outputBaseName=$baseName")
    }
    val result = host.submit(RenderRequest.Render(payload = payload), timeoutMs = 60_000)
    assertNotNull("pngPath must be populated", result.pngPath)
    val png = File(result.pngPath!!)
    assertTrue("rendered PNG must exist: ${png.absolutePath}", png.exists())
    return png
  }

  /** ARGB of the top-left corner pixel — the background signal. */
  private fun cornerArgb(png: File): Int {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    assertNotNull("PNG must decode", img)
    return img.getRGB(2, 2)
  }

  /** Count of fully-opaque pixels — the amount of "painted" surface in the render. */
  private fun opaquePixelCount(png: File): Int {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    var n = 0
    for (y in 0 until img.height) for (x in 0 until img.width) {
      if (((img.getRGB(x, y) ushr 24) and 0xFF) == 0xFF) n++
    }
    return n
  }

  @Test
  fun clearBackgroundMakesTheSurfaceTransparent() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    val evidenceDir = File("build/clearbg-evidence").apply { mkdirs() }
    try {
      val defaultPng = render(host, clearBackground = false, baseName = "surface-card-default")
      val clearedPng = render(host, clearBackground = true, baseName = "surface-card-clear")

      defaultPng.copyTo(File(evidenceDir, "surface-card-default.png"), overwrite = true)
      clearedPng.copyTo(File(evidenceDir, "surface-card-clear.png"), overwrite = true)

      val defaultAlpha = (cornerArgb(defaultPng) ushr 24) and 0xFF
      val clearedAlpha = (cornerArgb(clearedPng) ushr 24) and 0xFF

      assertEquals("default render corner must be fully opaque", 0xFF, defaultAlpha)
      assertEquals("cleared render corner must be fully transparent", 0x00, clearedAlpha)

      // The outlined button itself survives clearing — its border stroke + label are still opaque,
      // so the cleared render is a crisp floating outline, not an empty PNG. But it paints far less
      // than the default render, whose whole surface card is filled: clearing removes the
      // background,
      // not the component.
      val defaultOpaque = opaquePixelCount(defaultPng)
      val clearedOpaque = opaquePixelCount(clearedPng)
      assertTrue(
        "cleared render must still contain the outlined component (some opaque pixels), got $clearedOpaque",
        clearedOpaque > 50,
      )
      assertTrue(
        "cleared render ($clearedOpaque opaque px) must paint far less than the filled default " +
          "($defaultOpaque opaque px) — the surface fill is gone",
        clearedOpaque < defaultOpaque / 2,
      )
    } finally {
      host.shutdown()
    }
  }
}
