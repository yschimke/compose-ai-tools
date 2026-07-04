package ee.schimke.composeai.renderer

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the still SVG capture: [renderSvgAsset] inflates a discovered `.svg` off the render
 * classpath via Skia's `loadSvgPainter` and encodes a single PNG. The fixture `svg/badge.svg` is a
 * gradient-filled rounded square with a white ring + triangle, so a correct render is opaque and
 * multi-coloured (not a blank/transparent frame).
 */
class DesktopSvgRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `renders a non-empty multi-colour png from a classpath svg`() {
    val outputFile = File(tempFolder.newFolder("renders"), "badge.png")
    renderSvgAsset(
      assetPath = "svg/badge.svg",
      widthPx = 120,
      heightPx = 120,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
    )

    assertTrue(
      "rendered PNG must exist and be non-empty",
      outputFile.exists() && outputFile.length() > 0,
    )

    val image = ImageIO.read(ByteArrayInputStream(outputFile.readBytes()))
    assertTrue("PNG must decode", image != null)
    // The badge fills the frame with a gradient plus white/dark accents — a correct render has many
    // distinct colours. A blank or single-fill frame (a decode/draw failure) would have ≤ 2.
    val distinct = buildSet {
      for (y in 0 until image.height) for (x in 0 until image.width) add(image.getRGB(x, y))
    }
    assertTrue(
      "expected a multi-colour render, got ${distinct.size} colour(s)",
      distinct.size > 100,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `missing classpath svg fails with a clear error`() {
    renderSvgAsset(
      assetPath = "svg/does-not-exist.svg",
      widthPx = 32,
      heightPx = 32,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = File(tempFolder.newFolder("out"), "x.png"),
    )
  }
}
