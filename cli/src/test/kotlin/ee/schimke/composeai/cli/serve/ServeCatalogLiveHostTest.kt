package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog-id bridge: [ServeCatalogLiveHost] fronts the baked catalog with an opt-in daemon
 * stream. Every **snapshot** is the baked PNG (browsing stays instant and never wakes the daemon,
 * even when the viewer replays a sticky theme override); the daemon is reached only via the **live
 * stream**, mapping the catalog id to its daemon-preview id. An unmapped id (an Android-only
 * variant) has no stream. The composite reports itself as a static-snapshot host
 * ([canApplyOverrides] false) that still offers Live ([hasLiveStream] true), and exposes its baked
 * host so the trust badge + card title survive.
 */
class ServeCatalogLiveHostTest {

  /** Records the (id, overrides) of the last call and whether it was reached at all. */
  private class RecordingHost(
    override val previews: List<ServePreview>,
    private val tag: String,
    private val streaming: Boolean = false,
  ) : ServeHost {
    override val label: String = tag
    override val canApplyOverrides: Boolean = streaming
    var lastRenderId: String? = null
    var lastStreamId: String? = null
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      lastRenderId = previewId
      return RenderOutcome.Ok("$tag:$previewId".encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? {
      lastStreamId = previewId
      if (!streaming) return null
      return object : StreamHandle {
        override fun input(
          kind: InteractiveInputKind,
          pixelX: Int?,
          pixelY: Int?,
          pointerId: Int?,
          scrollDeltaY: Float?,
          keyCode: String?,
        ) {}

        override fun close() {}
      }
    }

    override fun activeStreamCount(): Int = if (streaming) 1 else 0

    override fun close() {
      closed = true
    }
  }

  private val catalogId = "button-filled__ideal__default__dark"
  private val daemonId = "FilledButton_Dark"
  private val androidOnlyId = "button-filled__ideal__keyboard-focus__dark"

  private fun host(): Triple<ServeCatalogLiveHost, RecordingHost, RecordingHost> {
    val baked =
      RecordingHost(
        previews =
          listOf(ServePreview(catalogId, catalogId), ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    return Triple(composite, live, baked)
  }

  @Test
  fun `presents as a static-snapshot host that still offers Live`() {
    val (composite, _, baked) = host()
    assertEquals(baked.previews, composite.previews)
    // Snapshots stay static (baked, instant) so the viewer shows the published pixels + trust
    // badge…
    assertEquals(false, composite.canApplyOverrides)
    // …but the "Live (stream)" toggle is still offered.
    assertTrue(composite.hasLiveStream)
    // The baked host is exposed so the HTTP layer can read its title / subtitle / trust verdict.
    assertEquals(baked, composite.bakedHost)
  }

  @Test
  fun `plain snapshot of a mapped id serves the baked PNG, never the daemon`() {
    val (composite, live, baked) = host()
    val out = composite.render(catalogId, PreviewOverrides()) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", out.png.decodeToString())
    assertEquals(catalogId, baked.lastRenderId)
    assertNull(live.lastRenderId) // daemon untouched by ordinary browsing
  }

  @Test
  fun `snapshots stay baked even with overrides on a mapped id`() {
    // The viewer replays a sticky theme override into the snapshot URL; the composite must still
    // serve the baked PNG (never cold-start the daemon on the snapshot lane) — the daemon is the
    // live-stream lane only.
    val (composite, live, baked) = host()
    val out = composite.render(catalogId, PreviewOverrides(density = 2.0f)) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", out.png.decodeToString())
    assertEquals(catalogId, baked.lastRenderId)
    assertNull(live.lastRenderId)
  }

  @Test
  fun `an unmapped id serves baked, even with overrides`() {
    val (composite, live, baked) = host()
    val out = composite.render(androidOnlyId, PreviewOverrides(density = 2.0f)) as RenderOutcome.Ok
    assertEquals("baked:$androidOnlyId", out.png.decodeToString())
    assertEquals(androidOnlyId, baked.lastRenderId)
    assertNull(live.lastRenderId)
  }

  @Test
  fun `live stream is offered for a mapped id under the daemon id`() {
    val (composite, live, _) = host()
    val handle = composite.subscribeStream(catalogId, PreviewOverrides(), null, null) {}
    assertTrue(handle != null)
    assertEquals(daemonId, live.lastStreamId)
  }

  @Test
  fun `an unmapped id has no live stream and never reaches the daemon`() {
    val (composite, live, _) = host()
    val handle = composite.subscribeStream(androidOnlyId, PreviewOverrides(), null, null) {}
    assertNull(handle)
    assertNull(live.lastStreamId)
  }

  @Test
  fun `closing the composite closes both lanes`() {
    val (composite, live, baked) = host()
    composite.close()
    assertTrue(live.closed)
    assertTrue(baked.closed)
  }
}
