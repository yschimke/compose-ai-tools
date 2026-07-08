package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rasterised coverage for [TalkBackFocusOverlay] (issue #1956 Phase 1). Same Robolectric + native
 * graphics setup as [AccessibilityOverlayMergedTest] so `Canvas.drawRect` / `drawRoundRect`
 * actually paint. The overlay composites onto the source frame at native size, so we assert the
 * green focus rectangle lands on the focused node's bounds, the caption card paints at the bottom,
 * and the output stays the same dimensions as the input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TalkBackFocusOverlayTest {

  @get:Rule val tempDir = TemporaryFolder()

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
      // An unmerged child — must NOT be treated as a focus stop.
      AccessibilityNode(label = "inner", merged = false, boundsInScreen = "50,150,200,200"),
    )

  @Test
  fun `focus rectangle is green and lands on the focused node`() {
    val source = blackSource(400, 400)
    val out = TalkBackFocusOverlay.generate(source, nodes, focusedStop = 1, destPng = dest())
    assertNotNull("overlay should be written", out)
    val bm = BitmapFactory.decodeFile(out!!.absolutePath)
    assertEquals("overlay keeps source dimensions", 400, bm.width)
    assertEquals(400, bm.height)

    // The focused node (stop 1) bounds are 40,140..360,210. The green stroke rings just outside;
    // sample the top stroke row a few px above the bounds top.
    val greenHitsFocused = countGreenAlong(bm, y = 140 - 6, xRange = 40..360)
    assertTrue(
      "focused node should be ringed in green: hits=$greenHitsFocused",
      greenHitsFocused > 50,
    )

    // Stop 0 (the heading) is NOT focused, so its bounds top should have no green focus stroke.
    val greenHitsUnfocused = countGreenAlong(bm, y = 40 - 6, xRange = 40..360)
    assertTrue(
      "unfocused node must not be ringed in green: hits=$greenHitsUnfocused",
      greenHitsUnfocused < 10,
    )
  }

  @Test
  fun `caption card paints at the bottom of the frame`() {
    val source = blackSource(400, 400)
    val bm =
      BitmapFactory.decodeFile(
        TalkBackFocusOverlay.generate(source, nodes, focusedStop = 1, destPng = dest())!!
          .absolutePath
      )
    // The caption card is a near-opaque dark panel over the black source; its green accent bar on
    // the left edge is the easiest signal. Scan the left margin band in the bottom quarter.
    var accentHits = 0
    for (y in 300 until 400) {
      for (x in 16..28) {
        if (isGreen(bm.getPixel(x, y))) accentHits++
      }
    }
    assertTrue(
      "caption accent bar should paint near the bottom-left: hits=$accentHits",
      accentHits > 20,
    )
  }

  @Test
  fun `no focus stops yields no overlay`() {
    val onlyUnmerged =
      listOf(AccessibilityNode(label = "x", merged = false, boundsInScreen = "0,0,10,10"))
    assertNull(TalkBackFocusOverlay.generate(blackSource(40, 40), onlyUnmerged, 0, dest()))
  }

  @Test
  fun `out of range focused stop is clamped`() {
    // focusedStop far past the end clamps to the last stop rather than throwing.
    val out = TalkBackFocusOverlay.generate(blackSource(400, 400), nodes, focusedStop = 99, dest())
    assertNotNull(out)
  }

  private fun dest(): File = File(tempDir.newFolder(), "tb-${System.nanoTime()}.png")

  private fun blackSource(w: Int, h: Int): File {
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    val f = tempDir.newFile()
    f.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
  }

  private fun isGreen(px: Int): Boolean =
    Color.green(px) > 140 &&
      Color.green(px) > Color.red(px) + 40 &&
      Color.green(px) > Color.blue(px) + 40

  private fun countGreenAlong(bm: Bitmap, y: Int, xRange: IntRange): Int {
    var hits = 0
    // Stroke AA spreads across a couple of rows; check the row and its neighbours.
    for (yy in (y - 2)..(y + 2)) {
      if (yy !in 0 until bm.height) continue
      for (x in xRange) {
        if (x !in 0 until bm.width) continue
        if (isGreen(bm.getPixel(x, yy))) hits++
      }
    }
    return hits
  }
}
