package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/**
 * Real-mode counterpart to [S7LatencyRecordOnlyTest] — runs 5 cold/warm renders against the real
 * desktop daemon and writes per-render rows to the shared latency CSV.
 *
 * **Real-mode actuals are meaningful** (vs fake mode's wire-layer-overhead-only ~50ms): they
 * include Compose runtime composition + Skiko native draw + PNG encode. The first row carries the
 * cold-start tax (Compose/Skiko bootstrap, ~2-4s on the dev box); subsequent rows should land much
 * closer to the desktop baseline median (~1100ms per `docs/daemon/baseline-latency.csv`).
 *
 * **Record-only.** Same v1 decision as S7 fake-mode (TEST-HARNESS § 11) — humans read trends; no
 * test fails on perf. The `notes` column tags rows as real-mode so the v3 drift consumer can
 * distinguish them from the fake-mode rows that share the CSV.
 */
class S7LatencyRecordOnlyRealModeTest {

  @Test
  fun s7_latency_record_only_real_mode() {
    Assume.assumeTrue(
      "Skipping S7LatencyRecordOnlyRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S7LatencyRecordOnlyRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val previewId = "red-square"
    val paths =
      realModeScenario(
        name = "s7-real",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      // 5 cold/warm renders. The first one carries the daemon's spawn + classloader + Compose +
      // Skiko first-render overhead; subsequent ones should approach the real per-render cost.
      repeat(5) { i ->
        val tag = if (i == 0) "cold" else "warm-$i"
        val start = System.currentTimeMillis()
        val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals(listOf(previewId), rn.queued)
        val finished = client.pollRenderFinishedFor(previewId, timeout = 60.seconds)
        val took = System.currentTimeMillis() - start
        // Record both the wall-clock the harness saw and the daemon's reported tookMs (today
        // hardcoded to 0 in JsonRpcServer.emitRenderFinished — gap with PROTOCOL.md). The notes
        // column documents the difference for human readers.
        val daemonTookMs =
          finished["params"]
            ?.jsonObject
            ?.get("tookMs")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull() ?: -1L
        recorder.record(
          scenario = "s7-real",
          preview = "$previewId@$tag",
          actualMs = took,
          notes = "S7 real: daemon reported tookMs=$daemonTookMs (wire metric is hardcoded 0 today)",
        )
        System.err.println(
          "S7LatencyRecordOnlyRealModeTest: $tag wall=${took}ms daemonTookMs=$daemonTookMs"
        )
      }

      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S7LatencyRecordOnlyRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
