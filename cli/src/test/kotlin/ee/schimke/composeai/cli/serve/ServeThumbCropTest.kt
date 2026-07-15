package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [computeThumbCrop] — the server-side thumbnail crop that frames a render to its
 * component's figma-svg content box, so a Wear sticker (small component on a 454² watch canvas)
 * shows the component instead of the empty canvas. Mirrors the static gallery's client crop
 * (`render-index-html.mjs`).
 */
class ServeThumbCropTest {

  private fun svg(viewBox: String?, translate: String?): String {
    val vb = viewBox?.let { " viewBox=\"$it\"" } ?: ""
    val tr = translate?.let { "<g transform=\"$it\">" } ?: "<g>"
    return "<svg xmlns=\"http://www.w3.org/2000/svg\"$vb>$tr</g></svg>"
  }

  @Test
  fun `a small wear sticker on a 454 canvas is cropped to its component box`() {
    // viewBox = component box (120×48), translate places it in the centred 454² render.
    val crop = computeThumbCrop(svg("0 0 120 48", "translate(-167, -203)"), 454, 454)
    assertNotNull(crop)
    // maxEdge 120 < cap 240 → scale clamps to 1 (no upscaling).
    assertEquals(120, crop.boxW)
    assertEquals(48, crop.boxH)
    assertEquals(454, crop.imgW)
    assertEquals(454, crop.imgH)
    // Negative offsets shift the render so the component's top-left meets the clip origin.
    assertEquals(-167, crop.left)
    assertEquals(-203, crop.top)
  }

  @Test
  fun `a render already tight to the component is not cropped`() {
    // A phone/desktop capture: the component box ≈ the render (within 10%), so no framing.
    assertNull(computeThumbCrop(svg("0 0 301 210", "translate(0, 0)"), 301, 210))
    // Just inside the 90% guard on both axes → still a no-op.
    assertNull(computeThumbCrop(svg("0 0 280 200", "translate(-5, -5)"), 301, 210))
  }

  @Test
  fun `a component larger than the cap is scaled down, offsets scale with it`() {
    // 300×100 component in a 600² render → not close-cropped, so it frames + downscales.
    val crop = computeThumbCrop(svg("0 0 300 100", "translate(-150, -250)"), 600, 600)
    assertNotNull(crop)
    // scale = 240 / max(300,100) = 0.8
    assertEquals(240, crop.boxW) // 300 * 0.8
    assertEquals(80, crop.boxH) //  100 * 0.8
    assertEquals(480, crop.imgW) // 600 * 0.8
    assertEquals(480, crop.imgH)
    assertEquals(-120, crop.left) // -150 * 0.8
    assertEquals(-200, crop.top) // -250 * 0.8
  }

  @Test
  fun `a missing translate defaults the component box to the render origin`() {
    val crop = computeThumbCrop(svg("0 0 120 48", null), 454, 454)
    assertNotNull(crop)
    assertEquals(0, crop.left)
    assertEquals(0, crop.top)
  }

  @Test
  fun `svgContentBox reads the native-pixel box (viewBox size, translate origin)`() {
    val box = svgContentBox(svg("0 0 166 136", "translate(-144, -159)"))
    assertNotNull(box)
    // Origin is the negated translate (component's top-left in the render); size is the viewBox.
    assertEquals(144, box.x)
    assertEquals(159, box.y)
    assertEquals(166, box.w)
    assertEquals(136, box.h)
    assertNull(svgContentBox(svg(null, "translate(-1,-1)")), "no viewBox → null")
  }

  @Test
  fun `no viewBox, non-positive dimensions, or degenerate box yield no crop`() {
    assertNull(computeThumbCrop(svg(null, "translate(-10, -10)"), 454, 454)) // no viewBox
    assertNull(computeThumbCrop(svg("0 0 120 48", "translate(-1, -1)"), 0, 454)) // renderW <= 0
    assertNull(computeThumbCrop(svg("0 0 120 48", "translate(-1, -1)"), 454, 0)) // renderH <= 0
    assertNull(computeThumbCrop(svg("0 0 0 48", "translate(-1, -1)"), 454, 454)) // vw <= 0
  }
}
