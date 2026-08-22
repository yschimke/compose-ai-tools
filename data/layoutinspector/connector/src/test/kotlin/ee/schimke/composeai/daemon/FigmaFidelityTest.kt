package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for the pure [FigmaFidelity] scoring/compositing engine (no render, no Skia). */
class FigmaFidelityTest {

  private fun solid(w: Int, h: Int, rgb: Int): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(rgb)
    g.fillRect(0, 0, w, h)
    g.dispose()
    return img
  }

  @Test
  fun `identical images score 1`() {
    val a = solid(40, 30, 0x3366CC)
    val b = solid(40, 30, 0x3366CC)
    val r = FigmaFidelity.compare(a, b)
    assertEquals(1.0, r.score, 1e-9)
    assertEquals(0.0, r.meanAbsError, 1e-9)
    // Composite is `svg | diff | render` across, plus a label strip on top.
    assertEquals(40 * 3 + 12 * 2, r.composite.width)
    assertTrue(
      "composite must be taller than the panels for the label strip",
      r.composite.height > 30,
    )
  }

  @Test
  fun `the composite puts the spec first and the render last`() {
    // The house rule, pinned in pixels: an imported/exported design spec is drawn to the LEFT of
    // the render it is compared against — the same order the viewer's spec lane uses (Spec / Diff /
    // Render). This composite used to read `render | figma-svg | diff`, so the two surfaces
    // disagreed about which side was which.
    //
    // Two solid, unmistakable colours make the panels identifiable: the SVG panel is green, the
    // render panel is blue, and a sample from the middle of each third says which landed where.
    val render = solid(40, 30, 0x0000FF)
    val svg = solid(40, 30, 0x00FF00)
    val r = FigmaFidelity.compare(render, svg)
    val labelH = r.composite.height - 30
    val y = labelH + 15
    val stride = 40 + 12
    assertEquals("first panel is the figma-svg", 0x00FF00, r.composite.getRGB(20, y) and 0xFFFFFF)
    assertEquals(
      "last panel is the render",
      0x0000FF,
      r.composite.getRGB(2 * stride + 20, y) and 0xFFFFFF,
    )
    // The diff sits between them: every pixel mismatches here, so the middle third is the
    // mismatch red rather than either source colour.
    assertEquals(
      "the diff is the middle panel",
      0xE53935,
      r.composite.getRGB(stride + 20, y) and 0xFFFFFF,
    )
  }

  @Test
  fun `fully different images score near 0`() {
    val a = solid(20, 20, 0x000000)
    val b = solid(20, 20, 0xFFFFFF)
    val r = FigmaFidelity.compare(a, b)
    assertEquals(0.0, r.score, 1e-9)
    assertEquals(255.0, r.meanAbsError, 1e-9)
  }

  @Test
  fun `half-mismatched image scores about one half`() {
    // Left half identical, right half opposite → ~50% agreement.
    val a = solid(20, 20, 0x000000)
    val b = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
    val g = b.createGraphics()
    g.color = Color(0x000000)
    g.fillRect(0, 0, 10, 20)
    g.color = Color(0xFFFFFF)
    g.fillRect(10, 0, 10, 20)
    g.dispose()
    // Position-locked (radius 0) so the boundary column isn't bridged by the spatial tolerance —
    // this asserts the raw agreement fraction.
    val r = FigmaFidelity.compare(a, b, FigmaFidelity.Options(spatialRadius = 0))
    assertEquals(0.5, r.score, 1e-9)
  }

  @Test
  fun `svg raster is scaled to the render size before comparison`() {
    // A 2x-size svg raster of the same colour must still align and score 1 after downscale.
    val render = solid(30, 30, 0x22AA88)
    val svg = solid(60, 60, 0x22AA88)
    val r = FigmaFidelity.compare(render, svg)
    assertEquals(30, r.width)
    assertEquals(30, r.height)
    assertTrue("scaled-but-identical colour must score high, got ${r.score}", r.score > 0.99)
  }

  @Test
  fun `one-pixel shift is absorbed by the default spatial tolerance`() {
    // A thin vertical black bar, shifted right by 1px between the two images — the kind of
    // sub-pixel
    // drift a text baseline / edge shows between the render and its SVG re-rasterisation. With the
    // default spatialRadius=1 this must score ~perfect; position-locked (radius 0) it must not.
    fun barAt(col: Int): BufferedImage {
      val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
      val g = img.createGraphics()
      g.color = Color(0xFFFFFF)
      g.fillRect(0, 0, 20, 20)
      g.color = Color(0x000000)
      g.fillRect(col, 0, 2, 20)
      g.dispose()
      return img
    }
    val a = barAt(8)
    val b = barAt(9) // shifted 1px

    assertTrue(
      "1px shift must score ~perfect with spatial tolerance, got ${FigmaFidelity.compare(a, b).score}",
      FigmaFidelity.compare(a, b).score > 0.99,
    )
    assertTrue(
      "position-locked (radius 0) must flag the shifted bar",
      FigmaFidelity.compare(a, b, FigmaFidelity.Options(spatialRadius = 0)).score < 0.95,
    )
  }

  @Test
  fun `tolerance absorbs small channel drift`() {
    val a = solid(16, 16, 0x808080)
    val b = solid(16, 16, 0x8A8A8A) // +10 per channel
    // Default tolerance 24 → matches; tolerance 4 → mismatch.
    assertTrue(FigmaFidelity.compare(a, b).score > 0.99)
    assertEquals(0.0, FigmaFidelity.compare(a, b, FigmaFidelity.Options(tolerance = 4)).score, 1e-9)
  }
}
