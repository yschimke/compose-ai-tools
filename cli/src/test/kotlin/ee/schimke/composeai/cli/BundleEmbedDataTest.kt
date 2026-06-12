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
}
