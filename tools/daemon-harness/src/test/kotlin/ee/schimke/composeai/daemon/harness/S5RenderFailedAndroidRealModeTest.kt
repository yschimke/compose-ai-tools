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
 * D-harness.v2 Android counterpart of [S5RenderFailedRealModeTest] — verifies that an
 * in-composition throw from [`BoomComposable`][ee.schimke.composeai.daemon.BoomComposable] does not
 * crash the Android daemon and that a follow-up healthy render still works.
 *
 * **Real-mode-specific gap (Android-only — desktop fixed it post-D-harness.v1.5b).**
 * `RobolectricHost.SandboxRunner.holdSandboxOpen` catches the in-composition Throwable and falls
 * back to [`renderStub`][ee.schimke.composeai.daemon.RobolectricHost.SandboxRunner.renderStub],
 * returning a *successful* [`RenderResult`][ee.schimke.composeai.daemon.RenderResult] with no
 * `pngPath`. The wire surfaces this as `renderFinished` carrying the daemon's
 * `daemon-stub-<id>.png` placeholder rather than `renderFailed`. This is the exact same gap that
 * `:renderer-desktop-daemon`'s `DesktopHost.runRenderLoop` had before the D-harness.v1.5b
 * follow-up; the fix is mechanical (post the Throwable onto the result queue instead of swallowing
 * it; `JsonRpcServer.submitRenderAsync` already does the rest), but porting it to Robolectric
 * requires routing the Throwable across the sandbox classloader bridge — out of scope for v2.
 *
 * The test pins the current behaviour:
 * 1. The broken render produces a `renderFinished` notification with the stub PNG path (gap).
 * 2. The daemon survives — a follow-up `renderNow([RedSquare])` returns a real PNG.
 *
 * When the Android RobolectricHost is taught to propagate composition exceptions, this test should
 * be tightened to assert `renderFailed.params.error.kind` and `error.message contains "boom"` —
 * same shape as the desktop S5 test.
 *
 * **No baseline PNG.** Test asserts on the wire shape only.
 */
class S5RenderFailedAndroidRealModeTest {

  @Test
  fun s5_render_failed_surfacing_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S5RenderFailedAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S5RenderFailedAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val brokenId = "boom"
    val goodId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "s5-android",
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

      // 1. Broken render — RobolectricHost catches the Throwable and stubs out, so the wire sees
      //    `renderFinished` not `renderFailed`. Pin the current behaviour and document the gap so
      //    the assertion flips green when the Android side is taught to propagate.
      val brokenStart = System.currentTimeMillis()
      val rn1 = client.renderNow(previews = listOf(brokenId), tier = RenderTier.FAST)
      assertEquals(listOf(brokenId), rn1.queued)
      val finished = client.pollRenderFinishedFor(brokenId, timeout = 120.seconds)
      val brokenFinishedAt = System.currentTimeMillis()
      val brokenPath = finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present (stub path)", brokenPath)
      // The stub path doesn't include the previewId; sanity-check it's the daemon-stub form.
      // This assertion is the gap-flagged regression test — when the Android side propagates
      // exceptions, the stub path will disappear and this assertion will need updating to assert
      // a `renderFailed` notification was received instead.
      assertTrue(
        "Android v2 reality: broken render returns the daemon-stub PNG path because " +
          "RobolectricHost.SandboxRunner catches the in-composition Throwable and falls back " +
          "to renderStub. When that's fixed (mirror DesktopHost.runRenderLoop's post-Throwable " +
          "behaviour), this assertion needs to flip to a renderFailed assertion. Got: $brokenPath",
        brokenPath!!.contains("daemon-stub"),
      )

      // 2. Healthy render — daemon stayed up after the failure.
      val goodStart = System.currentTimeMillis()
      val rn2 = client.renderNow(previews = listOf(goodId), tier = RenderTier.FAST)
      assertEquals(listOf(goodId), rn2.queued)
      val finishedGood = client.pollRenderFinishedFor(goodId, timeout = 60.seconds)
      val goodFinishedAt = System.currentTimeMillis()
      val pngPath = finishedGood["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", pngPath)
      assertTrue(
        "follow-up renderFinished.pngPath must be a real on-disk file, not a stub: $pngPath",
        File(pngPath!!).exists(),
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s5-android",
        preview = brokenId,
        actualMs = brokenFinishedAt - brokenStart,
        notes =
          "S5 android: broken render — surfaces as renderFinished with daemon-stub path " +
            "(GAP: RobolectricHost catches Throwable; mirrors pre-fix DesktopHost behaviour)",
      )
      recorder.record(
        scenario = "s5-android",
        preview = goodId,
        actualMs = goodFinishedAt - goodStart,
        notes = "S5 android: post-failure healthy render",
      )

      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S5RenderFailedAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
