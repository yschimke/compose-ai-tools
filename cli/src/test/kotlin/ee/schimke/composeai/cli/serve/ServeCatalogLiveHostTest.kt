package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog-id bridge: [ServeCatalogLiveHost] fronts the baked catalog with an opt-in daemon
 * stream. An override-free snapshot (or one replaying only the variant's own sticky theme) is the
 * baked PNG — browsing stays instant and never wakes the daemon. A snapshot carrying a
 * pixel-changing override (a knob, font scale, device, a differing theme, …) re-renders on the
 * daemon, mapping the catalog id to its daemon-preview id; an unmapped id (an Android-only variant)
 * has no daemon twin and always replays baked. The composite reports itself as a static-snapshot
 * host ([canApplyOverrides] false) that still offers Live ([hasLiveStream] true), and exposes its
 * baked host so the trust badge + card title survive.
 */
class ServeCatalogLiveHostTest {

  /** Records the (id, overrides) of the last call and whether it was reached at all. */
  private class RecordingHost(
    override val previews: List<ServePreview>,
    private val tag: String,
    private val streaming: Boolean = false,
    /** When true, `renderSvg` reports `NotFound` (a baked catalog missing this slug's vector). */
    private val svgNotFound: Boolean = false,
    override val declaredThemes: List<ServeTheme> = emptyList(),
    override val gesturesRenderable: Boolean = false,
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
  fun `canRenderOverridesFor is true only for aliased previews`() {
    val (composite, _, _) = host()
    // A daemon-twinned catalog preview can re-render an override…
    assertTrue(composite.canRenderOverridesFor(catalogId))
    // …but an unaliased (Android-only) variant can't — it always replays baked, so its override
    // controls (App theme, knobs) must render disabled rather than enabled-but-dead.
    assertEquals(false, composite.canRenderOverridesFor(androidOnlyId))
    // The host-wide flag stays true (the session offers on-demand re-render for the mapped ids).
    assertTrue(composite.canRenderOverrides)
  }

  @Test
  fun `gesturesRenderable is forwarded from the daemon lane`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    // An Android-backed daemon lane ⇒ the composite advertises the gesture control as renderable…
    val androidLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        gesturesRenderable = true,
      )
    assertTrue(
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), androidLive, baked).gesturesRenderable
    )
    // …a desktop-backed daemon lane ⇒ the composite gates the control off.
    val desktopLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    assertEquals(
      false,
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), desktopLive, baked).gesturesRenderable,
    )
  }

  @Test
  fun `declared themes come from the daemon lane, not the baked browse surface`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        declaredThemes = listOf(ServeTheme("Brand Dark", "com.example.BrandDarkTheme", "Brand")),
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    // Forwarded from the daemon lane (which read them from the live bundle's previews.json); the
    // baked browse surface carries none. canRenderOverrides is true, so the selector is live.
    assertEquals(listOf("Brand Dark"), composite.declaredThemes.map { it.name })
    assertEquals("com.example.BrandDarkTheme", composite.declaredThemes.single().providerFqn)
    assertTrue(composite.canRenderOverrides)
  }

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
  fun `grafts the daemon's detected-feature flags onto the mapped baked preview`() {
    // The baked catalog images carry no detected-feature flags; the daemon twin (from
    // previews.json)
    // does. Without grafting, a mapped @FocusedPreview catalog component would never show the
    // Keyboard focus control even though the daemon could render focus=0.
    val baked =
      RecordingHost(
        previews =
          listOf(ServePreview(catalogId, catalogId), ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId, supportsFocus = true)),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    assertTrue(composite.previews.first { it.id == catalogId }.supportsFocus)
    // An unmapped variant has no daemon twin, so it stays feature-flagless.
    assertEquals(false, composite.previews.first { it.id == androidOnlyId }.supportsFocus)
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
  fun `a plain SVG export falls back to the daemon when the baked vector is absent`() {
    // The SVG row is advertised because a lane can export, but this mapped preview has no baked
    // figma/<slug>.svg — the baked lane 404s. Rather than 404 the advertised link, a plain
    // (no-knob)
    // SVG export falls back to the daemon (an explicit action, so waking it is fine).
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(catalogId, catalogId)),
        tag = "baked",
        svgNotFound = true,
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    val out = composite.renderSvg(catalogId, PreviewOverrides()) as SvgOutcome.Ok
    assertEquals("live-svg:$daemonId", out.svg.decodeToString())
    assertEquals(daemonId, live.lastSvgId)
  }

  @Test
  fun `a plain SVG export of an unmapped id with no baked vector stays NotFound`() {
    // No daemon twin → nothing to fall back to; surface the baked NotFound rather than inventing
    // one.
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
        svgNotFound = true,
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    assertEquals(SvgOutcome.NotFound, composite.renderSvg(androidOnlyId, PreviewOverrides()))
    assertNull(live.lastSvgId)
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
  fun `a variant-matching uiMode (sticky-theme replay) stays baked`() {
    // The viewer replays its sticky theme into the snapshot URL. catalogId is the `…__dark`
    // variant, so a uiMode=dark override is a no-op the baked PNG already encodes — it must NOT
    // cold-start the daemon.
    val (composite, live, baked) = host()
    val out =
      composite.render(catalogId, PreviewOverrides(uiMode = UiMode.DARK)) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", out.png.decodeToString())
    assertEquals(catalogId, baked.lastRenderId)
    assertNull(live.lastRenderId)
  }

  @Test
  fun `a display-axis override on a mapped id routes to the daemon`() {
    // A font scale (like device / locale / orientation) can't be replayed from the baked sticker,
    // so it must re-render on the daemon — the correctness fix for standalone
    // `/render?fontScale=…`.
    val (composite, live, baked) = host()
    val out = composite.render(catalogId, PreviewOverrides(fontScale = 1.5f)) as RenderOutcome.Ok
    assertEquals("live:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, live.lastRenderId)
    assertEquals(1.5f, live.lastRenderOverrides?.fontScale)
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `a uiMode differing from the baked variant routes to the daemon`() {
    // catalogId is the `…__dark` variant; asking for light is a real re-render, not a no-op.
    val (composite, live, baked) = host()
    val out =
      composite.render(catalogId, PreviewOverrides(uiMode = UiMode.LIGHT)) as RenderOutcome.Ok
    assertEquals("live:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, live.lastRenderId)
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `baked theme is the last theme segment, not a stray earlier one`() {
    // A `dark` STATE segment sits before the real `light` theme segment. Detection must take the
    // last theme segment (matching wasmAppSrc / cardTheme), so this variant reads as LIGHT: a
    // uiMode=light is the no-op (baked) and uiMode=dark is the real re-render (daemon). A naive
    // "dark in segments" check would flip both.
    val trickyId = "toggle__dark__default__light"
    val daemon = "ToggleLight"
    val baked = RecordingHost(previews = listOf(ServePreview(trickyId, trickyId)), tag = "baked")
    val live =
      RecordingHost(previews = listOf(ServePreview(daemon, daemon)), tag = "live", streaming = true)
    val composite = ServeCatalogLiveHost(mapOf(trickyId to daemon), live, baked)

    composite.render(trickyId, PreviewOverrides(uiMode = UiMode.LIGHT))
    assertEquals(trickyId, baked.lastRenderId) // light == variant theme → baked
    assertNull(live.lastRenderId)

    composite.render(trickyId, PreviewOverrides(uiMode = UiMode.DARK))
    assertEquals(daemon, live.lastRenderId) // dark != variant theme → daemon
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
