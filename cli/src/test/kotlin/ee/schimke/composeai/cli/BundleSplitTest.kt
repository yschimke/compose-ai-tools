package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Coverage for [splitBundleZip] — turning a sheet bundle into one bundle per preview. Exercises the
 * pure repackaging directly (no Gradle, no daemon): a synthetic sheet with three previews (one with
 * no baked image) is split in both modes and the outputs are inspected entry-by-entry.
 */
class BundleSplitTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun sheetZip(): ByteArray {
    val entries =
      linkedMapOf(
        "bundle.json" to
          """
          {"schemaVersion":8,"backend":"desktop","previewIds":["A","B","C"],
           "coverPreviewId":"A","resolution":"coordinates",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "intermediateRepresentations":[],"dataExtensions":[{"extensionId":"a11y","path":"extensions/a11y.json"}]}
          """
            .trimIndent()
            .encodeToByteArray(),
        "previews.json" to
          """{"schemaVersion":8,"previews":[{"id":"A"},{"id":"B"},{"id":"C"}]}"""
            .encodeToByteArray(),
        "previews/A.png" to "PNG-A".encodeToByteArray(),
        "previews/A.semantics.json" to """{"nodes":["a"]}""".encodeToByteArray(),
        "previews/A.figma.svg" to "<svg>A</svg>".encodeToByteArray(),
        "previews/B.png" to "PNG-B".encodeToByteArray(),
        // C has NO baked image on purpose — it must be skipped.
        "previews/C.semantics.json" to """{"nodes":["c"]}""".encodeToByteArray(),
        "classes/app.jar" to ByteArray(2048) { 1 },
        "libs/dep.jar" to ByteArray(4096) { 2 },
        "report.json" to """{"reachableClassCount":1}""".encodeToByteArray(),
      )
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zout ->
      for ((name, bytes) in entries) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(bytes)
        zout.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  private fun readEntries(zip: ByteArray): Map<String, ByteArray> {
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
  fun `full split emits one re-renderable bundle per preview with a baked image`() {
    val split = splitBundleZip(sheetZip(), SplitMode.FULL)

    // C is skipped (no baked image); A and B remain, in manifest order.
    assertEquals(listOf("A", "B"), split.map { it.id })

    val a = split.first { it.id == "A" }
    // The cover is A's baked PNG, and the polyglot leads with it.
    assertContentEquals("PNG-A".encodeToByteArray(), a.coverPng)
    assertTrue(a.polyglot().copyOfRange(0, 5).contentEquals("PNG-A".encodeToByteArray()))

    val entries = readEntries(a.zipBytes)
    // A's own sidecars are present; B's image is not.
    assertTrue("previews/A.png" in entries)
    assertTrue("previews/A.semantics.json" in entries)
    assertTrue("previews/A.figma.svg" in entries)
    assertFalse("previews/B.png" in entries)
    // FULL keeps the shared re-render classpath.
    assertTrue("classes/app.jar" in entries)
    assertTrue("libs/dep.jar" in entries)

    val manifest =
      json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    assertEquals(listOf("A"), manifest["previewIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("A", manifest["coverPreviewId"]!!.jsonPrimitive.content)
    // Cover-scoped data-extension pointers are dropped, not carried onto another preview.
    assertFalse("dataExtensions" in manifest)

    val previews =
      json.parseToJsonElement(entries.getValue("previews.json").decodeToString()).jsonObject
    assertEquals(
      listOf("A"),
      previews["previews"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun `view-only split drops the classpath and is smaller`() {
    val full = splitBundleZip(sheetZip(), SplitMode.FULL).first { it.id == "A" }
    val viewOnly = splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }

    val entries = readEntries(viewOnly.zipBytes)
    // No re-render classpath.
    assertFalse("classes/app.jar" in entries)
    assertFalse(entries.keys.any { it.startsWith("libs/") })
    assertFalse("report.json" in entries)
    // But the baked image + sidecars still travel.
    assertTrue("previews/A.png" in entries)
    assertTrue("previews/A.semantics.json" in entries)
    assertTrue("previews/A.figma.svg" in entries)

    val manifest =
      json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    assertEquals("view-only", manifest["resolution"]!!.jsonPrimitive.content)
    assertTrue(manifest["classpath"]!!.jsonArray.isEmpty())

    assertTrue(
      viewOnly.zipBytes.size < full.zipBytes.size,
      "view-only bundle (${viewOnly.zipBytes.size}B) should be smaller than full (${full.zipBytes.size}B)",
    )
  }

  @Test
  fun `split is deterministic`() {
    val a1 = splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }
    val a2 = splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }
    assertContentEquals(a1.zipBytes, a2.zipBytes)
  }
}
