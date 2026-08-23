package ee.schimke.composeai.renderer

import androidx.compose.ui.unit.IntSize
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@CaptureGutter` on the desktop **motion** products (issue #4452).
 *
 * The claim under test is not "the GIF got bigger" — it is that a component's still and its
 * recording agree about the component's bounds. Before this, a preview that declared a gutter
 * published a PNG with its shadow and a GIF beside it with the shadow sliced off: two artefacts of
 * one component disagreeing about how big it is, which is worse for a consumer than neither
 * carrying the gutter. So every assertion here compares the motion capture against the still of the
 * same fixture at the same gutter rather than against a hardcoded number.
 */
class DesktopMotionGutterTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.AnimatedRenderTestFixturesKt"
  private val fixtureFunction = "SweepingDot"

  /** `@CaptureGutter(all = 4, bottom = 5)` at density 1 — the sample fixture's own gutter. */
  private val gutter = PreviewCaptureGutter(startPx = 4, topPx = 4, endPx = 4, bottomPx = 5)

  private fun renderStill(
    name: String,
    gutter: PreviewCaptureGutter,
    wrapWidth: Boolean = true,
    wrapHeight: Boolean = true,
    widthPx: Int = 400,
    heightPx: Int = 400,
  ): BufferedImage {
    val out = File(tempFolder.newFolder(name), "$name.png")
    renderPreview(
      className = fixtureClass,
      functionName = fixtureFunction,
      widthPx = widthPx,
      heightPx = heightPx,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      previewArgs = emptyList(),
      localeTag = null,
      captureGutter = gutter,
    )
    assertTrue("still must exist: ${out.absolutePath}", out.exists() && out.length() > 0)
    return ByteArrayInputStream(out.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
  }

  private fun renderGif(
    name: String,
    gutter: PreviewCaptureGutter,
    wrapWidth: Boolean = true,
    wrapHeight: Boolean = true,
    widthPx: Int = 400,
    heightPx: Int = 400,
  ): File {
    val out = File(tempFolder.newFolder(name), "$name.gif")
    renderAnimatedPreview(
      className = fixtureClass,
      functionName = fixtureFunction,
      widthPx = widthPx,
      heightPx = heightPx,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      durationMs = 300,
      frameIntervalMs = 100,
      showCurves = false,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      captureGutter = gutter,
    )
    assertTrue("gif must exist: ${out.absolutePath}", out.exists() && out.length() > 0)
    return out
  }

  private fun gifFrameSize(file: File): IntSize {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(file.readBytes())).use { stream ->
      reader.input = stream
      val frame = reader.read(0)
      return IntSize(frame.width, frame.height)
    }
  }

  @Test
  fun `a wrapped animated capture comes out on the same canvas as the still`() {
    val bare = renderStill("bare", PreviewCaptureGutter.None)
    val guttered = renderStill("guttered", gutter)
    // Sanity: the still path is doing what the annotation promises, so the comparison below has
    // something to compare against.
    assertEquals(bare.width + gutter.horizontalPx, guttered.width)
    assertEquals(bare.height + gutter.verticalPx, guttered.height)

    val gif = gifFrameSize(renderGif("motion", gutter))
    assertEquals("guttered GIF must be as wide as the guttered still", guttered.width, gif.width)
    assertEquals("guttered GIF must be as tall as the guttered still", guttered.height, gif.height)
  }

  @Test
  fun `a fixed-axis animated capture adds the gutter to the declared frame`() {
    val gif =
      gifFrameSize(
        renderGif(
          "fixed",
          gutter,
          wrapWidth = false,
          wrapHeight = false,
          widthPx = 120,
          heightPx = 90,
        )
      )
    // A fixed axis measures the component in exactly the frame it declared, and the gutter is
    // canvas around it — the same rule the still path applies, and the reason `motionCropSize`
    // adds the gutter on a fixed axis but not on a wrapped one (where the measured size already
    // carries it).
    assertEquals(120 + gutter.horizontalPx, gif.width)
    assertEquals(90 + gutter.verticalPx, gif.height)
  }

  @Test
  fun `a gutterless animated capture is unchanged`() {
    val still = renderStill("plain", PreviewCaptureGutter.None)
    val gif = gifFrameSize(renderGif("plainMotion", PreviewCaptureGutter.None))
    assertEquals(still.width, gif.width)
    assertEquals(still.height, gif.height)
  }

  @Test
  fun `motionCropSize adds the gutter on a fixed axis and never doubles it on a wrapped one`() {
    val scene = IntSize(500, 500)
    // `ComposePreviewContentBox` reports `child + gutter`, so a wrapped axis's measured size is
    // already the full canvas — adding the gutter again here would publish a frame with a second,
    // empty gutter stapled to it.
    val measured = IntSize(64 + gutter.horizontalPx, 64 + gutter.verticalPx)
    assertEquals(
      measured,
      motionCropSize(measured, true, true, 400, 300, scene, gutter),
    )
    assertEquals(
      IntSize(400 + gutter.horizontalPx, 300 + gutter.verticalPx),
      motionCropSize(measured, false, false, 400, 300, scene, gutter),
    )
    assertEquals(
      IntSize(measured.width, 300 + gutter.verticalPx),
      motionCropSize(measured, true, false, 400, 300, scene, gutter),
    )
    // Never larger than the scene that was actually rendered, gutter or no gutter.
    assertEquals(
      IntSize(scene.width, scene.height),
      motionCropSize(IntSize(0, 0), false, false, 9000, 9000, scene, gutter),
    )
  }
}
