package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the live-daemon `kind=LOTTIE` animated path: a [RenderSpec] carrying `kind="LOTTIE"`,
 * `renderMode="lottie-gif"`, and an `assetPath` sweeps the asset's intrinsic timeline into a
 * looping GIF (delegating to `:renderer-desktop`'s `renderLottieGif`) rather than capturing one
 * still PNG — so a file-discovered Lottie preview animates through the daemon, and therefore VS
 * Code.
 *
 * The fixture `lottie/test-square.json` declares 30 frames at 30fps → a 1000ms intrinsic window.
 */
class RenderEngineLottieGifTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun lottieGifModeProducesAnimatedGif() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val spec =
      RenderSpec(
        previewId = "lottie__test",
        renderMode = RenderEngine.LOTTIE_GIF_RENDER_MODE,
        className = "",
        functionName = "test-square.json",
        kind = "LOTTIE",
        assetPath = "lottie/test-square.json",
        widthPx = 48,
        heightPx = 48,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "lottie-test",
      )

    val result = engine.render(spec, requestId = 7L, classLoader = javaClass.classLoader)

    val gifFile = File(result.pngPath!!)
    assertTrue("rendered GIF must exist and be non-empty", gifFile.exists() && gifFile.length() > 0)
    assertTrue("artefact must be a .gif", gifFile.name.endsWith(".gif"))

    // 1000ms intrinsic / 40ms default interval → 25 frames.
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(gifFile.readBytes())).use { stream ->
      reader.input = stream
      assertEquals(25, reader.getNumImages(true))
    }
  }
}
