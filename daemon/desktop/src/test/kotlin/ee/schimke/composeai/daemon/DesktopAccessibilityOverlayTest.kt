package ee.schimke.composeai.daemon

import ee.schimke.composeai.cli.AccessibilityNode
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the AWT overlay composer: synthetic nodes + a tiny source PNG compose into an image whose
 * width is the (upscaled) screenshot width plus the 540px legend panel, and whose left-hand
 * screenshot region carries a translucent palette-coloured fill over a node's bounds.
 */
class DesktopAccessibilityOverlayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun composes_screenshot_plus_540px_legend_and_paints_node_fill() {
    // A 600x400 white source (>= MIN_SCREENSHOT_DIM so no upscale — width math is exact).
    val src = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
    val sg = src.createGraphics()
    sg.color = Color.WHITE
    sg.fillRect(0, 0, 600, 400)
    sg.dispose()
    val srcFile = tempFolder.newFile("source.png")
    ImageIO.write(src, "png", srcFile)

    val nodes =
      listOf(
        AccessibilityNode(
          label = "Submit",
          role = "Button",
          states = listOf("clickable"),
          merged = true,
          boundsInScreen = "40,40,200,120",
        ),
        AccessibilityNode(
          label = "Submit",
          role = null,
          states = emptyList(),
          merged = false,
          boundsInScreen = "60,60,180,100",
        ),
      )

    val dest = tempFolder.newFile("overlay.png")
    val out = DesktopAccessibilityOverlay.generate(srcFile, nodes, dest)
    assertNotNull("overlay must be produced for non-empty nodes", out)

    val composite = ImageIO.read(dest)
    assertEquals("composite width = source width (600) + LEGEND_WIDTH (540)", 1140, composite.width)
    assertEquals("composite height >= source height", 400, composite.height)

    // The merged node's fill is a translucent pastel over [40,40 .. 200,120]; sample a pixel well
    // inside it (and inside the white source) — it must read as a tinted, non-white colour.
    val argb = composite.getRGB(100, 80)
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    assertTrue(
      "node fill must tint the white screenshot (got #${Integer.toHexString(argb)})",
      !(r == 255 && g == 255 && b == 255),
    )
    // First palette colour is pink (0xF8, 0xBB, 0xD0) at ~9% over white → reddest channel highest.
    assertTrue("pink-ish fill: red channel should dominate blue/green", r >= g && r >= b)
  }

  @Test
  fun empty_nodes_produces_no_overlay() {
    val src = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
    val srcFile = tempFolder.newFile("empty-source.png")
    ImageIO.write(src, "png", srcFile)
    val dest = tempFolder.newFile("empty-overlay.png")
    dest.delete()

    val out = DesktopAccessibilityOverlay.generate(srcFile, emptyList(), dest)
    assertNull("no overlay when there are no nodes", out)
    assertTrue("dest file must not be written for empty nodes", !dest.exists())
  }
}
