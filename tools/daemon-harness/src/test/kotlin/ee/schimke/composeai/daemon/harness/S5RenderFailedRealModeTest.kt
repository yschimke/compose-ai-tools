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
 * Real-mode counterpart to [S5RenderFailedTest] — pins the desktop daemon's actual
 * exception-in-composition behaviour today, which **diverges from fake mode in a load-bearing way**.
 *
 * **Real-mode-only quirk surfaced by writing this test (gap with TEST-HARNESS § 3 + with fake mode
 * S5).** When [`BoomComposable`][ee.schimke.composeai.daemon.BoomComposable] throws inside the
 * Compose composition, [`DesktopHost.runRenderLoop`][ee.schimke.composeai.daemon.DesktopHost]
 * catches the exception (`DesktopHost.kt` line ~134), prints the stack to stderr, and falls back
 * to [`renderStubFallback`][ee.schimke.composeai.daemon.DesktopHost] which returns a *successful*
 * `RenderResult` (no `pngPath`, no `metrics`). `JsonRpcServer.emitRenderFinished` then forwards
 * that as a `renderFinished` notification carrying the daemon-stub placeholder pngPath — so the
 * client never sees `renderFailed` for an in-composition throw. Fake mode's S5 surfaces
 * `renderFailed` because `FakeHost` propagates the exception out of `submit()` and
 * `JsonRpcServer.runHostSubmitter` catches it on the JsonRpc side (line ~371). The real desktop
 * host catches it one layer too early.
 *
 * Once `DesktopHost` is taught to propagate composition exceptions (or to translate them into a
 * structured `RenderFailed` shape — likely as part of B1.4 timing / B2.3 metrics work), this test
 * should flip and assert the `renderFailed` shape directly. Until then the assertions pin **what
 * actually happens**:
 *
 * 1. The broken render produces a `renderFinished` notification (not `renderFailed`).
 * 2. The `pngPath` it reports points to a placeholder that may or may not exist on disk —
 *    `renderStubFallback` does not write a PNG and `JsonRpcServer` doesn't materialise the stub
 *    file either.
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

      // 1. Broken render — today's daemon swallows the exception in DesktopHost and emits
      //    renderFinished with a stub pngPath rather than renderFailed (see KDoc gap). Assert that
      //    real-mode behaviour explicitly so the test flips when the gap closes.
      val brokenStart = System.currentTimeMillis()
      val rn1 = client.renderNow(previews = listOf(brokenId), tier = RenderTier.FAST)
      assertEquals(listOf(brokenId), rn1.queued)
      val finishedBroken = client.pollRenderFinishedFor(brokenId, timeout = 60.seconds)
      val brokenFinishedAt = System.currentTimeMillis()
      val brokenParams =
        finishedBroken["params"]?.jsonObject ?: error("renderFinished missing params: $finishedBroken")
      val brokenPngPath = brokenParams["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present even for the broken render", brokenPngPath)
      assertTrue(
        "v1 daemon reality: a thrown @Composable surfaces as renderFinished with the stub " +
          "placeholder pngPath ('daemon-stub-<id>.png'), not renderFailed (gap with TEST-HARNESS " +
          "§ 3 + fake-mode S5). pngPath=$brokenPngPath. If this assertion ever flips green, " +
          "DesktopHost has been taught to propagate composition exceptions and this test should " +
          "tighten to assert the renderFailed shape directly.",
        brokenPngPath!!.contains("daemon-stub-"),
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
        notes = "S5 real: broken render — surfaces as renderFinished w/ stub pngPath (gap)",
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
