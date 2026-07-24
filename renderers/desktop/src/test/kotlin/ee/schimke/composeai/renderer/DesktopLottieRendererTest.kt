package ee.schimke.composeai.renderer

import ee.schimke.composeai.preview.lottie.lottieIntrinsicDurationMillis
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the animated Lottie capture: [renderLottieGif] sweeps a discovered asset's intrinsic
 * timeline into a looping GIF. The fixture `lottie/spin.json` is a rounded rectangle rotating
 * 0°→360° over 60 frames at 30fps, so its intrinsic duration is 2000ms and successive frames are
 * visually distinct.
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
  fun `renders a multi-frame looping gif spanning the intrinsic duration`() {
    val outputFile = File(tempFolder.newFolder("renders"), "spin.gif")
    // 2000ms intrinsic / 100ms interval → 20 frames, stepped i/20 so the loop wraps seamlessly.
    val written =
      renderLottieGif(
        assetPath = "lottie/spin.json",
        widthPx = 64,
        heightPx = 64,
        density = 1.0f,
        showBackground = true,
        backgroundColor = 0L,
        outputFile = outputFile,
        frameIntervalMs = 100,
      )

    assertTrue("encoder should report a written file", written != null)
    assertTrue(
      "rendered GIF must exist and be non-empty",
      outputFile.exists() && outputFile.length() > 0,
    )
    assertEquals(20, readGifFrameCount(outputFile))

    // The first and a mid-sweep frame must differ — proof the progress sweep actually advanced the
    // rotation rather than re-stamping frame zero.
    assertTrue("frames 0 and 10 should differ across the sweep", framesDiffer(outputFile, 0, 10))
  }

  @Test
  fun `caps the captured window at the max duration`() {
    val outputFile = File(tempFolder.newFolder("renders"), "spin-capped.gif")
    renderLottieGif(
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
    assertEquals(10, readGifFrameCount(outputFile))
  }

  private fun readGifFrameCount(file: File): Int {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(file.readBytes())).use { stream ->
      reader.input = stream
      return reader.getNumImages(true)
    }
  }

  private fun framesDiffer(file: File, a: Int, b: Int): Boolean {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(file.readBytes())).use { stream ->
      reader.input = stream
      val imgA = reader.read(a)
      val imgB = reader.read(b)
      for (y in 0 until imgA.height) for (x in 0 until imgA.width) {
        if (imgA.getRGB(x, y) != imgB.getRGB(x, y)) return true
      }
      return false
    }
  }
}
