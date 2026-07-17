package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [RenderSvgOutput.write] — the on-disk landing of `render --format svg`. The two
 * cases that matter are the pure-vector export (a single self-contained `.svg`) and the hybrid
 * export, whose relative `figma-raster/<node>.png` hrefs must be re-pointed at the flattened crop
 * dir so the `<image>` layers still resolve once the SVG sits beside its peers in `renders/`.
 */
class RenderSvgOutputTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(): File =
    Files.createTempDirectory("compose-preview-svgout-").toFile().also { tempDirs += it }

  private fun vectorSvg(): ByteArray =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""
      .encodeToByteArray()

  private fun hybridSvg(): ByteArray =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><image href="figma-raster/node-1.png" width="10" height="10"/></svg>"""
      .encodeToByteArray()

  @Test
  fun `pure vector writes a single self-contained svg`() {
    val dir = tempDir()
    val target = File(dir, "MyPreview.svg")

    val written = RenderSvgOutput.write(target, vectorSvg())

    assertEquals(1, written)
    assertTrue(target.isFile)
    assertEquals(vectorSvg().decodeToString(), target.readText())
    // No crop dir materialised for a vector-only export.
    assertFalse(File(dir, "MyPreview.figma-raster").exists())
  }

  @Test
  fun `hybrid rewrites href prefix and lands crops in a stem-named sibling dir`() {
    val dir = tempDir()
    val target = File(dir, "MyPreview.svg")
    val cropBytes =
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    val written = RenderSvgOutput.write(target, hybridSvg(), mapOf("node-1.png" to cropBytes))

    // svg + one crop.
    assertEquals(2, written)
    val svgText = target.readText()
    // Href now points at the flattened, stem-named crop dir — not the bare daemon-relative one.
    assertTrue(
      svgText.contains("href=\"MyPreview.figma-raster/node-1.png\""),
      "expected rewritten href, got: $svgText",
    )
    assertFalse(
      svgText.contains("href=\"figma-raster/node-1.png\""),
      "bare figma-raster/ href should have been rewritten",
    )
    val crop = File(dir, "MyPreview.figma-raster/node-1.png")
    assertTrue(crop.isFile)
    assertTrue(cropBytes.contentEquals(crop.readBytes()))
  }

  @Test
  fun `safeFilename strips filesystem-hostile characters`() {
    assertEquals("com.example.Foo_bar_", RenderSvgOutput.safeFilename("com.example.Foo bar/"))
  }
}
