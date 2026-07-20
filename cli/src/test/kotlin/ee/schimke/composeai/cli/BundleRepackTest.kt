package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `bundle repack` swaps a bundle's baked per-preview artifacts for the re-renders produced by
 * `bundle render --knob … [--svg]` — a `<id>.png` replaces `previews/<id>.png` and a `<id>.svg`
 * replaces the editable vector `previews/<id>.figma.svg` — preserving every other zip entry + the
 * leading PNG cover. It's the bridge that turns a re-themed render dir into a drop-in bundle the
 * catalog exporter consumes. Pure zip surgery, so [repackRethemedPreviews] is exercised directly
 * (no daemon / native renderer).
 */
class BundleRepackTest {

  private fun tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private fun png(): ByteArray {
    val baos = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", baos)
    return baos.toByteArray()
  }

  /** A minimal PNG+ZIP polyglot: the [cover] PNG followed by a zip of [entries]. */
  private fun polyglot(cover: ByteArray, entries: Map<String, ByteArray>): File {
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { z ->
      for ((name, bytes) in entries) {
        z.putNextEntry(ZipEntry(name))
        z.write(bytes)
        z.closeEntry()
      }
    }
    return Files.createTempFile("bundle", ".png").toFile().also {
      it.deleteOnExit()
      it.writeBytes(cover + zip.toByteArray())
    }
  }

  private fun entryOf(bundle: File, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(bundle))).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        if (e.name == name) return z.readBytes()
        z.closeEntry()
      }
    }
    return null
  }

  private fun rendersDir(files: Map<String, ByteArray>): File {
    val dir = tempDir("renders")
    for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
    return dir
  }

  @Test
  fun `swaps matching baked previews and preserves every other entry`() {
    val bundleJson = """{"schemaVersion":8,"backend":"desktop"}""".toByteArray()
    val previewsJson = """{"previews":[]}""".toByteArray()
    val source =
      polyglot(
        png(),
        mapOf(
          "previews/a.png" to "STOCK-A".toByteArray(),
          "previews/b.png" to "STOCK-B".toByteArray(),
          "bundle.json" to bundleJson,
          "previews.json" to previewsJson,
        ),
      )
    val renders = rendersDir(mapOf("a.png" to "THEMED-A".toByteArray()))
    val out = File(tempDir("out"), "themed.png")

    val outcome = repackRethemedPreviews(source, renders, out)

    assertEquals(1, outcome.repacked)
    assertEquals(emptyList(), outcome.unmatched)
    // The re-themed render replaced a's baked slot…
    assertContentEquals("THEMED-A".toByteArray(), entryOf(out, "previews/a.png"))
    // …and everything else is byte-preserved.
    assertContentEquals("STOCK-B".toByteArray(), entryOf(out, "previews/b.png"))
    assertContentEquals(bundleJson, entryOf(out, "bundle.json"))
    assertContentEquals(previewsJson, entryOf(out, "previews.json"))
  }

  @Test
  fun `swaps a re-rendered svg into the baked figma svg slot and reports png plus svg counts`() {
    val source =
      polyglot(
        png(),
        mapOf(
          "previews/a.png" to "STOCK-A".toByteArray(),
          "previews/a.figma.svg" to "STOCK-A-SVG".toByteArray(),
          "previews/a.semantics.json" to "{}".toByteArray(),
        ),
      )
    // `bundle render --knob --svg` writes <id>.png + <id>.svg; repack maps .svg → .figma.svg.
    val renders =
      rendersDir(
        mapOf("a.png" to "THEMED-A".toByteArray(), "a.svg" to "THEMED-A-SVG".toByteArray())
      )
    val out = File(tempDir("out"), "themed.png")

    val outcome = repackRethemedPreviews(source, renders, out)

    assertEquals(1, outcome.png, "one raster swapped")
    assertEquals(1, outcome.svg, "one vector swapped")
    assertEquals(2, outcome.repacked)
    assertEquals(emptyList(), outcome.unmatched)
    assertContentEquals("THEMED-A".toByteArray(), entryOf(out, "previews/a.png"))
    assertContentEquals("THEMED-A-SVG".toByteArray(), entryOf(out, "previews/a.figma.svg"))
    // The JSON sidecar is not a swap target — it's preserved verbatim.
    assertContentEquals("{}".toByteArray(), entryOf(out, "previews/a.semantics.json"))
  }

  @Test
  fun `an svg render with no baked figma svg slot is reported unmatched`() {
    // The bundle carries only the raster slot (no vector), so the .svg render matches nothing.
    val source = polyglot(png(), mapOf("previews/a.png" to "STOCK-A".toByteArray()))
    val renders =
      rendersDir(
        mapOf("a.png" to "THEMED-A".toByteArray(), "a.svg" to "THEMED-A-SVG".toByteArray())
      )
    val out = File(tempDir("out"), "themed.png")

    val outcome = repackRethemedPreviews(source, renders, out)

    assertEquals(1, outcome.png)
    assertEquals(0, outcome.svg)
    assertEquals(listOf("a.svg"), outcome.unmatched)
    // A vector with no baked slot must NOT be added to the bundle.
    assertNull(entryOf(out, "previews/a.figma.svg"))
  }

  @Test
  fun `reports renders that match no baked preview`() {
    val source =
      polyglot(
        png(),
        mapOf("previews/a.png" to "A".toByteArray(), "bundle.json" to "{}".toByteArray()),
      )
    val renders =
      rendersDir(mapOf("a.png" to "THEMED-A".toByteArray(), "ghost.png" to "X".toByteArray()))
    val out = File(tempDir("out"), "themed.png")

    val outcome = repackRethemedPreviews(source, renders, out)

    assertEquals(1, outcome.repacked)
    assertEquals(listOf("ghost.png"), outcome.unmatched)
    // A render with no baked slot must NOT be added to the bundle.
    assertNull(entryOf(out, "previews/ghost.png"))
  }

  @Test
  fun `throws when no render matches a baked preview`() {
    val source = polyglot(png(), mapOf("previews/a.png" to "A".toByteArray()))
    val renders = rendersDir(mapOf("nomatch.png" to "X".toByteArray()))
    assertFailsWith<IllegalStateException> {
      repackRethemedPreviews(source, renders, File(tempDir("out"), "themed.png"))
    }
  }

  @Test
  fun `treats only top-level preview PNGs as swappable slots, not nested crops`() {
    val source =
      polyglot(
        png(),
        mapOf(
          "previews/a.png" to "STOCK-A".toByteArray(),
          "previews/a.figma-raster/0.png" to "CROP".toByteArray(),
        ),
      )
    val renders = rendersDir(mapOf("a.png" to "THEMED-A".toByteArray()))
    val out = File(tempDir("out"), "themed.png")

    val outcome = repackRethemedPreviews(source, renders, out)

    assertEquals(1, outcome.repacked)
    assertContentEquals("THEMED-A".toByteArray(), entryOf(out, "previews/a.png"))
    // A nested figma-raster crop is not a baked-preview slot and stays untouched.
    assertContentEquals("CROP".toByteArray(), entryOf(out, "previews/a.figma-raster/0.png"))
  }

  @Test
  fun `zipEntryNames lists files and skips directories`() {
    val source =
      polyglot(
        png(),
        mapOf("previews/a.png" to "A".toByteArray(), "bundle.json" to "{}".toByteArray()),
      )
    val names = zipEntryNames(BundleReader.extractZipBytes(source))
    assertTrue(
      names.containsAll(listOf("previews/a.png", "bundle.json")),
      "lists file entries: $names",
    )
  }
}
