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
    assertEquals(166 to 136, dims(cropped!!), "cropped to the figma-svg content box")
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
    assertEquals(54 to 54, dims(cropped!!), "clamped to the 454 edge (454-400)")
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
}
