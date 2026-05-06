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
 * D-harness.v2 Android counterpart of [S7LatencyRecordOnlyRealModeTest] — runs 5 cold/warm renders
 * against the real Android daemon and writes per-render rows to the shared latency CSV.
 *
 * The cold row carries the Robolectric sandbox bootstrap (~3-10s) on top of the JVM/Compose start;
 * subsequent warm renders should land much closer to the per-render render-engine cost. Notes
 * column tags rows as android-mode so the v3 drift consumer can distinguish them.
 *
 * Record-only — same v1 decision as S7 fake-mode (TEST-HARNESS § 11). Humans read trends.
 */
class S7LatencyRecordOnlyAndroidRealModeTest {

  @Test
  fun s7_latency_record_only_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S7LatencyRecordOnlyAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S7LatencyRecordOnlyAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "s7-android",
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
      assertEquals(2, client.initialize().protocolVersion)
      client.sendInitialized()

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      // 5 cold/warm renders. The first one carries the daemon's spawn + Robolectric sandbox +
      // Compose first-render overhead; subsequent ones approach the real per-render cost.
      repeat(5) { i ->
        val tag = if (i == 0) "cold" else "warm-$i"
        val start = System.currentTimeMillis()
        val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals(listOf(previewId), rn.queued)
        val finished =
          client.pollRenderFinishedFor(previewId, timeout = if (i == 0) 120.seconds else 60.seconds)
        val took = System.currentTimeMillis() - start
        val daemonTookMs =
          finished["params"]
            ?.jsonObject
            ?.get("tookMs")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull() ?: -1L
        recorder.record(
          scenario = "s7-android",
          preview = "$previewId@$tag",
          actualMs = took,
          notes =
            "S7 android: daemon reported tookMs=$daemonTookMs " +
              "(android wire metric is hardcoded 0 today — same gap as desktop's pre-fix S8)",
        )
        System.err.println(
          "S7LatencyRecordOnlyAndroidRealModeTest: $tag wall=${took}ms daemonTookMs=$daemonTookMs"
        )
      }

      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S7LatencyRecordOnlyAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
