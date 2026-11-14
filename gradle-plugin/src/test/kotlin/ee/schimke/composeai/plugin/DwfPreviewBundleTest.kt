package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewKind
import ee.schimke.composeai.discovery.PreviewManifest
import ee.schimke.composeai.discovery.PreviewParams
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DwfPreviewBundleTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `hasPreviewDependency passes for pure-DWF module with WatchFace XML under src`() {
    val projectDir = tmp.newFolder("pure-dwf")
    File(projectDir, "src/main/res/raw").mkdirs()
    File(projectDir, "src/main/res/raw/watchface.xml")
      .writeText("""<WatchFace width="450" height="450"><Scene/></WatchFace>""")

    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    assertThat(ComposePreviewTasks.hasDwfXmlSources(project)).isTrue()
    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isTrue()
  }

  @Test
  fun `PrepareRobolectricManifestTask marks DWF entries as SVG so Robolectric skips them`() {
    val outDir = tmp.newFolder("robo-manifest")
    val manifest =
      PreviewManifest(
        module = ":wear",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "dwf__raw_watchface",
              functionName = "watchface",
              className = "",
              params =
                PreviewParams(
                  name = "watchface",
                  kind = PreviewKind.COMPOSE,
                  dwfAssetPath = "raw/watchface.xml",
                ),
              captures = listOf(Capture(renderOutput = "dwf-renders/dwf__raw_watchface.png")),
            ),
            PreviewInfo(
              id = "com.example.WearKt.CardPreview",
              functionName = "CardPreview",
              className = "com.example.WearKt",
              params = PreviewParams(name = "CardPreview", kind = PreviewKind.COMPOSE),
              captures = listOf(Capture(renderOutput = "renders/CardPreview.png")),
            ),
          ),
      )
    val inFile =
      File(outDir, "previews.json").apply {
        writeText(json.encodeToString(PreviewManifest.serializer(), manifest))
      }
    val outFile = File(outDir, "previews-robolectric.json")

    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register("prepareRoboManifest", PrepareRobolectricManifestTask::class.java)
        .get()
    task.previewsJson.set(inFile)
    task.outputFile.set(outFile)
    task.rewrite()

    val rewritten = json.decodeFromString(PreviewManifest.serializer(), outFile.readText())
    assertThat(rewritten.previews[0].params.kind).isEqualTo(PreviewKind.SVG)
    assertThat(rewritten.previews[1].params.kind).isEqualTo(PreviewKind.COMPOSE)

    val expanded = PreviewPermutations.expand(manifest.previews, listOf("accessibility"))
    assertThat(expanded.map { it.id })
      .containsExactly(
        "dwf__raw_watchface",
        "com.example.WearKt.CardPreview",
        "com.example.WearKt.CardPreview_dark",
        "com.example.WearKt.CardPreview_rtl",
        "com.example.WearKt.CardPreview_fontscale-2x",
      )
      .inOrder()
  }

  @Test
  fun `BundlePreviewTask packs DWF PNG, RemoteCompose IR, and knob declarations from dwf-renders`() {
    val outDir = tmp.newFolder("dwf-bundle")
    val stem = "dwf__raw_watchface"
    val manifest =
      PreviewManifest(
        module = ":wear",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = stem,
              functionName = "watchface",
              className = "",
              params =
                PreviewParams(
                  name = "watchface",
                  widthDp = 450,
                  heightDp = 450,
                  density = 1.0f,
                  dwfAssetPath = "raw/watchface.xml",
                ),
              captures = listOf(Capture(renderOutput = "dwf-renders/$stem.png")),
            ),
          ),
      )
    val previewsJson =
      File(outDir, "previews.json").apply {
        writeText(json.encodeToString(PreviewManifest.serializer(), manifest))
      }
    val rendersDir = File(outDir, "renders").apply { mkdirs() }
    val dwfRendersDir = File(outDir, "dwf-renders").apply { mkdirs() }
    // Minimal valid 1x1 PNG bytes so polyglot cover extraction works cleanly.
    val tinyPng =
      byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        0x15,
        0xC4.toByte(),
        0x89.toByte(),
        0x00,
        0x00,
        0x00,
        0x0A,
        0x49,
        0x44,
        0x41,
        0x54,
        0x78,
        0x9C.toByte(),
        0x63,
        0x00,
        0x01,
        0x00,
        0x00,
        0x05,
        0x00,
        0x01,
        0x0D,
        0x0A,
        0x2D,
        0xB4.toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        0xAE.toByte(),
        0x42,
        0x60,
        0x82.toByte(),
      )
    File(dwfRendersDir, "$stem.png").writeBytes(tinyPng)
    File(dwfRendersDir, "$stem.rc").writeBytes(byteArrayOf(1, 2, 3, 4))
    File(dwfRendersDir, "$stem.remotecompose.json")
      .writeText(
        """{"capturePlayer":"cmp-jvm","declarations":[{"name":"dwf.is_ambient","domain":"SYSTEM","default":{"type":"boolean","value":false}}]}""",
      )
    File(dwfRendersDir, "$stem.watchface.json")
      .writeText(
        """{"previewId":"$stem","dwfAssetPath":"raw/watchface.xml","widthPx":450,"heightPx":450,"slots":[{"slotId":1,"supportedTypes":["SHORT_TEXT"],"defaultType":"SHORT_TEXT","activeState":{"type":"SHORT_TEXT","text":"72"}}]}""",
      )

    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task = project.tasks.register("composePreviewBundle", BundlePreviewTask::class.java).get()
    task.previewsJson.set(previewsJson)
    task.output.set(File(outDir, "bundle.png"))
    task.rendersDir.set(rendersDir)
    task.modulePath.set(":wear")
    task.producedBy.set("test")
    task.backend.set("dwf")
    task.previewIds.set(emptyList())
    task.pack()

    val zipBytes = extractZipBytes(task.output.get().asFile.readBytes())
    val entries = readAllZipEntries(zipBytes)
    assertThat(entries.keys).contains("ir/$stem.rc")
    assertThat(entries.keys).contains("previews/$stem.png")
    assertThat(entries.keys).contains("previews/$stem.remotecompose.json")
    assertThat(entries.keys).contains("previews/$stem.watchface.json")

    val bundleJson =
      Json.parseToJsonElement(entries.getValue("bundle.json").decodeToString()).jsonObject
    assertThat(bundleJson["backend"]?.jsonPrimitive?.content).isEqualTo("dwf")
    val irEntry = bundleJson.getValue("intermediateRepresentations").jsonArray.single().jsonObject
    assertThat(irEntry["format"]?.jsonPrimitive?.content).isEqualTo("remotecompose")
    val rcSidecar =
      Json.parseToJsonElement(
          entries.getValue("previews/$stem.remotecompose.json").decodeToString(),
        )
        .jsonObject
    assertThat(rcSidecar["capturePlayer"]?.jsonPrimitive?.content).isEqualTo("cmp-jvm")
    val wfSidecar =
      Json.parseToJsonElement(
          entries.getValue("previews/$stem.watchface.json").decodeToString(),
        )
        .jsonObject
    assertThat(wfSidecar["dwfAssetPath"]?.jsonPrimitive?.content).isEqualTo("raw/watchface.xml")
  }

  @Test
  fun `bundleRenderable admits codeless dwf backend on Android and KMP runtime configs`() {
    assertThat(ComposePreviewTasks.bundleRenderable("dwf", "debugRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.bundleRenderable("dwf", "androidRuntimeClasspath")).isTrue()
  }

  private fun readAllZipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
    val map = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        map[entry.name] = zin.readBytes()
        zin.closeEntry()
      }
    }
    return map
  }

  private fun extractZipBytes(bytes: ByteArray): ByteArray {
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    var offset = 8
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return bytes.copyOfRange(offset, bytes.size)
    }
    error("PNG IEND not found")
  }
}
