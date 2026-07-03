package ee.schimke.composeai.renderer

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [renderAnimatedPreview]'s duration contract and, at the [main] level, the
 * animated-vs-single-frame dispatch. The regression pinned here is issue #2190: `durationMs = 0` is
 * `@AnimatedPreview`'s auto-detect sentinel (and its default), but the renderer used to gate the
 * animated path on `durationMs > 0` — so a default-args `@AnimatedPreview` fell through to the
 * single-frame path and PNG bytes were written into the `.gif`-named output.
 */
class DesktopAnimatedRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.AnimatedRenderTestFixturesKt"
  private val fixtureFunction = "SweepingDot"

  @Test
  fun `auto-detect sentinel captures the fallback window as a real multi-frame gif`() {
    val outputFile = File(tempFolder.newFolder("renders"), "auto.gif")

    renderAnimatedPreview(
      className = fixtureClass,
      functionName = fixtureFunction,
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      durationMs = 0,
      frameIntervalMs = 100,
      showCurves = false,
    )

    assertTrue("GIF must exist and be non-empty", outputFile.exists() && outputFile.length() > 0)
    assertTrue("output must be GIF-encoded, not PNG", isGif(outputFile))
    // 1500ms fallback window / 100ms interval → 15 frames.
    assertEquals(15, readGifFrameCount(outputFile))
    assertTrue("frames 0 and 5 should differ across the sweep", framesDiffer(outputFile, 0, 5))
  }

  @Test
  fun `explicit duration overrides the fallback`() {
    val outputFile = File(tempFolder.newFolder("renders"), "explicit.gif")

    renderAnimatedPreview(
      className = fixtureClass,
      functionName = fixtureFunction,
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      durationMs = 500,
      frameIntervalMs = 100,
      showCurves = false,
    )

    assertEquals(5, readGifFrameCount(outputFile))
  }

  @Test
  fun `main dispatches the auto-detect sentinel to the animated path`() {
    val outputFile = File(tempFolder.newFolder("renders"), "dispatch.gif")

    main(rendererArgs(outputFile, animDurationMs = "0"))

    val sidecar = File(outputFile.parentFile, outputFile.name + ".error.json")
    assertTrue(
      "render must not fail: ${if (sidecar.exists()) sidecar.readText() else ""}",
      !sidecar.exists(),
    )
    assertTrue("GIF must exist and be non-empty", outputFile.exists() && outputFile.length() > 0)
    assertTrue(
      "durationMs=0 (@AnimatedPreview default) must produce GIF bytes, not a PNG frame",
      isGif(outputFile),
    )
    assertTrue("auto-detect must capture multiple frames", readGifFrameCount(outputFile) > 1)
  }

  @Test
  fun `main keeps previews without animation intent on the single-frame path`() {
    val outputFile = File(tempFolder.newFolder("renders"), "static.png")

    main(rendererArgs(outputFile, animDurationMs = "-1"))

    assertTrue("PNG must exist and be non-empty", outputFile.exists() && outputFile.length() > 0)
    assertTrue("no-animation renders stay PNG", isPng(outputFile))
  }

  @Test
  fun `main keeps a legacy caller's zero on the single-frame path for png outputs`() {
    // A pre-fix RenderPreviewsTask (possible when a consumer pins a newer renderer on the
    // `composePreviewRenderer` configuration while its plugin lags) sent `0` for every preview
    // without an `@AnimatedPreview`. That `0` must not be read as auto-detect intent when the
    // capture's shape says "static preview" — a `.png` renderOutput.
    val outputFile = File(tempFolder.newFolder("renders"), "legacy.png")

    main(rendererArgs(outputFile, animDurationMs = "0"))

    assertTrue("PNG must exist and be non-empty", outputFile.exists() && outputFile.length() > 0)
    assertTrue("legacy zero + .png output must stay a single-frame PNG", isPng(outputFile))
  }

  /**
   * Builds the full 27-slot positional arg list `RenderPreviewsTask.invokeRenderer` sends, with
   * everything defaulted except the output path and the 25th (`animDurationMs`) slot under test.
   */
  private fun rendererArgs(outputFile: File, animDurationMs: String): Array<String> =
    arrayOf(
      fixtureClass,
      fixtureFunction,
      "64",
      "64",
      "1.0",
      "false",
      "0",
      outputFile.absolutePath,
      "", // wrapperClassName
      "false", // wrapWidth
      "false", // wrapHeight
      "", // previewParameterProviderFqn
      "0", // previewParameterLimit
      "", // localeTag
      "", // scrollMode
      "", // scrollAxis
      "0", // scrollMaxScrollPx
      "0", // scrollFrameIntervalMs
      "COMPOSE", // previewKind
      "", // assetPath
      "1.0", // fontScale
      "false", // showSystemUi
      "0", // uiMode
      "", // device
      animDurationMs,
      "33", // animFrameIntervalMs
      "false", // animShowCurves
    )

  private fun isGif(file: File): Boolean {
    val header = file.inputStream().use { it.readNBytes(6) }
    return header.size == 6 &&
      String(header, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }
  }

  private fun isPng(file: File): Boolean {
    val header = file.inputStream().use { it.readNBytes(8) }
    val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    return header.contentEquals(pngMagic)
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
