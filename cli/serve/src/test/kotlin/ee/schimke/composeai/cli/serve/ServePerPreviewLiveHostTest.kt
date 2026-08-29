package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    /** Published typography this host can replay over its baked frame, keyed by preview id. */
    private val publishedTypography: Set<String> = emptySet(),
    /** Models a daemon carrying no `compose/semantics` lane: the annotation fetch NotFounds. */
    private val annotationsNotFound: Boolean = false,
  ) : ServeHost {
    override val label: String = tag
    override val canApplyOverrides: Boolean = streaming
    var lastRenderId: String? = null
    var lastRenderOverrides: PreviewOverrides? = null
    var lastSvgId: String? = null
    var lastStreamId: String? = null
    var lastA11yId: String? = null
    var lastAnnotationsId: String? = null
    var closed = false

    override fun hasPublishedTypographyFor(previewId: String): Boolean =
      previewId in publishedTypography

    override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
      lastA11yId = previewId
      lastRenderOverrides = overrides
      return A11yOutcome.Ok(
        """{"previewId":"$previewId","nodes":[],"findings":[],"touchTargets":[]}"""
          .encodeToByteArray()
      )
    }

    override fun renderAnnotations(
      previewId: String,
      overrides: PreviewOverrides,
    ): AnnotationsOutcome {
      lastAnnotationsId = previewId
      lastRenderOverrides = overrides
      if (annotationsNotFound) return AnnotationsOutcome.NotFound
      if (!streaming && previewId !in publishedTypography) return AnnotationsOutcome.NotFound
      return AnnotationsOutcome.Ok(
        """{"previewId":"$previewId","annotations":[],"tags":{}}""".encodeToByteArray()
      )
    }

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
      onUnavailable: ((String) -> Unit)?,
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
          text: String?,
          pointerType: String?,
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
    bakedPublishedTypography: Set<String> = emptySet(),
  ): Pair<ServePerPreviewLiveHost, RecordingHost> {
    val baked =
      RecordingHost(
        previews =
          listOf(
            ServePreview(
              catalogId,
              catalogId,
              dataProductKinds = setOf(ServeRenderHost.SCROLL_LONG_KIND),
            ),
            ServePreview(androidOnlyId, androidOnlyId),
          ),
        tag = "baked",
        publishedTypography = bakedPublishedTypography,
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
    assertTrue(composite.hasScrollExport)
    assertTrue(composite.hasScrollExportFor(catalogId))
    assertFalse(composite.hasScrollExportFor(androidOnlyId))
  }

  @Test
  fun `mapped preview without long-scroll metadata does not offer full-page export`() {
    val baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked")
    val composite =
      ServePerPreviewLiveHost(
        alias = mapOf(catalogId to daemonId),
        baked = baked,
        resolveLive = { null },
        previews = baked.previews,
      )

    assertFalse(composite.hasScrollExport)
    assertFalse(composite.hasScrollExportFor(catalogId))
  }

  @Test
  fun `svg prefers the per-preview daemon for a mapped id, override or not`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val (composite, baked) = host(live)
    // Override → the daemon's figma-svg, by daemon id.
    val overSvg = composite.renderSvg(catalogId, knobOverride())
    assertEquals(daemonId, live.lastSvgId)
    assertEquals("live-svg:$daemonId", (overSvg as SvgOutcome.Ok).svg.decodeToString())
    // No override → STILL the daemon's per-variant vector. The baked figma/<slug>.svg is slug-keyed
    // +
    // light-preferred (the catalog emits one SVG per component, the light variant), so a `…__dark`
    // id
    // would otherwise serve the LIGHT vector even though its PNG + live render are dark. A mapped
    // id
    // routes its plain SVG to the daemon (which carries the variant's uiMode/theme); the baked slug
    // SVG — which still exists here — isn't consulted.
    resolved.clear()
    live.lastSvgId = null
    val plainSvg = composite.renderSvg(catalogId, PreviewOverrides())
    assertEquals(daemonId, live.lastSvgId)
    assertEquals("live-svg:$daemonId", (plainSvg as SvgOutcome.Ok).svg.decodeToString())
    assertNull(baked.lastSvgId)
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
  fun `inspection layers route to the preview's own daemon, by daemon id, with overrides intact`() {
    val live = RecordingHost(listOf(ServePreview(daemonId, daemonId)), "live", streaming = true)
    val (composite, baked) = host(live)
    val overrides = knobOverride().copy(uiMode = UiMode.DARK, fontScale = 1.3f)

    assertTrue(composite.hasA11yOverlayFor(catalogId))
    assertTrue(composite.hasDesignAnnotationsFor(catalogId))
    val a11y = composite.renderA11y(catalogId, overrides) as A11yOutcome.Ok
    val annotations = composite.renderAnnotations(catalogId, overrides) as AnnotationsOutcome.Ok
    assertEquals(daemonId, live.lastA11yId)
    assertEquals(daemonId, live.lastAnnotationsId)
    assertEquals(overrides, live.lastRenderOverrides)
    assertTrue(a11y.json.decodeToString().contains("\"previewId\":\"$daemonId\""))
    assertTrue(annotations.json.decodeToString().contains("\"previewId\":\"$daemonId\""))
    assertNull(baked.lastAnnotationsId)
  }

  @Test
  fun `an unmapped preview inspects the catalog's published typography instead of nothing`() {
    // No daemon twin, so there is no semantics tree to capture — but the catalog measured
    // typography over the very PNG this id serves, and browsing an unmapped id IS that PNG.
    val (composite, baked) = host(bakedPublishedTypography = setOf(androidOnlyId))

    assertFalse(composite.hasDesignAnnotationsFor(androidOnlyId))
    assertTrue(composite.hasPublishedTypographyFor(androidOnlyId))
    val out =
      composite.renderAnnotations(androidOnlyId, PreviewOverrides()) as AnnotationsOutcome.Ok
    assertEquals(androidOnlyId, baked.lastAnnotationsId)
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$androidOnlyId\""))
    assertTrue(resolved.isEmpty(), "an unmapped id must not resolve a daemon")
    // Accessibility has no baked half at all, so it stays unavailable rather than answering empty.
    assertFalse(composite.hasA11yOverlayFor(androidOnlyId))
    assertEquals(A11yOutcome.NotFound, composite.renderA11y(androidOnlyId, PreviewOverrides()))
  }

  @Test
  fun `published annotations are withheld from a request whose pixels they do not describe`() {
    // A font scale re-renders on the daemon (or, for an unmapped id, is reported as dropped) — but
    // either way the published bounds were measured at the baked scale. Drawing them over those
    // pixels would misplace every box while looking entirely deliberate.
    val (composite, baked) = host(bakedPublishedTypography = setOf(androidOnlyId))

    assertEquals(
      AnnotationsOutcome.NotFound,
      composite.renderAnnotations(androidOnlyId, PreviewOverrides(fontScale = 1.5f)),
    )
    assertNull(baked.lastAnnotationsId)
  }

  @Test
  fun `a mapped preview whose daemon has no semantics lane falls back to published typography`() {
    val live =
      RecordingHost(
        listOf(ServePreview(daemonId, daemonId)),
        "live",
        streaming = true,
        annotationsNotFound = true,
      )
    val (composite, baked) = host(live, bakedPublishedTypography = setOf(catalogId))

    val out = composite.renderAnnotations(catalogId, PreviewOverrides()) as AnnotationsOutcome.Ok
    assertEquals(daemonId, live.lastAnnotationsId, "the daemon is asked first")
    assertEquals(catalogId, baked.lastAnnotationsId, "…then the catalog's published layer")
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$catalogId\""))
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
