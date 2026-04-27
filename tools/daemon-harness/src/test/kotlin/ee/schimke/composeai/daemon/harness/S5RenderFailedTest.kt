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
 * Scenario **S5 — renderFailed surfacing** from
 * [TEST-HARNESS § 3](../../../../docs/daemon/TEST-HARNESS.md#s5--renderfailed-surfacing).
 *
 * Per [TEST-HARNESS § 8a](../../../../docs/daemon/TEST-HARNESS.md#8a-the-fakehost-test-fixture):
 * `FakeHost` reads optional `<previewId>.error` files in the fixture dir; presence triggers a
 * thrown exception on render. Drives:
 *
 * 1. `renderNow(["preview-broken"])` → assert `renderFailed` arrives for "preview-broken".
 * 2. `renderNow(["preview-good"])` → assert `renderFinished` arrives normally (daemon stayed
 *    responsive after the failure).
 * 3. Clean shutdown.
 *
 * The fixture format is the JSON shape from the v1 task brief: `{"kind":
 * "runtime"|"compile"|"capture", "message": "...", "stackTrace": "..."}`. FakeHost's loader (see
 * `FakeHost.parseErrorSidecar`) parses both the JSON shape and the legacy plain-text shape; this
 * test exercises the JSON shape.
 *
 * **v1 daemon reality (documented gap).** `JsonRpcServer.emitRenderFailed` (line ~406) currently
 * emits `kind: "internal"` regardless of the host-supplied kind — the structured `RenderError`
 * shape from `RenderFailedParams` is not yet plumbed through. The test asserts the wire-level shape
 * that exists today (a `renderFailed` notification with `params.id` and a `params.error` object); a
 * stricter assertion on `error.kind == "runtime"` would fail until the gap closes.
 */
class S5RenderFailedTest {

  @Test
  fun s5_render_failed_surfacing() {
    val paths = HarnessTestSupport.scenario("s5")
    val brokenId = "preview-broken"
    val goodId = "preview-good"

    // Both previews have PNG fixtures so the success path can land cleanly. The broken preview's
    // PNG is never served (the error sidecar fires first), but having it on disk keeps the
    // fixture-loading code path consistent across both ids.
    File(paths.fixtureDir, "$brokenId.png")
      .writeBytes(TestPatterns.solidColour(32, 32, 0xFFFF0000.toInt()))
    File(paths.fixtureDir, "$goodId.png")
      .writeBytes(TestPatterns.solidColour(32, 32, 0xFF00FF00.toInt()))
    File(paths.fixtureDir, "$brokenId.error")
      .writeText(
        """{"kind":"runtime","message":"deliberate test failure","stackTrace":"<stub stacktrace>"}"""
      )
    writePreviewsManifest(paths.fixtureDir, listOf(brokenId, goodId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. Broken render.
      val brokenStart = System.currentTimeMillis()
      val rn1 = client.renderNow(previews = listOf(brokenId), tier = RenderTier.FAST)
      assertEquals(listOf(brokenId), rn1.queued)
      val failed =
        client.pollNotificationMatching("renderFailed", 15.seconds) { msg ->
          msg["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == brokenId
        }
      val brokenFinishedAt = System.currentTimeMillis()
      val errorObj = failed["params"]?.jsonObject?.get("error")?.jsonObject
      assertNotNull("renderFailed.params.error must be present: $failed", errorObj)
      val msgField = errorObj!!["message"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFailed.params.error.message must be present", msgField)
      assertTrue(
        "renderFailed.params.error.message should mention the configured failure: $msgField",
        msgField!!.contains("deliberate test failure"),
      )

      // 2. Healthy render — daemon stayed up.
      val goodStart = System.currentTimeMillis()
      val rn2 = client.renderNow(previews = listOf(goodId), tier = RenderTier.FAST)
      assertEquals(listOf(goodId), rn2.queued)
      val finished = client.pollRenderFinishedFor(goodId, timeout = 15.seconds)
      val goodFinishedAt = System.currentTimeMillis()
      val pngPath = finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", pngPath)
      assertTrue("renderFinished.pngPath must exist: $pngPath", File(pngPath!!).exists())

      paths.latency.record(
        scenario = paths.name,
        preview = brokenId,
        actualMs = brokenFinishedAt - brokenStart,
        notes = "S5: renderFailed path",
      )
      paths.latency.record(
        scenario = paths.name,
        preview = goodId,
        actualMs = goodFinishedAt - goodStart,
        notes = "S5: post-failure healthy render",
      )

      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
