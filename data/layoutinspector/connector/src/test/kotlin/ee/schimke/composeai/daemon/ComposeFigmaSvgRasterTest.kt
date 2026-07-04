package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Producer-level coverage for the **hybrid** `compose/figma-svg` export: given the frame the render
 * already captured, opaque components (`Image`/`Icon`/`Canvas`/charts) must export as `<image>`
 * layers *and* the referenced background-free raster must be cropped out of that frame and written,
 * so the SVG never dangles a reference. Complements the pure model coverage in
 * `FigmaLayeredSvgTest`, which asserts the `<image>` markup but not the on-disk PNG.
 */
class ComposeFigmaSvgRasterTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-figma-raster-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  private fun node(
    component: String,
    l: Int,
    t: Int,
    r: Int,
    b: Int,
    tokens: ComposeSemanticsTokens? = null,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = component,
      component = component,
      bounds = LayoutInspectorBounds(l, t, r, b),
      size = LayoutInspectorSize(r - l, b - t),
      tokens = tokens,
      children = children,
    )

  /**
   * A frame painted red everywhere except a green rectangle over [gl,gt,gr,gb] (the Image region).
   */
  private fun writeFrame(gl: Int, gt: Int, gr: Int, gb: Int, w: Int = 200, h: Int = 200): File {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color.RED
    g.fillRect(0, 0, w, h)
    g.color = Color.GREEN
    g.fillRect(gl, gt, gr - gl, gb - gt)
    g.dispose()
    val file = File(rootDir, "frame.png")
    ImageIO.write(img, "png", file)
    return file
  }

  @Test
  fun `hybrid export crops the opaque node out of the frame`() {
    // Screen (vector Surface) wrapping one opaque Image over 20,20..180,120.
    val layout =
      LayoutInspectorPayload(
        node(
          "Screen",
          0,
          0,
          200,
          200,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFBFE"),
          children = listOf(node("Image", 20, 20, 180, 120)),
        )
      )
    val frame = writeFrame(gl = 20, gt = 20, gr = 180, gb = 120)

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = "preview",
      layout = layout,
      frameImage = frame,
    )

    val previewDir = rootDir.resolve("preview")
    val svg = previewDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    // The SVG emits the opaque node as an <image> layer referencing the raster.
    assertTrue("expected an <image> layer", svg.contains("<image "))
    assertTrue("expected the raster href", svg.contains("""href="figma-raster/Image.png""""))
    // The vector part (the Surface fill) is still present.
    assertTrue("vector Surface fill must remain", svg.contains("""fill="#FFFBFE""""))

    // The referenced PNG exists, is sized to the node bounds, and carries the node's pixels — i.e.
    // the crop landed on the right region of the frame (green), not the surrounding red.
    val raster = previewDir.resolve("figma-raster").resolve("Image.png")
    assertTrue("raster PNG must be written: ${raster.absolutePath}", raster.exists())
    val cropped = ImageIO.read(raster)
    assertEquals("crop width = node width", 160, cropped.width)
    assertEquals("crop height = node height", 100, cropped.height)
    val center = Color(cropped.getRGB(cropped.width / 2, cropped.height / 2))
    assertTrue(
      "crop must capture the Image region (green), got $center",
      center.green > 200 && center.red < 80,
    )
  }

  @Test
  fun `no frame keeps the export vector-only with no dangling raster refs`() {
    val layout = LayoutInspectorPayload(node("Image", 0, 0, 100, 100))

    ComposeFigmaSvgDataProducer.writeSvg(rootDir = rootDir, previewId = "preview", layout = layout)

    val previewDir = rootDir.resolve("preview")
    val svg = previewDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertFalse("no frame → no <image> refs", svg.contains("<image "))
    assertFalse("no raster dir must be created", previewDir.resolve("figma-raster").exists())
  }

  @Test
  fun `node measured partly off-canvas still yields a valid raster`() {
    // Bounds run off the right/bottom edge of the frame; the crop clips to the frame and still
    // produces a decodable PNG so the <image> reference resolves.
    val layout = LayoutInspectorPayload(node("Canvas", 150, 150, 320, 320))
    val frame = writeFrame(gl = 150, gt = 150, gr = 200, gb = 200)

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = "preview",
      layout = layout,
      frameImage = frame,
    )

    val raster = rootDir.resolve("preview").resolve("figma-raster").resolve("Canvas.png")
    assertTrue("clipped raster PNG must be written", raster.exists())
    val cropped = ImageIO.read(raster)
    assertEquals("clipped to frame width", 50, cropped.width)
    assertEquals("clipped to frame height", 50, cropped.height)
  }
}
