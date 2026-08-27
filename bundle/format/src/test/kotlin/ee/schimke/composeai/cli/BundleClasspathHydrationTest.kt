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
import kotlin.test.assertFailsWith
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

  @Test
  fun `failed hydration never publishes the thin derivative and a later retry succeeds`() {
    val dir = kotlin.io.path.createTempDirectory("bundle-hydration-retry-test").toFile()
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
        )
      )
    )

    val output = File(dir, "complete.png")
    assertFailsWith<IllegalStateException> {
      BundleClasspathHydration.hydrate(source, output) { null }
    }
    assertFalse(output.exists(), "a failed hydration must not cache the thin source as complete")

    BundleClasspathHydration.hydrate(source, output) { appJar }
    assertContentEquals(appJar, entries(output).getValue("classes/app.jar"))
  }

  @Test
  fun `hydrate re-embeds external resources into app jar`() {
    val dir = kotlin.io.path.createTempDirectory("bundle-resource-hydration-test").toFile()
    val font = "FONT-BYTES".encodeToByteArray()
    val fontSha = sha256(font)
    val appJar = zipOf(mapOf("example/A.class" to byteArrayOf(1, 2, 3)))
    val source = File(dir, "external-resources.png")
    source.writeBytes(
      zipOf(
        mapOf(
          "bundle.json" to
            """{"schemaVersion":8,"backend":"desktop","previewIds":["A"],"coverPreviewId":"A","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test","externalResources":[{"path":"fonts/Test.ttf","sha256":"$fontSha","size":${font.size}}]}"""
              .encodeToByteArray(),
          "previews.json" to """{"previews":[{"id":"A"}]}""".encodeToByteArray(),
          "classes/app.jar" to appJar,
          BundleSigning.SIGNATURES_PATH to "stale".encodeToByteArray(),
        )
      )
    )

    val output = File(dir, "complete.png")
    BundleClasspathHydration.hydrate(
      source = source,
      output = output,
      resolveClasspath = { null },
      resolveResource = { font },
    )

    val outer = entries(output)
    assertContentEquals(
      font,
      zipEntries(outer.getValue("classes/app.jar")).getValue("fonts/Test.ttf"),
    )
    val manifest =
      Json.parseToJsonElement(outer.getValue("bundle.json").decodeToString()).jsonObject
    assertFalse("externalResources" in manifest)
    assertFalse(BundleSigning.SIGNATURES_PATH in outer)
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
    return zipEntries(BundleReader.extractZipBytes(file))
  }

  private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
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
