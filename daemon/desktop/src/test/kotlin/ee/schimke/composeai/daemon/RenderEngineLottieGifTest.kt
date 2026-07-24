package ee.schimke.composeai.daemon

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the live-daemon `kind=LOTTIE` animated path: a [RenderSpec] carrying `kind="LOTTIE"`,
 * `renderMode="lottie-gif"`, and an `assetPath` sweeps the asset's intrinsic timeline into a
 * looping **APNG** (delegating to `:renderer-desktop`'s `renderLottieApng`) rather than capturing
 * one still PNG — so a file-discovered Lottie preview animates through the daemon, and therefore VS
 * Code. APNG rather than GIF so the transparent-background edge keeps real alpha.
 *
 * The fixture `lottie/test-square.json` declares 30 frames at 30fps → a 1000ms intrinsic window.
 */
class RenderEngineLottieGifTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun lottieGifModeProducesAnimatedApng() {
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

    val apngFile = File(result.pngPath!!)
    assertTrue(
      "rendered APNG must exist and be non-empty",
      apngFile.exists() && apngFile.length() > 0,
    )
    assertTrue(
      "animated companion must be an `_animated.png`",
      apngFile.name.endsWith("_animated.png"),
    )

    // 1000ms intrinsic / 40ms default interval → 25 frames, recorded in the APNG `acTL` chunk.
    assertEquals(25, apngNumFrames(apngFile))
  }

  /** Read an APNG's `acTL` chunk and return its `numFrames` field. */
  private fun apngNumFrames(file: File): Int {
    val bytes = file.readBytes()
    val marker = "acTL".toByteArray(Charsets.US_ASCII)
    for (i in 8 until bytes.size - 8) {
      if (
        bytes[i] == marker[0] &&
          bytes[i + 1] == marker[1] &&
          bytes[i + 2] == marker[2] &&
          bytes[i + 3] == marker[3]
      ) {
        // acTL data follows the 4-byte type: numFrames (4) then numPlays (4).
        return ByteBuffer.wrap(bytes, i + 4, 4).int
      }
    }
    error("APNG has no acTL chunk: ${file.absolutePath}")
  }
}
