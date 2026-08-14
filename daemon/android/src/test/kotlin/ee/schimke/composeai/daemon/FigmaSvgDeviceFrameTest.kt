package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The figma-svg canvas must be the frame the render actually produced.
 *
 * A `@Preview(device = …)` carries no explicit `widthDp`/`heightDp`, so the router resolves the
 * spec to its fixed fallback frame and lets the device qualifier size the real composition. If the
 * export sizes its canvas off the *spec* instead of the *rendered frame*, a Wear large-round
 * preview exports a small square canvas while the PNG stays at the device's real pixels — the "SVG
 * viewport shrank to 352×352 while the PNG stayed 454×454" cluster on #2615/#2883.
 */
class FigmaSvgDeviceFrameTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `device preview exports its rendered frame, not the spec fallback`() {
    val outputDir = tempFolder.newFolder("renders-figma-device-frame")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wear-device-frame",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              density = 2.0f,
              device = "id:wearos_large_round",
              outputBaseName = "wear-device-frame",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(payload = "previewId=wear-device-frame"),
        timeoutMs = 120_000,
      )

      val png = File(outputDir, "wear-device-frame.png")
      assertTrue("render must produce a PNG: ${png.absolutePath}", png.exists())
      val image = ImageIO.read(png)

      val svgFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("wear-device-frame")
          .resolve("compose-figma.svg")
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()
      val size =
        Regex("""<svg[^>]*\swidth="(\d+)"\s+height="(\d+)"""").find(svg)
          ?: error("no <svg> width/height in:\n${svg.take(400)}")
      val svgWidth = size.groupValues[1].toInt()
      val svgHeight = size.groupValues[2].toInt()

      System.err.println(
        "FIGMA-SVG-DEVICE-FRAME png=${image.width}x${image.height} svg=${svgWidth}x$svgHeight"
      )
      // `id:wearos_large_round` is 227dp at density 2 — the render must compose at the device's
      // 454², not the router's fixed 320² fallback.
      assertEquals("render must use the device frame", 454, image.width)
      assertEquals("render must use the device frame", 454, image.height)
      // The export's canvas IS the rendered frame — a device mask anchors it there with no margin,
      // so the SVG and its paired PNG are the same box and the viewer's SVG toggle doesn't resize
      // the stage or move the content.
      assertEquals("SVG canvas must match the rendered width", image.width, svgWidth)
      assertEquals("SVG canvas must match the rendered height", image.height, svgHeight)
    } finally {
      host.shutdown()
      System.clearProperty(RenderEngine.OUTPUT_DIR_PROP)
      System.clearProperty("roborazzi.test.record")
    }
  }
}
