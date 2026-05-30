package ee.schimke.composeai.tui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Unit coverage for [BundlePngMetadata.readContents] — the detached-bundle reader that pulls every
 * baked `previews/<id>.png` (schema v2) out of a PNG+ZIP polyglot so the TUI can page through a
 * bundle with no project on disk.
 */
class BundleContentsTest {

  @TempDir lateinit var tempDir: File

  @Test
  fun `reads baked previews cover-first`() {
    val bundle =
      writeBundle(
        cover = "pkg.Foo",
        modulePath = ":sample",
        previewPngs = linkedMapOf("pkg.Baz" to png(0x111111), "pkg.Foo" to png(0x222222)),
      )

    val contents = BundlePngMetadata.readContents(bundle)

    assertEquals(":sample", contents.metadata?.modulePath)
    // Cover (pkg.Foo) sorts first; the rest follow by id.
    assertEquals(listOf("pkg.Foo", "pkg.Baz"), contents.previews.map { it.id })
    assertTrue(contents.previews.all { it.pngBytes.isNotEmpty() })
  }

  @Test
  fun `non-bundle file yields empty contents`() {
    val notABundle = File(tempDir, "random.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val contents = BundlePngMetadata.readContents(notABundle)
    assertNull(contents.metadata)
    assertTrue(contents.previews.isEmpty())
  }

  @Test
  fun `v1-shaped bundle with no previews dir reads zero previews`() {
    val bundle = writeBundle(cover = "pkg.Foo", modulePath = ":sample", previewPngs = linkedMapOf())
    val contents = BundlePngMetadata.readContents(bundle)
    assertEquals(":sample", contents.metadata?.modulePath)
    assertTrue(contents.previews.isEmpty())
  }

  /** Builds a PNG+ZIP polyglot: a tiny PNG cover, then a zip with bundle.json + previews/*.png. */
  private fun writeBundle(
    cover: String,
    modulePath: String,
    previewPngs: Map<String, ByteArray>,
  ): File {
    val coverPng = previewPngs[cover] ?: png(0x808080)
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { zos ->
      val previewIds = previewPngs.keys.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
      val bundleJson =
        "{\"schemaVersion\":2,\"backend\":\"desktop\",\"previewIds\":$previewIds,\"coverPreviewId\":\"$cover\",\"classpath\":[],\"modulePath\":\"$modulePath\",\"producedBy\":\"test\"}"
      zos.putNextEntry(ZipEntry("bundle.json"))
      zos.write(bundleJson.toByteArray())
      zos.closeEntry()
      previewPngs.forEach { (id, bytes) ->
        zos.putNextEntry(ZipEntry("previews/$id.png"))
        zos.write(bytes)
        zos.closeEntry()
      }
    }
    val out = File(tempDir, "bundle.png")
    out.outputStream().use {
      it.write(coverPng)
      it.write(zip.toByteArray())
    }
    return out
  }

  private fun png(rgb: Int): ByteArray {
    val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until 2) for (x in 0 until 2) img.setRGB(x, y, rgb and 0xFFFFFF)
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }
}
