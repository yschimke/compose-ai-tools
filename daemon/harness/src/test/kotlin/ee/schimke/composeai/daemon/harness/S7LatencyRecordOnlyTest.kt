package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S7 — Latency record-only** from
 * [TEST-HARNESS § 3 / § 11](../../../../docs/daemon/TEST-HARNESS.md#11-decisions-made).
 *
 * Per the v1 decision (TEST-HARNESS § 11): every scenario in v1 captures wall-clock from
 * `renderNow` to `renderFinished` per preview, writes
 * `target,scenario,preview,actualMs,baselineMs,deltaPct,notes` rows to
 * `build/reports/daemon-harness/latency.csv`, and surfaces it as a CI artefact. **No assertion on
 * latency values** — humans read trends; no test fails on perf.
 *
 * The other v1 scenarios (S2-S5, S8) each call into [LatencyRecorder] themselves; this test exists
 * to:
 *
 * 1. Sanity-check a cold first-render latency in isolation (5 sequential renderNow calls, all of
 *    the same preview — gives a "cold then warm" picture).
 * 2. Verify the CSV file is correctly created at the expected path with a header row and at least
 *    one data row after the suite runs. (Per the task brief: "Latency CSV exists at
 *    `daemon/harness/build/reports/daemon-harness/latency.csv` after a test run, populated for
 *    every scenario × preview pair.")
 *
 * For fake mode the actual will be ~50ms (FakeHost just reads from disk + replies); the baselineMs
 * in the CSV is the desktop real-render median (~1100ms from
 * [`baseline-latency.csv`](../../../../docs/daemon/baseline-latency.csv)). The delta column
 * documents the cost the daemon is amortising into.
 */
class S7LatencyRecordOnlyTest {

  @Test
  fun s7_latency_record_only() {
    val paths = HarnessTestSupport.scenario("s7")
    val previewId = "preview-latency"
    File(paths.fixtureDir, "$previewId.png")
      .writeBytes(TestPatterns.alignmentGrid(64, 64, cellPx = 16))
    writePreviewsManifest(paths.fixtureDir, listOf(previewId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 5 cold/warm renders. The first one carries the daemon's spawn + classloader + first-render
      // overhead; subsequent ones should land at FakeHost's "just read a PNG" cost.
      repeat(5) { i ->
        val tag = if (i == 0) "cold" else "warm-$i"
        val start = System.currentTimeMillis()
        val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals(listOf(previewId), rn.queued)
        client.pollRenderFinishedFor(previewId, timeout = 15.seconds)
        val took = System.currentTimeMillis() - start
        paths.latency.record(scenario = paths.name, preview = "$previewId@$tag", actualMs = took)
      }

      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      // CSV sanity. The latency.csv must exist + start with the header + carry our rows.
      assertTrue(
        "latency.csv must exist at ${HarnessTestSupport.LATENCY_CSV.absolutePath}",
        HarnessTestSupport.LATENCY_CSV.exists(),
      )
      val lines = HarnessTestSupport.LATENCY_CSV.readLines()
      assertEquals(
        "first line must be the CSV header",
        "target,scenario,preview,actualMs,baselineMs,deltaPct,notes",
        lines.first(),
      )
      val s7Rows = lines.filter { it.contains(",${paths.name},") }
      assertTrue("S7 must have written ≥ 5 rows; saw=${s7Rows.size}", s7Rows.size >= 5)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
