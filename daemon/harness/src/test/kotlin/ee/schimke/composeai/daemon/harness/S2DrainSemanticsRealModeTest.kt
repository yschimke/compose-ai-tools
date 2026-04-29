package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Real-mode counterpart to [S2DrainSemanticsTest] — verifies the daemon's drain semantics
 * (`shutdown` does not resolve until the in-flight `renderFinished` arrives, per
 * [PROTOCOL.md § 3](../../../../docs/daemon/PROTOCOL.md#3-lifecycle) and
 * [DESIGN § 9](../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement))
 * against the real `:daemon:desktop` rather than `FakeHost`.
 *
 * Uses [`SlowSquare`][ee.schimke.composeai.daemon.SlowSquare] from the desktop daemon's
 * testFixtures source set: the composable calls `Thread.sleep(500)` *inside* the composition
 * (matching B-desktop.1.6's cancellation-invariant regression test). The real renderer's
 * `RenderEngine` will block on that sleep, so the harness can race `shutdown` against the in-flight
 * render and assert the response arrives only after `renderFinished`.
 *
 * **Skipped under fake mode.** Real-mode wall-clock for the slow render is bounded below by the
 * 500ms sleep + scene init + PNG encode; expect 700-1500ms on the dev box. The pixel-diff baseline
 * lands at `daemon/harness/baselines/desktop/s2/slow-square.png` (looks like a teal square —
 * [`SlowSquare`][ee.schimke.composeai.daemon.SlowSquare] paints `Color(0xFF80FFAA)` after
 * sleeping).
 */
class S2DrainSemanticsRealModeTest {

  @Test
  fun s2_drain_semantics_real_mode() {
    Assume.assumeTrue(
      "Skipping S2DrainSemanticsRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S2DrainSemanticsRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val previewId = "slow-square"
    val paths =
      realModeScenario(
        name = "s2-real",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "SlowSquare",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. renderNow + measure latency.
      val renderNowStartMs = System.currentTimeMillis()
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)

      // 2. Send shutdown without waiting — the whole point of the scenario.
      val shutdownId = client.sendShutdownAsync()

      // 3. renderFinished must arrive *before* the shutdown response. Generous timeout — the
      //    real renderer pays JVM cold-start + Compose/Skiko bootstrap on top of the 500ms sleep.
      val finished = client.pollRenderFinishedFor(previewId, timeout = 60.seconds)
      val finishedReceivedAtMs = System.currentTimeMillis()

      // 4. Now the shutdown response is allowed to arrive — and must.
      val shutdownResponse = client.awaitResponse(shutdownId, timeout = 30.seconds)
      val shutdownReceivedAtMs = System.currentTimeMillis()
      assertNotNull("shutdown must return a response object", shutdownResponse)
      assertTrue(
        "shutdown response must NOT precede renderFinished " +
          "(finished=${finishedReceivedAtMs}, shutdown=${shutdownReceivedAtMs})",
        shutdownReceivedAtMs >= finishedReceivedAtMs,
      )

      // 5. The reported PNG must exist and pixel-diff against the baseline (or be captured).
      val reportedPath =
        finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", reportedPath)
      val reportedPng = File(reportedPath!!)
      assertTrue("renderFinished.pngPath must exist: $reportedPath", reportedPng.exists())
      assertTrue("rendered PNG must be non-empty", reportedPng.length() > 0)

      diffOrCaptureBaseline(
        actualBytes = reportedPng.readBytes(),
        baseline = HarnessTestSupport.baselineFile("s2", "slow-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S2DrainSemanticsRealModeTest",
        stderrSupplier = { client.dumpStderr() },
      )

      // 6. exit + clean process exit.
      val exitCode = client.sendExitAndWait()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      val tookMs = finishedReceivedAtMs - renderNowStartMs
      System.err.println("S2DrainSemanticsRealModeTest: renderNow → renderFinished in ${tookMs}ms")
      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s2-real",
        preview = previewId,
        actualMs = tookMs,
        notes = "S2 real: includes 500ms SlowSquare sleep + scene init + PNG encode",
      )
    } catch (t: Throwable) {
      System.err.println(
        "S2DrainSemanticsRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
