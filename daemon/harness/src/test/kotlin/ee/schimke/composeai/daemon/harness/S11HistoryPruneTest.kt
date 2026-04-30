package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistorySourceInfo
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryPruneParams
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S11 — History prune (fake mode)**. Pre-populates a [LocalFsHistorySource] under
 * `<tmpHistoryDir>` with N entries on a single preview, drives [FakeDaemonMain] (no rendering)
 * with `composeai.daemon.history.maxEntriesPerPreview=3`, calls `history/prune` over the wire,
 * and asserts the wire-shape result matches the disk effect.
 *
 * Mirrors the S* fake-mode pattern: the daemon only sees the pre-populated history dir; nothing is
 * rendered during the test. We exercise the prune wire-format end-to-end without any renderer.
 */
class S11HistoryPruneTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  @Test
  fun s11_history_prune_fake_mode() {
    val moduleBuildDir = File("build")
    val fixtureDir =
      File(moduleBuildDir, "daemon-harness/fixtures/s11").apply {
        deleteRecursively()
        mkdirs()
      }
    File(fixtureDir, "previews.json").writeText("[]")

    val historyDir = Files.createTempDirectory("s11-history").toFile().apply { deleteOnExit() }
    // Populate the history dir with 5 entries on a single preview. We write directly via
    // LocalFsHistorySource so the index.jsonl + sidecars + PNGs land in the same shape the daemon
    // would produce.
    val previewId = "preview-A"
    val previewDir = File(historyDir, previewId)
    val source = LocalFsHistorySource(historyDir = historyDir.toPath())
    val written =
      (0 until 5).map { i ->
        val ts = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(i.toLong())
        val isoTs = ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val tsStem = ts.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val bytes = "render-$i".toByteArray(StandardCharsets.UTF_8)
        val hash = LocalFsHistorySource.sha256Hex(bytes)
        val id = "$tsStem-${hash.take(8)}"
        val entry =
          HistoryEntry(
            id = id,
            previewId = previewId,
            module = ":harness",
            timestamp = isoTs,
            pngHash = hash,
            pngSize = bytes.size.toLong(),
            pngPath = "$id.png",
            producer = "daemon",
            trigger = "renderNow",
            source =
              HistorySourceInfo(kind = "fs", id = "fs:${historyDir.toPath().toAbsolutePath()}"),
            renderTookMs = 1L,
          )
        source.write(entry, bytes)
        entry
      }

    // Sanity check: 5 sidecars + 5 PNGs + 1 index.
    assertEquals(5, previewDir.listFiles { _, name -> name.endsWith(".json") }!!.size)
    assertEquals(5, previewDir.listFiles { _, name -> name.endsWith(".png") }!!.size)

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
        // Disable auto-prune by setting interval to 0 — the test drives prune manually.
        pruneAutoIntervalMs = 0L,
      )
    val client = HarnessClient.start(launcher)
    try {
      client.initialize()
      client.sendInitialized()

      // Manual prune with maxEntriesPerPreview=2: 5 entries → 2 newest survive (newest by floor +
      // newest-1 by cap), 3 dropped.
      val result =
        client.historyPrune(
          HistoryPruneParams(
            maxEntriesPerPreview = 2,
            maxAgeDays = 0,
            maxTotalSizeBytes = 0L,
          )
        )
      assertEquals(3, result.removedEntries.size)
      assertTrue("freedBytes must be positive after live prune", result.freedBytes > 0L)
      assertEquals(1, result.sourceResults.size)

      // Disk effect: 2 sidecars + 2 PNGs survive.
      val sidecarsAfter = previewDir.listFiles { _, name -> name.endsWith(".json") }!!.toList()
      val pngsAfter = previewDir.listFiles { _, name -> name.endsWith(".png") }!!.toList()
      assertEquals(2, sidecarsAfter.size)
      assertEquals(2, pngsAfter.size)

      // The two surviving sidecars must be the 2 newest (lowest index in our `written` list).
      val survivingIds = sidecarsAfter.map { it.nameWithoutExtension }.toSet()
      assertEquals(setOf(written[0].id, written[1].id), survivingIds)

      // history/list confirms over the wire.
      val listed = client.historyList(HistoryListParams(previewId = previewId))
      assertEquals(2, listed.totalCount)

      // Dry-run prune with a tighter budget — would-remove returned, no further mutation.
      val dryResult =
        client.historyPrune(
          HistoryPruneParams(
            maxEntriesPerPreview = 1,
            maxAgeDays = 0,
            maxTotalSizeBytes = 0L,
            dryRun = true,
          )
        )
      assertEquals(1, dryResult.removedEntries.size)
      // Disk state unchanged from before the dry-run.
      assertEquals(2, previewDir.listFiles { _, name -> name.endsWith(".json") }!!.size)
      assertFalse(
        "dry-run must NOT delete the to-be-removed sidecar",
        !File(previewDir, "${dryResult.removedEntries[0]}.json").exists(),
      )

      val exitCode = client.shutdownAndExit()
      assertEquals(0, exitCode)
    } catch (t: Throwable) {
      System.err.println("S11HistoryPruneTest failed; stderr from daemon:\n${client.dumpStderr()}")
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
      historyDir.deleteRecursively()
    }
  }
}
