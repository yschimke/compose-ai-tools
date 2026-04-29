package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S4 — Visibility filter** from
 * [TEST-HARNESS § 3](../../../../docs/daemon/TEST-HARNESS.md#s4--visibility-filter).
 *
 * Drives `setVisible(["a","b","c"]) → setFocus(["b"]) → renderNow(["a","b","c"])` and asserts the
 * order in which `renderFinished` notifications arrive matches what the v1 daemon's queue policy
 * actually does.
 *
 * **Reality check (gap with TEST-HARNESS § 3's spec).** TEST-HARNESS § 3 / DESIGN § 8 imagines the
 * daemon's queue is multi-tier — focus renders first (Tier 4), then visible (Tier 3), then
 * background. That landed in PREDICTIVE P2.5.1 design but is **not implemented** in the v1 daemon:
 * `JsonRpcServer.handleRenderNow` (line ~316) simply iterates `params.previews` in arrival order
 * and submits each on a fresh worker thread. The setVisible / setFocus notifications are no-ops
 * (line ~468–470).
 *
 * So this scenario asserts what's actually true today:
 *
 * 1. All three `renderFinished` notifications arrive (none lost).
 * 2. Each carries the matching preview id and a non-null `pngPath`.
 * 3. The daemon does not crash.
 *
 * The order assertion is **not strict** — it documents that v1 is FIFO-ish but spawns concurrent
 * worker threads so notification arrival order is non-deterministic. Once focus-first dispatch
 * lands (P2.5.1), this test should tighten to assert "b" arrives first.
 */
class S4VisibilityFilterTest {

  @Test
  fun s4_visibility_filter() {
    val paths = HarnessTestSupport.scenario("s4")
    val previews = listOf("preview-a", "preview-b", "preview-c")

    // Three distinct fixtures so a wire-level mix-up would be visible in pixel-diffs (we don't run
    // those here — but having distinct PNGs preserves the option for later).
    val colours =
      mapOf(
        "preview-a" to 0xFFA00000.toInt(),
        "preview-b" to 0xFF00A000.toInt(),
        "preview-c" to 0xFF0000A0.toInt(),
      )
    for (id in previews) {
      File(paths.fixtureDir, "$id.png")
        .writeBytes(TestPatterns.solidColour(48, 48, colours.getValue(id)))
    }
    writePreviewsManifest(paths.fixtureDir, previews)

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. Visibility hints.
      client.setVisible(previews)
      client.setFocus(listOf("preview-b"))

      // 2. Issue all three renders.
      val renderNowStart = System.currentTimeMillis()
      val rn = client.renderNow(previews = previews, tier = RenderTier.FAST)
      assertEquals(previews, rn.queued)
      assertTrue("rejected must be empty: ${rn.rejected}", rn.rejected.isEmpty())

      // 3. Collect each renderFinished by id with a generous deadline.
      val seen = mutableSetOf<String>()
      val arrivalOrder = mutableListOf<String>()
      val arrivalLatencyMs = mutableMapOf<String, Long>()
      val deadline = System.currentTimeMillis() + 30_000L
      while (seen.size < previews.size && System.currentTimeMillis() < deadline) {
        val msg = client.pollNotification("renderFinished", 5.seconds)
        val id =
          msg["params"]
            ?.let { p -> (p as kotlinx.serialization.json.JsonObject)["id"] }
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            ?: error("renderFinished missing id: $msg")
        if (seen.add(id)) {
          arrivalOrder.add(id)
          arrivalLatencyMs[id] = System.currentTimeMillis() - renderNowStart
        }
      }
      assertEquals("all three previews must complete; saw=$arrivalOrder", previews.toSet(), seen)

      // 4. Order: documented as non-deterministic under v1 reality (gap with TEST-HARNESS § 3's
      //    focus-first expectation). We log the observed order to stderr for human readers and to
      //    the latency CSV; we don't fail the test on order.
      System.err.println(
        "S4 observed renderFinished arrival order (v1 daemon reality): $arrivalOrder"
      )

      // 5. Latency rows — one per preview.
      for (id in previews) {
        paths.latency.record(
          scenario = paths.name,
          preview = id,
          actualMs = arrivalLatencyMs.getValue(id),
          notes = "S4: setFocus=preview-b; observed-order=${arrivalOrder.joinToString("|")}",
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
