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
import kotlin.test.assertNull
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
          {"schemaVersion":9,"backend":"desktop","previewIds":["A","B","C"],
           "coverPreviewId":"A","resolution":"coordinates",
           "repositories":["https://androidx.dev/snapshots/builds/16113093/artifacts/repository"],
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
  fun `a full split keeps the bundle repositories and a view-only split drops them`() {
    // The per-preview bundles ARE the live lane for a `split-mode: full` catalog, so a repository
    // list the sheet needed to resolve its coordinates has to survive the split — dropping it puts
    // the daemon back on the incomplete classpath of #4259 / #4265. A view-only bundle carries no
    // coordinates, so it must not advertise repositories for them either.
    val full = manifestOf(splitBundleZip(sheetZip(), SplitMode.FULL).first().zipBytes)
    assertEquals(
      listOf("https://androidx.dev/snapshots/builds/16113093/artifacts/repository"),
      full.getValue("repositories").jsonArray.map { it.jsonPrimitive.content },
    )

    val viewOnly = manifestOf(splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first().zipBytes)
    assertNull(viewOnly["repositories"])
  }

  private fun manifestOf(zip: ByteArray) =
    json.parseToJsonElement(readEntries(zip).getValue("bundle.json").decodeToString()).jsonObject

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
  fun `shared classpath split publishes app jar once and records its digest`() {
    val pooled = LinkedHashMap<SharedClasspathEntry, ByteArray>()
    val split = ArrayList<SplitPreview>()
    forEachSplitPreview(
      sheetZip(),
      SplitMode.FULL_SHARED_CLASSPATH,
      onSharedClasspath = { entry, bytes -> pooled[entry] = bytes },
    ) {
      split += it
    }

    assertEquals(1, pooled.size)
    val record = pooled.keys.single()
    assertEquals("classes/app.jar", record.path)
    assertEquals(2048L, record.size)
    assertContentEquals(ByteArray(2048) { 1 }, pooled.values.single())

    val entries = readEntries(split.first { it.id == "A" }.zipBytes)
    assertFalse("classes/app.jar" in entries)
    assertTrue("libs/dep.jar" in entries)
    val manifest =
      json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    val external = manifest["externalClasspath"]!!.jsonArray.single().jsonObject
    assertEquals("classes/app.jar", external["path"]!!.jsonPrimitive.content)
    assertEquals(record.sha256, external["sha256"]!!.jsonPrimitive.content)
    assertEquals("2048", external["size"]!!.jsonPrimitive.content)
  }

  @Test
  fun `split is deterministic`() {
    val a1 = splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }
    val a2 = splitBundleZip(sheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }
    assertContentEquals(a1.zipBytes, a2.zipBytes)
  }

  /**
   * An Android sheet also carries the app-resource payload under `android/` (the merged
   * `resources.ap_` table, manifest, and generated R classes) plus an `androidResources` manifest
   * pointer — the carriage a detached daemon needs to resolve `stringResource(R.string.…)`.
   */
  private fun androidSheetZip(): ByteArray {
    val entries =
      linkedMapOf(
        "bundle.json" to
          """
          {"schemaVersion":8,"backend":"android","previewIds":["A"],
           "coverPreviewId":"A","resolution":"coordinates",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "androidResources":{"resourceApkPath":"android/resources.ap_",
             "mergedManifestPath":"android/AndroidManifest.xml",
             "rClassesJarPath":"android/r-classes.jar",
             "applicationPackage":"com.example.app"},
           "intermediateRepresentations":[],"dataExtensions":[]}
          """
            .trimIndent()
            .encodeToByteArray(),
        "previews.json" to """{"schemaVersion":8,"previews":[{"id":"A"}]}""".encodeToByteArray(),
        "previews/A.png" to "PNG-A".encodeToByteArray(),
        "classes/app.jar" to ByteArray(2048) { 1 },
        "android/resources.ap_" to ByteArray(1024) { 7 },
        "android/AndroidManifest.xml" to "<manifest/>".encodeToByteArray(),
        "android/r-classes.jar" to ByteArray(512) { 9 },
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

  @Test
  fun `full split carries the android app-resource payload so a detached daemon resolves stringResource`() {
    val a = splitBundleZip(androidSheetZip(), SplitMode.FULL).first { it.id == "A" }
    val entries = readEntries(a.zipBytes)

    // The whole `android/` carriage travels into the per-preview bundle — the merged resource APK,
    // the manifest, and the generated R classes — matching what the monolithic pack carries.
    assertTrue("android/resources.ap_" in entries, "merged resource APK must travel")
    assertTrue("android/AndroidManifest.xml" in entries, "merged manifest must travel")
    assertTrue("android/r-classes.jar" in entries, "generated R classes must travel")
    assertContentEquals(ByteArray(1024) { 7 }, entries.getValue("android/resources.ap_"))

    // The manifest keeps its `androidResources` pointer, now backed by the carried files.
    val manifest =
      json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    val res = manifest["androidResources"]!!.jsonObject
    assertEquals("android/resources.ap_", res["resourceApkPath"]!!.jsonPrimitive.content)
    assertEquals("com.example.app", res["applicationPackage"]!!.jsonPrimitive.content)
  }

  @Test
  fun `view-only split drops the android payload and its manifest pointer`() {
    val a = splitBundleZip(androidSheetZip(), SplitMode.VIEW_ONLY).first { it.id == "A" }
    val entries = readEntries(a.zipBytes)

    // A baked, non-re-rendering sticker carries no `android/` payload…
    assertFalse(entries.keys.any { it.startsWith("android/") })
    // …and doesn't advertise a `resources.ap_` table it isn't shipping.
    val manifest =
      json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    assertFalse("androidResources" in manifest)
    // The baked image still travels.
    assertTrue("previews/A.png" in entries)
  }

  /**
   * The property that stopped a 181-preview catalog OOMing in CI: the splitter hands each bundle to
   * the caller and keeps no reference, so peak memory is the source plus ONE output rather than one
   * per preview. Every FULL bundle carries the shared classpath and Android resource payload, so
   * accumulating them multiplies that payload by the preview count.
   *
   * Pinned structurally rather than by measuring heap: what the streaming pass emits must match the
   * list API exactly, so the list API can stay a thin wrapper over it and callers that care about
   * memory (the CLI) can write each bundle out and drop it.
   */
  @Test
  fun `streaming split emits the same bundles as the list API`() {
    val zip = sheetZip()
    val seen = mutableListOf<String>()
    var everyBundleCarriedItsZip = true

    val emitted =
      forEachSplitPreview(zip, SplitMode.FULL) { preview ->
        seen += preview.id
        if (preview.zipBytes.isEmpty()) everyBundleCarriedItsZip = false
      }

    val viaList = splitBundleZip(zip, SplitMode.FULL)
    assertEquals(viaList.size, emitted, "the streaming pass emits the same count")
    assertEquals(viaList.map { it.id }, seen, "in the same order")
    assertTrue(everyBundleCarriedItsZip, "each emitted bundle is complete when handed over")
  }

  private fun carriageOf(zip: ByteArray, mode: SplitMode): SplitCarriage {
    var carriage = SplitCarriage.NONE
    forEachSplitPreview(zip, mode, onCarriage = { carriage = it }) {}
    return carriage
  }

  /**
   * The size of a FULL split is a function of the **preview count**, not of the backend — the
   * carriage is a fixed per-bundle cost paid N times. Nothing measured that, so the growth was
   * invisible on a green run; the split now reports it before it writes anything.
   */
  @Test
  fun `full split measures the carriage it copies into every bundle`() {
    val carriage = carriageOf(sheetZip(), SplitMode.FULL)

    assertEquals(
      listOf("libs/dep.jar", "classes/app.jar", "report.json"),
      carriage.entries.map { it.path },
      "every shared entry is reported, largest first",
    )
    assertEquals(4096L, carriage.entries.first().bytes)
    assertTrue(carriage.bytesPerBundle > 0, "the carriage costs something in each bundle")
    // Measured as written (deflated), so it must not exceed what a whole bundle carrying it weighs.
    val fullBundle = splitBundleZip(sheetZip(), SplitMode.FULL).first()
    assertTrue(
      carriage.bytesPerBundle <= fullBundle.zipBytes.size,
      "carriage ${carriage.bytesPerBundle} exceeds the bundle that carries it " +
        "(${fullBundle.zipBytes.size})",
    )
  }

  @Test
  fun `view-only split carries nothing, so its carriage is zero`() {
    assertEquals(SplitCarriage.NONE, carriageOf(sheetZip(), SplitMode.VIEW_ONLY))
  }

  @Test
  fun `pooling app jar takes it out of the per-bundle carriage`() {
    val carriage = carriageOf(sheetZip(), SplitMode.FULL_SHARED_CLASSPATH)

    assertFalse(
      carriage.entries.any { it.path == "classes/app.jar" },
      "the pooled jar is published once, not carried per bundle",
    )
    assertTrue(carriage.entries.any { it.path == "libs/dep.jar" }, "libs/ still repeats")
    assertTrue(
      carriage.bytesPerBundle < carriageOf(sheetZip(), SplitMode.FULL).bytesPerBundle,
      "pooling lowers the per-bundle carriage",
    )
  }

  /**
   * The old warning fired on bundle size and named `--view-only`, which a tier that exists to
   * re-render cannot take — so it was noise on exactly the catalogs it should have caught. The
   * signal now fires on the carriage share and names the remedy that fits the mode.
   */
  @Test
  fun `a dominant carriage is reported with the remedy for its mode`() {
    val carriage = SplitCarriage(625_114L, listOf(SplitCarriageEntry("classes/app.jar", 620_000L)))
    val summary =
      SplitCarriageSummary(SplitMode.FULL, carriage, bundles = 1296, totalBytes = 828_091_478L)

    assertTrue(summary.dominates)
    assertEquals(810_147_744L, summary.repeatedBytes)
    val warning = summary.warning()!!
    assertTrue(warning.contains("625114 bytes in each of 1296 bundle(s)"), warning)
    assertTrue(warning.contains("--shared-classpath-out"), warning)
    assertTrue(warning.contains("classes/app.jar (620000 bytes)"), warning)

    val pooled =
      SplitCarriageSummary(
        SplitMode.FULL_SHARED_CLASSPATH,
        carriage,
        bundles = 1296,
        totalBytes = 828_091_478L,
      )
    assertTrue(pooled.warning()!!.contains("already pooled"), "the remedy fits the mode")
  }

  @Test
  fun `a carriage that is not the story stays quiet`() {
    val summary =
      SplitCarriageSummary(
        SplitMode.FULL,
        SplitCarriage(1_000L, listOf(SplitCarriageEntry("classes/app.jar", 900L))),
        bundles = 40,
        totalBytes = 10_000_000L,
      )

    assertFalse(summary.dominates)
    assertEquals(null, summary.warning())
  }

  /** A single-bundle split repeats nothing by definition, whatever the share arithmetic says. */
  @Test
  fun `one bundle never counts as repetition`() {
    val summary =
      SplitCarriageSummary(
        SplitMode.FULL,
        SplitCarriage(900_000L, listOf(SplitCarriageEntry("classes/app.jar", 900_000L))),
        bundles = 1,
        totalBytes = 1_000_000L,
      )

    assertFalse(summary.dominates)
  }

  /** The publisher gates on this JSON, so its shape is part of the contract. */
  @Test
  fun `the carriage report carries the numbers a publisher gates on`() {
    val summary =
      SplitCarriageSummary(
        SplitMode.FULL,
        SplitCarriage(625_114L, listOf(SplitCarriageEntry("classes/app.jar", 620_000L))),
        bundles = 1296,
        totalBytes = 828_091_478L,
      )

    val report = json.parseToJsonElement(summary.toJson()).jsonObject
    assertEquals("full", report["mode"]!!.jsonPrimitive.content)
    assertEquals(1296, report["bundles"]!!.jsonPrimitive.content.toInt())
    assertEquals(625_114L, report["carriageBytesPerBundle"]!!.jsonPrimitive.content.toLong())
    assertEquals(810_147_744L, report["repeatedBytes"]!!.jsonPrimitive.content.toLong())
    assertEquals(828_091_478L, report["totalBytes"]!!.jsonPrimitive.content.toLong())
    assertEquals(97.8, report["sharePercent"]!!.jsonPrimitive.content.toDouble())
    assertEquals(true, report["dominates"]!!.jsonPrimitive.content.toBoolean())
    assertEquals(
      "classes/app.jar",
      report["carriageEntries"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content,
    )
  }
}
