package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BundleClasspathHydrationTest {
  @Test
  fun `hydrate restores app jar and removes external declaration and stale signature`() {
    val dir = kotlin.io.path.createTempDirectory("bundle-hydration-test").toFile()
    val appJar = "APP-JAR".encodeToByteArray()
    val sha = sha256(appJar)
    val source = File(dir, "thin.png")
    source.writeBytes(
      zipOf(
        mapOf(
          "bundle.json" to
            """{"schemaVersion":8,"backend":"desktop","previewIds":["A"],"coverPreviewId":"A","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test","externalClasspath":[{"path":"classes/app.jar","sha256":"$sha","size":${appJar.size}}]}"""
              .encodeToByteArray(),
          "previews.json" to """{"previews":[{"id":"A"}]}""".encodeToByteArray(),
          "previews/A.png" to "PNG".encodeToByteArray(),
          BundleSigning.SIGNATURES_PATH to "stale".encodeToByteArray(),
        )
      )
    )

    val output = File(dir, "complete.png")
    BundleClasspathHydration.hydrate(source, output) { appJar }
    val entries = entries(output)
    assertContentEquals(appJar, entries.getValue("classes/app.jar"))
    assertFalse(BundleSigning.SIGNATURES_PATH in entries)
    val manifest =
      Json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    assertFalse("externalClasspath" in manifest)
    assertTrue(manifest["classpath"]!!.jsonArray.isNotEmpty())
    assertContentEquals("PK".encodeToByteArray(), output.readBytes().copyOfRange(0, 2))
  }

  private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      for ((name, bytes) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun entries(file: File): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(file))).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) out[entry.name] = zip.readBytes()
      }
    }
    return out
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
