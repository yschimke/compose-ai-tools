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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [injectSemanticsIntoBundle] / [addOrReplaceZipEntries] — carrying the per-preview
 * `previews/<id>.semantics.json` blob inside a packed bundle (issue #1843) while keeping the
 * leading PNG cover and every existing entry intact, and staying idempotent across re-injection.
 */
class BundleSemanticsInjectTest {

  private val workRoot = Files.createTempDirectory("bundle-semantics-test-").toFile()

  @AfterTest
  fun cleanup() {
    workRoot.deleteRecursively()
  }

  private fun png(w: Int = 4, h: Int = 4): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, 0x112233)
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  private fun polyglot(cover: ByteArray, entries: Map<String, ByteArray>): File {
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { z ->
      for ((path, bytes) in entries) {
        z.putNextEntry(ZipEntry(path))
        z.write(bytes)
        z.closeEntry()
      }
    }
    val file = File(workRoot, "bundle-${entries.hashCode()}.png")
    file.outputStream().use {
      it.write(cover)
      it.write(zip.toByteArray())
    }
    return file
  }

  private fun entries(zip: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (!e.isDirectory) out[e.name] = zin.readBytes()
        zin.closeEntry()
      }
    }
    return out
  }

  @Test
  fun `injects semantics sidecars and keeps the bundle a valid polyglot`() {
    val coverPng = png(4, 8)
    val bundleJson =
      """
      {"schemaVersion":2,"backend":"android","previewIds":["a","b"],"coverPreviewId":"a",
       "classpath":[{"kind":"module","path":"classes/app.jar"}],
       "modulePath":":app","producedBy":"test"}
      """
        .trimIndent()
    val file =
      polyglot(
        coverPng,
        linkedMapOf(
          "bundle.json" to bundleJson.toByteArray(),
          "previews/a.png" to coverPng,
          "previews/b.png" to png(6, 6),
        ),
      )
    val prefixLen = file.readBytes().size - BundleReader.extractZipBytes(file).size

    val written =
      injectSemanticsIntoBundle(
        file,
        linkedMapOf(
          "a" to """{"root":{"nodeId":"1","boundsInRoot":"0,0,4,8"}}""".toByteArray(),
          "b" to """{"root":{"nodeId":"2","boundsInRoot":"0,0,6,6"}}""".toByteArray(),
        ),
      )

    assertEquals(2, written)
    // Leading PNG cover is byte-identical — still a polyglot readers detect as PNG.
    assertEquals(coverPng.toList(), file.readBytes().copyOfRange(0, prefixLen).toList())
    val names = entries(BundleReader.extractZipBytes(file))
    // Originals survive; the semantics sidecars land beside the PNGs.
    assertTrue("bundle.json" in names.keys)
    assertTrue("previews/a.png" in names.keys && "previews/b.png" in names.keys)
    assertEquals(
      """{"root":{"nodeId":"1","boundsInRoot":"0,0,4,8"}}""",
      names.getValue("previews/a.semantics.json").toString(Charsets.UTF_8),
    )
    assertEquals(
      """{"root":{"nodeId":"2","boundsInRoot":"0,0,6,6"}}""",
      names.getValue("previews/b.semantics.json").toString(Charsets.UTF_8),
    )
    // The web-embed reader still parses the enriched bundle.
    assertEquals(listOf("a", "b"), BundleReader.readWebEmbedData(file).previews.map { it.id })
  }

  @Test
  fun `injects layout sidecars beside semantics without disturbing either`() {
    val cover = png(4, 8)
    val file =
      polyglot(
        cover,
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "previews/a.png" to cover,
          "previews/a.semantics.json" to """{"root":{"nodeId":"1"}}""".toByteArray(),
        ),
      )

    val written =
      injectLayoutIntoBundle(file, mapOf("a" to """{"root":{"component":"Box"}}""".toByteArray()))

    assertEquals(1, written)
    val names = entries(BundleReader.extractZipBytes(file))
    // Semantics blob untouched; the layout tree lands beside it under the .layout.json suffix.
    assertEquals(
      """{"root":{"nodeId":"1"}}""",
      names.getValue("previews/a.semantics.json").toString(Charsets.UTF_8),
    )
    assertEquals(
      """{"root":{"component":"Box"}}""",
      names.getValue("previews/a.layout.json").toString(Charsets.UTF_8),
    )
  }

  @Test
  fun `injects fonts sidecars beside semantics without disturbing either`() {
    val cover = png(4, 8)
    val file =
      polyglot(
        cover,
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "previews/a.png" to cover,
          "previews/a.semantics.json" to """{"root":{"nodeId":"1"}}""".toByteArray(),
        ),
      )

    val written =
      injectFontsIntoBundle(
        file,
        mapOf("a" to """{"fonts":[{"requestedFamily":"serif","weight":400}]}""".toByteArray()),
      )

    assertEquals(1, written)
    val names = entries(BundleReader.extractZipBytes(file))
    // Semantics blob untouched; the fonts/used record lands beside it under .fonts.json.
    assertEquals(
      """{"root":{"nodeId":"1"}}""",
      names.getValue("previews/a.semantics.json").toString(Charsets.UTF_8),
    )
    assertEquals(
      """{"fonts":[{"requestedFamily":"serif","weight":400}]}""",
      names.getValue("previews/a.fonts.json").toString(Charsets.UTF_8),
    )
  }

  @Test
  fun `injects figma-svg sidecars beside semantics without disturbing either`() {
    val cover = png(4, 8)
    val file =
      polyglot(
        cover,
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "previews/a.png" to cover,
          "previews/a.semantics.json" to """{"root":{"nodeId":"1"}}""".toByteArray(),
        ),
      )

    val svg = """<svg xmlns="http://www.w3.org/2000/svg"><g id="Box"/></svg>"""
    val written = injectFigmaSvgIntoBundle(file, mapOf("a" to svg.toByteArray()))

    assertEquals(1, written)
    val names = entries(BundleReader.extractZipBytes(file))
    // Semantics blob untouched; the layered figma-svg lands beside it under .figma.svg.
    assertEquals(
      """{"root":{"nodeId":"1"}}""",
      names.getValue("previews/a.semantics.json").toString(Charsets.UTF_8),
    )
    assertEquals(svg, names.getValue("previews/a.figma.svg").toString(Charsets.UTF_8))
  }

  @Test
  fun `injects hybrid figma-raster crops under a per-preview dir`() {
    val cover = png(4, 8)
    val file =
      polyglot(
        cover,
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "previews/a.png" to cover,
          "previews/a.figma.svg" to
            """<svg><image href="figma-raster/n1.png"/></svg>""".toByteArray(),
        ),
      )

    val crop = png(6, 6)
    val written = injectFigmaRasterIntoBundle(file, mapOf("a" to linkedMapOf("n1.png" to crop)))

    assertEquals(1, written)
    val names = entries(BundleReader.extractZipBytes(file))
    // The crop lands under previews/<id>.figma-raster/, beside the SVG that references it.
    assertTrue("previews/a.figma-raster/n1.png" in names.keys)
    assertEquals(crop.toList(), names.getValue("previews/a.figma-raster/n1.png").toList())
    // SVG untouched.
    assertTrue("previews/a.figma.svg" in names.keys)
  }

  @Test
  fun `re-injection replaces a stale semantics entry without duplicating it`() {
    val cover = png()
    val file =
      polyglot(
        cover,
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "previews/a.png" to cover,
          "previews/a.semantics.json" to "OLD".toByteArray(),
        ),
      )

    injectSemanticsIntoBundle(file, mapOf("a" to "NEW".toByteArray()))

    val names = entries(BundleReader.extractZipBytes(file))
    assertEquals(1, names.keys.count { it == "previews/a.semantics.json" })
    assertEquals("NEW", names.getValue("previews/a.semantics.json").toString(Charsets.UTF_8))
  }

  @Test
  fun `empty input is a no-op`() {
    val file = polyglot(png(), linkedMapOf("bundle.json" to "{}".toByteArray()))
    val before = file.readBytes().toList()
    assertEquals(0, injectSemanticsIntoBundle(file, emptyMap()))
    assertEquals(before, file.readBytes().toList())
  }

  @Test
  fun `addOrReplaceZipEntries preserves originals and replaces collisions`() {
    val original =
      ByteArrayOutputStream()
        .also { baos ->
          ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("keep.txt"))
            z.write("keep".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("replace.txt"))
            z.write("old".toByteArray())
            z.closeEntry()
          }
        }
        .toByteArray()

    val result =
      entries(
        addOrReplaceZipEntries(
          original,
          mapOf("replace.txt" to "new".toByteArray(), "add.txt" to "added".toByteArray()),
        )
      )

    assertEquals("keep", result.getValue("keep.txt").toString(Charsets.UTF_8))
    assertEquals("new", result.getValue("replace.txt").toString(Charsets.UTF_8))
    assertEquals("added", result.getValue("add.txt").toString(Charsets.UTF_8))
    assertEquals(1, result.keys.count { it == "replace.txt" })
  }
}
