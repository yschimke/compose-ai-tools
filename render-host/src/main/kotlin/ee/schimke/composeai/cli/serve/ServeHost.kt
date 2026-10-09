package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * A servable preview session behind the multi-tenant registry + HTTP layer. Two implementations:
 * - [ServeRenderHost] — live daemon-backed snapshot renders + a streaming lane;
 * - [ServeBundleHost] — a static, pre-rendered portable bundle (no daemon), for the shared/public
 *   "host bundles, don't build" mode.
 *
 * The HTTP routes and the registry only need this surface, so either kind can be served at
 * `?session=<id>` uniformly.
 */
public interface ServeHost : AutoCloseable {
  /** The whole servable preview set for this session. */
  public val previews: List<ServePreview>

  /** One same-origin asset from a preview's portable spatial scene, or null when unavailable. */
  public fun spatialAsset(previewId: String, relativePath: String): ServeSpatialAsset? = null

  /** Whether the server can return a self-contained executable bundle for this preview. */
  public fun canDownloadExecutableBundle(previewId: String): Boolean = false

  /** A hydrated, self-contained PNG+ZIP preview bundle, or null when this host has no such lane. */
  public fun executableBundle(previewId: String): ByteArray? = null

  /** Independently-authored design references mapped to [previewId], if this host carries any. */
  public fun designReferencesFor(previewId: String): List<DesignReference> = emptyList()

  /** Canonical PNG bytes for a previously advertised design [referenceId]. */
  public fun designReferenceRaster(referenceId: String): ByteArray? = null

  /**
   * Design pages this session publishes (`pages/index.json`), or empty when it publishes none — the
   * common case, and the one every host defaults to. See [ServeDesignPages].
   */
  public fun designPages(): ServeDesignPageStore = ServeDesignPageStore.empty()

  /** Sanitized SVG markup for a previously advertised page. */
  public fun designPageSvg(pageId: String): String? = designPages().svg(pageId)

  /**
   * Typography / layout annotations over this preview's *rendered* frame, if the session carries
   * any. Empty by default — a host with no annotation manifest serves the compare page unchanged.
   */
  public fun annotationsForPreview(previewId: String): List<DesignAnnotation> = emptyList()

  /** Typography / layout annotations over a design reference's raster. */
  public fun annotationsForReference(referenceId: String): List<DesignAnnotation> = emptyList()

  /**
   * The published tag index for [previewId] (`testTag → {count, bounds}`), read by
   * [ServeBundleHost] from `tags/index.json`. A daemon-backed host projects the same shape live per
   * render instead ([ServeSemanticsTags]).
   */
  public fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    emptyMap()

  /** The design-parity activity feed this catalog published (`parity/activity.json`), if any. */
  public fun parityActivity(): ParityActivity? = null

  /** The validated GitHub issue snapshot this catalog published. */
  public fun parityIssues(): ParityIssues? = null

  /**
   * The design-guideline result this catalog published for [previewId] (the bundle's
   * `guidelines.json`, read through [ServeGuidelineResultsStore]): which rules the preview was
   * asked, each verdict with the nodes and regions it points at, and the model that answered. Null
   * when the catalog publishes none, or none for this preview.
   */
  public fun guidelineResultFor(
    previewId: String
  ): ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1? = null

  /**
   * The parity findings this catalog published (`parity/findings.json`) for one preview/reference
   * pair; see [ServeParityFindingStore.forComparison] for unscoped sets.
   */
  public fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    emptyList()

  /**
   * This catalog's known-difference document, verbatim and unparsed (see [ServeKnownDifferences]),
   * or null when it publishes none.
   */
  public fun knownDifferences(): ServeKnownDifferences.Document? = null

  /**
   * One of that document's artifacts, addressed as `<id>/<path>`. A host with no artifact tree
   * answers [ServeKnownDifferences.Artifact.Unreadable], the same as a missing file.
   */
  public fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    ServeKnownDifferences.Artifact.Unreadable

  /**
   * The module's declared `@ThemeCatalog` themes. Only a daemon-backed host can apply one, so a
   * static bundle leaves this empty.
   */
  public val declaredThemes: List<ServeTheme>
    get() = emptyList()

  /**
   * Why this session is snapshot-only when a live lane would otherwise be offered, recorded by
   * [ServeCatalogStore] when it decides the fallback. Non-empty drives the viewer's banner.
   */
  public val degradations: List<ServeDegradation>
    get() = emptyList()

  /**
   * Previews with no baked pixels (the catalog's `deferred[]` records), registered only when a live
   * lane can render them; routed to the daemon even for an override-free browse
   * ([CatalogLiveRouting.daemonIdForRender]).
   */
  public val liveOnlyPreviewIds: Set<String>
    get() = emptySet()

  /**
   * The light/dark mode [previewId]'s baked pixels are drawn in, or null when unknown (see
   * [ServeBakedTheme]). Asked of the host because pairing is a fact about the manifest, not the id;
   * null conservatively routes a `uiMode` request to a real render.
   */
  public fun bakedTheme(previewId: String): UiMode? = ServeBakedTheme.token(previewId)

  /**
   * The Remote Compose player [previewId]'s baked pixels were drawn with, or null when this session
   * cannot say. When a request names that player the snapshot answers it exactly; any other player
   * is a re-render.
   *
   * Null means unknown, not "no player": every `rcPlayer` then stays a genuine override. Don't
   * default to [RemoteComposePlayerKind.EMBEDDED]; a `RemoteViewPreviewWrapper` pin draws with the
   * view-backed player, and only the manifest knows. [ServeBundleHost] answers from `previews.json`
   * or [ServeCatalogStore.PreviewParamsMeta.capturePlayer].
   */
  public fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? = null

  /** Human label for the tenant (module Gradle path, `module@rev`, or a bundle name). */
  public val label: String

  /**
   * Whether editing an override re-renders. False for a static bundle, whose knobs are shown as
   * informational.
   */
  public val canApplyOverrides: Boolean
    get() = false

  /**
   * Whether an override-bearing `/render` returns fresh pixels even when the default lane is baked.
   * Differs from [canApplyOverrides] only for [ServeCatalogLiveHost], which browses baked but
   * re-renders overrides through its carried daemon.
   */
  public val canRenderOverrides: Boolean
    get() = canApplyOverrides

  /**
   * Per-preview [canRenderOverrides]. [ServeCatalogLiveHost] answers false for previews with no
   * daemon twin, so their controls show as disabled rather than silently ignored.
   */
  public fun canRenderOverridesFor(previewId: String): Boolean = canRenderOverrides

  /**
   * Named-colour overrides (`<state name>` → `#RRGGBB`) that apply [providerFqn] to a replayed
   * Remote Compose document via `setNamedColorOverride`: the only way to theme a preview whose
   * composable bytecode is gone.
   *
   * Empty keeps a replayed `themeProvider` render a refusal; answering 200 with nothing applied
   * would claim a theme it never drew (#3449). Previews that can recompose don't consult this.
   */
  public fun themeReplayColors(providerFqn: String): Map<String, String> = emptyMap()

  /**
   * The declared themes a replayed preview can be rendered under: per theme, since a catalog may
   * map only some of them.
   */
  public fun replayableThemes(): List<ServeTheme> = declaredThemes.filter {
    themeReplayColors(it.providerFqn).isNotEmpty()
  }

  /**
   * Maximum browser-side concurrency a short-lived themed-thumbnail burst may request. A plain
   * daemon has one render lock, so the default remains serial. A composite backed by independent
   * per-preview daemons may opt into a larger temporary burst; the HTTP server still clamps it to
   * its render slots and shares that burst across every active page for the same catalog.
   */
  public val themeRenderBurstCapacity: Int
    get() = 1

  /**
   * Return an already-materialised PNG without entering the HTTP render admission queue. Hosts with
   * no cache return null. [render] remains the authoritative path and must recheck its cache after
   * admission to close the lookup/render race.
   */
  public fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Serve [previewId] from a baked PNG already on disk, or null when answering would need a render
   * or fetch. Lets browsing bypass render admission so cold daemon renders can't head-of-line block
   * readers. Must be cheap and non-blocking; null is always safe.
   */
  public fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Fetch [previewId]'s published pixels so a later [bakedRender] succeeds. Blocking: called by
   * [ServeThumbWarmer] off a background pool, never on a request thread. Never renders or wakes a
   * daemon; failures are silent and not remembered.
   */
  public fun warmBakedRender(previewId: String) {}

  /**
   * [previewId]'s baked render size from the PNG header alone, for `og:image:width`/`height`
   * ([ServeWeb.UnfurlMetadata]), or null when the pixels aren't local.
   */
  public fun bakedRenderSize(previewId: String): Pair<Int, Int>? = null

  /**
   * One published animated capture, or the reason it could not be read, so the route can tell
   * "never published" from "branch refusing". [extension] is validated against what the catalog
   * declared rather than trusted.
   */
  public fun motionRead(motionId: String, extension: String): BranchFetch = BranchFetch.NotFound

  /**
   * A visitor is present (`POST /api/presence`): warm the live lane so their first live render
   * skips the cold start (~68 s on Android). Called every few minutes by every open tab, so it must
   * return immediately and be a no-op once warm.
   */
  public fun keepLiveWarm() {}

  /**
   * Render-performance counters for `/status` (`runningServers[].renderStats`), or null when the
   * host has no live render lane.
   */
  public fun renderPerfStats(): RenderPerfSnapshot? = null

  /**
   * This lane's open render breaker, or null while rendering normally. When non-null the host has
   * stopped attempting renders, and background render work must stand down (#3448).
   */
  public fun renderBreaker(): RenderBreakerSnapshot? = null

  /**
   * Bounded child-daemon pools owned by this host, surfaced on `/status.json` so production
   * monitors can distinguish "one catalog daemon is up" from "a catalog daemon plus N per-preview
   * daemons are resident". Empty for ordinary hosts.
   */
  public fun daemonPoolStats(): List<DaemonPoolSnapshot> = emptyList()

  /** Server-side catalog theme optimization progress, or null for hosts without that cache. */
  public fun themeOptimizationSnapshot(): ThemeOptimizationSnapshot? = null

  /** Memory occupancy of this catalog generation's durable rendered-preview cache. */
  public fun catalogRenderCacheSnapshot(): CatalogRenderCacheSnapshot? = null

  /** True while low-priority work still needs this host resident. */
  public val backgroundWorkActive: Boolean
    get() = false

  /**
   * Whether the daemon advertises the `"gestures"` capability (only the Android backend does), so
   * the viewer offers "Show gesture hints" only where it does something.
   */
  public val gesturesRenderable: Boolean
    get() = false

  /**
   * Whether [renderSvg] can produce a `compose/figma-svg` export: always for a daemon-backed host,
   * only with baked `figma/<slug>.svg` vectors for a static bundle.
   */
  public val hasSvgExport: Boolean
    get() = false

  /**
   * Per-preview [hasSvgExport]: a static catalog may lack the vector for one component's slug
   * (#2352).
   */
  public fun hasSvgExportFor(previewId: String): Boolean = hasSvgExport

  /** Whether this host can produce the tall raster `render/scroll/long` export. */
  public val hasScrollExport: Boolean
    get() = false

  /** Per-preview refinement of [hasScrollExport]. */
  public fun hasScrollExportFor(previewId: String): Boolean = hasScrollExport

  /**
   * Render subprocesses this host is carrying right now; a static bundle reports 0 so `/status`
   * never claims it has a render server.
   */
  public val daemonProcessCount: Int
    get() = 0

  /**
   * Whether this host's daemon subprocess exists yet; a registered catalog opens its session on
   * first use, and `/status` must not count it as running before then.
   */
  public val daemonStarted: Boolean
    get() = true

  /**
   * Whether a live daemon stream is offered. Differs from [canApplyOverrides] only for
   * [ServeCatalogLiveHost], whose snapshots stay baked while streams are offered on demand.
   */
  public val hasLiveStream: Boolean
    get() = canApplyOverrides

  /** Render [previewId] at [overrides] (cached where possible). */
  public fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome

  /**
   * Why this host has permanently given up on rendering [previewId] at [overrides], or null while
   * it may still succeed, so the HTTP layer can answer with a terminal status instead of a
   * retryable [RenderOutcome.Busy].
   */
  public fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? = null

  /**
   * Close daemon subprocesses idle for [idleMillis] without closing the host, returning how many
   * were closed. Pinned sessions are never suspended by [ServeSessionRegistry.suspendIdle]; this
   * keeps them from holding replica and per-preview pools forever.
   */
  public fun releaseIdleDaemons(idleMillis: Long): Int = 0

  /**
   * Render a request admitted by the server's short-lived catalog theme lease. Hosts that cannot
   * parallelise keep the ordinary [render] behaviour. A catalog backed by a replica pool overrides
   * this to borrow an independent shared daemon, so only explicitly leased batches grow the pool.
   */
  public fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    render(previewId, overrides)

  /**
   * The captured Remote Compose document (`ir/<id>.rc`) for the in-browser player, served over `GET
   * /render/<id>.rc`, or null when this host carries none.
   */
  public fun remoteComposeDoc(previewId: String): ByteArray? = null

  /** Whether [remoteComposeDoc] exists; bundle hosts override with a cheap existence check. */
  public fun hasRemoteComposeDoc(previewId: String): Boolean = remoteComposeDoc(previewId) != null

  /**
   * The published Remote Compose player comparison ([ServeRcCompare]) behind the `?format=rc`
   * compare page, or null when the catalog's delivery branch published none.
   */
  public fun rcCompare(): RcCompareManifest? = null

  /**
   * Bytes for one staged rc-compare lane image ([RcCompareCell.render] / [RcCompareCell.diff]),
   * served over `GET /<system>/rc-compare/<lane>/<slot>.png`. Null for anything the host didn't
   * stage — the name vocabulary is fixed, so this is never a general file read.
   */
  public fun rcCompareImage(name: String): ByteArray? = null

  /**
   * The backends [previewId] has a published render for, in [RcPlayerBackend.UNIVERSE] order.
   * Folded into [enabledRcPlayersFor] so the picker offers exactly what the host can serve. Reads
   * the manifest only.
   */
  public fun stagedRcPlayers(previewId: String): List<RcPlayerBackend> {
    val row = rcCompare()?.rows?.firstOrNull { it.previewId == previewId } ?: return emptyList()
    return RcPlayerBackend.UNIVERSE.filter { backend ->
      val cell = backend.rcCompareLane?.let { row.lanes[it] }
      cell != null && cell.rendered && cell.render.isNotEmpty()
    }
  }

  /**
   * The published render of [previewId] by [backend], served without a daemon. Only answers a bare
   * player selection: the caller routes any other override to the renderer.
   */
  public fun publishedRcPlayerRender(previewId: String, backend: RcPlayerBackend): ByteArray? {
    val lane = backend.rcCompareLane ?: return null
    val cell = rcCompare()?.rows?.firstOrNull { it.previewId == previewId }?.lanes?.get(lane)
    val name = cell?.takeIf { it.rendered }?.render?.takeIf { it.isNotEmpty() } ?: return null
    return rcCompareImage(name)
  }

  /**
   * True while the published comparison may still be arriving, so the compare page is not
   * edge-cached in its pre-manifest shape.
   */
  public fun rcComparePending(): Boolean = false

  /**
   * Size and density for a cmp-jvm render of [previewId], matched to the baked capture, or null
   * when the host lacks the `.rc` sidecar or size metadata.
   */
  public fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? = null

  /**
   * Whether the server-side **cmp-jvm** lane can render [previewId]: the host carries the captured
   * document and a render spec, and the isolated CMP render subprocess is installed
   * ([RcJvmServerRenderer.isAvailable]). Hosts fold this into [enabledRcPlayersFor].
   */
  public fun supportsCmpJvm(previewId: String): Boolean =
    hasRemoteComposeDoc(previewId) &&
      remoteComposeRenderSpec(previewId) != null &&
      RcJvmServerRenderer.isAvailable()

  /**
   * [bakedRcPlayer] as a backend. Every [enabledRcPlayersFor] unions this in, since a bare URL
   * serves those pixels. Map recorded `capturePlayer` strings with
   * [RcPlayerBackend.fromCapturePlayer], not `fromWire`: older captures used `cmp-android` for the
   * embedded player.
   */
  public fun bakedRcPlayerBackend(previewId: String): RcPlayerBackend? =
    bakedRcPlayer(previewId)?.let { kind ->
      RcPlayerBackend.entries.firstOrNull { it.playerKind == kind }
    }

  /**
   * The subset of [RcPlayerBackend.UNIVERSE] the viewer may enable for [previewId]; the rest are
   * shown disabled. Empty for a non-Remote Compose preview.
   */
  public fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> =
    if (hasRemoteComposeDoc(previewId)) {
      buildList {
        add(RcPlayerBackend.CAMAELON_JS)
        // The CMP player on the desktop JVM renders the same `.rc` server-side via an isolated
        // subprocess; enable it wherever the sidecar player is installed and a render spec exists.
        if (supportsCmpJvm(previewId)) add(RcPlayerBackend.CMP_JVM)
        // Staged parity renders need no renderer. cmp-wasm is excluded: it is an interactive
        // iframe lane that needs an installed Wasm distribution.
        addAll(
          stagedRcPlayers(previewId).filterNot { it == RcPlayerBackend.CMP_WASM || it in this }
        )
        // …and the player the BAKED artifact already is ([bakedRcPlayerBackend]).
        bakedRcPlayerBackend(previewId)?.let { if (it !in this) add(it) }
      }
        .sortedBy { RcPlayerBackend.UNIVERSE.indexOf(it) }
    } else {
      emptyList()
    }

  /**
   * Whether the live lane honours `remoteCompose.player`: only an Android daemon with the Remote
   * Compose runtime, where VIEW vs EMBEDDED changes pixels.
   */
  public val remoteComposePlayerSelectable: Boolean
    get() = false

  /** Render [previewId]'s figma-svg export, or [SvgOutcome.NotFound] without a daemon. */
  public fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * [renderSvg] for `?mode=web`: a catalog host links raster crops to their public home instead of
   * base64-embedding them. Hosts with no public raster home keep embedding.
   */
  public fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    renderSvg(previewId, overrides)

  /**
   * Render [previewId]'s full-page figma-svg export (`compose/figma-svg-long`), or
   * [SvgOutcome.NotFound] without a daemon. See [docs/design/SCROLLING_SVG.md].
   */
  public fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * Render [previewId]'s full-page raster scroll capture (`render/scroll/long`) at [overrides], or
   * [RenderOutcome.NotFound] when this host has no daemon-backed scroll producer.
   */
  public fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    RenderOutcome.NotFound

  /**
   * Render [previewId]'s declared preview slots as JSON, or [SlotsOutcome.NotFound] without a
   * daemon.
   */
  public fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome =
    SlotsOutcome.NotFound

  /** Whether this host can produce the accessibility data products the viewer overlay draws. */
  public val hasA11yOverlay: Boolean
    get() = false

  /** Per-preview accessibility availability; composite hosts may only map part of a catalog. */
  public fun hasA11yOverlayFor(previewId: String): Boolean = hasA11yOverlay

  /**
   * Fetch [previewId]'s merged accessibility products at [overrides] as JSON (`{previewId, nodes,
   * findings, touchTargets}`), or [A11yOutcome.NotFound] when this host can't produce them. See
   * [ServeRenderHost.renderA11y].
   */
  public fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome =
    A11yOutcome.NotFound

  /**
   * Whether this host can derive the viewer's typography, theme and layout inspection layers from a
   * render's `compose/semantics` tree ([renderAnnotations]). Tracks [canApplyOverrides]: capturing
   * a semantics tree needs a daemon, exactly like [renderSlots].
   */
  public val hasDesignAnnotations: Boolean
    get() = canApplyOverrides

  /** Per-preview [hasDesignAnnotations]; composite hosts may only map part of a catalog. */
  public fun hasDesignAnnotationsFor(previewId: String): Boolean = hasDesignAnnotations

  /**
   * Whether `.annotations` for [previewId] can be answered from published typography annotations
   * ([ServeBundleHost.renderAnnotations]). Separate from [hasDesignAnnotationsFor] because the
   * theme layer only exists live.
   */
  public fun hasPublishedTypographyFor(previewId: String): Boolean = false

  /**
   * Render [previewId]'s inspection layers as JSON (`{previewId, annotations, tags}`), or
   * [AnnotationsOutcome.NotFound] without a daemon. `tags` comes from the same semantics payload so
   * both describe one frame. See [ServeRenderHost.renderAnnotations].
   *
   * [layers] (null = all) is a routing hint: a host may return a superset but never omit a named
   * layer. It lets a typography-only request be answered from a published bundle
   * ([AnnotationKind.publishedLayersSuffice]).
   */
  public fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>? = null,
  ): AnnotationsOutcome = AnnotationsOutcome.NotFound

  /**
   * Whether [renderAnnotations] describes the same frame an override-free `/render/<id>.png`
   * replays. True only for a pure replay of published data ([ServeBundleHost]); not implied by
   * `canApplyOverrides == false`, since live catalog wrappers serve baked PNGs with live
   * annotations.
   */
  public val annotationsFollowBakedFrame: Boolean
    get() = false

  /**
   * Join the shared live stream for [previewId], or `null` when this host has no live lane.
   * [onUnavailable] is called once, before the null return, with the reason the lane couldn't open.
   */
  public fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?

  /** Count of live upstream streams (0 for hosts without a live lane). */
  public fun activeStreamCount(): Int
}

/**
 * [ServeHost.motionRead]'s bytes. An extension rather than a member so no implementor can override
 * it and leave [ServeHost.motionRead] unimplemented.
 */
public fun ServeHost.motionBytes(motionId: String, extension: String): ByteArray? =
  motionRead(motionId, extension).bytesOrNull
