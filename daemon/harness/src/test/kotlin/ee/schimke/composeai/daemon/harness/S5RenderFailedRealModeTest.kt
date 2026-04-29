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
 * Real-mode counterpart to [S5RenderFailedTest] — verifies that the desktop daemon surfaces
 * in-composition throws as `renderFailed` notifications, matching fake mode S5 and the TEST-HARNESS
 * § 3 expectation.
 *
 * **Fix landed in:** D-harness.v1.5b follow-up — `DesktopHost.runRenderLoop` no longer catches
 * render-body exceptions on the render thread. When
 * [`BoomComposable`][ee.schimke.composeai.daemon.BoomComposable] throws inside the Compose
 * composition, `RenderEngine.render` propagates the Throwable; the host routes it through the
 * per-id result queue and `submit()` re-throws it on the `JsonRpcServer.submitRenderAsync` worker
 * thread. That worker's existing Throwable catch turns it into a `renderFailed` notification via
 * the watcher loop. The legacy stub-fallback path (non-spec `payload="render-N"` from
 * `DesktopHostTest`) is unchanged — that's the discriminator test for the only remaining stub
 * usage.
 *
 * Asserts:
 *
 * 1. The broken render produces a `renderFailed` notification (not `renderFinished`).
 * 2. The error's `kind` is one of the daemon's emitted values and `message` contains "boom".
 * 3. The daemon survives — a follow-up `renderNow([RedSquare])` returns a real PNG.
 * 4. `shutdown` + `exit` complete cleanly.
 *
 * **No baseline PNG.** Test asserts on the wire shape only.
 */
class S5RenderFailedRealModeTest {

  @Test
  fun s5_render_failed_surfacing_real_mode() {
    Assume.assumeTrue(
      "Skipping S5RenderFailedRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S5RenderFailedRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val brokenId = "boom"
    val goodId = "red-square"
    val paths =
      realModeScenario(
        name = "s5-real",
        previews =
          listOf(
            RealModePreview(
              id = brokenId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "BoomComposable",
            ),
            RealModePreview(
              id = goodId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            ),
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. Broken render — DesktopHost now propagates in-composition Throwables through the
      //    result queue, JsonRpcServer's submitRenderAsync re-raises them, and the watcher loop
      //    emits `renderFailed`. Assert the wire shape directly (kind + message substring).
      val brokenStart = System.currentTimeMillis()
      val rn1 = client.renderNow(previews = listOf(brokenId), tier = RenderTier.FAST)
      assertEquals(listOf(brokenId), rn1.queued)
      val failed =
        client.pollNotificationMatching("renderFailed", 60.seconds) { msg ->
          msg["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == brokenId
        }
      val brokenFinishedAt = System.currentTimeMillis()
      val errorObj =
        failed["params"]?.jsonObject?.get("error")?.jsonObject
          ?: error("renderFailed.params.error must be present: $failed")
      val errKind = errorObj["kind"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFailed.params.error.kind must be present", errKind)
      assertTrue(
        "renderFailed.params.error.kind must be one of the v1 RenderErrorKind values " +
          "(PROTOCOL.md § 6); was $errKind",
        errKind in setOf("internal", "runtime", "compile", "capture", "timeout"),
      )
      val errMsg = errorObj["message"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFailed.params.error.message must be present", errMsg)
      assertTrue(
        "renderFailed.params.error.message should mention the thrown 'boom': $errMsg",
        errMsg!!.contains("boom"),
      )

      // 2. Healthy render — daemon stayed up after the failure.
      val goodStart = System.currentTimeMillis()
      val rn2 = client.renderNow(previews = listOf(goodId), tier = RenderTier.FAST)
      assertEquals(listOf(goodId), rn2.queued)
      val finished = client.pollRenderFinishedFor(goodId, timeout = 60.seconds)
      val goodFinishedAt = System.currentTimeMillis()
      val pngPath = finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", pngPath)
      assertTrue(
        "follow-up renderFinished.pngPath must be a real on-disk file, not a stub: $pngPath",
        File(pngPath!!).exists(),
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s5-real",
        preview = brokenId,
        actualMs = brokenFinishedAt - brokenStart,
        notes = "S5 real: broken render — surfaces as renderFailed w/ kind+message",
      )
      recorder.record(
        scenario = "s5-real",
        preview = goodId,
        actualMs = goodFinishedAt - goodStart,
        notes = "S5 real: post-failure healthy render",
      )

      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S5RenderFailedRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
