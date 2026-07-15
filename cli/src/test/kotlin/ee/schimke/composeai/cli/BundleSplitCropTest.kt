package ee.schimke.composeai.cli

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the export-time content-crop in `bundle split`
 * ([cropPngToContentBox] + [splitBundleZip]'s `crop`): a Wear sticker rendered on a 454² watch
 * canvas ships tight to its component box (read from the carried figma-svg) instead of a speck
 * floating in empty canvas.
 */
class BundleSplitCropTest {

  /** A 454×454 ARGB PNG with an opaque rectangle at [x],[y] sized [w]×[h] (the "component"). */
  private fun renderPng(x: Int, y: Int, w: Int, h: Int, canvas: Int = 454): ByteArray {
    val img = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0x66, 0x55, 0x88)
    g.fillRect(x, y, w, h)
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  /** A content-cropped figma-svg: viewBox = the box size, translate places it at (-x,-y). */
  private fun figmaSvg(x: Int, y: Int, w: Int, h: Int): String =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h"><g transform="translate(${-x}, ${-y})"></g></svg>"""

  private fun dims(png: ByteArray): Pair<Int, Int> {
    val img = ImageIO.read(ByteArrayInputStream(png))
    return img.width to img.height
  }

  @Test
  fun `crops the sticker PNG to its component box`() {
    val png = renderPng(x = 144, y = 159, w = 166, h = 136)
    val cropped = cropPngToContentBox(png, figmaSvg(144, 159, 166, 136))
    assertNotNull(cropped)
    assertEquals(166 to 136, dims(cropped!!.png), "cropped to the figma-svg content box")
    assertEquals(
      144 to 159,
      cropped.cropX to cropped.cropY,
      "crop origin reported for sidecar re-base",
    )
  }

  @Test
  fun `unions the render's actual pixels so a focus ring outside the figma box is not clipped`() {
    // The layout-derived figma box (144,159,166,136) under-covers a variant whose focus ring is
    // drawn a few px outside it — here opaque pixels span (140,155)…(315,300).
    val png = renderPng(x = 140, y = 155, w = 175, h = 145)
    val cropped = cropPngToContentBox(png, figmaSvg(144, 159, 166, 136))
    assertNotNull(cropped)
    assertEquals(175 to 145, dims(cropped!!.png), "grown to cover the ring's actual pixels")
    assertEquals(140 to 155, cropped.cropX to cropped.cropY, "crop origin is the unioned top-left")
  }

  @Test
  fun `keeps the full render when the box already fills it (full-screen component)`() {
    val png = renderPng(x = 0, y = 0, w = 454, h = 454)
    // viewBox ≈ the whole 454² canvas → within the 10% no-op guard.
    assertNull(cropPngToContentBox(png, figmaSvg(0, 0, 454, 454)))
  }

  @Test
  fun `no crop without a parseable figma-svg box`() {
    val png = renderPng(x = 100, y = 100, w = 120, h = 80)
    assertNull(cropPngToContentBox(png, "<svg>no viewBox here</svg>"))
  }

  @Test
  fun `a box overrunning the image edge is clamped, not thrown`() {
    // A padded box whose origin+size exceeds the 454 canvas still crops safely to the edge.
    val png = renderPng(x = 400, y = 400, w = 60, h = 60)
    val cropped = cropPngToContentBox(png, figmaSvg(400, 400, 80, 80))
    assertNotNull(cropped)
    assertEquals(54 to 54, dims(cropped!!.png), "clamped to the 454 edge (454-400)")
  }

  private fun sheetZip(png: ByteArray, svg: String): ByteArray {
    val entries =
      linkedMapOf(
        "bundle.json" to """{"previewIds":["wear"],"coverPreviewId":"wear"}""".encodeToByteArray(),
        "previews.json" to """{"previews":[{"id":"wear"}]}""".encodeToByteArray(),
        "previews/wear.png" to png,
        "previews/wear.figma.svg" to svg.encodeToByteArray(),
      )
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { z ->
      for ((n, b) in entries) {
        z.putNextEntry(ZipEntry(n))
        z.write(b)
        z.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  private fun coverPngOf(zipBytes: ByteArray): ByteArray {
    java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (e.name == "previews/wear.png") return zin.readBytes()
        zin.closeEntry()
      }
    }
    error("no cover png in split bundle")
  }

  @Test
  fun `split crops the cover and the addressable png, and --no-crop keeps the full canvas`() {
    val sheet = sheetZip(renderPng(144, 159, 166, 136), figmaSvg(144, 159, 166, 136))

    val cropped = splitBundleZip(sheet, SplitMode.VIEW_ONLY, crop = true).single()
    assertEquals(166 to 136, dims(cropped.coverPng), "polyglot cover is cropped")
    assertEquals(
      166 to 136,
      dims(coverPngOf(cropped.zipBytes)),
      "addressable previews/<id>.png matches",
    )
    assertTrue(
      cropped.coverPng.contentEquals(coverPngOf(cropped.zipBytes)),
      "cover == addressable png",
    )

    val full = splitBundleZip(sheet, SplitMode.VIEW_ONLY, crop = false).single()
    assertEquals(454 to 454, dims(full.coverPng), "--no-crop ships the full render")
  }

  @Test
  fun `rebaseSidecarCoords shifts absolute coordinates into the cropped image space`() {
    val json =
      """
      {"nodes":[{"boundsInRoot":"144,159,310,295","bounds":{"left":144,"top":159,"right":310,"bottom":295,"width":166},
        "curvedTexts":[{"centerXPx":227.0,"centerYPx":227.0,"radiusPx":180}]}]}
      """
        .trimIndent()
    val out = rebaseSidecarCoords(json.encodeToByteArray(), dx = 144, dy = 159).decodeToString()
    // Absolute coords shift by the crop origin; sizes/radii/angles are position-independent.
    assertTrue("\"0,0,166,136\"" in out, "boundsInRoot re-based to the tight image")
    assertTrue("\"left\":0" in out && "\"top\":0" in out, "bounds object re-based")
    assertTrue("\"right\":166" in out && "\"bottom\":136" in out, "bounds far edge re-based")
    assertTrue("\"width\":166" in out, "size preserved")
    assertTrue(
      "\"centerXPx\":83" in out && "\"centerYPx\":68" in out,
      "curved-text centre re-based",
    )
    assertTrue("\"radiusPx\":180" in out, "radius preserved")
  }

  @Test
  fun `rebaseSidecarCoords is a no-op at a zero origin`() {
    val json = """{"boundsInRoot":"1,2,3,4"}"""
    assertTrue(
      rebaseSidecarCoords(json.encodeToByteArray(), 0, 0).contentEquals(json.encodeToByteArray()),
      "zero origin returns the bytes unchanged",
    )
  }
}
