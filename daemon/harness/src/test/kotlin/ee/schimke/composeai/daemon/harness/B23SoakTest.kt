package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2.3 soak test — runs 100 sequential `renderNow` calls against [FakeHost] and asserts every
 * `renderFinished` notification carries populated [RenderMetrics] with the four B2.3 fields.
 *
 * **What the soak test pins:**
 * - All 100 renders return `renderFinished.metrics` populated (not `null` / `JsonNull`).
 * - `sandboxAgeRenders` increments monotonically across renders (1, 2, …, 100). FakeHost holds
 *   one host instance for the whole test, so this counter starts at 1 and grows by 1 per render.
 * - `sandboxAgeMs` is non-decreasing across renders (wall-clock).
 * - `heapAfterGcMb > 0` and `nativeHeapMb > 0` — synthetic FakeHost defaults are 1, so this
 *   exercises the wire-level "metrics arrived populated" contract end-to-end.
 *
 * Real measurement-overhead assertion (the < 10ms-per-render DoD threshold) lives behind real-
 * mode soak tests on the desktop daemon — see [B23SoakDesktopRealModeTest] (`@Disabled` for now;
 * pulling in real-mode soak runs is a B2.4-era follow-up). FakeHost doesn't actually measure
 * anything (it just stamps synthetic values), so the overhead check is meaningless under fake
 * mode.
 */
class B23SoakTest {

  @Test
  fun b23_soak_100_renders_emit_populated_metrics() {
    val paths = HarnessTestSupport.scenario("b23-soak")
    val previewId = "preview-soak"
    val pngBytes = TestPatterns.solidColour(32, 32, 0xFF202020.toInt())
    File(paths.fixtureDir, "$previewId.png").writeBytes(pngBytes)
    writePreviewsManifest(paths.fixtureDir, listOf(previewId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      val renderCount = 100
      val observedRenders: MutableList<Long> = ArrayList(renderCount)
      val observedAgeMs: MutableList<Long> = ArrayList(renderCount)
      val observedHeapMb: MutableList<Long> = ArrayList(renderCount)
      val observedNativeMb: MutableList<Long> = ArrayList(renderCount)

      for (i in 1..renderCount) {
        val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals("renderNow($i) must queue exactly the one preview", listOf(previewId), rn.queued)
        val finished = client.pollRenderFinishedFor(previewId, timeout = 10.seconds)
        val params =
          finished["params"]?.jsonObject ?: error("renderFinished($i) missing params: $finished")

        // Every renderFinished must carry populated metrics — no JsonNull, no missing field.
        val wireMetrics = params["metrics"]
        assertNotNull("renderFinished($i).metrics must be populated", wireMetrics)
        assertNotEquals(
          "renderFinished($i).metrics must not be JsonNull",
          JsonNull,
          wireMetrics,
        )
        val metricsObj = wireMetrics!!.jsonObject

        val heapAfterGcMb =
          metricsObj[RenderMetrics.KEY_HEAP_AFTER_GC_MB]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
        val nativeHeapMb =
          metricsObj[RenderMetrics.KEY_NATIVE_HEAP_MB]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
        val sandboxAgeRenders =
          metricsObj[RenderMetrics.KEY_SANDBOX_AGE_RENDERS]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
        val sandboxAgeMs =
          metricsObj[RenderMetrics.KEY_SANDBOX_AGE_MS]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()

        assertNotNull("render($i).heapAfterGcMb must parse as Long", heapAfterGcMb)
        assertNotNull("render($i).nativeHeapMb must parse as Long", nativeHeapMb)
        assertNotNull("render($i).sandboxAgeRenders must parse as Long", sandboxAgeRenders)
        assertNotNull("render($i).sandboxAgeMs must parse as Long", sandboxAgeMs)

        // Brief assertion: synthetic FakeHost values are 1.
        assertTrue(
          "render($i).heapAfterGcMb > 0 (FakeHost synthetic 1): got $heapAfterGcMb",
          heapAfterGcMb!! > 0L,
        )
        assertTrue(
          "render($i).nativeHeapMb > 0 (FakeHost synthetic 1): got $nativeHeapMb",
          nativeHeapMb!! > 0L,
        )

        observedRenders.add(sandboxAgeRenders!!)
        observedAgeMs.add(sandboxAgeMs!!)
        observedHeapMb.add(heapAfterGcMb)
        observedNativeMb.add(nativeHeapMb)
      }

      // Monotonic increment for sandboxAgeRenders: 1, 2, …, 100.
      val expectedRenders = (1L..renderCount.toLong()).toList()
      assertEquals(
        "sandboxAgeRenders must increment monotonically 1..$renderCount across the soak",
        expectedRenders,
        observedRenders,
      )

      // Non-decreasing for sandboxAgeMs (wall-clock).
      for (i in 1 until observedAgeMs.size) {
        assertTrue(
          "sandboxAgeMs must be non-decreasing: render[$i]=${observedAgeMs[i]}, " +
            "render[${i - 1}]=${observedAgeMs[i - 1]}",
          observedAgeMs[i] >= observedAgeMs[i - 1],
        )
      }

      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
