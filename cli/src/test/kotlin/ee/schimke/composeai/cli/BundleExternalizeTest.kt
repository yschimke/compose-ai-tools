package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [BundleExternalize] — lifting the heavy font resources out of a bundle's
 * `classes/app.jar`, publishing them content-addressed by sha256, recording them in the manifest,
 * and staying idempotent + byte-stable across re-runs.
 */
class BundleExternalizeTest {

  private val workRoot = Files.createTempDirectory("bundle-externalize-test-").toFile()

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

  /** Build a jar (raw zip) from name→bytes entries. */
  private fun jar(entries: Map<String, ByteArray>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { z ->
      for ((path, bytes) in entries) {
        z.putNextEntry(ZipEntry(path))
        z.write(bytes)
        z.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  /** Build a PNG+ZIP polyglot bundle from a cover + top-level entries. */
  private fun polyglot(cover: ByteArray, entries: Map<String, ByteArray>): File {
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { z ->
      for ((path, bytes) in entries) {
        z.putNextEntry(ZipEntry(path))
        z.write(bytes)
        z.closeEntry()
      }
    }
    val file = File(workRoot, "bundle-${entries.hashCode()}-${cover.size}.png")
    file.outputStream().use {
      it.write(cover)
      it.write(zip.toByteArray())
    }
    return file
  }

  private fun jarEntries(bundle: File, jarPath: String): Map<String, ByteArray> {
    val zip = BundleReader.extractZipBytes(bundle)
    var jarBytes: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (e.name == jarPath) jarBytes = zin.readBytes()
        zin.closeEntry()
      }
    }
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(jarBytes!!)).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (!e.isDirectory) out[e.name] = zin.readBytes()
        zin.closeEntry()
      }
    }
    return out
  }

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val manifestJson =
    """
    {"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
     "classpath":[{"kind":"module","path":"classes/app.jar"}],
     "modulePath":":samples:design-catalog-m3","producedBy":"test"}
    """
      .trimIndent()

  private fun bundleWith(font: ByteArray, extraJar: Map<String, ByteArray> = emptyMap()): File {
    val cover = png(4, 8)
    val appJar =
      jar(
        mapOf(
          "com/example/CatalogKt.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 0),
          "fonts/Roboto-Regular.ttf" to font,
        ) + extraJar
      )
    return polyglot(
      cover,
      linkedMapOf(
        "bundle.json" to manifestJson.toByteArray(),
        "previews.json" to """{"previews":[{"id":"a","functionName":"A"}]}""".toByteArray(),
        "classes/app.jar" to appJar,
        "previews/a.png" to cover,
      ),
    )
  }

  @Test
  fun `strips fonts, writes them content-addressed, records them in the manifest, and shrinks`() {
    val font = ByteArray(4000) { (it % 251).toByte() }
    val bundle = bundleWith(font)
    val before = bundle.length()
    val resDir = File(workRoot, "res")

    val result = BundleExternalize.externalize(bundle, resDir)

    // One font lifted out, recorded by path + sha256 + size.
    assertEquals(1, result.externalized.size)
    val rec = result.externalized.single()
    assertEquals("fonts/Roboto-Regular.ttf", rec.path)
    assertEquals(sha256(font), rec.sha256)
    assertEquals(4000L, rec.size)

    // The bytes are written content-addressed under res/<sha256> and match the original.
    val poolFile = File(resDir, rec.sha256)
    assertTrue(poolFile.isFile)
    assertEquals(font.toList(), poolFile.readBytes().toList())

    // The font is gone from classes/app.jar; the class stays.
    val jarNames = jarEntries(bundle, "classes/app.jar").keys
    assertFalse("fonts/Roboto-Regular.ttf" in jarNames)
    assertTrue("com/example/CatalogKt.class" in jarNames)

    // The manifest records it in externalResources (parsed via the CLI mirror).
    val manifest = BundleReader.readMetadata(bundle).manifest
    assertEquals(1, manifest.externalResources.size)
    assertEquals("fonts/Roboto-Regular.ttf", manifest.externalResources.single().path)
    assertEquals(sha256(font), manifest.externalResources.single().sha256)
    // Other manifest fields survive the raw-JSON edit.
    assertEquals("desktop", manifest.backend)
    assertEquals(":samples:design-catalog-m3", manifest.modulePath)

    // The bundle shrank by roughly the font size.
    assertTrue(bundle.length() < before, "expected the bundle to shrink after externalizing")
  }

  @Test
  fun `dedupes identical fonts to a single content-addressed file`() {
    val font = ByteArray(2000) { (it % 97).toByte() }
    // Two entries with identical bytes → one pool file.
    val bundle = bundleWith(font, extraJar = mapOf("fonts/Roboto-Medium.ttf" to font))
    val resDir = File(workRoot, "res")

    val result = BundleExternalize.externalize(bundle, resDir)

    assertEquals(2, result.externalized.size)
    assertEquals(1, result.externalized.map { it.sha256 }.toSet().size)
    // Only one physical file in the pool (deduped by content address).
    assertEquals(listOf(sha256(font)), resDir.listFiles()!!.map { it.name })
  }

  @Test
  fun `re-running externalize is idempotent`() {
    val font = ByteArray(1500) { (it % 61).toByte() }
    val bundle = bundleWith(font)
    val resDir = File(workRoot, "res")

    BundleExternalize.externalize(bundle, resDir)
    val afterFirst = bundle.readBytes()
    // A second run finds the font already gone from the jar → externalizes nothing new, and the
    // bundle bytes are unchanged (byte-stable).
    val second = BundleExternalize.externalize(bundle, resDir)
    assertEquals(0, second.externalized.size)
    assertEquals(afterFirst.toList(), bundle.readBytes().toList())
    // The manifest still records exactly one resource (not duplicated).
    assertEquals(1, BundleReader.readMetadata(bundle).manifest.externalResources.size)
  }

  @Test
  fun `respects a custom extension list`() {
    val font = ByteArray(800) { it.toByte() }
    val bundle =
      bundleWith(font, extraJar = mapOf("data/strings.bin" to ByteArray(500) { it.toByte() }))
    val resDir = File(workRoot, "res")

    // Only lift .bin — the ttf stays inline.
    val result = BundleExternalize.externalize(bundle, resDir, extensions = listOf("bin"))

    assertEquals(listOf("data/strings.bin"), result.externalized.map { it.path })
    val jarNames = jarEntries(bundle, "classes/app.jar").keys
    assertTrue("fonts/Roboto-Regular.ttf" in jarNames)
    assertFalse("data/strings.bin" in jarNames)
  }
}
