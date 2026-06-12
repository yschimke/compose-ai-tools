package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [BundleReader.readWebEmbedData] — reading the previews a web embed needs out of a
 * real PNG+ZIP polyglot bundle. Builds the polyglot the same way the plugin does (a leading PNG
 * followed by the appended zip) so the reader's polyglot detection is exercised end to end.
 */
class BundleEmbedDataTest {

  private val workRoot = Files.createTempDirectory("bundle-embed-test-").toFile()

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

  @Test
  fun `reads previews in manifest order with cover flagged and labels from previews_json`() {
    val coverPng = png(4, 8)
    val bPng = png(6, 6)
    val bundleJson =
      """
      {"schemaVersion":2,"backend":"desktop","previewIds":["a","b"],"coverPreviewId":"a",
       "classpath":[{"kind":"module","path":"classes/app.jar"}],
       "modulePath":":samples:cmp","producedBy":"test"}
      """
        .trimIndent()
    val previewsJson =
      """
      {"module":":samples:cmp","variant":"main","previews":[
        {"id":"a","functionName":"Alpha","className":"X"},
        {"id":"b","functionName":"Beta","className":"X"}]}
      """
        .trimIndent()
    val file =
      polyglot(
        coverPng,
        linkedMapOf(
          "bundle.json" to bundleJson.toByteArray(),
          "previews.json" to previewsJson.toByteArray(),
          "previews/a.png" to coverPng,
          "previews/b.png" to bPng,
        ),
      )

    val data = BundleReader.readWebEmbedData(file)

    assertEquals(listOf("a", "b"), data.previews.map { it.id })
    assertEquals(listOf("Alpha", "Beta"), data.previews.map { it.label })
    assertTrue(data.previews[0].isCover)
    assertTrue(!data.previews[1].isCover)
    assertEquals(coverPng.toList(), data.previews[0].pngBytes.toList())
  }

  @Test
  fun `previews with no baked png are dropped`() {
    val bundleJson =
      """
      {"schemaVersion":2,"backend":"desktop","previewIds":["a","b","c"],"coverPreviewId":"a",
       "classpath":[{"kind":"module","path":"classes/app.jar"}],
       "modulePath":":m","producedBy":"test"}
      """
        .trimIndent()
    val file =
      polyglot(
        png(),
        linkedMapOf(
          "bundle.json" to bundleJson.toByteArray(),
          // Only a and c have baked PNGs; b is rendered-but-missing and must be dropped.
          "previews/a.png" to png(),
          "previews/c.png" to png(),
        ),
      )

    val data = BundleReader.readWebEmbedData(file)

    // b is omitted; a and c stay in manifest order. Labels fall back to ids (no previews.json).
    assertEquals(listOf("a", "c"), data.previews.map { it.id })
    assertEquals(listOf("a", "c"), data.previews.map { it.label })
  }

  @Test
  fun `end-to-end generate produces a self-contained gallery from the bundle`() {
    val bundleJson =
      """
      {"schemaVersion":2,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
       "classpath":[{"kind":"module","path":"classes/app.jar"}],
       "modulePath":":app","producedBy":"test"}
      """
        .trimIndent()
    val file =
      polyglot(
        png(),
        linkedMapOf("bundle.json" to bundleJson.toByteArray(), "previews/a.png" to png()),
      )

    val data = BundleReader.readWebEmbedData(file)
    val out =
      WebEmbed.generate(
        title = "Demo",
        modulePath = data.manifest.modulePath,
        previews = data.previews,
      )

    assertEquals(1, out.previewCount)
    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    assertTrue("compose-preview-gallery" in script)
    assertTrue("data:image/png;base64," in script)
  }

  private fun entries(zip: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (!e.isDirectory) out[e.name] = zin.readBytes()
        zin.closeEntry()
      }
    }
    return out
  }

  @Test
  fun `embedWebIntoZip adds web files and preserves existing entries`() {
    val original =
      ByteArrayOutputStream()
        .also { baos ->
          ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("bundle.json"))
            z.write("{}".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("previews/a.png"))
            z.write(png())
            z.closeEntry()
          }
        }
        .toByteArray()

    val web =
      mapOf(
        "web/index.html" to "<html></html>".toByteArray(),
        "web/compose-preview-embed.js" to "// js".toByteArray(),
      )
    val result = entries(embedWebIntoZip(original, web))

    // Originals survive untouched; the web files are added.
    assertTrue("bundle.json" in result.keys)
    assertTrue("previews/a.png" in result.keys)
    assertEquals("<html></html>", result.getValue("web/index.html").toString(Charsets.UTF_8))
    assertEquals("// js", result.getValue("web/compose-preview-embed.js").toString(Charsets.UTF_8))
  }

  @Test
  fun `embedWebIntoZip is idempotent — re-embedding replaces stale web entries`() {
    val withOldWeb =
      ByteArrayOutputStream()
        .also { baos ->
          ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("bundle.json"))
            z.write("{}".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("web/index.html"))
            z.write("OLD".toByteArray())
            z.closeEntry()
          }
        }
        .toByteArray()

    val result =
      entries(embedWebIntoZip(withOldWeb, mapOf("web/index.html" to "NEW".toByteArray())))

    // Exactly one web/index.html (no duplicate), carrying the new bytes.
    assertEquals(1, result.keys.count { it == "web/index.html" })
    assertEquals("NEW", result.getValue("web/index.html").toString(Charsets.UTF_8))
  }

  @Test
  fun `in-bundle target requires an explicit output for downloaded url inputs`() {
    // Explicit -o always wins, regardless of source.
    assertEquals("/out.png", resolveInBundleTarget("/out.png", "/tmp/x.png", sourceIsUrl = true))
    assertEquals("/out.png", resolveInBundleTarget("/out.png", "/tmp/x.png", sourceIsUrl = false))
    // Local input with no -o rewrites in place.
    assertEquals("/tmp/x.png", resolveInBundleTarget(null, "/tmp/x.png", sourceIsUrl = false))
    // URL input with no -o resolves to a delete-on-exit temp; refuse so the result isn't lost.
    assertEquals(null, resolveInBundleTarget(null, "/tmp/dl.png", sourceIsUrl = true))
  }

  @Test
  fun `in-bundle embed keeps the bundle a valid polyglot the reader can still parse`() {
    val coverPng = png(4, 8)
    val bundleJson =
      """
      {"schemaVersion":2,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
       "classpath":[{"kind":"module","path":"classes/app.jar"}],
       "modulePath":":samples:cmp","producedBy":"test"}
      """
        .trimIndent()
    val file =
      polyglot(
        coverPng,
        linkedMapOf("bundle.json" to bundleJson.toByteArray(), "previews/a.png" to coverPng),
      )
    val originalPrefixLen = file.readBytes().size - BundleReader.extractZipBytes(file).size

    // Mirror what `embed --in-bundle` does: regenerate the web embed and splice it into the zip,
    // re-attaching the leading PNG prefix.
    val full = file.readBytes()
    val zip = BundleReader.extractZipBytes(file)
    val prefix = full.copyOfRange(0, full.size - zip.size)
    val data = BundleReader.readWebEmbedData(file)
    val out = WebEmbed.generate("Demo", data.manifest.modulePath, data.previews)
    val newZip = embedWebIntoZip(zip, out.files.mapKeys { (rel, _) -> "web/$rel" })
    val enriched = File(workRoot, "enriched.png")
    enriched.outputStream().use {
      it.write(prefix)
      it.write(newZip)
    }

    // The leading PNG cover is byte-identical, so it's still a polyglot readers detect as PNG.
    assertEquals(originalPrefixLen, prefix.size)
    assertEquals(coverPng.toList(), enriched.readBytes().copyOfRange(0, prefix.size).toList())
    // The reader still parses it, and the new web/ entries are present alongside the originals.
    val reread = BundleReader.readWebEmbedData(enriched)
    assertEquals(listOf("a"), reread.previews.map { it.id })
    val names = entries(BundleReader.extractZipBytes(enriched)).keys
    assertTrue("bundle.json" in names && "previews/a.png" in names)
    assertTrue("web/index.html" in names && "web/compose-preview-embed.js" in names)
  }
}
