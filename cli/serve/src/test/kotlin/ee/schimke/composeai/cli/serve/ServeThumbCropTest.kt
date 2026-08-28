package ee.schimke.composeai.cli.serve

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    // The NATIVE box and the axis the cap acted on travel with the crop, so the page can re-derive
    // the window's width for a different cap (the narrow-viewport one) instead of freezing 240px.
    assertEquals(300, crop.natBoxW)
    assertEquals(300, crop.natCapAxis) // largest edge
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

  /** An ARGB PNG with an opaque rect at [x],[y] sized [w]×[h] on a transparent [canvas]² canvas. */
  private fun opaqueRectPng(x: Int, y: Int, w: Int, h: Int, canvas: Int = 454): ByteArray {
    val img = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0x66, 0x55, 0x88)
    g.fillRect(x, y, w, h)
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  @Test
  fun `pngAlphaBounds reads the tight bbox of the non-transparent pixels`() {
    val box = pngAlphaBounds(opaqueRectPng(x = 140, y = 155, w = 175, h = 145))
    assertNotNull(box)
    assertEquals(140, box.x)
    assertEquals(155, box.y)
    assertEquals(175, box.w)
    assertEquals(145, box.h)
    assertNull(
      pngAlphaBounds(
        BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).let {
          val baos = ByteArrayOutputStream()
          ImageIO.write(it, "png", baos)
          baos.toByteArray()
        }
      ),
      "fully transparent → null",
    )
    assertNull(pngAlphaBounds(byteArrayOf(1, 2, 3)), "undecodable → null")
  }

  @Test
  fun `contentBounds unions into the crop box so pixels outside the figma box are not clipped`() {
    // figma box = 166×136 at (144,159); the render's opaque pixels overrun it to
    // (140,155)…(315,300).
    val alpha = pngAlphaBounds(opaqueRectPng(x = 140, y = 155, w = 175, h = 145))
    val crop = computeThumbCrop(svg("0 0 166 136", "translate(-144, -159)"), 454, 454, alpha)
    assertNotNull(crop)
    // Box grew to the unioned 175×145 (< cap 240 → scale 1); origin is the unioned top-left.
    assertEquals(175, crop.boxW)
    assertEquals(145, crop.boxH)
    assertEquals(-140, crop.left)
    assertEquals(-155, crop.top)
  }

  @Test
  fun `contentBounds within the figma box leaves the crop unchanged`() {
    // Opaque pixels sit inside the figma box → union is the figma box, same crop as without bounds.
    val alpha = pngAlphaBounds(opaqueRectPng(x = 170, y = 210, w = 100, h = 40))
    val crop = computeThumbCrop(svg("0 0 120 48", "translate(-167, -203)"), 454, 454, alpha)
    assertNotNull(crop)
    assertEquals(120, crop.boxW)
    assertEquals(48, crop.boxH)
    assertEquals(-167, crop.left)
    assertEquals(-203, crop.top)
  }

  @Test
  fun `a full-screen render stays uncropped even when alpha bounds are supplied`() {
    // Opaque pixels fill the canvas → union still fills the render → the no-op guard trips.
    val alpha = pngAlphaBounds(opaqueRectPng(x = 0, y = 0, w = 454, h = 454))
    assertNull(computeThumbCrop(svg("0 0 454 454", "translate(0, 0)"), 454, 454, alpha))
  }

  @Test
  fun `no viewBox, non-positive dimensions, or degenerate box yield no crop`() {
    assertNull(computeThumbCrop(svg(null, "translate(-10, -10)"), 454, 454)) // no viewBox
    assertNull(computeThumbCrop(svg("0 0 120 48", "translate(-1, -1)"), 0, 454)) // renderW <= 0
    assertNull(computeThumbCrop(svg("0 0 120 48", "translate(-1, -1)"), 454, 0)) // renderH <= 0
    assertNull(computeThumbCrop(svg("0 0 0 48", "translate(-1, -1)"), 454, 454)) // vw <= 0
  }

  @Test
  fun `a declared capture gutter is trimmed off, leaving the component's own box`() {
    // m3-catalog's `Button/Elevated`: a 249x126 button captured with an 11/11/11/13 px gutter, so
    // the canvas is 271x150 and the sheet drew it 7% smaller than the four siblings beside it
    // (m3-catalog#179). Subtracting the gutter gives the sibling's box back, to the pixel.
    val crop = computeGutterCrop(11, 11, 11, 13, 271, 150)
    assertNotNull(crop)
    assertEquals(249, crop.boxW)
    assertEquals(126, crop.boxH)
    assertEquals(271, crop.imgW)
    assertEquals(150, crop.imgH)
    assertEquals(-11, crop.left)
    assertEquals(-11, crop.top)
    assertEquals(249, crop.natBoxW)
    assertEquals(126, crop.natCapAxis) // a gutter crop caps on HEIGHT, not the largest edge
    // The shadow lives in those 11px, so the window lines the box up without hiding what spills.
    assertFalse(crop.clip)
  }

  @Test
  fun `a gutter crop is not gated by the close-cropped guard the svg path applies`() {
    // A 392² box on a 400² render fills 98% of both axes, so the vector path reads it as a capture
    // already tight to its component and declines. A gutter is not inferred from the pixels — the
    // renderer recorded it when it grew the canvas — so there is nothing there to be unsure about,
    // and those 4px a side are exactly what a sibling render does not carry.
    assertNull(computeThumbCrop(svg("0 0 392 392", "translate(-4, -4)"), 400, 400))
    val crop = computeGutterCrop(4, 4, 4, 4, 400, 400)
    assertNotNull(crop)
    // 392 tall is past the 240 cap, so the box comes back scaled — square in, square out.
    assertEquals(240, crop.boxW)
    assertEquals(240, crop.boxH)
  }

  @Test
  fun `a tall gutter box is capped on its height, the way a plain image is`() {
    // `Card/Elevated`: 945x1260 of card inside a 967x1282 canvas. The plain card beside it is an
    // `<img>` the stylesheet caps at 240 tall, width following — so this box scales by the same
    // rule and the two land on the same size. (The cap cannot live in CSS here: this box carries
    // an aspect-ratio, and constraining its height there squashes it instead of scaling it.)
    val crop = computeGutterCrop(11, 11, 11, 11, 967, 1282)
    assertNotNull(crop)
    assertEquals(180, crop.boxW)
    assertEquals(240, crop.boxH)
    assertEquals(184, crop.imgW)
    assertEquals(244, crop.imgH)
    assertEquals(-2, crop.left)
    assertEquals(-2, crop.top)
  }

  @Test
  fun `a wide but short component is not shrunk by the cap`() {
    // 249 is past the 240 cap on the WIDTH axis, and a plain sibling image is not shrunk for that
    // — the stylesheet caps height. Capping the largest edge here would draw the guttered button
    // 3.6% smaller than the four beside it: the very mismatch this window removes.
    val crop = computeGutterCrop(11, 11, 11, 13, 271, 150)
    assertNotNull(crop)
    assertEquals(249, crop.boxW)
    assertEquals(126, crop.boxH)
  }

  @Test
  fun `no gutter, an empty one, or one larger than its render yields no crop`() {
    assertNull(computeGutterCrop(0, 0, 0, 0, 271, 150))
    // Negative edges clamp to zero rather than growing the box past the image.
    assertNull(computeGutterCrop(-4, -4, -4, -4, 271, 150))
    // A record that disagrees with its own image: show the image whole rather than guess.
    assertNull(computeGutterCrop(200, 11, 200, 13, 271, 150))
    assertNull(computeGutterCrop(11, 11, 11, 13, 0, 0))
  }
}
