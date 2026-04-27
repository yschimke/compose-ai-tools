package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S3 — Render-after-edit** from
 * [TEST-HARNESS § 3](../../../../docs/daemon/TEST-HARNESS.md#s3--render-after-edit).
 *
 * Fake-mode mapping (per
 * [TEST-HARNESS § 9 v1 scope](../../../../docs/daemon/TEST-HARNESS.md#9-phasing)): the "edit" maps
 * to swapping which `<previewId>.png` variant `FakeHost` serves. Two PNG variants exist in the
 * fixture directory — `preview-1.png` (v1) and `preview-1.v2.png` (v2). The harness:
 *
 * 1. `renderNow` → asserts v1 is what comes back.
 * 2. [editSource] swaps v1 → v2 on disk (auto-revert in `finally`).
 * 3. Sends `fileChanged({kind: source})`.
 * 4. `renderNow` again → asserts v2 is what comes back.
 *
 * **Gap from the daemon's v1 reality.** TEST-HARNESS § 3 imagines the daemon emits a
 * `discoveryUpdated` notification on `fileChanged` (Tier-2 incremental discovery, B2.2). The v1
 * daemon does **not** implement that — `JsonRpcServer.handleNotification`'s `fileChanged` arm is a
 * no-op (line ~470). This scenario assets only the renderNow round-trip with the new fixture wins;
 * `discoveryUpdated` is documented here as not yet emitted and is not asserted.
 */
class S3RenderAfterEditTest {

  @Test
  fun s3_render_after_edit() {
    val paths = HarnessTestSupport.scenario("s3")
    val previewId = "preview-1"

    // Two PNG variants. v1 is grey; v2 is teal — far enough apart that pixel-diff won't ambiguate.
    val v1Bytes = TestPatterns.solidColour(64, 64, 0xFF808080.toInt())
    val v2Bytes = TestPatterns.solidColour(64, 64, 0xFF008080.toInt())
    val pngFile = File(paths.fixtureDir, "$previewId.png")
    pngFile.writeBytes(v1Bytes)
    // Stash the v2 variant under the documented filename — purely for readers; not used by FakeHost
    // directly. The actual swap happens via `editSource` below replacing the live PNG bytes.
    File(paths.fixtureDir, "$previewId.v2.png").writeBytes(v2Bytes)
    writePreviewsManifest(paths.fixtureDir, listOf(previewId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. First render — must serve v1.
      val firstStart = System.currentTimeMillis()
      val rn1 = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn1.queued)
      val finished1 = client.pollRenderFinishedFor(previewId, timeout = 15.seconds)
      val firstFinishedAt = System.currentTimeMillis()
      val v1ReportedPath =
        finished1["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finished1")
      val v1Actual = File(v1ReportedPath).readBytes()
      val diffV1 = PixelDiff.compare(actual = v1Actual, expected = v1Bytes)
      assertTrue("v1 render must match v1 fixture: ${diffV1.message}", diffV1.ok)

      paths.latency.record(
        scenario = paths.name,
        preview = "$previewId@v1",
        actualMs = firstFinishedAt - firstStart,
      )

      // 2. Swap fixture to v2 with auto-revert. The `editSource` primitive (Scenario.kt) reverts in
      //    `finally` so a crashed test can't leave the fixture dirty.
      editSource(pngFile, v2Bytes) {
        // 3. Tell the daemon a source file changed. v1 daemon's fileChanged handler is a no-op
        //    today (gap with TEST-HARNESS § 3's discoveryUpdated expectation; see KDoc); we send
        //    it for protocol fidelity but don't poll for any acknowledgement.
        client.fileChanged(
          path = pngFile.absolutePath,
          kind = FileKind.SOURCE,
          changeType = ChangeType.MODIFIED,
        )
        // The harness must NOT see a discoveryUpdated under v1 daemon reality. We assert the
        // *absence* by polling with a short timeout that should expire.
        var sawDiscoveryUpdated = false
        try {
          client.pollNotification("discoveryUpdated", 250.milliseconds)
          sawDiscoveryUpdated = true
        } catch (_: Throwable) {
          // Expected: timed out → daemon doesn't emit discoveryUpdated yet (gap).
        }
        assertFalse(
          "v1 daemon does not yet emit discoveryUpdated on fileChanged (B2.2 unimplemented). " +
            "If this assertion ever flips green, B2.2 has landed and this test should tighten.",
          sawDiscoveryUpdated,
        )

        // 4. Second render — must serve v2.
        val secondStart = System.currentTimeMillis()
        val rn2 = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals(listOf(previewId), rn2.queued)
        val finished2 = client.pollRenderFinishedFor(previewId, timeout = 15.seconds)
        val secondFinishedAt = System.currentTimeMillis()
        val v2ReportedPath =
          finished2["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished missing pngPath: $finished2")
        val v2Actual = File(v2ReportedPath).readBytes()
        val diffV2 = PixelDiff.compare(actual = v2Actual, expected = v2Bytes)
        if (!diffV2.ok) {
          PixelDiff.writeDiffArtefacts(
            actual = v2Actual,
            expected = v2Bytes,
            outDir = paths.reportsDir,
          )
          throw AssertionError(
            "S3 v2 render did not match v2 fixture: ${diffV2.message}. " +
              "Artefacts under ${paths.reportsDir.absolutePath}. Stderr=\n${client.dumpStderr()}"
          )
        }

        // Sanity: v1 and v2 must actually differ (otherwise the swap was a no-op).
        val diffSwap = PixelDiff.compare(actual = v1Bytes, expected = v2Bytes)
        assertFalse(
          "S3 sanity: v1 and v2 fixtures must differ — otherwise editSource was a no-op",
          diffSwap.ok,
        )

        paths.latency.record(
          scenario = paths.name,
          preview = "$previewId@v2",
          actualMs = secondFinishedAt - secondStart,
        )
      }

      // 5. Clean shutdown.
      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
