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
import org.junit.Test

/**
 * Scenario **S2 — Lifecycle drain semantics (no-mid-render-cancellation)** from
 * [TEST-HARNESS § 3](../../../../docs/daemon/TEST-HARNESS.md#s2--lifecycle-drain-semantics-no-mid-render-cancellation).
 *
 * The load-bearing one. Configures one preview with `delay-ms = 500`, drives:
 *
 * 1. `initialize` → `initialized`.
 * 2. `renderNow(["preview-slow"])`.
 * 3. (No wait) `shutdown` (sent **without** blocking for the response).
 * 4. Poll `renderFinished` → must arrive *first*.
 * 5. Then `awaitResponse(shutdownId)` → must arrive *after* the renderFinished.
 * 6. `exit` → process exits with code 0.
 *
 * Asserts the daemon's drain semantics from
 * [PROTOCOL.md § 3](../../../../docs/daemon/PROTOCOL.md#3-lifecycle) ("daemon stops accepting
 * renderNow, drains the in-flight queue, then resolves") are real, not aspirational.
 *
 * Combined with B1.5a's enforcement (no `Thread.interrupt()` on the render thread, see
 * `JsonRpcServer.kt` § "Threading model"), this scenario is the regression test
 * [PREDICTIVE.md § 9](../../../../docs/daemon/PREDICTIVE.md#9-decisions-made) promises.
 */
class S2DrainSemanticsTest {

  @Test
  fun s2_drain_semantics() {
    val paths = HarnessTestSupport.scenario("s2")
    val previewId = "preview-slow"

    // 1. Fixture: one preview with a 500ms render delay.
    val pngBytes = TestPatterns.solidColour(64, 64, 0xFF408040.toInt())
    File(paths.fixtureDir, "$previewId.png").writeBytes(pngBytes)
    File(paths.fixtureDir, "$previewId.delay-ms").writeText("500")
    writePreviewsManifest(paths.fixtureDir, listOf(previewId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      val initResult = client.initialize()
      assertEquals(1, initResult.protocolVersion)
      client.sendInitialized()

      // 2. renderNow + measure latency for S7-record purposes.
      val renderNowStartMs = System.currentTimeMillis()
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)

      // 3. Send shutdown without waiting for response — this is the whole point.
      val shutdownId = client.sendShutdownAsync()

      // 4. renderFinished must arrive *before* the shutdown response.
      val finished = client.pollRenderFinishedFor(previewId, timeout = 30.seconds)
      val finishedReceivedAtMs = System.currentTimeMillis()

      // 5. Now the shutdown response is allowed to arrive — and must.
      val shutdownResponse = client.awaitResponse(shutdownId, timeout = 30.seconds)
      val shutdownReceivedAtMs = System.currentTimeMillis()
      assertNotNull("shutdown must return a response object", shutdownResponse)
      assertTrue(
        "shutdown response must NOT precede renderFinished " +
          "(finished=${finishedReceivedAtMs}, shutdown=${shutdownReceivedAtMs})",
        shutdownReceivedAtMs >= finishedReceivedAtMs,
      )

      // 6. PNG must exist.
      val reportedPath =
        finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", reportedPath)
      assertTrue(
        "renderFinished.pngPath must reference an existing file: $reportedPath",
        File(reportedPath!!).exists(),
      )

      // 7. exit + clean process exit.
      val exitCode = client.sendExitAndWait()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      // 8. Latency record (S7).
      paths.latency.record(
        scenario = paths.name,
        preview = previewId,
        actualMs = finishedReceivedAtMs - renderNowStartMs,
        notes = "S2 drain: includes 500ms configured FakeHost delay",
      )
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
