@file:OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.ui.ImageComposeScene
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D5 — pins the `compose/recomposition` producer's contract for the desktop interactive surface.
 * See [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) §
 * "Recomposition + interactive mode".
 *
 * Three scenarios:
 *
 * 1. **Capabilities + delta-mode happy path** — the producer advertises `compose/recomposition`
 *    with `requiresRerender=true`, then a stateful preview ([ClickRecomposingSquare]) attaches a
 *    non-empty counter payload after a click. The second post-click attachment carries strictly
 *    fewer counts than the first (the delta has been reset between flushes), proving the inputSeq
 *    increments and counters reset.
 * 2. **`mode=delta` against a non-live preview** — the producer falls back to snapshot at
 *    `onSubscribe` (per the D5 brief), so attachments still ship but with an empty payload until a
 *    live session arrives.
 * 3. **Safety net** — when observer install throws (simulated by closing the scene before
 *    onSubscribe so reflection sees a torn-down state), the subscription still succeeds and
 *    `attachmentsFor` ships an empty `nodes: []` payload. The daemon doesn't crash.
 *
 * Driven against a real [DesktopHost] + [DesktopInteractiveSession] so the end-to-end Compose
 * runtime path (Recomposer, CompositionObserver, Modifier.clickable, mutableStateOf) is covered.
 */
class RecompositionDataProductRegistryTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun capabilities_advertise_compose_recomposition_with_requires_rerender() {
    val registry = RecompositionDataProductRegistry()
    val byKind = registry.capabilities.associateBy { it.kind }
    assertEquals(setOf("compose/recomposition"), byKind.keys)
    val cap = byKind.getValue("compose/recomposition")
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertEquals(1, cap.schemaVersion)
    assertTrue("compose/recomposition must be attachable", cap.attachable)
    assertTrue("compose/recomposition must be fetchable", cap.fetchable)
    assertTrue(
      "compose/recomposition must declare requiresRerender=true so snapshot fetches re-render",
      cap.requiresRerender,
    )
  }

  @Test
  fun delta_subscribe_then_click_attaches_non_empty_payload_and_resets_between_flushes() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val registry = RecompositionDataProductRegistry()
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickRecomposingSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "click-recomposing-square",
            )
          } else null
        },
        interactiveSessionListener =
          DesktopHost.InteractiveSessionListener { previewId, scene ->
            registry.onSessionLifecycle(previewId, scene)
          },
      )
    host.start()
    try {
      val classLoader =
        RecompositionDataProductRegistryTest::class.java.classLoader
          ?: ClassLoader.getSystemClassLoader()
      val session = host.acquireInteractiveSession(FIXTURE_PREVIEW_ID, classLoader)
      try {
        // Subscribe with delta mode AFTER the session is live — the listener has already
        // populated liveScenes for FIXTURE_PREVIEW_ID, so onSubscribe installs the observer
        // immediately rather than going through the snapshot-promotion path.
        val subscribeParams = buildJsonObject {
          put("frameStreamId", JsonPrimitive("test-stream-1"))
          put("mode", JsonPrimitive("delta"))
        }
        registry.onSubscribe(FIXTURE_PREVIEW_ID, "compose/recomposition", subscribeParams)

        // 1. Bootstrap render — initial composition runs the ClickRecomposingSquare body once.
        //    The observer increments at least one scope count. We don't assert on the
        //    pre-click attachment shape — Compose's initial-composition counter pattern is
        //    runtime-version-sensitive; what we care about is the post-click delta below.
        session.render(requestId = RenderHost.nextRequestId())
        registry.attachmentsFor(FIXTURE_PREVIEW_ID, setOf("compose/recomposition"))

        // 2. Click. ClickRecomposingSquare wires the click to a `clicks` mutableStateOf, then
        //    reads it inside an inner `key(clicks)` block — the inner scope recomposes on
        //    every click. The whole-card pointerInput catches the press regardless of pixel
        //    position so we can be loose with the click coords.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-stream-1",
            kind = InteractiveInputKind.CLICK,
            pixelX = 32,
            pixelY = 32,
          )
        )
        session.render(requestId = RenderHost.nextRequestId())

        // 3. Pull the post-click attachment. The payload should carry mode=delta,
        //    sinceFrameStreamId=test-stream-1, inputSeq=2 (the second flush), and a
        //    non-empty nodes list.
        val postClickAttachments =
          registry.attachmentsFor(FIXTURE_PREVIEW_ID, setOf("compose/recomposition"))
        assertEquals(1, postClickAttachments.size)
        val postClick = postClickAttachments[0]
        assertEquals("compose/recomposition", postClick.kind)
        assertEquals(1, postClick.schemaVersion)
        assertNotNull("delta payload must travel inline", postClick.payload)
        assertNull("compose/recomposition is INLINE-only; path must be null", postClick.path)
        val payload = postClick.payload!!.jsonObject
        assertEquals("delta", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("test-stream-1", payload["sinceFrameStreamId"]?.jsonPrimitive?.content)
        // Two flushes have happened by now (the bootstrap-only and the post-click one), so
        // inputSeq is 2.
        assertEquals(
          "inputSeq must increment monotonically per attachmentsFor call",
          2L,
          payload["inputSeq"]?.jsonPrimitive?.content?.toLong(),
        )
        val nodes = payload["nodes"]?.jsonArray
        assertNotNull("nodes must be non-null in delta mode", nodes)
        assertTrue(
          "post-click delta must carry at least one recomposed scope (got '$nodes')",
          nodes!!.size >= 1,
        )

        // 4. Without a second click, the next flush carries an empty nodes list — the post-
        //    click counters got reset by the previous attachmentsFor call. Re-render so any
        //    invalidation pending after the click drains, then check.
        session.render(requestId = RenderHost.nextRequestId())
        val nextAttachments =
          registry.attachmentsFor(FIXTURE_PREVIEW_ID, setOf("compose/recomposition"))
        assertEquals(1, nextAttachments.size)
        val nextPayload = nextAttachments[0].payload!!.jsonObject
        assertEquals(
          "no further input → no new recompositions → empty nodes",
          0,
          nextPayload["nodes"]?.jsonArray?.size,
        )
        assertEquals(3L, nextPayload["inputSeq"]?.jsonPrimitive?.content?.toLong())
      } finally {
        registry.onUnsubscribe(FIXTURE_PREVIEW_ID, "compose/recomposition")
        session.close()
      }
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun delta_subscribe_against_non_live_preview_falls_back_to_snapshot_with_empty_nodes() {
    // No DesktopHost / session — just the producer in isolation. Subscribe asks for delta
    // mode against a previewId that has no live scene; the producer logs a warning and treats
    // the subscription as snapshot. attachmentsFor still ships a payload (the brief: "advertise
    // but useless") with mode=snapshot and empty nodes.
    val registry = RecompositionDataProductRegistry()
    val params = buildJsonObject {
      put("frameStreamId", JsonPrimitive("non-live-stream"))
      put("mode", JsonPrimitive("delta"))
    }
    registry.onSubscribe("non-live-preview", "compose/recomposition", params)
    val attachments = registry.attachmentsFor("non-live-preview", setOf("compose/recomposition"))
    assertEquals(1, attachments.size)
    val payload = attachments[0].payload!!.jsonObject
    assertEquals(
      "subscribe against non-live session must downgrade to snapshot mode",
      "snapshot",
      payload["mode"]?.jsonPrimitive?.content,
    )
    assertEquals("no live observer → no nodes", 0, payload["nodes"]?.jsonArray?.size)
    registry.onUnsubscribe("non-live-preview", "compose/recomposition")
  }

  @Test
  fun fetch_delta_against_non_live_preview_returns_not_available() {
    val registry = RecompositionDataProductRegistry()
    val params = buildJsonObject {
      put("frameStreamId", JsonPrimitive("nope"))
      put("mode", JsonPrimitive("delta"))
    }
    val outcome =
      registry.fetch(
        previewId = "no-such-preview",
        kind = "compose/recomposition",
        params = params,
        inline = true,
      )
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun fetch_snapshot_returns_requires_rerender_so_dispatcher_drives_the_render() {
    val registry = RecompositionDataProductRegistry()
    val outcome =
      registry.fetch(
        previewId = "any-preview",
        kind = "compose/recomposition",
        params = JsonObject(emptyMap()),
        inline = true,
      )
    assertTrue(
      "snapshot fetch must require a re-render in mode=recomposition; got $outcome",
      outcome is DataProductRegistry.Outcome.RequiresRerender,
    )
    assertEquals("recomposition", (outcome as DataProductRegistry.Outcome.RequiresRerender).mode)
  }

  @Test
  fun observer_install_failure_degrades_to_advertise_but_useless() {
    // Simulate a Compose-runtime API rename — we override `installObserver` to throw a
    // LinkageError, which is the exact failure shape the brief asks the safety net to catch.
    // The producer's catch block must:
    //   1. Not propagate the LinkageError (don't crash the daemon).
    //   2. Mark the subscription as `instrumentation unavailable`.
    //   3. Continue to ship `nodes: []` payloads from `attachmentsFor` so the panel can keep
    //      rendering, just without the heat-map data.
    val registry =
      object : RecompositionDataProductRegistry() {
        override fun installObserver(
          scene: androidx.compose.ui.ImageComposeScene,
          onScopeRecomposed: (androidx.compose.runtime.RecomposeScope) -> Unit,
          onScopeDisposed: (androidx.compose.runtime.RecomposeScope) -> Unit,
        ): androidx.compose.runtime.tooling.CompositionObserverHandle {
          throw NoSuchMethodError(
            "synthetic: androidx.compose.runtime.tooling.CompositionObserver.onScopeExit signature changed"
          )
        }
      }
    val previewId = "preview-with-broken-instrumentation"
    val scene =
      ImageComposeScene(width = 32, height = 32, density = androidx.compose.ui.unit.Density(1.0f)) {
        // Empty content — we just need a real scene to feed onSessionLifecycle, the override
        // above is what intercepts and throws.
      }
    try {
      registry.onSessionLifecycle(previewId, scene)
      val params = buildJsonObject {
        put("frameStreamId", JsonPrimitive("broken-stream"))
        put("mode", JsonPrimitive("delta"))
      }
      // The subscribe call must NOT propagate the LinkageError — that's the load-bearing
      // assertion. If the safety net is missing, this line throws and the test fails before
      // reaching any of the assertions below.
      registry.onSubscribe(previewId, "compose/recomposition", params)

      // attachmentsFor still ships — empty nodes list because the observer never installed.
      val attachments = registry.attachmentsFor(previewId, setOf("compose/recomposition"))
      assertEquals(1, attachments.size)
      val payload = attachments[0].payload!!.jsonObject
      assertEquals(
        "instrumentation-unavailable subscriptions still emit empty payloads",
        0,
        payload["nodes"]?.jsonArray?.size,
      )
      registry.onUnsubscribe(previewId, "compose/recomposition")
    } finally {
      scene.close()
    }
  }

  companion object {
    private const val FIXTURE_PREVIEW_ID = "click-recomposing-square"
  }
}
