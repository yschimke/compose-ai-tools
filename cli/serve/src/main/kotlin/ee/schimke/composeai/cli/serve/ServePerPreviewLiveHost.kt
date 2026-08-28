package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * A [ServeHost] that fronts a trusted design-system catalog's baked PNGs with an opt-in live daemon
 * that re-renders each preview from its **own per-preview bundle**
 * (`bundle/previews/<daemon-id>.png` on the `design-artifacts/<system>` branch), rather than from
 * one monolithic catalog `liveBundle` ([ServeCatalogLiveHost]).
 *
 * ## Browsing is baked
 *
 * Exactly like [ServeCatalogLiveHost]: [previews], the grid, deep links, thumbnails, title, and
 * trust badge all resolve to [baked], and an override-free `/render` (or one replaying only the
 * variant's own sticky theme) replays the baked PNG — so browsing is instant and never wakes a
 * daemon. Only an override the baked PNG can't satisfy — a `?knob.<key>=…` edit, a font scale, a
 * differing theme, … ([CatalogLiveRouting.overridesAffectRender]) — routes to [resolveLive].
 *
 * ## Per-preview live lane
 *
 * [resolveLive] maps a daemon-preview id to a daemon-backed host that serves **exactly that one
 * preview**, materialised from that preview's own bundle and pooled (with idle eviction) by the
 * caller — this host never owns or closes those daemons. `null` ⇒ no per-preview daemon is
 * available (fetch/materialise failed, or the preview ships no live bundle), so the request falls
 * back to the baked PNG. Because the resolved daemon serves a single id, [render] / [renderSvg] /
 * [subscribeStream] all call it with the mapped **daemon** id, not the catalog id.
 *
 * ## Why per-preview
 *
 * Each per-preview bundle carries only its preview's closure over a **maven-coordinate** classpath
 * (re-resolved at materialise time), so the delivery branch ships small addressable re-renderable
 * stickers and the server holds daemons only for the previews actually being edited — the pool
 * reaps idle ones. Contrast [ServeCatalogLiveHost], which launches one daemon carrying the whole
 * catalog.
 */
class ServePerPreviewLiveHost(
  /**
   * Catalog id (`button-elevated__ideal__default__light`) → daemon preview id
   * (`…ElevatedButtonSticker_Light`). An unmapped id (an Android-only variant) has no per-preview
   * live lane and always replays baked.
   */
  private val alias: Map<String, String>,
  /** The static baked-PNG host — the browse + snapshot surface, keyed by catalog ids. */
  private val baked: ServeHost,
  /**
   * Resolve a daemon-backed host that re-renders the given **daemon-preview id** from its own
   * per-preview bundle, or null when none is available. Called only for an alias-mapped id carrying
   * a pixel-changing override; the returned host is owned + pooled by the caller (this host never
   * closes it), so repeated calls for the same id should return the pooled instance.
   */
  private val resolveLive: (daemonId: String) -> ServeHost?,
  /**
   * The whole servable preview set — the baked catalog's previews with the author-declared knobs +
   * detected-feature flags already grafted on (read from the per-preview bundles' `overrides.json`
   * sidecars), so the viewer offers the editable controls. The caller assembles this; the host does
   * not read bundles itself.
   */
  override val previews: List<ServePreview>,
  /**
   * App-declared `@ThemeCatalog` themes (module-global); forwarded to the viewer's Theme selector.
   */
  override val declaredThemes: List<ServeTheme> = emptyList(),
  /**
   * Whether the per-preview daemons honour the one-handed gesture override (Android-backed only).
   */
  override val gesturesRenderable: Boolean = false,
  /**
   * SVG is exportable when either lane can: the baked catalog carries `figma/<slug>.svg` vectors
   * and a per-preview daemon exports a `compose/figma-svg`. Defaults to the baked host's
   * capability.
   */
  override val hasSvgExport: Boolean = baked.hasSvgExport,
  /** Live upstream stream count across the pooled per-preview daemons (supplied by the pool). */
  private val streamCount: () -> Int = { 0 },
) : ServeHost {

  /**
   * The underlying baked catalog host, so the HTTP layer's `catalogBundleHost()` can recover its
   * title / subtitle / trust verdict (which only a [ServeBundleHost] carries) even though the
   * session is fronted by this composite — otherwise `/api/previews`, the viewer badge, and the
   * home card would lose the trust badge + card title. Mirrors [ServeCatalogLiveHost.bakedHost].
   */
  internal val bakedHost: ServeHost = baked

  override val label: String = baked.label

  // The published pixels' size, which is what an unfurl card advertises — the live lane never
  // changes it, and asking the baked host costs a PNG header read.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? =
    baked.bakedRenderSize(previewId)

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    baked.designReferencesFor(previewId)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    baked.designReferenceRaster(referenceId)

  override fun designPages(): ServeDesignPageStore = baked.designPages()

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    baked.annotationsForPreview(previewId)

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    baked.annotationsForReference(referenceId)

  override fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    baked.tagIndexForPreview(previewId)

  override fun parityActivity(): ParityActivity? = baked.parityActivity()

  override fun parityIssues(): ParityIssues? = baked.parityIssues()

  // The known differences ride the baked staging dir, like the tag index and the two feeds above:
  // they are catalog data, not render output, so a live lane has nothing different to say about
  // them.
  override fun knownDifferences(): ServeKnownDifferences.Document? = baked.knownDifferences()

  override fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    baked.knownDifferenceArtifact(relativePath)

  // The catalog's published player comparison rides the baked staging dir, so it stays reachable
  // when a live daemon fronts this session.
  override fun rcCompare(): RcCompareManifest? = baked.rcCompare()

  override fun rcCompareImage(name: String): ByteArray? = baked.rcCompareImage(name)

  override fun rcComparePending(): Boolean = baked.rcComparePending()

  /**
   * The baked host's live-only (deferred) ids — listed previews with no PNG behind them, published
   * for on-demand render. Their requests always route to a per-preview daemon: there is no baked
   * sticker to replay. Mirrors [ServeCatalogLiveHost.liveOnlyPreviewIds].
   */
  override val liveOnlyPreviewIds: Set<String> = baked.liveOnlyPreviewIds

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant — the live lane is opt-in per edit.
   */
  override val canApplyOverrides: Boolean = false

  /**
   * A per-preview daemon CAN re-render an override on demand, so the viewer's knobs render live.
   */
  override val canRenderOverrides: Boolean = true

  /** The "Live (stream)" toggle is offered (unlike a plain static catalog). */
  override val hasLiveStream: Boolean = true

  /** Only a preview with a per-preview live bundle ([alias]) can re-render; others replay baked. */
  override fun canRenderOverridesFor(previewId: String): Boolean = previewId in alias

  /**
   * Per-preview SVG availability (issue #2352): narrows [hasSvgExport] so the viewer doesn't offer
   * the SVG control where the `.svg` lane would 404. A daemon-twinned id can export via its
   * per-preview daemon; an unmapped id only when the baked catalog carried its slug vector. Guarded
   * by [hasSvgExport] so it never advertises more broadly than the session already does. Mirrors
   * [renderSvg]'s routing.
   */
  override fun hasSvgExportFor(previewId: String): Boolean =
    hasSvgExport && (previewId in alias || baked.hasSvgExportFor(previewId))

  override val hasScrollExport: Boolean = previews.any {
    it.id in alias && ServeRenderHost.SCROLL_LONG_KIND in it.dataProductKinds
  }

  override fun hasScrollExportFor(previewId: String): Boolean =
    previewId in alias &&
      previews
        .firstOrNull { it.id == previewId }
        ?.dataProductKinds
        ?.contains(ServeRenderHost.SCROLL_LONG_KIND) == true

  /**
   * The inspection layers, advertised from [alias] membership — the same basis as
   * [canRenderOverridesFor] and the live stream, and for the same reason: a per-preview daemon is a
   * full [ServeRenderHost] once resolved, and asking it what it can produce would mean
   * materialising one (a bundle fetch and a JVM) on every page render, for a checkbox nobody has
   * ticked yet.
   *
   * An id that turns out not to resolve answers `NotFound` and the layer draws nothing, exactly as
   * [renderScrollPng] and [subscribeStream] already behave for the same case.
   */
  override val hasA11yOverlay: Boolean = alias.isNotEmpty()

  override fun hasA11yOverlayFor(previewId: String): Boolean = previewId in alias

  override val hasDesignAnnotations: Boolean = alias.isNotEmpty()

  override fun hasDesignAnnotationsFor(previewId: String): Boolean = previewId in alias

  /** The baked half: a published catalog can inspect typography with no daemon at all. */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    baked.hasPublishedTypographyFor(previewId)

  /**
   * Accessibility inspection routes to this preview's own daemon, carrying the viewer's overrides —
   * the layer has to describe the composition on screen, not the catalog's original pixels. There
   * is no baked fallback: a static sticker carries no semantics tree.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    val daemonId = alias[previewId] ?: return A11yOutcome.NotFound
    val live = resolveLive(daemonId) ?: return A11yOutcome.NotFound
    return live.renderA11y(daemonId, overrides)
  }

  /**
   * Typography + theme inspection follows [renderA11y] to the per-preview daemon, then falls back
   * to the catalog's published annotations for anything the live lane can't answer — an unmapped
   * id, a daemon that won't resolve, or one with no semantics lane.
   *
   * That fallback is gated on the request routing to baked pixels in the first place
   * ([CatalogLiveRouting.overridesAffectRender], the predicate [render] uses): published bounds
   * were measured over the baked frame, so under a font scale or a knob edit they would describe a
   * frame the visitor is not being shown.
   */
  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
  ): AnnotationsOutcome {
    val live =
      alias[previewId]?.let { daemonId ->
        resolveLive(daemonId)?.renderAnnotations(daemonId, overrides)
      }
    if (live != null && live !is AnnotationsOutcome.NotFound) return live
    if (CatalogLiveRouting.overridesAffectRender(previewId, overrides))
      return AnnotationsOutcome.NotFound
    return baked.renderAnnotations(previewId, overrides)
  }

  /**
   * Ordinary browsing serves the baked catalog PNG; an override the baked PNG can't represent
   * routes to that preview's own daemon. An unmapped id, or one whose per-preview daemon can't be
   * resolved, falls back to baked.
   */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId =
      CatalogLiveRouting.daemonIdForRender(previewId, overrides, alias, liveOnlyPreviewIds)
        ?: return baked.render(previewId, overrides)
    val live =
      resolveLive(daemonId)
        // A live-only (deferred) preview has no baked PNG to fall back to, so an unresolvable
        // daemon is a genuine miss rather than a quiet downgrade to the baked sticker.
        ?: return if (previewId in liveOnlyPreviewIds) RenderOutcome.NotFound
        else baked.render(previewId, overrides)
    return live.render(daemonId, overrides)
  }

  /** Full-page raster capture is not baked; route a mapped preview to its own daemon. */
  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = alias[previewId] ?: return RenderOutcome.NotFound
    val live = resolveLive(daemonId) ?: return RenderOutcome.NotFound
    return live.renderScrollPng(daemonId, overrides)
  }

  /** Full-page vector capture follows the same per-preview daemon route. */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val daemonId = alias[previewId] ?: return SvgOutcome.NotFound
    val live = resolveLive(daemonId) ?: return SvgOutcome.NotFound
    return live.renderScrollSvg(daemonId, overrides)
  }

  /**
   * SVG mirrors [render]'s per-preview routing, with the same baked fallback as
   * [ServeCatalogLiveHost]: an override-bearing mapped id renders on its daemon; otherwise the
   * baked catalog's `figma/<slug>.svg` vector serves it, and only when the baked lane has none does
   * a mapped id fall back to its daemon.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    CatalogLiveRouting.daemonIdForRender(previewId, overrides, alias, liveOnlyPreviewIds)?.let {
      daemonId ->
      resolveLive(daemonId)?.let {
        return it.renderSvg(daemonId, overrides)
      }
    }
    // No override — but the baked `figma/<slug>.svg` is slug-keyed + light-preferred, so a
    // `…__dark`
    // id would serve the LIGHT vector even though its PNG and live render are dark. Prefer the
    // daemon's per-variant SVG (carries the variant's uiMode/theme) for any daemon-twinned id; the
    // baked slug SVG stays the fallback for unmapped ids and if the daemon can't export.
    alias[previewId]?.let { daemonId ->
      resolveLive(daemonId)?.renderSvg(daemonId, overrides)?.let { live ->
        if (live !is SvgOutcome.NotFound) return live
      }
    }
    return baked.renderSvg(previewId, overrides)
  }

  /** Live streaming is available only for aliased ids that resolve a per-preview daemon. */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    val daemonId =
      alias[previewId]
        ?: run {
          onUnavailable?.invoke("no live daemon twin for '$previewId' (baked snapshot only)")
          return null
        }
    val live =
      resolveLive(daemonId)
        ?: run {
          onUnavailable?.invoke("live daemon for '$previewId' could not be resolved")
          return null
        }
    return live.subscribeStream(
      daemonId,
      overrides,
      codec,
      maxFps,
      onUnavailable = onUnavailable,
      onFrame = onFrame,
    )
  }

  override fun activeStreamCount(): Int = streamCount()

  /**
   * The per-preview daemons are owned by the pool; this host only closes the baked browse surface.
   */
  override fun close() {
    baked.close()
  }
}
