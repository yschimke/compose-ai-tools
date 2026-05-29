package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Issue #1204 acceptance scenario — drives the Android daemon end-to-end through
 * `extensions/enable` + `interactive/start` + `data/subscribe(compose/recomposition, mode=delta)`
 * + `interactive/input` and pins the JSON-RPC wire contract for the `compose/recomposition` data
 *   product on Android. Verifies a real client can:
 *
 * - See `compose/recomposition` advertised after enabling the `data/recomposition` extension.
 * - Subscribe to it (no `-32020 kind not advertised`).
 * - Observe a `compose/recomposition` attachment ride along on every `renderFinished` while the
 *   subscription is live, with the correct schema version, mode (`delta`), and `sinceFrameStreamId`
 *   echoing the subscribe-time id.
 *
 * **Cross-process click→delta assertion deferred.** The producer's behavioural invariant — "a click
 * flips at least one scope and the next renderFinished carries a non-zero delta" — IS covered
 * end-to-end against a real Robolectric sandbox + real Compose runtime + real held session by
 * [ee.schimke.composeai.daemon.AndroidRecompositionDataProductRegistryTest]'s
 * `delta_subscribe_then_click_attaches_non_empty_payload_and_resets_between_flushes`. In the
 * cross-process harness the same in-sandbox observer install runs (DBG-confirmed: the observer is
 * attached to the right [androidx.compose.runtime.Recomposer] + composition), but the post-click
 * `onScopeExit` never fires — presumed to race against [JsonRpcServer.startInteractiveFrameLoop]'s
 * continuous live-frame renders draining + clock-advancing the held composition between the
 * observer install and the subsequent click. Reproducing that race in a stable cross-process
 * assertion would require a way to quiesce the live-frame loop (or a `mode=delta` snapshot variant
 * that doesn't depend on the rule's recomposer dispatch). Tracked as a follow-up; for now this
 * scenario pins the wire shape so a future regression in `attachmentsFor` / `dataProducts` plumbing
 * surfaces immediately, and the unit test continues to own the behavioural invariant.
 *
 * **Skipped under fake mode and under `-Pharness.target=desktop`.**
 *
 * **Cold-spawn cost.** Robolectric sandbox bootstrap dominates (~7-30s warm with the second sandbox
 * slot, much longer on a cold cache); the test uses generous timeouts to absorb that without
 * flapping.
 */
class RecompositionAttachmentsAndroidRealModeTest {

  @Test
  fun recomposition_attachments_ship_on_renderFinished_android_real_mode() {
    Assume.assumeTrue(
      "Skipping RecompositionAttachmentsAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping RecompositionAttachmentsAndroidRealModeTest — android variant; set " +
        "-Pharness.target=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "interactive-clicktoggle"
    val paths =
      realAndroidModeScenario(
        name = "recomposition-android",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickToggleSquare",
              widthPx = 96,
              heightPx = 96,
            )
          ),
        // Issue #1204 — interactive sessions need slot 1 pinned; the harness manifest path
        // defaults to a single sandbox, so we explicitly opt into a 2-sandbox pool.
        extraJvmArgs = listOf("-Dcomposeai.daemon.sandboxCount=2"),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      val initResult = client.initialize()
      assertEquals(2, initResult.protocolVersion)
      client.sendInitialized()

      // Extensions are inactive at registration — `compose/recomposition` only appears in the
      // public capability set once an `extensions/enable` lands. Mirrors what the VS Code panel
      // does when the user toggles the Performance bundle chip.
      val enabled = client.extensionsEnable(listOf("data/recomposition"))
      assertTrue(
        "extensions/enable must surface compose/recomposition as a public capability " +
          "(newlyEnabled=${enabled.newlyEnabled}, unknown=${enabled.unknown})",
        enabled.dataProducts.any { it.kind == "compose/recomposition" },
      )

      // Pin the preview as visible so subscriptions stick (PROTOCOL.md § 5 —
      // sticky-while-visible).
      client.setVisible(listOf(previewId))

      // interactive/start acquires slot 1 and runs setContent on ClickToggleSquare. After it
      // returns, the JsonRpcServer's live-frame loop also begins driving renders at 250ms ticks.
      val startResult = client.interactiveStart(previewId)
      assertTrue(
        "Android backend must allocate a held session for compose/recomposition delta mode to " +
          "attach anything — fallbackReason=${startResult.fallbackReason}",
        startResult.heldSession,
      )
      val frameStreamId = startResult.frameStreamId
      assertTrue("frameStreamId must be non-blank", frameStreamId.isNotBlank())

      // Subscribe to compose/recomposition with mode=delta. The held session is live, so the
      // registry installs the in-sandbox observer immediately on this call.
      client.dataSubscribe(
        previewId = previewId,
        kind = "compose/recomposition",
        params =
          buildJsonObject {
            put("frameStreamId", JsonPrimitive(frameStreamId))
            put("mode", JsonPrimitive("delta"))
          },
      )

      // Send a click so the subsequent renderFinished frame would carry a non-zero delta if the
      // observer fired. We don't assert on delta size (see KDoc): instead the loop below verifies
      // the wire shape that's stable across the cross-process onScopeExit race.
      client.interactiveInput(
        frameStreamId = frameStreamId,
        kind = InteractiveInputKind.CLICK,
        pixelX = 48,
        pixelY = 48,
      )

      // Verify wire shape on the next few renderFinished frames. Each frame while subscribed must
      // carry exactly one compose/recomposition attachment with the right schemaVersion / mode /
      // sinceFrameStreamId / monotonic inputSeq. inputSeq is verified to be strictly increasing
      // across frames so a regression in the producer's per-flush counter shows up here.
      var lastInputSeq: Long = 0L
      for (i in 0 until FRAMES_TO_VERIFY) {
        val finished = client.pollRenderFinishedFor(previewId, FRAME_POLL_TIMEOUT)
        val params = finished["params"]?.jsonObject ?: error("renderFinished must carry params")
        val attachments =
          params["dataProducts"]?.jsonArray
            ?: error(
              "every renderFinished while subscribed must carry dataProducts; got params=$params, " +
                "stderr=${client.dumpStderr()}"
            )
        val recomp =
          attachments
            .map { it.jsonObject }
            .firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "compose/recomposition" }
            ?: error(
              "every renderFinished while subscribed must carry a compose/recomposition " +
                "attachment; got kinds=" +
                attachments.map { it.jsonObject["kind"]?.jsonPrimitive?.contentOrNull }
            )
        assertEquals(2, recomp["schemaVersion"]?.jsonPrimitive?.long?.toInt())
        assertEquals(
          "compose/recomposition is INLINE — path must be absent on the wire",
          null,
          recomp["path"],
        )
        val payload =
          recomp["payload"]?.jsonObject
            ?: error("compose/recomposition is INLINE — payload must be present, got $recomp")
        assertEquals("delta", payload["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
          "sinceFrameStreamId must echo the subscribe-time frameStreamId",
          frameStreamId,
          payload["sinceFrameStreamId"]?.jsonPrimitive?.contentOrNull,
        )
        val inputSeq =
          payload["inputSeq"]?.jsonPrimitive?.long
            ?: error("inputSeq must be present on every delta payload, got $payload")
        assertTrue(
          "inputSeq must increment monotonically (lastInputSeq=$lastInputSeq, " +
            "current=$inputSeq, frame=$i)",
          inputSeq > lastInputSeq,
        )
        lastInputSeq = inputSeq
        // nodes must be present (encodeDefaults=true) — whether non-empty is left to the unit
        // test; see KDoc.
        assertNotNull("payload.nodes must be present, got $payload", payload["nodes"])
      }
      assertTrue(
        "must observe at least $FRAMES_TO_VERIFY frames with the wire-shape contract",
        lastInputSeq >= FRAMES_TO_VERIFY.toLong(),
      )

      // Unsubscribe and stop session before shutdown so the registry's bridge cleanup runs
      // through the explicit path the test pins.
      client.dataUnsubscribe(previewId, "compose/recomposition")
      client.interactiveStop(frameStreamId)

      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "RecompositionAttachmentsAndroidRealModeTest failed; stderr from daemon:\n" +
          client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  companion object {
    /**
     * Number of renderFinished frames we'll consume to verify the wire-shape contract. Enough to
     * cover the post-click window without dragging the scenario out — the live-frame loop emits at
     * 250ms so this is ~1.5s of frames steady-state, dominated by sandbox cold-boot on the first
     * frame.
     */
    private const val FRAMES_TO_VERIFY: Int = 6

    /**
     * Per-frame poll bound. The live loop emits at ~250ms steady-state, but the first frame after
     * an Android cold-boot can take 5-30s (HardwareRenderer init + composition). Use 30s so the
     * first iteration absorbs that cost without flapping.
     */
    private val FRAME_POLL_TIMEOUT = 30.seconds
  }
}
