package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S9 — History recording (fake mode)**. Drives the [FakeDaemonMain] with a
 * `composeai.daemon.historyDir` sysprop set to a per-test temp dir, renders one preview, and
 * asserts:
 * 1. A `historyAdded` notification arrives within the polling window with the expected schema.
 * 2. A sidecar JSON file lives on disk under `<historyDir>/<sanitisedPreviewId>/<id>.json`.
 * 3. The sidecar's `producer == "daemon"`, `trigger == "renderNow"`, and `source.kind == "fs"`.
 * 4. `history/list` over the wire returns the same entry; `history/read` resolves the PNG path.
 *
 * **Git provenance is not gated.** The harness's CI environment may or may not have git on the
 * path; the brief explicitly says don't gate this test on it. We assert the `git` field's PRESENCE
 * (it serializes as either an object with all-nullable fields or as `null`), not on any specific
 * branch/commit value.
 */
class S9HistoryRecordingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun s9_history_recording_fake_mode() {
    val moduleBuildDir = File("build")
    val fixtureDir = File(moduleBuildDir, "daemon-harness/fixtures/s9").apply {
      deleteRecursively()
      mkdirs()
    }
    val historyDir = Files.createTempDirectory("s9-history").toFile().apply { deleteOnExit() }
    val previewId = "preview-1"
    val pngBytes = TestPatterns.solidColour(64, 64, 0xFF202080.toInt())
    val pngFile = File(fixtureDir, "$previewId.png").apply { writeBytes(pngBytes) }
    File(fixtureDir, "previews.json")
      .writeText("""[{"id":"$previewId","className":"fake.S9","functionName":"S9"}]""")

    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
    val launcher =
      FakeHarnessLauncher(
        fixtureDir = fixtureDir,
        classpath = classpath,
        historyDir = historyDir,
      )
    val client = HarnessClient.start(launcher)
    try {
      client.initialize()
      client.sendInitialized()

      val renderNowResult =
        client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), renderNowResult.queued)

      // historyAdded notification — within 5s of renderFinished. We poll for it explicitly
      // (not via pollNotificationMatching("historyAdded")) so the diagnostic on timeout is clear.
      val added = client.pollNotification("historyAdded", 5.seconds)
      val entryJson = added["params"]!!.jsonObject["entry"]!!.jsonObject
      val entryId = entryJson["id"]!!.jsonPrimitive.content
      assertEquals(previewId, entryJson["previewId"]?.jsonPrimitive?.contentOrNull)
      assertEquals("daemon", entryJson["producer"]?.jsonPrimitive?.contentOrNull)
      assertEquals("renderNow", entryJson["trigger"]?.jsonPrimitive?.contentOrNull)
      val sourceObj = entryJson["source"]!!.jsonObject
      assertEquals("fs", sourceObj["kind"]?.jsonPrimitive?.contentOrNull)

      // Sidecar on disk — under <historyDir>/preview-1/<entryId>.json. The previewId "preview-1"
      // sanitises to itself (alnum + '-').
      val sidecarFile = File(File(historyDir, "preview-1"), "$entryId.json")
      assertTrue("sidecar must exist on disk: $sidecarFile", sidecarFile.exists())
      val sidecarText = sidecarFile.readText()
      assertTrue("sidecar must be valid JSON", sidecarText.startsWith("{"))
      // We re-decode to assert producer/trigger came through the disk write too.
      val parsedSidecar = json.parseToJsonElement(sidecarText).jsonObject
      assertEquals("daemon", parsedSidecar["producer"]?.jsonPrimitive?.contentOrNull)
      assertEquals("renderNow", parsedSidecar["trigger"]?.jsonPrimitive?.contentOrNull)
      assertEquals("fs", parsedSidecar["source"]!!.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)

      // history/list returns the entry over the wire.
      val listResult = client.historyList(HistoryListParams())
      assertEquals(1, listResult.totalCount)
      assertEquals(1, listResult.entries.size)
      assertEquals(
        entryId,
        listResult.entries[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      // history/read resolves the PNG path.
      val read = client.historyRead(entryId, inline = false)
      assertNotNull(read.pngPath)
      assertTrue("history/read pngPath must exist on disk", File(read.pngPath).exists())

      // Tear down.
      val exitCode = client.shutdownAndExit()
      assertEquals(0, exitCode)
    } catch (t: Throwable) {
      System.err.println("S9HistoryRecordingTest failed; stderr from daemon:\n${client.dumpStderr()}")
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
