package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServePerPreviewLiveHost] fronts the baked catalog and re-renders each preview from its OWN
 * per-preview bundle's daemon (resolved lazily + pooled by the caller). Browsing / no-op overrides
 * stay baked; only a pixel-changing override on a mapped id resolves a per-preview daemon — and it
 * must be called with the mapped **daemon** id, not the catalog id. An unmapped id, or one whose
 * daemon can't be resolved, falls back to baked. The baked-vs-render decision is shared with
 * [ServeCatalogLiveHost] via [CatalogLiveRouting], so this test focuses on the per-preview routing.
 */
class ServePerPreviewLiveHostTest {

  private class RecordingHost(
    override val previews: List<ServePreview>,
    private val tag: String,
    private val streaming: Boolean = false,
    private val svgNotFound: Boolean = false,
    override val hasSvgExport: Boolean = true,
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
      if (svgNotFound) return SvgOutcome.NotFound
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

  private val catalogId = "button-elevated__ideal__default__light"
  private val daemonId = "ElevatedButtonSticker_Light"
  private val androidOnlyId = "button-filled__ideal__keyboard-focus__dark"

  /** The daemon ids [resolveLive] was asked for — proves per-preview routing uses the daemon id. */
  private val resolved = mutableListOf<String>()

  private fun host(
    liveHost: RecordingHost = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", true),
    resolve: (String) -> ServeHost? = { liveHost },
  ): Pair<ServePerPreviewLiveHost, RecordingHost> {
    val baked =
      RecordingHost(
        previews =
          listOf(ServePreview(catalogId, catalogId), ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
      )
    val composite =
      ServePerPreviewLiveHost(
        alias = mapOf(catalogId to daemonId),
        baked = baked,
        resolveLive = { id ->
          resolved.add(id)
          resolve(id)
        },
        previews = baked.previews,
        streamCount = { liveHost.activeStreamCount() },
      )
    return composite to baked
  }

  private fun knobOverride() =
    PreviewOverrides(
      namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("Televise"))
    )

  @Test
  fun `a knob override renders on the preview's own daemon, called with the daemon id`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val (composite, baked) = host(live)
    val outcome = composite.render(catalogId, knobOverride())
    // Routed to the per-preview daemon — resolved by DAEMON id, rendered by DAEMON id.
    assertEquals(listOf(daemonId), resolved)
    assertEquals(daemonId, live.lastRenderId)
    assertEquals("live:$daemonId", (outcome as RenderOutcome.Ok).png.decodeToString())
    // The baked host was NOT asked to render.
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `an override-free browse stays baked and never resolves a daemon`() {
    val (composite, baked) = host()
    composite.render(catalogId, PreviewOverrides())
    assertEquals(catalogId, baked.lastRenderId)
    assertTrue(resolved.isEmpty(), "browsing must not wake a per-preview daemon")
  }

  @Test
  fun `a uiMode matching the baked variant theme is a no-op and stays baked`() {
    val (composite, baked) = host()
    // The variant id ends in `__light`, so uiMode=LIGHT changes nothing — replay baked.
    composite.render(catalogId, PreviewOverrides(uiMode = UiMode.LIGHT))
    assertEquals(catalogId, baked.lastRenderId)
    assertTrue(resolved.isEmpty())
    // A DIFFERING theme (dark on a light variant) does need a re-render.
    composite.render(catalogId, PreviewOverrides(uiMode = UiMode.DARK))
    assertEquals(listOf(daemonId), resolved)
  }

  @Test
  fun `an unmapped preview always replays baked and reports no live lane`() {
    val (composite, baked) = host()
    assertEquals(false, composite.canRenderOverridesFor(androidOnlyId))
    composite.render(androidOnlyId, knobOverride())
    assertEquals(androidOnlyId, baked.lastRenderId)
    assertTrue(resolved.isEmpty())
    assertNull(composite.subscribeStream(androidOnlyId, PreviewOverrides(), null, null) {})
  }

  @Test
  fun `when no per-preview daemon can be resolved the override falls back to baked`() {
    val (composite, baked) = host(resolve = { null })
    val outcome = composite.render(catalogId, knobOverride())
    assertEquals(listOf(daemonId), resolved, "it tried to resolve the daemon…")
    assertEquals(catalogId, baked.lastRenderId, "…then fell back to the baked catalog PNG")
    assertEquals("baked:$catalogId", (outcome as RenderOutcome.Ok).png.decodeToString())
  }

  @Test
  fun `host advertises static snapshots, on-demand render, live stream, and mapped-only render`() {
    val (composite, _) = host()
    assertEquals(false, composite.canApplyOverrides)
    assertTrue(composite.canRenderOverrides)
    assertTrue(composite.hasLiveStream)
    assertTrue(composite.canRenderOverridesFor(catalogId))
    assertTrue(composite.hasSvgExport, "hasSvgExport defaults to the baked host's capability")
  }

  @Test
  fun `svg prefers the per-preview daemon for an override, else the baked vector`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val (composite, baked) = host(live)
    // Override → the daemon's figma-svg, by daemon id.
    val overSvg = composite.renderSvg(catalogId, knobOverride())
    assertEquals(daemonId, live.lastSvgId)
    assertEquals("live-svg:$daemonId", (overSvg as SvgOutcome.Ok).svg.decodeToString())
    // No override → the baked catalog's committed vector (no daemon).
    resolved.clear()
    val bakedSvg = composite.renderSvg(catalogId, PreviewOverrides())
    assertEquals(catalogId, baked.lastSvgId)
    assertEquals("baked-svg:$catalogId", (bakedSvg as SvgOutcome.Ok).svg.decodeToString())
  }

  @Test
  fun `svg falls back to the per-preview daemon when the baked catalog has no vector`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val baked =
      RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked", svgNotFound = true)
    val composite =
      ServePerPreviewLiveHost(
        alias = mapOf(catalogId to daemonId),
        baked = baked,
        resolveLive = {
          resolved.add(it)
          live
        },
        previews = baked.previews,
      )
    // No override, but the baked lane 404s the vector → fall back to the mapped daemon.
    val svg = composite.renderSvg(catalogId, PreviewOverrides())
    assertEquals("live-svg:$daemonId", (svg as SvgOutcome.Ok).svg.decodeToString())
  }

  @Test
  fun `a live stream subscribes on the preview's own daemon by daemon id`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val (composite, _) = host(live)
    val handle = composite.subscribeStream(catalogId, knobOverride(), null, null) {}
    assertTrue(handle != null, "a mapped preview offers a live stream")
    assertEquals(daemonId, live.lastStreamId)
    assertEquals(1, composite.activeStreamCount())
  }
}
