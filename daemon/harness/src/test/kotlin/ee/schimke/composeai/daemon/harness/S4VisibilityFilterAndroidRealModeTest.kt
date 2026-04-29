package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D-harness.v2 Android counterpart of [S4VisibilityFilterRealModeTest] — drives
 * `setVisible([a,b,c]) → setFocus([b]) → renderNow([a,b,c])` against the real Android daemon and
 * asserts all three `renderFinished` notifications arrive.
 *
 * Same gap with v1 daemon's lack of focus prioritisation as the desktop S4 (see
 * [S4VisibilityFilterRealModeTest] KDoc); arrival order is logged, not asserted.
 *
 * Three baselines under `daemon/harness/baselines/android/s4/`.
 */
class S4VisibilityFilterAndroidRealModeTest {

  @Test
  fun s4_visibility_filter_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S4VisibilityFilterAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S4VisibilityFilterAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previews =
      listOf(
        RealModePreview(
          id = "red-square",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RedSquare",
        ),
        RealModePreview(
          id = "blue-square",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "BlueSquare",
        ),
        RealModePreview(
          id = "green-square",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "GreenSquare",
        ),
      )
    val ids = previews.map { it.id }
    val paths = realAndroidModeScenario(name = "s4-android", previews = previews)

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. Visibility hints. Today's daemon ignores both — see KDoc gap reference.
      client.setVisible(ids)
      client.setFocus(listOf("blue-square"))

      // 2. Issue all three renders.
      val renderNowStart = System.currentTimeMillis()
      val rn = client.renderNow(previews = ids, tier = RenderTier.FAST)
      assertEquals(ids, rn.queued)
      assertTrue("rejected must be empty: ${rn.rejected}", rn.rejected.isEmpty())

      // 3. Collect each renderFinished by id with a generous deadline. First render carries
      //    Robolectric bootstrap (~3-10s); the subsequent two land much faster.
      val seen = mutableMapOf<String, JsonObject>()
      val arrivalOrder = mutableListOf<String>()
      val arrivalLatencyMs = mutableMapOf<String, Long>()
      val deadline = System.currentTimeMillis() + 240_000L
      while (seen.size < ids.size && System.currentTimeMillis() < deadline) {
        val msg = client.pollNotification("renderFinished", 120.seconds)
        val params = msg["params"] as? JsonObject ?: error("renderFinished missing params: $msg")
        val id =
          (params["id"] as? JsonPrimitive)?.content ?: error("renderFinished missing id: $msg")
        if (seen.put(id, msg) == null) {
          arrivalOrder.add(id)
          arrivalLatencyMs[id] = System.currentTimeMillis() - renderNowStart
        }
      }
      assertEquals("all three previews must complete; saw=$arrivalOrder", ids.toSet(), seen.keys)

      // 4. Pixel-diff each rendered PNG against its baseline.
      for (id in ids) {
        val params = seen.getValue(id)["params"] as JsonObject
        val pngPath = (params["pngPath"] as JsonPrimitive).content
        val pngFile = File(pngPath)
        assertTrue("renderFinished.pngPath must exist for $id: $pngPath", pngFile.exists())
        diffOrCaptureBaseline(
          actualBytes = pngFile.readBytes(),
          baseline = HarnessTestSupport.baselineFile("s4", "$id.png"),
          reportsDir = paths.reportsDir,
          scenario = "S4VisibilityFilterAndroidRealModeTest[$id]",
          stderrSupplier = { client.dumpStderr() },
        )
      }

      // 5. Order: documented as non-deterministic. Log + record; do not assert.
      System.err.println(
        "S4VisibilityFilterAndroidRealModeTest observed renderFinished arrival order " +
          "(v1 daemon, focus-not-honoured): $arrivalOrder"
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      for (id in ids) {
        recorder.record(
          scenario = "s4-android",
          preview = id,
          actualMs = arrivalLatencyMs.getValue(id),
          notes =
            "S4 android: setFocus=blue-square; observed-order=${arrivalOrder.joinToString("|")}",
        )
      }

      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S4VisibilityFilterAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
