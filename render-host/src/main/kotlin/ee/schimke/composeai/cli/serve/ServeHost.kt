package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * A servable preview session behind the registry + HTTP layer: either a live daemon-backed
 * [ServeRenderHost] or a static pre-rendered [ServeBundleHost], served uniformly at
 * `?session=<id>`.
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

  /** Design pages this session publishes (`pages/index.json`); empty by default. */
  public fun designPages(): ServeDesignPageStore = ServeDesignPageStore.empty()

  /** Sanitized SVG markup for a previously advertised page. */
  public fun designPageSvg(pageId: String): String? = designPages().svg(pageId)

  /** Typography / layout annotations over this preview's rendered frame; empty by default. */
  public fun annotationsForPreview(previewId: String): List<DesignAnnotation> = emptyList()

  /** Typography / layout annotations over a design reference's raster. */
  public fun annotationsForReference(referenceId: String): List<DesignAnnotation> = emptyList()

  /**
   * The published tag index for [previewId] (`testTag → {count, bounds}`); live hosts project the
   * same shape per render ([ServeSemanticsTags]).
   */
  public fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    emptyMap()

  /** The design-parity activity feed this catalog published (`parity/activity.json`), if any. */
  public fun parityActivity(): ParityActivity? = null

  /** The validated GitHub issue snapshot this catalog published. */
  public fun parityIssues(): ParityIssues? = null

  /** The published design-guideline result for [previewId] (`guidelines.json`), or null. */
  public fun guidelineResultFor(
    previewId: String
  ): ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1? = null

  /** Published parity findings (`parity/findings.json`) for one preview/reference pair. */
  public fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    emptyList()

  /** This catalog's known-difference document, verbatim, or null ([ServeKnownDifferences]). */
  public fun knownDifferences(): ServeKnownDifferences.Document? = null

  /**
   * One artifact of that document, addressed as `<id>/<path>`; missing reads as
   * [ServeKnownDifferences.Artifact.Unreadable].
   */
  public fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    ServeKnownDifferences.Artifact.Unreadable

  /**
   * The module's declared `@ThemeCatalog` themes; empty for a static bundle, which cannot apply
   * one.
   */
  public val declaredThemes: List<ServeTheme>
    get() = emptyList()

  /**
   * Why this session is snapshot-only when a live lane would otherwise be offered; drives the
   * banner.
   */
  public val degradations: List<ServeDegradation>
    get() = emptyList()

  /**
   * Previews with no baked pixels (catalog `deferred[]`), registered only when a live lane can
   * render them ([CatalogLiveRouting.daemonIdForRender]).
   */
  public val liveOnlyPreviewIds: Set<String>
    get() = emptySet()

  /**
   * The light/dark mode [previewId]'s baked pixels are drawn in, or null when unknown; null routes
   * a `uiMode` request to a real render.
   */
  public fun bakedTheme(previewId: String): UiMode? = ServeBakedTheme.token(previewId)

  /**
   * The Remote Compose player [previewId]'s baked pixels were drawn with, or null when unknown.
   *
   * Null means unknown, not "no player": don't default to [RemoteComposePlayerKind.EMBEDDED], since
   * a `RemoteViewPreviewWrapper` pin draws with the view-backed player and only the manifest knows.
   */
  public fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? = null

  /** Human label for the tenant (module Gradle path, `module@rev`, or a bundle name). */
  public val label: String

  /** Whether editing an override re-renders; false for a static bundle. */
  public val canApplyOverrides: Boolean
    get() = false

  /**
   * Whether an override-bearing `/render` returns fresh pixels even when the default lane is baked
   * (differs from [canApplyOverrides] only for [ServeCatalogLiveHost]).
   */
  public val canRenderOverrides: Boolean
    get() = canApplyOverrides

  /** Per-preview [canRenderOverrides]; false hides controls for previews with no daemon twin. */
  public fun canRenderOverridesFor(previewId: String): Boolean = canRenderOverrides

  /**
   * Named-colour overrides (`<state name>` → `#RRGGBB`) applying [providerFqn] to a replayed Remote
   * Compose document — the only way to theme a preview whose bytecode is gone. Empty keeps a
   * replayed `themeProvider` render a refusal rather than claiming a theme it never drew.
   */
  public fun themeReplayColors(providerFqn: String): Map<String, String> = emptyMap()

  /** The declared themes a replayed preview can be rendered under (a catalog may map only some). */
  public fun replayableThemes(): List<ServeTheme> = declaredThemes.filter {
    themeReplayColors(it.providerFqn).isNotEmpty()
  }

  /**
   * Maximum browser-side concurrency for a themed-thumbnail burst. Serial by default (one render
   * lock); hosts backed by independent per-preview daemons may raise it, still clamped by the
   * server.
   */
  public val themeRenderBurstCapacity: Int
    get() = 1

  /**
   * An already-materialised PNG without entering render admission, or null. [render] must recheck
   * its cache after admission to close the lookup/render race.
   */
  public fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Serve [previewId] from a baked PNG on disk, bypassing render admission, or null if that would
   * need a render or fetch. Must be cheap and non-blocking.
   */
  public fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Fetch [previewId]'s published pixels so a later [bakedRender] succeeds. Blocking (background
   * pool only); never renders or wakes a daemon.
   */
  public fun warmBakedRender(previewId: String) {}

  /** [previewId]'s baked render size from the PNG header, or null when the pixels aren't local. */
  public fun bakedRenderSize(previewId: String): Pair<Int, Int>? = null

  /**
   * One published animated capture, or why it couldn't be read. [extension] is validated against
   * the catalog, not trusted.
   */
  public fun motionRead(motionId: String, extension: String): BranchFetch = BranchFetch.NotFound

  /**
   * A visitor is present: warm the live lane to skip the cold start. Called often by every open
   * tab, so it must return immediately and be a no-op once warm.
   */
  public fun keepLiveWarm() {}

  /** Render-performance counters for `/status`, or null without a live render lane. */
  public fun renderPerfStats(): RenderPerfSnapshot? = null

  /**
   * This lane's open render breaker, or null; while non-null, background render work must stand
   * down.
   */
  public fun renderBreaker(): RenderBreakerSnapshot? = null

  /**
   * Bounded child-daemon pools owned by this host, for `/status.json`. Empty for ordinary hosts.
   */
  public fun daemonPoolStats(): List<DaemonPoolSnapshot> = emptyList()

  /** Server-side catalog theme optimization progress, or null for hosts without that cache. */
  public fun themeOptimizationSnapshot(): ThemeOptimizationSnapshot? = null

  /** Memory occupancy of this catalog generation's durable rendered-preview cache. */
  public fun catalogRenderCacheSnapshot(): CatalogRenderCacheSnapshot? = null

  /** True while low-priority work still needs this host resident. */
  public val backgroundWorkActive: Boolean
    get() = false

  /** Whether the daemon advertises the `"gestures"` capability (Android only). */
  public val gesturesRenderable: Boolean
    get() = false

  /**
   * Whether [renderSvg] can produce a `compose/figma-svg` export: always when daemon-backed, only
   * with baked vectors for a static bundle.
   */
  public val hasSvgExport: Boolean
    get() = false

  /** Per-preview [hasSvgExport]: a static catalog may lack one slug's vector. */
  public fun hasSvgExportFor(previewId: String): Boolean = hasSvgExport

  /** Whether this host can produce the tall raster `render/scroll/long` export. */
  public val hasScrollExport: Boolean
    get() = false

  /** Per-preview refinement of [hasScrollExport]. */
  public fun hasScrollExportFor(previewId: String): Boolean = hasScrollExport

  /** Render subprocesses this host is carrying right now; 0 for a static bundle. */
  public val daemonProcessCount: Int
    get() = 0

  /** Whether this host's daemon subprocess exists yet (catalogs open their session lazily). */
  public val daemonStarted: Boolean
    get() = true

  /**
   * Whether a live daemon stream is offered (differs from [canApplyOverrides] only for
   * [ServeCatalogLiveHost]).
   */
  public val hasLiveStream: Boolean
    get() = canApplyOverrides

  /** Render [previewId] at [overrides] (cached where possible). */
  public fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome

  /**
   * Why this host has permanently given up on [previewId] at [overrides], so HTTP can answer with a
   * terminal status instead of a retryable [RenderOutcome.Busy]; null while it may still succeed.
   */
  public fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? = null

  /**
   * Close daemon subprocesses idle for [idleMillis] without closing the host; returns how many.
   * Keeps pinned sessions, which are never suspended, from holding pools forever.
   */
  public fun releaseIdleDaemons(idleMillis: Long): Int = 0

  /**
   * Render a request admitted under the catalog theme lease. Replica-pool hosts override this to
   * borrow an independent daemon; others fall back to [render].
   */
  public fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    render(previewId, overrides)

  /** The captured Remote Compose document (`ir/<id>.rc`) for the in-browser player, or null. */
  public fun remoteComposeDoc(previewId: String): ByteArray? = null

  /** Whether [remoteComposeDoc] exists; bundle hosts override with a cheap existence check. */
  public fun hasRemoteComposeDoc(previewId: String): Boolean = remoteComposeDoc(previewId) != null

  /** The published Remote Compose player comparison behind `?format=rc`, or null. */
  public fun rcCompare(): RcCompareManifest? = null

  /**
   * Bytes for one staged rc-compare lane image, or null. The name vocabulary is fixed, so this is
   * never a general file read.
   */
  public fun rcCompareImage(name: String): ByteArray? = null

  /** The backends [previewId] has a published render for, in [RcPlayerBackend.UNIVERSE] order. */
  public fun stagedRcPlayers(previewId: String): List<RcPlayerBackend> {
    val row = rcCompare()?.rows?.firstOrNull { it.previewId == previewId } ?: return emptyList()
    return RcPlayerBackend.UNIVERSE.filter { backend ->
      val cell = backend.rcCompareLane?.let { row.lanes[it] }
      cell != null && cell.rendered && cell.render.isNotEmpty()
    }
  }

  /**
   * The published render of [previewId] by [backend], served without a daemon; bare player
   * selections only.
   */
  public fun publishedRcPlayerRender(previewId: String, backend: RcPlayerBackend): ByteArray? {
    val lane = backend.rcCompareLane ?: return null
    val cell = rcCompare()?.rows?.firstOrNull { it.previewId == previewId }?.lanes?.get(lane)
    val name = cell?.takeIf { it.rendered }?.render?.takeIf { it.isNotEmpty() } ?: return null
    return rcCompareImage(name)
  }

  /**
   * True while the published comparison may still be arriving, so the page isn't edge-cached early.
   */
  public fun rcComparePending(): Boolean = false

  /**
   * Size and density for a cmp-jvm render matched to the baked capture, or null without metadata.
   */
  public fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? = null

  /**
   * Whether the server-side cmp-jvm lane can render [previewId]: document, render spec and the
   * isolated CMP subprocess ([RcJvmServerRenderer.isAvailable]) are all present.
   */
  public fun supportsCmpJvm(previewId: String): Boolean =
    hasRemoteComposeDoc(previewId) &&
      remoteComposeRenderSpec(previewId) != null &&
      RcJvmServerRenderer.isAvailable()

  /**
   * [bakedRcPlayer] as a backend. Uses [RcPlayerBackend.fromCapturePlayer], not `fromWire`: older
   * captures used `cmp-android` for the embedded player.
   */
  public fun bakedRcPlayerBackend(previewId: String): RcPlayerBackend? =
    bakedRcPlayer(previewId)?.let { kind ->
      RcPlayerBackend.entries.firstOrNull { it.playerKind == kind }
    }

  /**
   * The subset of [RcPlayerBackend.UNIVERSE] the viewer may enable for [previewId]; empty for a
   * non-Remote Compose preview.
   */
  public fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> =
    if (hasRemoteComposeDoc(previewId)) {
      buildList {
        add(RcPlayerBackend.CAMAELON_JS)
        if (supportsCmpJvm(previewId)) add(RcPlayerBackend.CMP_JVM)
        // cmp-wasm is excluded: it is an interactive iframe lane needing an installed Wasm
        // distribution.
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
   * Whether the live lane honours `remoteCompose.player` (Android daemon with Remote Compose only).
   */
  public val remoteComposePlayerSelectable: Boolean
    get() = false

  /** Render [previewId]'s figma-svg export, or [SvgOutcome.NotFound] without a daemon. */
  public fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * [renderSvg] for `?mode=web`: catalog hosts link raster crops instead of base64-embedding them.
   */
  public fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    renderSvg(previewId, overrides)

  /** Render [previewId]'s full-page figma-svg export, or [SvgOutcome.NotFound] without a daemon. */
  public fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * Render [previewId]'s full-page raster scroll capture, or [RenderOutcome.NotFound] without a
   * daemon-backed scroll producer.
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

  /** [previewId]'s merged accessibility products as JSON, or [A11yOutcome.NotFound]. */
  public fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome =
    A11yOutcome.NotFound

  /**
   * Whether this host can derive typography/theme/layout layers from a render's semantics tree
   * (needs a daemon, so it tracks [canApplyOverrides]).
   */
  public val hasDesignAnnotations: Boolean
    get() = canApplyOverrides

  /** Per-preview [hasDesignAnnotations]; composite hosts may only map part of a catalog. */
  public fun hasDesignAnnotationsFor(previewId: String): Boolean = hasDesignAnnotations

  /**
   * Whether `.annotations` for [previewId] can be answered from published typography annotations
   * (the theme layer only exists live).
   */
  public fun hasPublishedTypographyFor(previewId: String): Boolean = false

  /**
   * Render [previewId]'s inspection layers as JSON (`{previewId, annotations, tags}`), or
   * [AnnotationsOutcome.NotFound] without a daemon.
   *
   * [layers] (null = all) is a routing hint: a host may return a superset but never omit a named
   * layer.
   */
  public fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>? = null,
  ): AnnotationsOutcome = AnnotationsOutcome.NotFound

  /**
   * Whether [renderAnnotations] describes the same frame an override-free PNG replays. True only
   * for pure replays ([ServeBundleHost]); live catalog wrappers serve baked PNGs with live
   * annotations.
   */
  public val annotationsFollowBakedFrame: Boolean
    get() = false

  /**
   * Join the shared live stream for [previewId], or null without a live lane; [onUnavailable] gets
   * the reason first.
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

/** [ServeHost.motionRead]'s bytes. An extension so no implementor can override it instead. */
public fun ServeHost.motionBytes(motionId: String, extension: String): ByteArray? =
  motionRead(motionId, extension).bytesOrNull
