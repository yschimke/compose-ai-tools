package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the interactive Lottie timeline scrub: `overrides.lottie.progress` lands the captured
 * frame at the requested timeline position for a `kind=LOTTIE` preview, winning over the default
 * (frame 0). The fixture `lottie/spin.json` rotates 0°→360° over its timeline, so two distinct
 * progress positions produce visibly different frames — proof the override reached `LottiePreview`
 * via `LocalLottieProgress`.
 *
 * Kept to a single two-render comparison (small canvas) so the shared-JVM Skiko render budget stays
 * low; the null-is-a-no-op contract is covered without rendering in [LottieOverrideDecodeTest].
 */
class RenderEngineLottieScrubTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun renderAt(progress: Float, name: String): File {
    val engine = RenderEngine(outputDir = tempFolder.newFolder(name))
    val spec =
      RenderSpec(
        previewId = "lottie__spin",
        className = "",
        functionName = "spin.json",
        kind = "LOTTIE",
        assetPath = "lottie/spin.json",
        widthPx = 48,
        heightPx = 48,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "spin",
        overrides = PreviewOverrides(lottie = LottieOverride(progress = progress)),
      )
    val result = engine.render(spec, requestId = 1L, classLoader = javaClass.classLoader)
    return File(result.pngPath!!)
  }

  @Test
  fun scrubProgressChangesRenderedFrame() {
    val early = renderAt(0.0f, "early")
    val mid = renderAt(0.25f, "mid")

    assertTrue("early frame should exist", early.exists() && early.length() > 0)
    assertTrue("mid frame should exist", mid.exists() && mid.length() > 0)
    assertFalse(
      "scrubbing progress 0.0 → 0.25 must change the rotated frame",
      pixelsEqual(early, mid),
    )
  }

  private fun pixelsEqual(a: File, b: File): Boolean {
    val imgA = ByteArrayInputStream(a.readBytes()).use { ImageIO.read(it) }
    val imgB = ByteArrayInputStream(b.readBytes()).use { ImageIO.read(it) }
    if (imgA.width != imgB.width || imgA.height != imgB.height) return false
    for (y in 0 until imgA.height) for (x in 0 until imgA.width) {
      if (imgA.getRGB(x, y) != imgB.getRGB(x, y)) return false
    }
    return true
  }
}
