package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
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
    var lastRenderOverrides: PreviewOverrides? = null
    var lastSvgId: String? = null
    var lastStreamId: String? = null
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      lastRenderId = previewId
      lastRenderOverrides = overrides
      return RenderOutcome.Ok("$tag:$previewId".encodeToByteArray())
    }

    override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
      lastSvgId = previewId
      lastRenderOverrides = overrides
      return SvgOutcome.Ok("$tag-svg:$previewId".encodeToByteArray())
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
        previews = listOf(ServePreview(daemonId, daemonId, overrides = listOf(labelKnob))),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    return Triple(composite, live, baked)
  }

  /** An author-declared `label` knob the daemon carries for the mapped preview. */
  private val labelKnob =
    PreviewOverrideDeclaration(
      key = "label",
      type = PreviewOverrideType.STRING,
      default = PreviewOverrideValue.StringValue("Filled"),
    )

  /** A knob-bearing override — the sole case the baked PNG can't satisfy. */
  private fun knobOverride() =
    PreviewOverrides(namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("Tap me")))

  @Test
  fun `presents as a static-snapshot host that still offers Live`() {
    val (composite, _, baked) = host()
    // Same ids + order as the baked browse surface (deep links + grid resolve unchanged).
    assertEquals(baked.previews.map { it.id }, composite.previews.map { it.id })
    // Snapshots stay static (baked, instant) so the viewer shows the published pixels + trust
    // badge…
    assertEquals(false, composite.canApplyOverrides)
    // …but the carried daemon CAN re-render an override on demand, so the knob controls are live…
    assertTrue(composite.canRenderOverrides)
    // …and the "Live (stream)" toggle is still offered.
    assertTrue(composite.hasLiveStream)
    // The baked host is exposed so the HTTP layer can read its title / subtitle / trust verdict.
    assertEquals(baked, composite.bakedHost)
  }

  @Test
  fun `grafts the daemon's declared knobs onto the mapped baked preview`() {
    val (composite, _, _) = host()
    // The baked catalog images carry no knob declarations; the daemon does. The composite exposes
    // the daemon's declarations on the browse surface so /api/previews + the viewer advertise them.
    val mapped = composite.previews.first { it.id == catalogId }
    assertEquals(listOf(labelKnob), mapped.overrides)
    // An unmapped (Android-only) preview has no daemon twin, so it stays knob-free.
    val unmapped = composite.previews.first { it.id == androidOnlyId }
    assertTrue(unmapped.overrides.isEmpty())
  }

  @Test
  fun `a knob-bearing render on a mapped id routes to the daemon`() {
    val (composite, live, baked) = host()
    val out = composite.render(catalogId, knobOverride()) as RenderOutcome.Ok
    // A named-override edit can only be honoured by re-running the composable — routed to the
    // daemon
    // under its daemon id, with the override carried through.
    assertEquals("live:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, live.lastRenderId)
    assertEquals(knobOverride().namedOverrides, live.lastRenderOverrides?.namedOverrides)
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `a knob-bearing SVG render on a mapped id routes to the daemon`() {
    val (composite, live, _) = host()
    val out = composite.renderSvg(catalogId, knobOverride()) as SvgOutcome.Ok
    assertEquals("live-svg:$daemonId", out.svg.decodeToString())
    assertEquals(daemonId, live.lastSvgId)
  }

  @Test
  fun `a knob-bearing render on an unmapped id stays baked`() {
    // No daemon twin → nothing can honour the knob; serve the baked PNG rather than 404.
    val (composite, live, baked) = host()
    val out = composite.render(androidOnlyId, knobOverride()) as RenderOutcome.Ok
    assertEquals("baked:$androidOnlyId", out.png.decodeToString())
    assertEquals(androidOnlyId, baked.lastRenderId)
    assertNull(live.lastRenderId)
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
