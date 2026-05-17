package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the `compose/recomposition` producer's contract on Android (issue #1204) — the
 * in-sandbox observer install + cross-classloader counter bridge end-to-end. Mirrors the
 * desktop `RecompositionDataProductRegistryTest` shape but drives a real
 * [RobolectricHost] + sandbox + held interactive session so the full Robolectric / Compose
 * runtime / `findViewTreeCompositionContext` path is covered.
 */
class AndroidRecompositionDataProductRegistryTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun capabilities_advertise_compose_recomposition_with_requires_rerender() {
    val registry = AndroidRecompositionDataProductRegistry()
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
  fun fetch_delta_against_non_live_preview_returns_not_available() {
    val registry = AndroidRecompositionDataProductRegistry()
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
    val registry = AndroidRecompositionDataProductRegistry()
    val outcome =
      registry.fetch(
        previewId = "any-preview",
        kind = "compose/recomposition",
        params = kotlinx.serialization.json.JsonObject(emptyMap()),
        inline = true,
      )
    assertTrue(
      "snapshot fetch must require a re-render in mode=recomposition; got $outcome",
      outcome is DataProductRegistry.Outcome.RequiresRerender,
    )
    assertEquals("recomposition", (outcome as DataProductRegistry.Outcome.RequiresRerender).mode)
  }

  @Test
  fun delta_subscribe_against_non_live_preview_falls_back_to_snapshot_with_empty_nodes() {
    val registry = AndroidRecompositionDataProductRegistry()
    val params = buildJsonObject {
      put("frameStreamId", JsonPrimitive("non-live-stream"))
      put("mode", JsonPrimitive("delta"))
    }
    registry.onSubscribe("non-live-preview", "compose/recomposition", params)
    val attachments = registry.attachmentsFor("non-live-preview", setOf("compose/recomposition"))
    // No live session → snapshot fallback ships no payload (sandboxStreamId is null).
    assertEquals(0, attachments.size)
    registry.onUnsubscribe("non-live-preview", "compose/recomposition")
  }

  @Test
  fun delta_subscribe_then_click_attaches_non_empty_payload_and_resets_between_flushes() {
    val outputDir = tempFolder.newFolder("renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val registry = AndroidRecompositionDataProductRegistry()
    val resolver = previewSpecResolver()
    val host =
      RobolectricHost(
        sandboxCount = 2,
        previewSpecResolver = resolver,
        interactiveSessionListener =
          RobolectricHost.InteractiveSessionListener { event -> registry.onSessionLifecycle(event) },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = CLICKABLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        // Subscribe after the listener has populated the live session so onSubscribe takes the
        // install-immediately path.
        registry.onSubscribe(
          CLICKABLE_PREVIEW_ID,
          "compose/recomposition",
          buildJsonObject {
            put("frameStreamId", JsonPrimitive("test-frame-stream-1"))
            put("mode", JsonPrimitive("delta"))
          },
        )

        // First flush: drains any initial-composition counts. We don't assert on the exact
        // nodes — Compose's bootstrap scope pattern varies across runtime versions; the post-
        // click delta below is what we care about.
        session.render(requestId = RenderHost.nextRequestId())
        registry.attachmentsFor(CLICKABLE_PREVIEW_ID, setOf("compose/recomposition"))

        // Click flips `ClickableToggleSquare`'s remember'd state, which forces at least one
        // RecomposeScope to recompose. The observer captures the count via the bridge.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-frame-stream-1",
            kind = InteractiveInputKind.CLICK,
            pixelX = INTERACTIVE_WIDTH_PX / 2,
            pixelY = INTERACTIVE_HEIGHT_PX / 2,
          )
        )
        session.render(requestId = RenderHost.nextRequestId())

        val postClick =
          registry.attachmentsFor(CLICKABLE_PREVIEW_ID, setOf("compose/recomposition"))
        assertEquals(1, postClick.size)
        assertEquals("compose/recomposition", postClick[0].kind)
        val payload = postClick[0].payload!!.jsonObject
        assertEquals("delta", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("test-frame-stream-1", payload["sinceFrameStreamId"]?.jsonPrimitive?.content)
        assertEquals(2L, payload["inputSeq"]?.jsonPrimitive?.content?.toLong())
        val nodes = payload["nodes"]?.jsonArray
        assertNotNull("nodes must be non-null", nodes)
        assertTrue(
          "post-click delta must carry at least one recomposed scope (got '$nodes')",
          nodes!!.size >= 1,
        )

        // Quiet flush — no further input, so the next drain ships an empty nodes list.
        session.render(requestId = RenderHost.nextRequestId())
        val nextAttachments =
          registry.attachmentsFor(CLICKABLE_PREVIEW_ID, setOf("compose/recomposition"))
        assertEquals(1, nextAttachments.size)
        val nextPayload = nextAttachments[0].payload!!.jsonObject
        assertEquals(
          "no further input → no new recompositions → empty nodes",
          0,
          nextPayload["nodes"]?.jsonArray?.size,
        )
        assertEquals(3L, nextPayload["inputSeq"]?.jsonPrimitive?.content?.toLong())
      } finally {
        registry.onUnsubscribe(CLICKABLE_PREVIEW_ID, "compose/recomposition")
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    when (previewId) {
      CLICKABLE_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "ClickableToggleSquare",
          widthPx = INTERACTIVE_WIDTH_PX,
          heightPx = INTERACTIVE_HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-clickable",
        )
      else -> null
    }
  }

  companion object {
    private const val CLICKABLE_PREVIEW_ID = "interactive-clickable"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
  }
}
