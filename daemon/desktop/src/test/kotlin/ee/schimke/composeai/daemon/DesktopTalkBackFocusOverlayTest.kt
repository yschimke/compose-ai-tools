package ee.schimke.composeai.daemon

import ee.schimke.composeai.cli.AccessibilityNode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rasterised coverage for [DesktopTalkBackFocusOverlay] (issue #1956) — the AWT desktop twin of the
 * Android `TalkBackFocusOverlay`. Pure JVM (`Graphics2D` + `ImageIO`), no Robolectric: the green
 * focus rectangle must land on the focused node, the caption accent must paint at the bottom, and a
 * stopless tree must produce no overlay.
 */
class DesktopTalkBackFocusOverlayTest {

  private val nodes =
    listOf(
      AccessibilityNode(
        label = "Settings",
        role = "Heading",
        merged = true,
        boundsInScreen = "40,40,360,90",
      ),
      AccessibilityNode(
        label = "Buy now",
        role = "Button",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = "40,140,360,210",
      ),
      AccessibilityNode(label = "inner", merged = false, boundsInScreen = "50,150,200,200"),
    )

  private fun blackPng(w: Int, h: Int): ByteArray {
    val bm = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = bm.createGraphics()
    g.color = Color.BLACK
    g.fillRect(0, 0, w, h)
    g.dispose()
    return ByteArrayOutputStream().use {
      ImageIO.write(bm, "png", it)
      it.toByteArray()
    }
  }

  private fun isGreen(rgb: Int): Boolean {
    val c = Color(rgb, true)
    return c.green > 140 && c.green > c.red + 40 && c.green > c.blue + 40
  }

  @Test
  fun `focus rectangle is green and lands on the focused node`() {
    val out =
      DesktopTalkBackFocusOverlay.overlayPngBytes(blackPng(400, 400), nodes, focusedStop = 1)
    assertNotNull(out)
    val bm = ImageIO.read(ByteArrayInputStream(out))

    var greenFocused = 0
    for (yy in (140 - 8)..(140 - 2)) for (x in 40..360) if (isGreen(bm.getRGB(x, yy)))
      greenFocused++
    assertTrue("focused node should be ringed in green: $greenFocused", greenFocused > 50)

    var greenUnfocused = 0
    for (yy in (40 - 8)..(40 - 2)) for (x in 60..360) if (isGreen(bm.getRGB(x, yy)))
      greenUnfocused++
    assertTrue("unfocused heading must not be ringed: $greenUnfocused", greenUnfocused < 10)
  }

  @Test
  fun `caption accent bar paints at the bottom-left`() {
    val out =
      DesktopTalkBackFocusOverlay.overlayPngBytes(blackPng(400, 400), nodes, focusedStop = 1)
    assertNotNull(out)
    val bm = ImageIO.read(ByteArrayInputStream(out))
    var accent = 0
    for (y in 300 until 400) for (x in 16..26) if (isGreen(bm.getRGB(x, y))) accent++
    assertTrue("caption accent bar should paint near the bottom-left: $accent", accent > 20)
  }

  @Test
  fun `no focus stops yields no overlay`() {
    val onlyUnmerged =
      listOf(AccessibilityNode(label = "x", merged = false, boundsInScreen = "0,0,10,10"))
    assertNull(DesktopTalkBackFocusOverlay.overlayPngBytes(blackPng(40, 40), onlyUnmerged, 0))
  }

  @Test
  fun `out of range focused stop is clamped`() {
    assertNotNull(
      DesktopTalkBackFocusOverlay.overlayPngBytes(blackPng(400, 400), nodes, focusedStop = 99)
    )
  }
}
