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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    private val renderDelayMillis: Long = 0,
    override val declaredThemes: List<ServeTheme> = emptyList(),
    override val gesturesRenderable: Boolean = false,
    override val hasA11yOverlay: Boolean = false,
    /** Whether this host can project the typography / theme layers off a semantics tree. */
    override val hasDesignAnnotations: Boolean = false,
    /** Published typography this host can replay over its baked frame, keyed by preview id. */
    private val publishedTypography: Set<String> = emptySet(),
    /**
     * Ids this host lists but has no pixels for (a catalog's deferred previews) — `render` reports
     * `NotFound` for them, exactly as the real baked host does.
     */
    override val liveOnlyPreviewIds: Set<String> = emptySet(),
    /** Published captures this host can serve, keyed `<motionId><extension>`. */
    private val motion: Map<String, ByteArray> = emptyMap(),
    /** An open circuit breaker's reason, as [ServeRenderHost] reports one after a linkage fault. */
    private val breakerReason: String? = null,
    /**
     * False models a **catalog** baked host whose published PNGs are fetched from the delivery
     * branch on first use: `bakedRender` answers only from local pixels (it must never trigger the
     * fetch), while `render` fetches and serves. The distinction is what turned an open breaker
     * into a catalog-wide 409 in #4220.
     */
    private val bakedPixelsLocal: Boolean = true,
  ) : ServeHost {

    override fun renderBreaker(): RenderBreakerSnapshot? = breakerReason?.let {
      RenderBreakerSnapshot(open = true, fatal = true, reason = it)
    }

    override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
      breakerReason

    override fun motionRead(motionId: String, extension: String): BranchFetch =
      motion["$motionId$extension"]?.let { BranchFetch.Ok(it) } ?: BranchFetch.NotFound

    override val label: String = tag
    override val canApplyOverrides: Boolean = streaming
    var lastRenderId: String? = null
    var lastRenderOverrides: PreviewOverrides? = null
    var lastSvgId: String? = null
    var lastStreamId: String? = null
    var lastA11yId: String? = null
    var lastAnnotationsId: String? = null
    var renderCalls = 0
    val renderedIds = Collections.synchronizedList(mutableListOf<String>())
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renderCalls++
      renderedIds += previewId
      lastRenderId = previewId
      lastRenderOverrides = overrides
      if (renderDelayMillis > 0) Thread.sleep(renderDelayMillis)
      forcedRenderOutcome?.let {
        return it
      }
      if (previewId in liveOnlyPreviewIds) return RenderOutcome.NotFound
      return RenderOutcome.Ok("$tag:$previewId".encodeToByteArray())
    }

    // Local pixels, served without admission. Deliberately does NOT count as a render call, so a
    // test can assert the daemon was never reached.
    override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
      if (previewId in liveOnlyPreviewIds || !bakedPixelsLocal) null
      else RenderOutcome.Ok("$tag:$previewId".encodeToByteArray())

    override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
      lastSvgId = previewId
      lastRenderOverrides = overrides
      if (svgNotFound) return SvgOutcome.NotFound
      return SvgOutcome.Ok("$tag-svg:$previewId".encodeToByteArray())
    }

    override fun renderA11y(
      previewId: String,
      overrides: PreviewOverrides,
    ): A11yOutcome {
      lastA11yId = previewId
      lastRenderOverrides = overrides
      if (!hasA11yOverlay) return A11yOutcome.NotFound
      return A11yOutcome.Ok(
        """{"previewId":"$previewId","nodes":[],"findings":[],"touchTargets":[]}"""
          .encodeToByteArray()
      )
    }

    override fun hasPublishedTypographyFor(previewId: String): Boolean =
      previewId in publishedTypography

    override fun renderAnnotations(
      previewId: String,
      overrides: PreviewOverrides,
    ): AnnotationsOutcome {
      lastAnnotationsId = previewId
      lastRenderOverrides = overrides
      if (!hasDesignAnnotations && previewId !in publishedTypography)
        return AnnotationsOutcome.NotFound
      return AnnotationsOutcome.Ok(
        """{"previewId":"$previewId","annotations":[],"tags":{}}""".encodeToByteArray()
      )
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
  fun `a published capture is read off the baked host`() {
    // The composite merges `previews` FROM the baked host, so a catalog's captures are listed —
    // and the viewer offers a Motion chip for them — through this host. Without the delegation the
    // bytes behind that chip fell to `ServeHost.motionBytes`'s null default, so the lane opened on
    // an error for every live-fronted catalog in production while every (pinned, bundle-hosted)
    // fixture stayed green. A daemon has no notion of a recording; the branch is the only source.
    val motionId = "switch-on__ideal__default__light"
    val capture = byteArrayOf(9, 8, 7)
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(catalogId, catalogId)),
        tag = "baked",
        motion = mapOf("$motionId.apng" to capture),
      )
    val live = RecordingHost(previews = listOf(ServePreview(daemonId, daemonId)), tag = "live")
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    assertContentEquals(capture, composite.motionBytes(motionId, ".apng"))
    // The guards the baked host applies still decide — the composite forwards, it does not widen.
    assertNull(composite.motionBytes(motionId, ".gif"))
    assertNull(composite.motionBytes("never-published", ".apng"))
  }

  @Test
  fun `executable bundle download maps catalog id to daemon id`() {
    val (_, live, baked) = host()
    var requested: String? = null
    val bytes = byteArrayOf(1, 2, 3)
    val composite =
      ServeCatalogLiveHost(
        mapOf(catalogId to daemonId),
        live,
        baked,
        executableBundleAvailable = { it == daemonId },
        executableBundleProvider = {
          requested = it
          bytes
        },
      )

    assertTrue(composite.canDownloadExecutableBundle(catalogId))
    assertFalse(composite.canDownloadExecutableBundle(androidOnlyId))
    assertEquals(bytes.toList(), composite.executableBundle(catalogId)?.toList())
    assertEquals(daemonId, requested)
  }

  @Test
  fun `executable download is not advertised when the per-preview bundle is unpublished`() {
    val (_, live, baked) = host()
    var providerCalled = false
    val composite =
      ServeCatalogLiveHost(
        mapOf(catalogId to daemonId),
        live,
        baked,
        executableBundleAvailable = { false },
        executableBundleProvider = {
          providerCalled = true
          byteArrayOf(1)
        },
      )

    assertFalse(composite.canDownloadExecutableBundle(catalogId))
    assertFalse(providerCalled)
  }

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
  fun `accessibility inspection maps the catalog id and preserves live overrides`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        hasA11yOverlay = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    val overrides = knobOverride().copy(uiMode = UiMode.DARK, fontScale = 1.3f)

    assertTrue(composite.hasA11yOverlay)
    assertTrue(composite.hasA11yOverlayFor(catalogId))
    val out = composite.renderA11y(catalogId, overrides) as A11yOutcome.Ok
    assertEquals(daemonId, live.lastA11yId)
    assertEquals(overrides, live.lastRenderOverrides)
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$daemonId\""))
    // CMP represents unavailable Android-only ATF products as empty arrays. Do not add an
    // unsupported-platform note to the component-page legend.
    assertFalse(out.json.decodeToString().contains("unsupported", ignoreCase = true))
  }

  @Test
  fun `accessibility inspection stays unavailable for a catalog preview without a daemon twin`() {
    val baked =
      RecordingHost(previews = listOf(ServePreview(androidOnlyId, androidOnlyId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        hasA11yOverlay = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    assertFalse(composite.hasA11yOverlayFor(androidOnlyId))
    assertEquals(
      A11yOutcome.NotFound,
      composite.renderA11y(androidOnlyId, PreviewOverrides()),
    )
    assertNull(live.lastA11yId)
  }

  @Test
  fun `typography inspection maps the catalog id and preserves live overrides`() {
    // Issue #4254: the composite reports `canApplyOverrides = false` (browsing is baked pixels),
    // and
    // the default `hasDesignAnnotations` reads exactly that flag — so a catalog fronted by a live
    // daemon claimed it could not produce the layers its daemon produces on request, and
    // `.annotations` 404'd while the viewer still offered the Typography checkbox.
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        hasDesignAnnotations = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)
    val overrides = knobOverride().copy(uiMode = UiMode.DARK, fontScale = 1.3f)

    assertTrue(composite.hasDesignAnnotations)
    assertTrue(composite.hasDesignAnnotationsFor(catalogId))
    val out = composite.renderAnnotations(catalogId, overrides) as AnnotationsOutcome.Ok
    assertEquals(daemonId, live.lastAnnotationsId)
    // The viewer's overrides must reach the daemon: a font-scaled frame's type sizes are the whole
    // point of the layer, so inspecting the catalog's original pixels would describe the wrong
    // text.
    assertEquals(overrides, live.lastRenderOverrides)
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$daemonId\""))
    assertNull(baked.lastAnnotationsId)
  }

  @Test
  fun `typography inspection stays unavailable for a catalog preview without a daemon twin`() {
    val baked =
      RecordingHost(previews = listOf(ServePreview(androidOnlyId, androidOnlyId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        hasDesignAnnotations = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    // No daemon twin AND nothing published for it — the layer has no source at all.
    assertFalse(composite.hasDesignAnnotationsFor(androidOnlyId))
    assertFalse(composite.hasPublishedTypographyFor(androidOnlyId))
    assertEquals(
      AnnotationsOutcome.NotFound,
      composite.renderAnnotations(androidOnlyId, PreviewOverrides()),
    )
    assertNull(live.lastAnnotationsId)
  }

  @Test
  fun `a catalog preview with no daemon twin inspects the published typography instead`() {
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
        publishedTypography = setOf(androidOnlyId),
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        hasDesignAnnotations = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    // Browsing an unmapped id serves the baked PNG, and the published annotations were measured
    // over exactly that frame — so the layer is answerable without any daemon.
    assertTrue(composite.hasPublishedTypographyFor(androidOnlyId))
    val out =
      composite.renderAnnotations(androidOnlyId, PreviewOverrides()) as AnnotationsOutcome.Ok
    assertEquals(androidOnlyId, baked.lastAnnotationsId)
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$androidOnlyId\""))
    assertNull(live.lastAnnotationsId, "an unmapped id must never wake the daemon")

    // …but only while the baked pixels are what the request asks for. A font scale would be
    // reported as a dropped override and answered with the baked PNG, yet the published bounds
    // were measured at the baked scale, so drawing them would misplace every box.
    baked.lastAnnotationsId = null
    assertEquals(
      AnnotationsOutcome.NotFound,
      composite.renderAnnotations(androidOnlyId, PreviewOverrides(fontScale = 1.5f)),
    )
    assertNull(baked.lastAnnotationsId)
  }

  @Test
  fun `a mapped preview whose daemon has no semantics lane falls back to published typography`() {
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(catalogId, catalogId)),
        tag = "baked",
        publishedTypography = setOf(catalogId),
      )
    // hasDesignAnnotations = false: a daemon backend carrying no `compose/semantics` lane.
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    val out = composite.renderAnnotations(catalogId, PreviewOverrides()) as AnnotationsOutcome.Ok
    assertEquals(daemonId, live.lastAnnotationsId, "the daemon is asked first")
    assertEquals(catalogId, baked.lastAnnotationsId, "…then the catalog's published layer")
    assertTrue(out.json.decodeToString().contains("\"previewId\":\"$catalogId\""))
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
  fun `a presence heartbeat gets the daemon ready for a visitor who has only browsed baked pixels`() {
    // The point of the keepalive's warming half. Browsing a catalog is entirely baked by design, so
    // a visitor reading the grid has never woken the daemon — and their first theme click would pay
    // its cold start. A heartbeat while they read turns that into a warm render.
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
      )
    val composite =
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked, warmInBackground = true)
    // Every open tab calls this every few minutes, so it must be safe to repeat — the warm set and
    // the in-flight guard collapse the repeats into the one render below.
    repeat(5) { composite.keepLiveWarm() }

    val out =
      awaitOk(2_000) {
        (composite.renderSvg(catalogId, PreviewOverrides()) as? SvgOutcome.Ok)?.takeIf {
          it.svg.decodeToString() == "live-svg:$daemonId"
        }
      }
    assertEquals("live-svg:$daemonId", out.svg.decodeToString())
    assertEquals(daemonId, live.lastRenderId, "the warm went through the daemon")
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
        sharedDaemonRenders = false,
      )
    assertEquals(5, pooled.themeRenderBurstCapacity)
  }

  @Test
  fun `shared replica pool enables a five-wide lease and is used only by leased renders`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val primary =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId, overrides = listOf(labelKnob))),
        tag = "primary",
        declaredThemes = listOf(brandTheme),
      )
    val replica =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "replica",
        declaredThemes = listOf(brandTheme),
      )
    val pool = ServeSharedDaemonPool(primary = primary, openReplica = { replica })
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = primary,
        baked = baked,
        sharedDaemonPool = pool,
      )

    assertEquals(5, composite.themeRenderBurstCapacity)

    composite.render(catalogId, knobOverride())
    assertEquals(1, primary.renderCalls)
    assertEquals(0, replica.renderCalls)

    composite.renderLeased(catalogId, themeOverride())
    assertEquals(2, primary.renderCalls, "a sequential lease reuses the warm primary")
    assertEquals(0, replica.renderCalls, "replicas open only when leased requests overlap")
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
        sharedDaemonRenders = false,
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
  fun `catalog generation cache accumulates mixed overrides`() {
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
    val cached = composite.render(catalogId, mixed) as RenderOutcome.Ok
    assertEquals(1, live.renderCalls)
    assertEquals(RenderOutcome.Generation.CATALOG_CACHE, cached.generation)
  }

  @Test
  fun `mixed override cache survives host replacement within the catalog generation`() {
    val cache = CatalogThemeCache()
    val mixed =
      themeOverride()
        .copy(namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("First")))
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
      )
    first.render(catalogId, mixed)
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

    val cached = replacement.render(catalogId, mixed) as RenderOutcome.Ok
    assertEquals(RenderOutcome.Generation.CATALOG_CACHE, cached.generation)
    assertEquals(0, replacementLive.renderCalls)
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
  fun `idle theme optimization is enabled by default after the quiet window`() {
    val idleMillis = AtomicLong(59_999)
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = idleMillis::get,
      )

    composite.prewarm()
    Thread.sleep(100)
    assertEquals(0, live.renderCalls)

    idleMillis.set(60_000)
    assertTrue(awaitOptimization(composite).fullyOptimized)
    assertEquals(1, live.renderCalls)
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
        themeOptimizationEnabled = true,
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

  /**
   * The dirty queue's one load-bearing property: a marked entry is actually RE-RENDERED.
   *
   * A dirty render is deliberately still servable — a possibly-stale preview beats a cold one — so
   * the ordinary render path answers it straight out of [CatalogThemeCache] and never reaches a
   * daemon. That is correct for a visitor and fatal for the pass: no daemon render means no fresh
   * bytes, no `put`, and no flag cleared, so the pass re-selects the same dirty set every slice,
   * renders nothing, and converges never. Regeneration has to ask the renderer, because a render is
   * the entire question being asked.
   */
  @Test
  fun `a marked render is regenerated through the daemon, not answered from the cache`() {
    val root = java.nio.file.Files.createTempDirectory("theme-cache-regen").toFile()
    root.deleteOnExit()
    val fp = "f".repeat(64)
    val generation =
      assertNotNull(
        ThemeCacheStore(root, graceMillis = 0)
          .open(
            "m3-catalog",
            fp,
            GenerationInputs(
              system = "m3-catalog",
              fingerprint = fp,
              toolVersion = "1.14.0",
              variant = "desktop",
              renderConfig = "density=2",
            ),
          )
      )
    val cache = CatalogThemeCache(persistence = generation)
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        catalogThemeCache = cache,
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    val warmed = awaitOptimization(composite)
    assertEquals(1, warmed.cached, "the gap is filled first")
    assertEquals(0, warmed.dirty)
    val rendersWhileWarming = live.renderCalls

    // The operator's regenerate: nothing is deleted, so the entry stays warm and servable.
    assertEquals(1, cache.markPersistedDirty(), "the mark lands on the one warmed render")
    assertEquals(1, cache.snapshot().dirty)
    assertTrue(cache.snapshot().fullyOptimized, "still warm everywhere — dirty is not absent")
    assertFalse(cache.snapshot().converged, "but no longer finished")

    composite.keepLiveWarm()
    val settled = awaitOk(5_000) { composite.themeOptimizationSnapshot()?.takeIf { it.converged } }
    assertEquals(0, settled.dirty, "the queue drains")
    assertTrue(
      live.renderCalls > rendersWhileWarming,
      "and it drains by RENDERING: answering the dirty key from the cache would clear no flag, " +
        "leaving the pass to select the same set every slice forever",
    )
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
        themeOptimizationEnabled = true,
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
        sharedDaemonRenders = false,
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
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
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
      )

    first.prewarm()
    Thread.sleep(50)
    assertEquals(0, firstLive.renderCalls)
    assertEquals("paused", first.themeOptimizationSnapshot()?.state)
    // Parked at the gate, holding no lane — and therefore NOT keeping its host resident. The
    // catalog's progress is in the shared cache, not in the daemon, which is what lets the registry
    // reclaim the daemon's memory while the pass waits.
    assertFalse(first.backgroundWorkActive, "a parked pass does not pin its daemon")
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

  @Test
  fun `the no-admission fast path serves baked pixels but never a daemon render`() {
    // `bakedRender` is what lets a mostly-browsing box skip the global render-slot queue. It must
    // answer exactly the requests `render` would have replayed from baked pixels — and refuse the
    // ones that were supposed to reach a daemon, or the fast path would silently serve stale
    // pixels for a re-render.
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
        declaredThemes = listOf(brandTheme),
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    // The default page view: no overrides, so it replays published pixels without admission.
    assertTrue(composite.bakedRender(catalogId, PreviewOverrides()) != null)
    // An unaliased variant has no daemon twin at all — always baked.
    assertTrue(composite.bakedRender(androidOnlyId, PreviewOverrides()) != null)
    // A knob and an app-declared theme both need the daemon, so the fast path must decline.
    assertNull(composite.bakedRender(catalogId, knobOverride()))
    assertNull(composite.bakedRender(catalogId, themeOverride()))
    // None of that woke the daemon.
    assertEquals(0, live.renderCalls)
  }

  @Test
  fun `idle optimizer stays parked until the server has finished loading its catalogs`() {
    val backgroundWork = ServeBackgroundWork()
    backgroundWork.expectInitialCatalogLoad()
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        // An idle server by the registry's reckoning — startup draws no request traffic, which is
        // exactly how the optimizer used to end up competing with the catalogs still loading.
        serverIdleMillis = backgroundWork.idleClock { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    Thread.sleep(100)
    assertEquals(0, live.renderCalls, "background renders must not run while catalogs load")
    assertEquals("paused", composite.themeOptimizationSnapshot()?.state)

    backgroundWork.initialCatalogLoadFinished()

    assertTrue(awaitOptimization(composite).fullyOptimized)
    assertEquals(1, live.renderCalls)
  }

  @Test
  fun `a pause arriving during the quiet wait parks work until resume`() {
    val idleMillis = AtomicLong(0)
    val backgroundWork = ServeBackgroundWork()
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = idleMillis::get,
        themeOptimizationIdleMillis = 100,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    // The pass is at the quiet gate, which it waits out holding NO lane — and, since residency
    // follows the lane, not keeping its host resident either. So what says it has started is the
    // gate wait it is accruing, not an admission and not `backgroundWorkActive`.
    awaitOk(5_000) { composite.themeOptimizationSnapshot()?.takeIf { it.gateWaitMillis > 0 } }
    assertEquals(
      0,
      backgroundWork.optimizerAdmissionSnapshot().running,
      "a pass waiting for quiet must not be occupying a lane while it waits",
    )
    backgroundWork.pauseOptimizers(60_000, "deploy")
    idleMillis.set(Long.MAX_VALUE)
    Thread.sleep(150)

    assertEquals(0, live.renderCalls, "the completed quiet wait must recheck the pause")
    backgroundWork.resumeOptimizers()
    assertTrue(awaitOptimization(composite).fullyOptimized)
    assertEquals(1, live.renderCalls, "resume must restart work without a visitor heartbeat")
    composite.close()
  }

  /**
   * A pass gives its lane back on a preview boundary and re-queues for another.
   *
   * Without this, fair admission is not enough to un-starve anything. A pass on an idle box runs
   * until its catalog is fully optimized — measured on the deployed box, `reply` took a lane and
   * held it through all 354 of its entries — so a 10,120-target catalog would hold a lane for ~28
   * hours and the queue behind it would be fair and immovable at the same time.
   */
  @Test
  fun `a long pass yields its lane between previews instead of holding it to the end`() {
    val backgroundWork = ServeBackgroundWork(maxConcurrentRenders = 4, maxConcurrentOptimizers = 1)
    val previews = (1..4).map { ServePreview("$daemonId-$it", "$daemonId-$it") }
    fun host(sliceMillis: Long) =
      ServeCatalogLiveHost(
        alias = previews.associate { "cat-${it.id}" to it.id },
        live =
          RecordingHost(previews = previews, tag = "live", declaredThemes = listOf(brandTheme)),
        baked =
          RecordingHost(previews.map { ServePreview("cat-${it.id}", "cat-${it.id}") }, "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        optimizerSliceMillis = sliceMillis,
        backgroundWork = backgroundWork,
      )

    // A slice that is already spent hands the lane back after every preview, so the four previews
    // cost four admissions rather than one.
    val sliced = host(sliceMillis = 0)
    sliced.prewarm()
    val slicedSnapshot = awaitOptimization(sliced)

    assertTrue(slicedSnapshot.fullyOptimized, "yielding the lane must not lose the work")
    assertEquals(4, slicedSnapshot.cached)
    assertEquals(
      4,
      backgroundWork.optimizerAdmissionSnapshot().admissions,
      "one admission per preview — the lane goes back to the queue between them",
    )

    // The control: a slice longer than the pass never fires, and the whole catalog is one
    // admission. Without it, "4 admissions" would also be satisfied by a pass that re-queues for
    // reasons having nothing to do with the slice.
    val whole = ServeBackgroundWork(maxConcurrentRenders = 4, maxConcurrentOptimizers = 1)
    val unsliced =
      ServeCatalogLiveHost(
        alias = previews.associate { "cat-${it.id}" to it.id },
        live =
          RecordingHost(previews = previews, tag = "live", declaredThemes = listOf(brandTheme)),
        baked =
          RecordingHost(previews.map { ServePreview("cat-${it.id}", "cat-${it.id}") }, "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        optimizerSliceMillis = 10 * 60_000,
        backgroundWork = whole,
      )
    unsliced.prewarm()
    assertTrue(awaitOptimization(unsliced).fullyOptimized)
    assertEquals(1, whole.optimizerAdmissionSnapshot().admissions)
  }

  /**
   * A slice yields at least one preview however short it is.
   *
   * The failure this closes is a livelock: check the deadline before doing any work and a slice
   * shorter than the admission round-trip re-queues forever, rendering nothing while looking busy.
   */
  @Test
  fun `an already-spent slice still renders one preview before giving the lane back`() {
    val backgroundWork = ServeBackgroundWork(maxConcurrentRenders = 4, maxConcurrentOptimizers = 1)
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        optimizerSliceMillis = 0,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()

    assertTrue(awaitOptimization(composite).fullyOptimized)
    assertEquals(1, live.renderCalls)
    assertEquals(1, backgroundWork.optimizerAdmissionSnapshot().admissions)
  }

  @Test
  fun `an optimizer refused at startup retries without a visitor heartbeat`() {
    val backgroundWork = ServeBackgroundWork(maxConcurrentRenders = 1, maxConcurrentOptimizers = 1)
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread {
      backgroundWork.withOptimizerSlot("holder", 0) {
        held.countDown()
        release.await()
      }
    }
    holder.start()
    assertTrue(held.await(5, TimeUnit.SECONDS))
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 0,
        optimizerAdmissionWaitMillis = 5,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    awaitOk(5_000) { backgroundWork.optimizerAdmissionSnapshot().takeIf { it.refusals > 0 } }
    release.countDown()

    assertTrue(awaitOptimization(composite).fullyOptimized)
    holder.join(5_000)
  }

  @Test
  fun `optimizer slices rotate past an evicted prefix`() {
    val previews = (1..4).map { ServePreview("$daemonId-$it", "$daemonId-$it") }
    val live = RecordingHost(previews = previews, tag = "live", declaredThemes = listOf(brandTheme))
    // Only roughly one tiny test render fits. Once preview 2 evicts preview 1, restarting each
    // slice at the first miss would ping-pong between them forever and never attempt 3 or 4.
    val cache = CatalogThemeCache(maxBytes = 40)
    val composite =
      ServeCatalogLiveHost(
        alias = previews.associate { "cat-${it.id}" to it.id },
        live = live,
        baked =
          RecordingHost(previews.map { ServePreview("cat-${it.id}", "cat-${it.id}") }, "baked"),
        catalogThemeCache = cache,
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 0,
        optimizerSliceMillis = 0,
      )

    composite.prewarm()
    awaitOk(5_000) {
      synchronized(live.renderedIds) {
        live.renderedIds.toSet().takeIf { seen -> previews.all { it.id in seen } }
      }
    }

    composite.close()
  }

  /**
   * The shared lane bounds the optimizers — it no longer serialises them.
   *
   * This asserted a peak of exactly 1 while the background cap was 1. That cap was measured on the
   * deployed server as the prefetcher's dominant bottleneck: 15 catalogs queueing on one permit,
   * 74.3% of the pass's active time spent waiting for it against 6.3% rendering. The invariant
   * worth keeping is the ceiling, not the serialisation, so the cap is now passed explicitly and
   * the assertion follows it.
   */
  @Test
  fun `catalog optimizers sharing one server never exceed the background lane`() {
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val cap = 2
    val backgroundWork = ServeBackgroundWork(maxConcurrentRenders = cap)
    fun host(tag: String): ServeCatalogLiveHost {
      val delegate =
        RecordingHost(
          previews = listOf(ServePreview(daemonId, daemonId)),
          tag = tag,
          declaredThemes = listOf(brandTheme, ServeTheme("Brand Light", "com.example.BrandLight")),
        )
      val counting =
        object : ServeHost by delegate {
          override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
            peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
              Thread.sleep(25)
              return delegate.render(previewId, overrides)
            } finally {
              inFlight.decrementAndGet()
            }
          }
        }
      return ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = counting,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked-$tag"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        backgroundWork = backgroundWork,
      )
    }

    val hosts = listOf(host("a"), host("b"), host("c"))
    hosts.forEach { it.prewarm() }
    hosts.forEach { assertTrue(awaitOptimization(it).fullyOptimized) }

    assertTrue(
      peak.get() in 1..cap,
      "the background lane holds at most $cap renders server-wide, saw ${peak.get()}",
    )
  }

  /**
   * The optimization pass used to run exactly once, from `prewarm` at catalog open. Anything it
   * could not fill on that single attempt stayed unfilled for the life of the catalog generation —
   * `startThemeOptimization` clears `optimizationStarted` in its `finally`, but nothing ever called
   * it again.
   *
   * meshcore-mobile sat at `paused 288/372, failed: 0` across two server lifetimes because of it,
   * stopping at the same 288 both times while the other fourteen catalogs on the box reached
   * `complete`. The presence heartbeat is the natural re-entry point: it already fires while a
   * visitor is on the catalog's pages, and the pass holds its own idle gate, so the work it
   * schedules waits for quiet rather than competing with them.
   */
  @Test
  fun `a presence heartbeat re-enters an optimization pass that ended unfinished`() {
    val delegate =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    // The plain warm succeeds and only the THEMED render is Busy — the shape observed on the live
    // server, where these previews' baked pixels and daemon warm were both fine.
    val busyOnThemes =
      object : ServeHost by delegate {
        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          if (overrides.themeProvider != null) RenderOutcome.Busy
          else delegate.render(previewId, overrides)
      }
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = busyOnThemes,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    awaitPassIdle(composite)
    // The pass finished without filling its one target, and said nothing about why.
    assertEquals(1, composite.themeOptimizationSnapshot()?.remaining)
    assertEquals(0, composite.themeOptimizationSnapshot()?.failed)

    // Heartbeats re-enter it. Each pass records one Busy against the key; at BUSY_LATCH it latches.
    repeat(CatalogThemeCache.BUSY_LATCH + 2) {
      composite.keepLiveWarm()
      awaitPassIdle(composite)
    }

    val snapshot = composite.themeOptimizationSnapshot()
    assertEquals(1, snapshot?.failed, "the stuck target is now counted, not silently abandoned")
    assertEquals("degraded", snapshot?.state, "and no longer reads as ordinary throttling")
    // ...and the request lane can answer terminally (409) instead of a retry-after it can't honour.
    assertNotNull(composite.renderFailureLatch(catalogId, themeOverride()))
  }

  @Test
  fun `a broken live lane stops theme optimization, live advertising, and busy retries`() {
    // Issue #3448: the m3-catalog daemon hit an UnsatisfiedLinkError and theme pre-optimization
    // kept feeding it — 4740 remaining, a ~7h ETA, 275s of gate wait, all on work where every
    // single render fails — while the catalog went on reporting `live: true, degradation: null`
    // and visitors got `503 render busy; retry shortly`.
    val renderRoot = java.nio.file.Files.createTempDirectory("breaker").toFile()
    renderRoot.deleteOnExit()
    lateinit var session: FakeRenderSession
    session =
      FakeRenderSession(
        renderRoot,
        renderHook = { _, _ ->
          session.emitFailed(daemonId, "UnsatisfiedLinkError: 'long PathBuilder_nMakeFromPath'")
        },
      )
    val live =
      ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(daemonId, daemonId)),
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked =
          RecordingHost(
            listOf(ServePreview(catalogId, catalogId), ServePreview(androidOnlyId, androidOnlyId)),
            "baked",
          ),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    awaitPassIdle(composite)
    assertEquals(1, session.renderCount.get(), "the failing daemon is asked once")

    // Heartbeats re-enter the pass; it must stand down rather than queue more doomed renders.
    repeat(10) {
      composite.keepLiveWarm()
      awaitPassIdle(composite)
    }
    assertEquals(1, session.renderCount.get(), "background work must not feed a broken renderer")

    // The catalog stops claiming a healthy live lane and says why.
    assertFalse(composite.hasLiveStream, "a catalog with no working render lane is not live")
    val degradation =
      composite.degradations.single { it.code == ServeDegradation.RENDER_LANE_BROKEN }
    assertTrue(degradation.detail.contains("UnsatisfiedLinkError"), degradation.detail)
    assertTrue(assertNotNull(composite.renderBreaker()).fatal)

    // A request for a daemon-twinned preview is answered terminally with the real reason, so the
    // HTTP layer 409s instead of sending the browser back round the retry-after loop…
    val latched = assertNotNull(composite.renderFailureLatch(catalogId, themeOverride()))
    assertTrue(latched.contains("UnsatisfiedLinkError"), latched)
    // …while a preview that never needed the daemon keeps serving its baked pixels.
    assertNull(composite.renderFailureLatch(androidOnlyId, PreviewOverrides()))
    live.close()
  }

  @Test
  fun `a heartbeat does not restart a pass that already filled everything`() {
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { Long.MAX_VALUE },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
      )

    composite.prewarm()
    assertTrue(awaitOptimization(composite).fullyOptimized)
    val rendersAfterPass = live.renderCalls

    repeat(5) { composite.keepLiveWarm() }
    Thread.sleep(100)

    assertEquals(rendersAfterPass, live.renderCalls, "a complete cache is not re-rendered")
  }

  /** Wait for the background pass to go quiet, finished or not (unlike [awaitOptimization]). */
  /**
   * A pass still waiting at the idle gate must report the time it is spending there.
   *
   * The wait used to be charged by the caller, once [awaitQuiet] returned — so a gate that had not
   * opened yet contributed nothing, and `gateWaitMillis: 0` meant both "sailed straight through"
   * and "has been stuck here for three hours". The deployed server published exactly that: 23
   * catalogs, `turnsGranted 0`, and a wait counter reading zero on every one of them.
   */
  @Test
  fun `time spent at a gate that has not opened is reported while it is still shut`() {
    val backgroundWork = ServeBackgroundWork()
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        // Null is how the registry spells "a session holds a lease": permanently busy, so this
        // gate never opens and the pass never gets a turn.
        serverIdleMillis = { null },
        themeOptimizationIdleMillis = 100,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    val waited =
      awaitOk(5_000) { composite.themeOptimizationSnapshot()?.takeIf { it.gateWaitMillis > 0 } }

    assertEquals(0, waited.turnsGranted, "the gate never opened, so no turn was ever granted")
    assertEquals(0, live.renderCalls, "and nothing was rendered")
    assertTrue(
      waited.waitingMillis >= waited.gateWaitMillis,
      "the total must not contradict the part it is made of",
    )
    composite.close()
  }

  /**
   * A gated pass must not hold a lane while it waits — the failure that took the deployed server's
   * theme cache to a standstill.
   *
   * Two catalogs won the two lanes at startup, blocked in the quiet wait, and never came out:
   * `idleMillis()` answers *busy* outright while any session holds a lease, so nothing they were
   * waiting for could ever arrive. Every other catalog was refused every 20s — 8,052 refusals, 43
   * cumulative hours at the door — and not one of the 23 was ever granted a turn.
   */
  @Test
  fun `a pass waiting on a gate that never opens leaves both lanes free for everyone else`() {
    val backgroundWork = ServeBackgroundWork(maxConcurrentOptimizers = 1)
    val previews = listOf(ServePreview(daemonId, daemonId))
    fun host(idle: () -> Long?) =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live =
          RecordingHost(previews = previews, tag = "live", declaredThemes = listOf(brandTheme)),
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = idle,
        themeOptimizationIdleMillis = 100,
        // The ceiling is the subject of its own test; off here so this one measures the lane.
        optimizerGateCeilingMillis = 0,
        backgroundWork = backgroundWork,
      )

    // Null is the registry's "a session holds an open lease": this catalog can never be granted a
    // turn, however long it waits.
    val blocked = host { null }
    blocked.prewarm()
    // The accruing gate wait, not `backgroundWorkActive`: a pass that holds no lane holds no
    // residency either, so the flag stays false for exactly as long as this test cares about.
    awaitOk(5_000) { blocked.themeOptimizationSnapshot()?.takeIf { it.gateWaitMillis > 0 } }

    repeat(5) {
      assertEquals(
        0,
        backgroundWork.optimizerAdmissionSnapshot().running,
        "a pass that cannot be granted a turn must wait at the gate, not on the only lane",
      )
      Thread.sleep(50)
    }

    // ...and the single lane is still there for a catalog whose box IS quiet.
    val quiet = host { Long.MAX_VALUE }
    quiet.prewarm()
    assertTrue(awaitOptimization(quiet).fullyOptimized, "the free lane must be usable")

    blocked.close()
    quiet.close()
  }

  /**
   * The ceiling: a gate that can shut permanently is indistinguishable from the feature being off.
   *
   * `idleMillis()` returning null is not "very busy", it is "unanswerable" — no quiet window ever
   * satisfies it — so without a ceiling one leaked session lease switches theme optimization off
   * for the life of the process, silently. The forced turn is deliberately one preview wide, which
   * this asserts by leaving the box permanently busy and still expecting the work to finish.
   */
  @Test
  fun `a gate that stays shut past the ceiling still grants a turn`() {
    val backgroundWork = ServeBackgroundWork()
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { null },
        themeOptimizationIdleMillis = 100,
        optimizerGateCeilingMillis = 50,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    val done = awaitOptimization(composite)

    assertTrue(done.fullyOptimized, "the ceiling must make progress a shut gate never would")
    assertTrue(done.turnsForced > 0, "and say so: the box never granted this turn, the ceiling did")
    assertTrue(
      done.turnsForced <= done.turnsGranted,
      "forced turns are a slice of the granted total, not a separate tally",
    )
    composite.close()
  }

  /**
   * The one refusal the ceiling must respect.
   *
   * Catalog loading reads as busy on purpose: a daemon start starved by background renders is
   * recorded as `livebundle-unavailable` and degrades that catalog to baked PNGs for the whole
   * process. A cold theme cache is recoverable; that is not — so the ceiling waits for loading in a
   * way it deliberately does not wait for traffic.
   */
  @Test
  fun `the ceiling does not override the catalog-loading gate`() {
    val backgroundWork = ServeBackgroundWork()
    backgroundWork.expectInitialCatalogLoad()
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        // Quiet as far as requests go — only the load is holding the gate.
        serverIdleMillis = backgroundWork.idleClock { Long.MAX_VALUE },
        themeOptimizationIdleMillis = 100,
        optimizerGateCeilingMillis = 50,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    // Comfortably past the ceiling, which would have forced a turn were traffic the only reason.
    Thread.sleep(400)
    assertEquals(0, live.renderCalls, "no render may start while catalogs are still loading")
    assertEquals(0, composite.themeOptimizationSnapshot()?.turnsForced)

    backgroundWork.initialCatalogLoadFinished()
    assertTrue(awaitOptimization(composite).fullyOptimized)
    composite.close()
  }

  /**
   * The residency rule this whole memory fix rests on.
   *
   * `backgroundWorkActive` is what [ServeSessionRegistry.suspendIdle] reads as "this host must stay
   * resident", and the pass worker does not end while a catalog has targets left — it loops through
   * the quiet gate forever. Setting the flag for the worker's life therefore meant "not fully
   * optimized" implied "daemon can never be released", and on the public box nine catalogs held a
   * ~1.2 GB Android daemon each with no traffic, which is the memory reading the pressure gate then
   * refused to admit work against.
   */
  @Test
  fun `optimizer residency follows the lane, not the backlog`() {
    val backgroundWork = ServeBackgroundWork(maxConcurrentOptimizers = 1)
    val idle = AtomicBoolean()
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live =
          RecordingHost(
            previews = listOf(ServePreview(daemonId, daemonId)),
            tag = "live",
            declaredThemes = listOf(brandTheme),
          ),
        baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked"),
        serverIdleMillis = { if (idle.get()) Long.MAX_VALUE else null },
        themeOptimizationEnabled = true,
        themeOptimizationIdleMillis = 0,
        optimizerGateCeilingMillis = 0,
        backgroundWork = backgroundWork,
      )

    composite.prewarm()
    // Targets left and a worker running, but parked at a gate that answers busy: nothing is held.
    awaitOk(5_000) { composite.themeOptimizationSnapshot()?.takeIf { it.gateWaitMillis > 0 } }
    assertFalse(
      composite.backgroundWorkActive,
      "a catalog with work left but no lane must not pin its daemon",
    )
    assertEquals(1, composite.themeOptimizationSnapshot()?.remaining)

    // Let it in: now it is rendering on a lane, and the host has to stay put under it.
    idle.set(true)
    assertTrue(awaitOptimization(composite).fullyOptimized)
    awaitPassIdle(composite)
    assertFalse(composite.backgroundWorkActive, "and it is released again once the slice ends")
    composite.close()
  }

  private fun awaitPassIdle(host: ServeCatalogLiveHost) {
    // The WORKER, not the residency flag. Since residency follows the lane, `backgroundWorkActive`
    // is false while a worker is parked at the gate and before it takes its first slice — so
    // reading it here returned the instant the pass was scheduled, ahead of any render.
    repeat(200) {
      if (!host.optimizationPassRunning) return
      Thread.sleep(25)
    }
    error("theme optimization pass did not settle: ${host.themeOptimizationSnapshot()}")
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
  fun `burst capacity follows the lane snapshots actually render on`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live = RecordingHost(previews = listOf(ServePreview(daemonId, daemonId)), tag = "mono")
    val perPreview = RecordingHost(previews = emptyList(), tag = "per")

    // A shared daemon without replicas still has one render lock.
    assertEquals(
      1,
      ServeCatalogLiveHost(
          alias = mapOf(catalogId to daemonId),
          live = live,
          baked = baked,
          perPreviewResolve = { perPreview },
        )
        .themeRenderBurstCapacity,
    )
    // Shared mode becomes genuinely parallel when it carries identical monolithic replicas.
    assertEquals(
      5,
      ServeCatalogLiveHost(
          alias = mapOf(catalogId to daemonId),
          live = live,
          baked = baked,
          sharedDaemonPool = ServeSharedDaemonPool(live, openReplica = { perPreview }),
        )
        .themeRenderBurstCapacity,
    )
    // Per-preview routing genuinely parallelises — one daemon per preview — so it keeps the burst.
    assertEquals(
      5,
      ServeCatalogLiveHost(
          alias = mapOf(catalogId to daemonId),
          live = live,
          baked = baked,
          perPreviewResolve = { perPreview },
          sharedDaemonRenders = false,
        )
        .themeRenderBurstCapacity,
    )
    // No pool at all was always serial.
    assertEquals(
      1,
      ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked).themeRenderBurstCapacity,
    )
  }

  @Test
  fun `snapshot renders go to the shared daemon by default`() {
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
        perPreviewResolve = { perPreview },
      )

    // A grid is a batch: one cold start on the shared daemon, then every remaining card is warm.
    // Routing each card to its own per-preview daemon made every card pay its own cold start.
    val out = composite.render(catalogId, knobOverride()) as RenderOutcome.Ok
    assertEquals("mono:$daemonId", out.png.decodeToString())
    assertEquals(daemonId, monolithic.lastRenderId)
    assertNull(perPreview.lastRenderId)
  }

  @Test
  fun `per-preview routing is still available behind its flag`() {
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
        sharedDaemonRenders = false,
      )

    // With per-preview routing selected, a knob edit resolves the per-preview daemon FIRST and the
    // monolithic one is never touched.
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
        sharedDaemonRenders = false,
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

  @Test
  fun `a daemon that carries no such id falls back to the baked snapshot`() {
    // Both lanes can miss an aliased id: the shared monolithic daemon lists only the primary
    // bundle's previews, and the per-preview daemon that would cover the rest fails to start when
    // its classpath won't resolve on this box. That is a fact about the daemons, not about the
    // pixels — the preview has a baked PNG, so an override request must degrade to the snapshot
    // rather than hand the browser a broken image.
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        forcedRenderOutcome = RenderOutcome.NotFound,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    val out = composite.render(catalogId, PreviewOverrides(fontScale = 1.5f)) as RenderOutcome.Ok

    assertEquals("baked:$catalogId", out.png.decodeToString())
    assertEquals(daemonId, live.lastRenderId, "the daemon was tried first")
  }

  @Test
  fun `a theme render on an id no daemon carries reports Busy, never baked pixels`() {
    // The grid retries a Busy card; a 200 carrying baked pixels would make it believe the requested
    // theme had loaded and it would sit on the wrong palette forever. So the NotFound fallback
    // above must stop short of the theme lane.
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        streaming = true,
        declaredThemes = listOf(ServeTheme(name = "Brand", providerFqn = "com.example.Brand")),
        forcedRenderOutcome = RenderOutcome.NotFound,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    assertEquals(
      RenderOutcome.Busy,
      composite.render(catalogId, PreviewOverrides(themeProvider = "com.example.Brand")),
    )
  }

  /**
   * The theme cache is an optimization, not a correctness requirement. A cache miss on a cold
   * daemon id used to schedule a warm and then abandon the request — returning Busy despite the
   * caller already holding a render slot — which made a server restart (empty cache) break the
   * whole grid until the hours-long background pass refilled it.
   */
  @Test
  fun `a pure theme render on a cold id waits for its warm instead of reporting busy`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        warmInBackground = true,
      )

    // Nothing has warmed this id: under the old gate this returned Busy without rendering.
    val outcome = composite.render(catalogId, themeOverride())

    assertTrue(
      outcome is RenderOutcome.Ok,
      "a cold id must still produce themed pixels, got $outcome",
    )
    assertTrue(
      live.renderCalls >= 2,
      "one warm render plus the themed one, got ${live.renderCalls}",
    )
  }

  /**
   * A knob edit cannot use the baked fallback: those pixels ignore the requested value and the HTTP
   * layer correctly turns them into a 503. The foreground request must therefore wait for the
   * bounded warm just like a theme edit, then render the requested value itself.
   */
  @Test
  fun `a knob render on a cold id waits for its warm instead of dropping the override`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        warmInBackground = true,
      )
    val overrides =
      PreviewOverrides(
        namedOverrides = mapOf("color" to PreviewOverrideValue.StringValue("outlined"))
      )

    val outcome = composite.render(catalogId, overrides)

    assertTrue(
      outcome is RenderOutcome.Ok,
      "a cold knob edit must produce live pixels, got $outcome",
    )
    assertEquals("live:$daemonId", outcome.png.decodeToString())
    assertTrue(live.renderCalls >= 2, "one warm render plus the knob render")
  }

  /** A warm that never succeeds must not hold the request for the whole budget. */
  @Test
  fun `a pure theme render gives up when the warm itself fails`() {
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        forcedRenderOutcome = RenderOutcome.Busy,
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        warmInBackground = true,
      )

    val startedAt = System.nanoTime()
    val outcome = composite.render(catalogId, themeOverride())
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

    assertEquals(
      RenderOutcome.Busy,
      outcome,
      "a theme render that cannot succeed still reports busy",
    )
    assertTrue(
      elapsedMs < ServeCatalogLiveHost.FOREGROUND_WARM_AWAIT_MILLIS,
      "it must not burn the whole warm budget on a failing warm (took ${elapsedMs}ms)",
    )
  }

  @Test
  fun `a knob render never falls back to baked pixels when its cold warm fails`() {
    val baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        forcedRenderOutcome = RenderOutcome.Busy,
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        warmInBackground = true,
      )

    val outcome =
      composite.render(
        catalogId,
        PreviewOverrides(
          namedOverrides = mapOf("color" to PreviewOverrideValue.StringValue("outlined"))
        ),
      )

    assertEquals(RenderOutcome.Busy, outcome)
    assertEquals(0, baked.renderCalls, "baked pixels cannot claim to contain a knob override")
  }

  @Test
  fun `the first override render after warming is bounded too`() {
    val baked = RecordingHost(listOf(ServePreview(catalogId, catalogId)), "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        renderDelayMillis = 30,
        declaredThemes = listOf(brandTheme),
      )
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        warmInBackground = true,
        foregroundOverrideTimeoutMillis = 5,
      )

    val outcome = composite.render(catalogId, themeOverride())

    assertEquals(RenderOutcome.Busy, outcome)
    assertEquals(0, baked.renderCalls)
    composite.close()
  }

  /**
   * The optimizer used to re-demand the whole 60s entry window before EVERY render, so a server
   * that was quiet-but-not-silent-for-a-minute advanced one entry and then stalled. On the public
   * box that measured one entry per ~105s against a sub-second render — ~99% waiting.
   *
   * Modelled here by an idle clock that reports a full minute once (letting the pass start) and
   * then a steady 10s: quiet by any reasonable measure, but under the entry window. The old gate
   * caches exactly one entry and blocks; keeping the turn caches them all.
   */
  @Test
  fun `the optimizer keeps its turn while the server stays quiet`() {
    val secondTheme = ServeTheme("Brand Light", "com.example.BrandLight")
    val baked = RecordingHost(previews = listOf(ServePreview(catalogId, catalogId)), tag = "baked")
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId)),
        tag = "live",
        declaredThemes = listOf(brandTheme, secondTheme),
      )
    val cache = CatalogThemeCache()
    val firstCall = java.util.concurrent.atomic.AtomicBoolean(true)
    val composite =
      ServeCatalogLiveHost(
        alias = mapOf(catalogId to daemonId),
        live = live,
        baked = baked,
        catalogThemeCache = cache,
        // Quiet enough to keep a turn (>= OPTIMIZER_YIELD_MILLIS), never enough to re-earn entry.
        serverIdleMillis = { if (firstCall.getAndSet(false)) 60_000L else 10_000L },
        themeOptimizationIdleMillis = 60_000L,
      )

    composite.prewarm()

    val snapshot = awaitOk(10_000) { cache.snapshot().takeIf { it.cached >= 2 } }
    assertEquals(2, snapshot.cached, "both themes cached without re-earning the entry window")
  }

  /**
   * #4220: one broken daemon must not black out the pixels it was never the source of.
   *
   * A catalog fetches its published PNGs from the delivery branch on first use, so `bakedRender`
   * (deliberately local-only) says null for a preview nobody has asked for yet — and the HTTP layer
   * consults [renderFailureLatch] the moment it does. Latching every id in the alias therefore
   * answered `409` to a plain override-free browse whose pixels `render` would have fetched
   * happily, breaking every `<img>` on every page of the catalog while the degradation banner
   * promised those very snapshots. Only requests the daemon was the answer to may be refused.
   */
  @Test
  fun `an open breaker does not latch a browse the baked lane can answer`() {
    val brokenLane =
      "render lane disabled after a non-recoverable UnsatisfiedLinkError \u2014 retrying cannot help."
    val baked =
      RecordingHost(
        previews =
          listOf(ServePreview(catalogId, catalogId), ServePreview(androidOnlyId, androidOnlyId)),
        tag = "baked",
        // The production shape: published, but not yet pulled onto this box.
        bakedPixelsLocal = false,
      )
    val live =
      RecordingHost(
        previews = listOf(ServePreview(daemonId, daemonId, overrides = listOf(labelKnob))),
        tag = "live",
        streaming = true,
        breakerReason = brokenLane,
      )
    val composite = ServeCatalogLiveHost(mapOf(catalogId to daemonId), live, baked)

    // The browse the baked PNG answers truthfully: no latch, and the pixels arrive through
    // `render`, which is the lane that fetches them.
    assertNull(composite.renderFailureLatch(catalogId, PreviewOverrides()))
    val outcome = composite.render(catalogId, PreviewOverrides()) as RenderOutcome.Ok
    assertEquals("baked:$catalogId", outcome.png.decodeToString())
    assertEquals(0, live.renderCalls, "a broken daemon must not be asked to serve a baked browse")

    // An unmapped (Android-only) variant never had a live twin, so it was never latched.
    assertNull(composite.renderFailureLatch(androidOnlyId, PreviewOverrides()))

    // A `uiMode` restating the variant's own baked theme is a no-op, so it stays baked too.
    assertNull(composite.renderFailureLatch(catalogId, PreviewOverrides(uiMode = UiMode.DARK)))
  }

  /**
   * The other half of the same rule: a request the baked pixels cannot answer still gets the
   * terminal `409` naming the linkage error, rather than a dishonest `200` of the un-overridden
   * snapshot or a `503` that invites the viewer to retry a lane that will never come back.
   */
  @Test
  fun `an open breaker still latches the renders only the daemon could serve`() {
    val brokenLane =
      "render lane disabled after a non-recoverable UnsatisfiedLinkError \u2014 retrying cannot help."
    val liveOnlyId = "chip-assist__ideal__deferred__light"
    val liveOnlyDaemonId = "AssistChip_Deferred_Light"
    val baked =
      RecordingHost(
        previews = listOf(ServePreview(catalogId, catalogId), ServePreview(liveOnlyId, liveOnlyId)),
        tag = "baked",
        liveOnlyPreviewIds = setOf(liveOnlyId),
      )
    val live =
      RecordingHost(
        previews =
          listOf(
            ServePreview(daemonId, daemonId, overrides = listOf(labelKnob)),
            ServePreview(liveOnlyDaemonId, liveOnlyDaemonId),
          ),
        tag = "live",
        streaming = true,
        breakerReason = brokenLane,
      )
    val composite =
      ServeCatalogLiveHost(
        mapOf(catalogId to daemonId, liveOnlyId to liveOnlyDaemonId),
        live,
        baked,
      )

    // A knob the baked PNG cannot represent — the daemon was the only lane, and it is broken.
    assertEquals(brokenLane, composite.renderFailureLatch(catalogId, knobOverride()))
    // A deferred (live-only) preview has no baked pixels at all, so even a bare browse is latched.
    assertEquals(brokenLane, composite.renderFailureLatch(liveOnlyId, PreviewOverrides()))
  }
}
