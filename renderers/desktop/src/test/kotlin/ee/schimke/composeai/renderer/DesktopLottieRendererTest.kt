package ee.schimke.composeai.renderer

import ee.schimke.composeai.preview.lottie.lottieIntrinsicDurationMillis
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the animated Lottie capture: [renderLottieApng] sweeps a discovered asset's intrinsic
 * timeline into a looping APNG. The fixture `lottie/spin.json` is a rounded rectangle rotating
 * 0°→360° over 60 frames at 30fps, so its intrinsic duration is 2000ms.
 */
class DesktopLottieRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `intrinsic duration is durationFrames over frameRate`() {
    // 60 frames at 30fps → 2000ms.
    assertEquals(2000, lottieIntrinsicDurationMillis("lottie/spin.json"))
  }

  @Test
  fun `intrinsic duration falls back when the asset is unreadable`() {
    assertEquals(1234, lottieIntrinsicDurationMillis("lottie/does-not-exist.json", default = 1234))
  }

  @Test
  fun `renders a transparent anti-aliased apng for a discovered asset`() {
    // The discovered-asset path renders with no background (showBackground=false). renderLottieApng
    // keeps the transparent surface and encodes to APNG, whose 8-bit alpha carries the anti-aliased
    // edge — unlike GIF's 1-bit alpha, which crushed it into a churn-prone hard boundary.
    val outputFile = File(tempFolder.newFolder("renders"), "spin_animated.png")
    val written =
      renderLottieApng(
        assetPath = "lottie/spin.json",
        widthPx = 96,
        heightPx = 96,
        density = 1.0f,
        showBackground = false,
        backgroundColor = 0L,
        outputFile = outputFile,
        frameIntervalMs = 100,
      )

    assertTrue("encoder should report a written file", written != null)
    assertTrue("rendered APNG must exist and be non-empty", outputFile.exists() && outputFile.length() > 0)
    // 2000ms intrinsic / 100ms interval → 20 frames, recorded in the APNG acTL chunk.
    assertEquals(20, apngNumFrames(outputFile))

    // The default (frame 0 = base IDAT) decodes with alpha, and the corners are fully transparent —
    // proof the transparent background survived (GIF would have thresholded it to opaque/black).
    val firstFrame = ImageIO.read(ByteArrayInputStream(outputFile.readBytes()))
    assertTrue("APNG must carry an alpha channel", firstFrame.colorModel.hasAlpha())
    assertEquals("top-left corner must be fully transparent", 0, firstFrame.getRGB(0, 0) ushr 24)
    // The spinner edge keeps anti-aliased blends — many more than the two colours the transparent
    // GIF path collapsed to.
    val colors = HashSet<Int>()
    for (y in 0 until firstFrame.height) for (x in 0 until firstFrame.width) {
      colors.add(firstFrame.getRGB(x, y))
    }
    assertTrue("expected anti-aliased edge blends (>2 colours), got ${colors.size}", colors.size > 2)
  }

  @Test
  fun `caps the captured window at the max duration`() {
    val outputFile = File(tempFolder.newFolder("renders"), "spin-capped_animated.png")
    renderLottieApng(
      assetPath = "lottie/spin.json",
      widthPx = 32,
      heightPx = 32,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = outputFile,
      durationMillisOverride = 60_000,
      frameIntervalMs = 100,
      maxDurationMillis = 1000,
    )
    // 1000ms cap / 100ms interval → 10 frames, not the 600 a 60s window would imply.
    assertEquals(10, apngNumFrames(outputFile))
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
        return ByteBuffer.wrap(bytes, i + 4, 4).int
      }
    }
    error("APNG has no acTL chunk: ${file.absolutePath}")
  }
}
