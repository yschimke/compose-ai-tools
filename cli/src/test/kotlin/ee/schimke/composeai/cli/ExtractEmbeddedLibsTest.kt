package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [BundleReader.extractEmbeddedLibs] — the shared `libs/` extractor both
 * [BundleRenderer] and [BundleDaemonCommand] use to put an embedded-mode bundle's third-party jars
 * on the consumer classpath. Operates on raw zip bytes (the player passes the polyglot's appended
 * zip), so these tests build a plain zip in memory.
 */
class ExtractEmbeddedLibsTest {

  private val workRoot = Files.createTempDirectory("extract-libs-test-").toFile()

  @AfterTest
  fun cleanup() {
    workRoot.deleteRecursively()
  }

  private fun libsDir(name: String) = File(workRoot, name).apply { mkdirs() }

  private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      for ((path, bytes) in entries) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  @Test
  fun `extracts every libs jar sorted by name`() {
    val zip =
      zipOf(
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "classes/app.jar" to byteArrayOf(1, 2, 3),
          // Intentionally out of order to prove the result is name-sorted.
          "libs/zebra.jar" to byteArrayOf(9),
          "libs/alpha.jar" to byteArrayOf(7),
        )
      )

    val out = BundleReader.extractEmbeddedLibs(zip, libsDir("a"))

    assertEquals(listOf("alpha.jar", "zebra.jar"), out.map { it.name })
    assertTrue(out.all { it.isFile })
    assertEquals(listOf<Byte>(7), out[0].readBytes().toList())
  }

  @Test
  fun `coordinate bundle with no libs yields empty list`() {
    val zip =
      zipOf(
        linkedMapOf(
          "bundle.json" to "{}".toByteArray(),
          "classes/app.jar" to byteArrayOf(1),
          "previews.json" to "{}".toByteArray(),
        )
      )

    assertTrue(BundleReader.extractEmbeddedLibs(zip, libsDir("b")).isEmpty())
  }

  @Test
  fun `ignores non-jar libs entries and flattens nested jars to basenames`() {
    val zip =
      zipOf(
        linkedMapOf(
          "libs/keep.jar" to byteArrayOf(1),
          "libs/notes.txt" to byteArrayOf(2), // not a .jar — skipped
          "libs/nested/deep.jar" to byteArrayOf(3), // nested — taken, flattened to basename
        )
      )

    val out = BundleReader.extractEmbeddedLibs(zip, libsDir("c"))

    // The .txt is skipped; both jars are kept, the nested one flattened to its basename. Every
    // extracted file lives directly under libsDir.
    assertEquals(listOf("deep.jar", "keep.jar"), out.map { it.name })
    assertTrue(out.all { it.parentFile.name == "c" })
  }

  @Test
  fun `zip-slip traversal cannot escape the libs dir`() {
    val libs = libsDir("d")
    val zip = zipOf(linkedMapOf("libs/../../evil.jar" to byteArrayOf(66)))

    val out = BundleReader.extractEmbeddedLibs(zip, libs)

    // Flattening to the basename neutralises the `../` traversal: the entry is written safely as
    // libsDir/evil.jar, and crucially NOT at the parent dir it tried to escape to.
    assertEquals(listOf("evil.jar"), out.map { it.name })
    assertTrue(out.single().canonicalFile.parentFile == libs.canonicalFile)
    assertFalse(
      File(libs.parentFile, "evil.jar").exists(),
      "traversal entry must not land outside libsDir",
    )
  }
}
