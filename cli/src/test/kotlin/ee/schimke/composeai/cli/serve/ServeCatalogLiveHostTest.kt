package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val forcedRenderOutcome: RenderOutcome? = null,
    override val declaredThemes: List<ServeTheme> = emptyList(),
    override val gesturesRenderable: Boolean = false,
    /**
     * Ids this host lists but has no pixels for (a catalog's deferred previews) — `render` reports
     * `NotFound` for them, exactly as the real baked host does.
     */
    override val liveOnlyPreviewIds: Set<String> = emptySet(),
  ) : ServeHost {
    override val label: String = tag
    override val canApplyOverrides: Boolean = streaming
    var lastRenderId: String? = null
    var lastRenderOverrides: PreviewOverrides? = null
    var lastSvgId: String? = null
    var lastStreamId: String? = null
    var renderCalls = 0
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renderCalls++
      lastRenderId = previewId
      lastRenderOverrides = overrides
      forcedRenderOutcome?.let {
        return it
      }
      if (previewId in liveOnlyPreviewIds) return RenderOutcome.NotFound
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

  private fun themeOverride(provider: String = "com.example.BrandDark") =
    PreviewOverrides(themeProvider = provider)

  private val brandTheme = ServeTheme("Brand Dark", "com.example.BrandDark")

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
  fun `grafts the daemon's uiMode onto the mapped baked preview`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId, uiMode = 0x20)),
        tag = "live",
        streaming = true,
      )

    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    assertEquals(0x20, composite.previews.single().uiMode)
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
  fun `a plain SVG export of a mapped id prefers the daemon's per-variant vector over the baked slug`() {
    // The baked figma/<slug>.svg is slug-keyed + light-preferred (the catalog emits one SVG per
    // component, the light variant), so a `…__dark` id would otherwise serve the LIGHT vector even
    // though its PNG + live render are dark. A daemon-twinned id must route its plain SVG to the
    // daemon — which carries the variant's uiMode/theme — NOT the baked slug SVG, which still
    // exists.
    val (composite, live, baked) = host()
    val out = composite.renderSvg(catalogId, PreviewOverrides()) as SvgOutcome.Ok
    assertEquals("live-svg:$daemonId", out.svg.decodeToString())
    assertEquals(daemonId, live.lastSvgId)
    // The baked slug SVG was NOT consulted (the daemon vector wins).
    assertNull(baked.lastSvgId)
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
  fun `warmInBackground serves the baked SVG first, then per-variant once the daemon warms`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked, warmInBackground = true)

    // Cold: the first no-override SVG serves the BAKED vector immediately — a cold daemon must
    // never
    // block the browse. The daemon's renderSvg is NOT awaited synchronously on this first call.
    val first = composite.renderSvg(catalogId, PreviewOverrides()) as SvgOutcome.Ok
    assertEquals("baked-svg:$catalogId", first.svg.decodeToString())

    // The background warm rendered the daemon (a throwaway render()), flipping it warm; once warm,
    // the per-variant daemon vector takes over for subsequent browses.
    val warmed =
      awaitOk(2_000) {
        (composite.renderSvg(catalogId, PreviewOverrides()) as? SvgOutcome.Ok)?.takeIf {
          it.svg.decodeToString() == "live-svg:$daemonId"
        }
      }
    assertEquals("live-svg:$daemonId", warmed.svg.decodeToString())
    assertEquals(daemonId, live.lastRenderId) // the warm went through the daemon's render()
  }

  @Test
  fun `prewarm warms the daemon so the first browse is already per-variant`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked, warmInBackground = true)
    composite.prewarm()

    // After prewarm settles, the very first no-override browse already gets the per-variant vector.
    val out =
      awaitOk(2_000) {
        (composite.renderSvg(catalogId, PreviewOverrides()) as? SvgOutcome.Ok)?.takeIf {
          it.svg.decodeToString() == "live-svg:$daemonId"
        }
      }
    assertEquals("live-svg:$daemonId", out.svg.decodeToString())
  }

  @Test
  fun `prewarm does not open per-preview daemons eagerly`() {
    var resolved = false
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        perPreviewResolve = {
          resolved = true
          null
        },
        warmInBackground = true,
      )

    composite.prewarm()

    assertEquals(false, resolved, "startup prewarm must not fan out into per-preview daemon JVMs")
    assertNull(live.lastRenderId, "per-preview catalogs warm lazily on demand, not at startup")
  }

  @Test
  fun `daemonPoolStats exposes per-preview pool occupancy`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live = RecordingHost(previews = listOf(ServePreview(daemonId, daemonId)), tag = "mono")
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        perPreviewPoolStats = {
          listOf(DaemonPoolSnapshot("per-preview", open = 2, maxOpen = 4, activeStreams = 1))
        },
      )

    assertEquals(
      listOf(DaemonPoolSnapshot("per-preview", open = 2, maxOpen = 4, activeStreams = 1)),
      composite.daemonPoolStats(),
    )
  }

  /** Poll [block] until it returns non-null or [timeoutMs] elapses (for the async warm). */
  private fun <T : Any> awaitOk(timeoutMs: Long, block: () -> T?): T {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
      block()?.let {
        return it
      }
      Thread.sleep(20)
    }
    error("condition not met within ${timeoutMs}ms")
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

  // --- Per-preview lane (default, with monolithic fallback) -----------------------------------

  @Test
  fun `theme redraw is parallel only when the per-preview lane is available`() {
    val (monolithicOnly, live, baked) = host()
    assertEquals(1, monolithicOnly.themeRenderBurstCapacity)

    val pooled =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        perPreviewResolve = { null },
      )
    assertEquals(5, pooled.themeRenderBurstCapacity)
  }

  @Test
  fun `pure theme render propagates busy from monolithic fallback`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        forcedRenderOutcome = RenderOutcome.Busy,
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = { null },
      )

    assertEquals(RenderOutcome.Busy, composite.render(catalogId, themeOverride()))
    assertEquals(1, monolithic.renderCalls)
    assertEquals(0, baked.renderCalls, "baked pixels must not masquerade as the requested theme")
  }

  @Test
  fun `pure theme renders stay cached across per-preview daemon reuse`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        declaredThemes = listOf(brandTheme),
      )
    val perPreview = RecordingHost(previews = emptyList(), tag = "per")
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = { perPreview },
      )

    val first = composite.render(catalogId, themeOverride()) as RenderOutcome.Ok
    val second = composite.render(catalogId, themeOverride()) as RenderOutcome.Ok

    assertEquals("per:$daemonId", first.png.decodeToString())
    assertEquals(RenderOutcome.Generation.CATALOG_CACHE, second.generation)
    assertEquals(1, perPreview.renderCalls)
    assertEquals(0, monolithic.renderCalls)
    assertEquals(0, baked.renderCalls)
  }

  @Test
  fun `catalog theme cache excludes mixed overrides`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val mixed =
      themeOverride()
        .copy(namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("First")))
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    composite.render(catalogId, mixed)
    composite.render(catalogId, mixed)
    assertEquals(2, live.renderCalls)
  }

  @Test
  fun `catalog refresh starts a fresh theme cache generation`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val oldLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "old",
        declaredThemes = listOf(brandTheme),
      )
    val oldHost = ServeCatalogLiveHost(mapOf(catalogId to daemonId), oldLive, baked)
    oldHost.render(catalogId, themeOverride())
    oldHost.render(catalogId, themeOverride())
    assertEquals(1, oldLive.renderCalls)

    val refreshedLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "new",
        declaredThemes = listOf(brandTheme),
      )
    val refreshedHost = ServeCatalogLiveHost(mapOf(catalogId to daemonId), refreshedLive, baked)
    refreshedHost.render(catalogId, themeOverride())
    assertEquals(1, refreshedLive.renderCalls)
  }

  @Test
  fun `idle optimizer fills every declared theme and reports completion`() {
    val secondTheme = ServeTheme("Brand Light", "com.example.BrandLight")
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme, secondTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    val snapshot = awaitOptimization(composite)

    assertEquals(2, live.renderCalls)
    assertEquals(2, snapshot.total)
    assertEquals(2, snapshot.cached)
    assertTrue(snapshot.fullyOptimized)
    assertEquals("complete", snapshot.state)
    assertEquals(false, composite.backgroundWorkActive)
  }

  @Test
  fun `idle optimizer waits for asynchronous cold warming without spending its retry budget`() {
    val warmStarted = CountDownLatch(1)
    val releaseWarm = CountDownLatch(1)
    val delegate =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val slowColdLive =
      object : ServeHost by delegate {
        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
          if (overrides == PreviewOverrides()) {
            warmStarted.countDown()
            check(releaseWarm.await(5, TimeUnit.SECONDS)) { "test warm was not released" }
          }
          return delegate.render(previewId, overrides)
        }
      }
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = slowColdLive,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        warmInBackground = true,
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    assertTrue(warmStarted.await(2, TimeUnit.SECONDS))
    Thread.sleep(750)
    assertTrue(composite.backgroundWorkActive)
    assertEquals(0, composite.themeOptimizationSnapshot()?.failed)
    releaseWarm.countDown()

    assertTrue(awaitOptimization(composite).fullyOptimized)
    assertEquals(2, delegate.renderCalls, "one cold warm, then one theme render")
  }

  @Test
  fun `idle optimizer renders all themes for a preview before opening the next daemon`() {
    val secondCatalogId = "switch-on__ideal__default__dark"
    val secondDaemonId = "SwitchOn_Dark"
    val secondTheme = ServeTheme("Brand Light", "com.example.BrandLight")
    val resolved = Collections.synchronizedList(mutableListOf<String>())
    val perPreview = RecordingHost(previews = emptyList(), tag = "per")
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId, secondCatalogId to secondDaemonId),
        live =
          RecordingHost(
            previews =
              listOf(
                ServePreview(daemonId, daemonId),
                ServePreview(secondDaemonId, secondDaemonId),
              ),
            tag = "mono",
            declaredThemes = listOf(brandTheme, secondTheme),
          ),
        baked =
          RecordingHost(
            previews =
              listOf(
                ServePreview(catalogId, catalogId),
                ServePreview(secondCatalogId, secondCatalogId),
              ),
            tag = "baked",
          ),
        perPreviewResolve = { id ->
          resolved += id
          perPreview
        },
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    awaitOptimization(composite)

    assertEquals(listOf(daemonId, daemonId, secondDaemonId, secondDaemonId), resolved.toList())
  }

  @Test
  fun `idle optimizer pauses for traffic and shared cache survives host replacement`() {
    val idle = AtomicBoolean()
    val cache = CatalogThemeCache()
    val firstLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "first",
        declaredThemes = listOf(brandTheme),
      )
    val first =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = firstLive,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        catalogThemeCache = cache,
        serverIdleMillis = { if (idle.get()) Long.MAX_VALUE else null },
        themeOptimizationIdleMillis = 0,
      )

    first.prewarm()
    Thread.sleep(50)
    assertEquals(0, firstLive.renderCalls)
    assertEquals("paused", first.themeOptimizationSnapshot()?.state)
    assertTrue(first.backgroundWorkActive)
    idle.set(true)
    awaitOptimization(first)
    first.close()

    val replacementLive =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "replacement",
        declaredThemes = listOf(brandTheme),
      )
    val replacement =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = replacementLive,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked2"),
        catalogThemeCache = cache,
      )
    replacement.prewarm()

    assertEquals(0, replacementLive.renderCalls)
    val cached = replacement.render(catalogId, themeOverride()) as RenderOutcome.Ok
    assertEquals(RenderOutcome.Generation.CATALOG_CACHE, cached.generation)
  }

  private fun awaitOptimization(host: ServeCatalogLiveHost): ThemeOptimizationSnapshot {
    repeat(100) {
      host
        .themeOptimizationSnapshot()
        ?.takeIf { it.fullyOptimized }
        ?.let {
          return it
        }
      Thread.sleep(25)
    }
    error("theme optimization did not finish: ${host.themeOptimizationSnapshot()}")
  }

  @Test
  fun `an override render prefers the per-preview daemon over the monolithic one`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId, overrides = listOf(labelKnob))),
        tag = "mono",
        streaming = true,
      )
    val perPreview = RecordingHost(previews = emptyList(), tag = "per")
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = { id -> if (id == daemonId) perPreview else null },
      )

    // A knob edit resolves the per-preview daemon FIRST — the monolithic one is never touched, so
    // the small per-preview bundle is the routinely-exercised default lane.
    val out = composite.render(catalogId, knobOverride()) as RenderOutcome.Ok
    assertEquals("per:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, perPreview.lastRenderId)
    assertNull(monolithic.lastRenderId)
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `an override render falls back to the monolithic daemon when no per-preview daemon resolves`() {
    val (_, monolithic, baked) = host()
    // The per-preview resolver always fails (no per-preview bundle / materialise failed). The
    // composite must fall back to the monolithic liveBundle daemon, never baked, so a knob edit
    // still re-renders — worst case is exactly the pre-per-preview behaviour.
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = { null },
      )

    val out = composite.render(catalogId, knobOverride()) as RenderOutcome.Ok
    assertEquals("live:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, monolithic.lastRenderId)
    assertNull(baked.lastRenderId)
  }

  @Test
  fun `a plain snapshot never resolves a per-preview daemon`() {
    var resolved = false
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = {
          resolved = true
          null
        },
      )
    // Ordinary browsing stays baked and must not even ask the pool to spin up a per-preview daemon.
    val out = composite.render(catalogId, PreviewOverrides()) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", out.png.decodeToString())
    assertEquals(false, resolved)
    assertNull(monolithic.lastRenderId)
  }

  @Test
  fun `a live stream prefers the per-preview daemon over the monolithic one`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        streaming = true,
      )
    val perPreview = RecordingHost(previews = emptyList(), tag = "per", streaming = true)
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic,
        baked = baked,
        perPreviewResolve = { perPreview },
      )
    val handle = composite.subscribeStream(catalogId, PreviewOverrides(), null, null) {}
    assertTrue(handle != null)
    assertEquals(daemonId, perPreview.lastStreamId)
    assertNull(monolithic.lastStreamId)
  }

  @Test
  fun `activeStreamCount sums the monolithic and per-preview lanes`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val monolithic =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "mono",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = monolithic, // reports 1 active stream
        baked = baked,
        perPreviewStreamCount = { 3 },
      )
    assertEquals(4, composite.activeStreamCount())
  }

  @Test
  fun `a live-only (deferred) preview renders through the daemon even with no override`() {
    // A deferred preview has NO baked PNG — the baked host lists it (so it has a card, a route and
    // its place in the grid) but every render must reach the daemon, including the plain
    // override-free browse that keeps ordinary catalog previews on baked pixels.
    val deferredId = "button-filled__ideal__default__light"
    val deferredDaemonId = "FilledButton_Light"
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(catalogId, catalogId), ServePreview(deferredId, deferredId)),
        tag = "baked",
        // The baked host has no pixels for the live-only id; a fallback would 404 the card.
        liveOnlyPreviewIds = setOf(deferredId),
      )
    val live =
      RecordingHost(
        previews =
          listOf(
            ServePreview(daemonId, daemonId),
            ServePreview(deferredDaemonId, deferredDaemonId),
          ),
        tag = "live",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(
        mapOf(catalogId to daemonId, deferredId to deferredDaemonId),
        live,
        baked,
      )

    // The composite advertises which ids are live-only, so the viewer can badge the card.
    assertEquals(setOf(deferredId), composite.liveOnlyPreviewIds)
    val out = composite.render(deferredId, PreviewOverrides()) as RenderOutcome.Ok
    assertEquals("live:$deferredDaemonId", out.png.decodeToString())
    assertEquals(deferredDaemonId, live.lastRenderId)
    // …while an ordinary catalog preview still browses baked (no daemon wake).
    live.lastRenderId = null
    val baked1 = composite.render(catalogId, PreviewOverrides()) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", baked1.png.decodeToString())
    assertNull(live.lastRenderId)
  }
}
