package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
 * 3. Sends `fileChanged({kind: source})` against a `.kt` file the index anchors the preview to.
 * 4. **B2.2 phase 2** — asserts the daemon emits `discoveryUpdated` with `removed = [<previewId>]`
 *    (the harness's classpath has no compiled bytecode under the fixture dir, so the scoped scan
 *    returns empty and the diff path treats the preview as deleted-from-this-file). Pre-phase-2
 *    this assertion was inverted: we polled with a 250ms timeout that was expected to expire.
 * 5. `renderNow` again → asserts v2 is what comes back.
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

    // B2.2 phase 2 — synthesise a stand-in source file that the daemon's PreviewIndex anchors the
    // preview to. The cheap pre-filter trips on either an `@Preview` text match OR an index hit by
    // `sourceFile`; we use the latter (no real Compose code in the fixture). The follow-up
    // `fileChanged` carries this same path so the diff path sees "preview was on this file, scan
    // returned nothing → emit removed = [previewId]".
    val sourceKtFile = File(paths.fixtureDir, "Preview1.kt")
    sourceKtFile.writeText(
      """
      // Fake-mode harness fixture for S3. Not compiled. The daemon's discovery cascade reads this
      // path purely as the `sourceFile` anchor recorded in the in-memory PreviewIndex.
      """
        .trimIndent()
    )
    writePreviewsManifest(paths.fixtureDir, listOf(previewId to sourceKtFile.absolutePath))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      val initResult = client.initialize()
      assertEquals(1, initResult.protocolVersion)
      assertTrue(
        "B2.2 phase 2 — IncrementalDiscovery wired in fake-mode, capability must flip true",
        initResult.capabilities.incrementalDiscovery,
      )
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
        // 3. Tell the daemon a source file changed. Use the `.kt` anchor — the cheap pre-filter
        //    consults the index by `sourceFile` and trips on the match, the scoped scan returns
        //    empty (no compiled bytecode under the fixture), and the diff emits removed.
        client.fileChanged(
          path = sourceKtFile.absolutePath,
          kind = FileKind.SOURCE,
          changeType = ChangeType.MODIFIED,
        )

        // 4. Second render — must serve v2.
        //    The new save-after-render invariant gates `discoveryUpdated` on the next
        //    `renderFinished`, so the test asserts the render lands first (matching what a real
        //    editor's save loop would do) and only then polls for the deferred metadata
        //    notification.
        val secondStart = System.currentTimeMillis()
        val rn2 = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
        assertEquals(listOf(previewId), rn2.queued)
        val finished2 = client.pollRenderFinishedFor(previewId, timeout = 15.seconds)
        val secondFinishedAt = System.currentTimeMillis()
        val v2ReportedPath =
          finished2["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished missing pngPath: $finished2")

        // 5. Assert the daemon emits `discoveryUpdated` AFTER the render finished. B2.2 phase 2 —
        //    pre-phase-2 this assertion was the *absence* of the notification (the regression
        //    marker); now we tighten and pin the new render-before-metadata ordering.
        val discovery = client.pollNotification("discoveryUpdated", 5.seconds)
        val params =
          discovery["params"]?.jsonObject ?: error("discoveryUpdated missing params: $discovery")
        val removedIds =
          params["removed"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        assertTrue(
          "discoveryUpdated.removed must contain $previewId; got $removedIds",
          previewId in removedIds,
        )
        // The fake-mode scan returns no @Preview classes; nothing else changes.
        assertEquals(emptyList<Any?>(), params["added"]?.jsonArray?.toList() ?: emptyList<Any?>())
        assertEquals(0, params["totalPreviews"]?.jsonPrimitive?.intOrNull)

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

      // 6. Clean shutdown.
      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
