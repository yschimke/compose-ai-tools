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
 * The catalog-id bridge: [ServeCatalogLiveHost] must front the baked catalog with the daemon so the
 * published catalog ids resolve — plain browsing stays baked (never wakes the daemon), an override
 * edit / live stream on a mapped id goes to the daemon under its daemon-preview id, and an unmapped
 * id (an Android-only variant) falls back to baked.
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
  fun `browse surface and knobs come from the baked catalog`() {
    val (composite, _, baked) = host()
    assertEquals(baked.previews, composite.previews)
    // Mapped ids can re-render live, so the viewer should offer editable controls.
    assertTrue(composite.canApplyOverrides)
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
  fun `overridden snapshot of a mapped id renders live under the daemon id`() {
    val (composite, live, _) = host()
    val out = composite.render(catalogId, PreviewOverrides(density = 2.0f)) as RenderOutcome.Ok
    assertEquals("live:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, live.lastRenderId)
  }

  @Test
  fun `an unmapped id always serves baked, even with overrides`() {
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
