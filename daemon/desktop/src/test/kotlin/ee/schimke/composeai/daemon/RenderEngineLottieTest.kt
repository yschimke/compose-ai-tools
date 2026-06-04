package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the live-daemon `kind=LOTTIE` render path: a [RenderSpec] carrying `kind="LOTTIE"` + an
 * `assetPath` inflates the Lottie asset via Compottie with no class reflection, so a
 * file-discovered Lottie preview renders through the daemon (and therefore VS Code), not just the
 * one-shot Gradle task. The asset is a fully blue 100x100 square, so a correct render fills the
 * frame with blue.
 */
class RenderEngineLottieTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun lottieAssetRendersBlueSquare() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val spec =
      RenderSpec(
        previewId = "lottie__test",
        className = "",
        functionName = "test-square.json",
        kind = "LOTTIE",
        assetPath = "lottie/test-square.json",
        widthPx = 64,
        heightPx = 64,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "lottie-test",
      )

    // Pass the test classloader so the asset on the test classpath (src/test/resources) is found —
    // setUp installs it as the context loader, which `LottiePreview` consults first.
    val result = engine.render(spec, requestId = 1L, classLoader = javaClass.classLoader)

    val pngFile = File(result.pngPath!!)
    assertTrue("rendered PNG must exist", pngFile.exists() && pngFile.length() > 0)
    val img = ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }!!
    // Count pixels close to the asset's fill colour (#3380E6-ish). A blank/failed render would be
    // white (showBackground) with ~0% blue.
    var blue = 0
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val rgb = img.getRGB(x, y)
      val r = (rgb shr 16) and 0xFF
      val g = (rgb shr 8) and 0xFF
      val b = rgb and 0xFF
      if (b > 150 && b > r + 40 && g in 80..200) blue++
    }
    val pct = blue.toDouble() / (img.width * img.height)
    assertTrue(
      "expected the Lottie square to fill most of the frame; blue=${"%.0f".format(pct * 100)}%",
      pct > 0.6,
    )
  }
}
