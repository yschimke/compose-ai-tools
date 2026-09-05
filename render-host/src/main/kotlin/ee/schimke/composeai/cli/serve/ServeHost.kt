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
interface ServeHost : AutoCloseable {
  /** The whole servable preview set for this session. */
  val previews: List<ServePreview>

  /** One same-origin asset from a preview's portable spatial scene, or null when unavailable. */
  fun spatialAsset(previewId: String, relativePath: String): ServeSpatialAsset? = null

  /** Whether the server can return a self-contained executable bundle for this preview. */
  fun canDownloadExecutableBundle(previewId: String): Boolean = false

  /** A hydrated, self-contained PNG+ZIP preview bundle, or null when this host has no such lane. */
  fun executableBundle(previewId: String): ByteArray? = null

  /** Independently-authored design references mapped to [previewId], if this host carries any. */
  fun designReferencesFor(previewId: String): List<DesignReference> = emptyList()

  /** Canonical PNG bytes for a previously advertised design [referenceId]. */
  fun designReferenceRaster(referenceId: String): ByteArray? = null

  /**
   * Design pages this session publishes (`pages/index.json`), or empty when it publishes none — the
   * common case, and the one every host defaults to. See [ServeDesignPages].
   */
  fun designPages(): ServeDesignPageStore = ServeDesignPageStore.empty()

  /** Sanitized SVG markup for a previously advertised page. */
  fun designPageSvg(pageId: String): String? = designPages().svg(pageId)

  /**
   * Typography / layout annotations over this preview's *rendered* frame, if the session carries
   * any. Empty by default — a host with no annotation manifest serves the compare page unchanged.
   */
  fun annotationsForPreview(previewId: String): List<DesignAnnotation> = emptyList()

  /** Typography / layout annotations over a design reference's raster. */
  fun annotationsForReference(referenceId: String): List<DesignAnnotation> = emptyList()

  /**
   * The **published** tag index for [previewId] — `testTag → {count, bounds}`, the element identity
   * a scoped parity acceptance resolves against. Empty by default and for any host that publishes
   * none.
   *
   * This is the *static* half of the pair: a published catalog's renders happened in CI, so its
   * index is computed there and read back by [ServeBundleHost] from `tags/index.json`. A
   * daemon-backed [ServeRenderHost] instead projects the same shape live, per render, inside its
   * `.annotations` response ([ServeSemanticsTags]) — where it can be keyed to the frame it came
   * from. Two producers, one projection, deliberately not one code path: only one of them has a
   * daemon.
   */
  fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> = emptyMap()

  /**
   * The design-parity activity feed this catalog published (`parity/activity.json`), or null when
   * it publishes none — the common case, and the one every host defaults to. Drives the
   * `/<system>/parity` view's code / Figma feeds; the coverage half of that page is derived from
   * [previews] + [designReferencesFor] and needs no feed at all.
   */
  fun parityActivity(): ParityActivity? = null

  /** The validated GitHub issue snapshot this catalog published. */
  fun parityIssues(): ParityIssues? = null

  /**
   * The parity **verdict** this catalog published for one comparison (`parity/findings.json`) — the
   * accessibility, i18n, token and layout findings a parity run concluded about this preview read
   * against [referenceId]. Empty by default and for any catalog that publishes none.
   *
   * Keyed by the PAIR rather than by the preview alone because that is what a finding describes;
   * see [ServeParityFindingStore.forComparison] for what an unscoped set means.
   */
  fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    emptyList()

  /**
   * This catalog's committed known-difference document, **verbatim**, or null when it publishes
   * none — the common case, and the one every host defaults to.
   *
   * Raw text on purpose. Unlike every other carried artifact, the host does not parse this one:
   * `compose-preview-known-differences/v1`'s verdicts belong to the engine, which runs from one
   * shared implementation in the browser and in `design-parity`, and a host that pre-parsed it
   * would be a third implementation of the same rules with no conformance suite behind it. See
   * [ServeKnownDifferences].
   */
  fun knownDifferences(): ServeKnownDifferences.Document? = null

  /**
   * One of that document's artifacts, addressed as the document addresses it (`<id>/<path>`).
   *
   * The default is [ServeKnownDifferences.Artifact.Unreadable] rather than a null, because a host
   * with no artifact tree and a path that resolves to no file are the same answer to the consumer:
   * the record's bytes could not be read.
   */
  fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    ServeKnownDifferences.Artifact.Unreadable

  /**
   * The app's declared `@ThemeCatalog` themes — module-global, so the viewer's Theme selector can
   * offer "render this preview under Brand Dark". Non-empty only for a daemon-backed host
   * ([ServeRenderHost]) whose module declares them; a static bundle carries no theme-apply lane
   * (`themeProvider` needs the daemon to load the provider off the app classpath), so it stays
   * empty and the selector shows only the built-in light/dark axis.
   */
  val declaredThemes: List<ServeTheme>
    get() = emptyList()

  /**
   * Structured reasons this session is **degraded** — an interactive/live lane the viewer would
   * otherwise offer is unavailable, so the server falls back to baked PNG snapshots. Recorded at
   * catalog-load time by [ServeCatalogStore] (the point the fallback is decided, where it was
   * previously only logged to stderr) so the viewer + `/api/previews` can explain *why* a session
   * is snapshot-only rather than leaving the visitor to guess. Empty for a fully-live session (a
   * daemon-backed module, or a catalog served live from a carried bundle) — a non-empty list is the
   * signal the viewer shows its "why snapshot-only" banner. Defaults to empty; only
   * [ServeBundleHost] (the baked host [ServeCatalogStore] terminally registers) carries a populated
   * list.
   */
  val degradations: List<ServeDegradation>
    get() = emptyList()

  /**
   * The previews this session lists that have **no baked pixels** — the catalog's `deferred[]`
   * records (issue #2965): coverage a spec declared `priority: "deferred"` (or thinned out of the
   * palette with `modePriority`), which CI deliberately didn't rasterise. They are registered only
   * when the session has a live lane to produce them on request, so an id in here always has a
   * daemon twin; a baked-only session omits them entirely rather than showing a card that can only
   * render a broken image.
   *
   * The live composites read this to route such an id to the daemon even for an override-free
   * browse (there is no baked PNG to replay — see [CatalogLiveRouting.daemonIdForRender]), and the
   * viewer can badge the card as live-only. Empty for every ordinary session.
   */
  val liveOnlyPreviewIds: Set<String>
    get() = emptySet()

  /**
   * The light/dark mode [previewId]'s **baked** pixels are drawn in, or null when this session
   * cannot name one — see [ServeBakedTheme].
   *
   * The routing predicates ask the host rather than the id alone, because only the session knows
   * what its catalog publishes: an untagged sticker is the light half of a folded pair exactly when
   * the `__dark` twin is published beside it, and that is a fact about the manifest, not about the
   * string. A host that carries no such manifest keeps the id-only answer, which is the
   * conservative one — an unnamed theme routes a `uiMode` request to a real render rather than
   * replaying a sticker whose mode nothing established.
   */
  fun bakedTheme(previewId: String): UiMode? = ServeBakedTheme.token(previewId)

  /**
   * The Remote Compose player [previewId]'s **baked** pixels were drawn with, or null when this
   * session cannot name one.
   *
   * Both the routing predicates ([CatalogLiveRouting.overridesAffectRender]) and the
   * published-parity shortcut in the HTTP layer need this: when a request names the player that
   * already drew the baked PNG, the snapshot answers it exactly and the parameter is a no-op, which
   * is what lets a default link stop carrying one. Naming any OTHER player is a genuine re-render.
   *
   * It is a question put to the host because the answer is a fact about the session's **manifest**,
   * not about the id. A capture goes through `RemoteOverridablePreview`, which defaults to
   * [RemoteComposePlayerKind.EMBEDDED] — but a preview pinning the view-backed lane with
   * `@PreviewWrapper(RemoteViewPreviewWrapper::class)` does not, and only whoever holds the
   * manifest knows which this is.
   *
   * **The default is null: not "no player", but "this session cannot say".** Both unknowns fail the
   * same safe way — every `rcPlayer` survives as a genuine, refusable override, exactly as a null
   * [bakedTheme] keeps every `uiMode` routing to a real render — and the cost is a redundant query
   * parameter rather than another player's pixels under a confident 200. Guessing the common answer
   * here is precisely the mistake this seam exists to stop: it is right for every catalog we
   * publish today and silently wrong for the one preview shape that matters.
   *
   * [ServeBundleHost] overrides it, because it holds the two manifests that can answer — a bundle's
   * root `previews.json` (which records the pin) and a published catalog's
   * [ServeCatalogStore.PreviewParamsMeta.capturePlayer] (which records the player outright, once an
   * exporter carries it). A catalog published before that field existed reads back null and keeps
   * naming its player, which is the behaviour that predates this seam.
   */
  fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? = null

  /** Human label for the tenant (module Gradle path, `module@rev`, or a bundle name). */
  val label: String

  /**
   * Whether editing an override actually re-renders. `true` for a daemon-backed host
   * ([ServeRenderHost]); `false` for a static pre-rendered bundle ([ServeBundleHost]) that can only
   * replay the baked PNGs — the viewer then shows the preview's declared knobs as disabled,
   * informational controls.
   */
  val canApplyOverrides: Boolean
    get() = false

  /**
   * Whether the host can produce a **freshly rendered** snapshot when an override is supplied —
   * even if the *default* (override-free) snapshot lane is baked. Governs whether the viewer offers
   * the author-declared knob controls as live (an edit re-renders via `/render`) rather than
   * disabled, informational ones. It defaults to [canApplyOverrides], so a plain daemon host (both
   * true) and a plain static bundle (both false) are unchanged. A trusted-catalog live session
   * ([ServeCatalogLiveHost]) is the exception: `canApplyOverrides = false` (browsing stays baked
   * and instant) but `canRenderOverrides = true` — an override-bearing `/render` re-renders through
   * the carried daemon on demand, so a `?knob.<key>=…` (or display-axis) URL returns fresh pixels.
   */
  val canRenderOverrides: Boolean
    get() = canApplyOverrides

  /**
   * Per-preview refinement of [canRenderOverrides]: whether *this* preview can be re-rendered with
   * an override. Defaults to the host-wide [canRenderOverrides] (true for every preview on a plain
   * daemon host, false on a static bundle). A trusted-catalog live session ([ServeCatalogLiveHost])
   * overrides it: only previews with a daemon twin can re-render, so an unaliased (e.g.
   * Android-only) variant returns false — the viewer then shows its override controls (knobs, App
   * theme) as disabled/informational rather than enabled-but-dead (an override on such a preview
   * falls back to the baked PNG, which ignores it).
   */
  fun canRenderOverridesFor(previewId: String): Boolean = canRenderOverrides

  /**
   * The **named-value overrides that apply a declared theme to an already-recorded document** —
   * `<name>` to a colour literal (`#RRGGBB`), keyed by the document's own state names.
   *
   * This is what lets a theme reach a preview whose composable is gone. A Remote Compose document
   * emits the roles it draws through as named state (`USER:WearM3.primary` and friends) rather than
   * folding them into constants, so the player's `setNamedColorOverride` can re-theme a *replayed*
   * document with no recomposition. Seeding these is that operation, and it is the only route a
   * theme has on a session that cannot recompose — a published catalog whose module bytecode was
   * dropped at pack time.
   *
   * Empty (the default) means this host publishes no such mapping for [providerFqn], and a
   * `themeProvider` render of a replayed preview stays the terminal refusal it is today. That is
   * deliberate: applying nothing and answering `200` would be the #3449 failure — a render that
   * claims a theme it never applied, indistinguishable from one where the theme changed nothing.
   *
   * Only consulted for previews that replay. A session that can recompose applies the provider by
   * re-running the composable, which reaches everything a theme does — including the typeface,
   * which has no named value to carry it.
   */
  fun themeReplayColors(providerFqn: String): Map<String, String> = emptyMap()

  /**
   * The declared themes a **replayed** preview can actually be rendered under — those this host
   * publishes a [themeReplayColors] mapping for.
   *
   * Per theme, not per host: a catalog may publish mappings for some of its themes and not others,
   * and a theme that moves only typography legitimately has no colours to seed at all. Offering the
   * whole declared set because *one* of them is mapped puts the unmapped ones back on the terminal
   * 409 the gate exists to prevent.
   */
  fun replayableThemes(): List<ServeTheme> = declaredThemes.filter {
    themeReplayColors(it.providerFqn).isNotEmpty()
  }

  /**
   * Maximum browser-side concurrency a short-lived themed-thumbnail burst may request. A plain
   * daemon has one render lock, so the default remains serial. A composite backed by independent
   * per-preview daemons may opt into a larger temporary burst; the HTTP server still clamps it to
   * its render slots and shares that burst across every active page for the same catalog.
   */
  val themeRenderBurstCapacity: Int
    get() = 1

  /**
   * Return an already-materialised PNG without entering the HTTP render admission queue. Hosts with
   * no cache return null. [render] remains the authoritative path and must recheck its cache after
   * admission to close the lookup/render race.
   */
  fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Serve [previewId] from pixels **already on this box** — a baked PNG on disk — or null when
   * answering would need work: a daemon render, or a fetch for a preview whose pixels haven't
   * arrived yet.
   *
   * This is what keeps a busy, mostly-browsing box responsive. Every `/render` request otherwise
   * competes for the same small pool of global render slots, so a handful of cold daemon renders —
   * which can take a minute each — head-of-line block dozens of readers whose answer is a local
   * file, and those readers eventually 503. A host that can answer from disk says so here and is
   * served without ever entering admission.
   *
   * Must be cheap and non-blocking: no daemon, no network, no waiting. Returning null is always
   * safe — the caller falls back to the admitted [render] path.
   */
  fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? = null

  /**
   * Make [previewId]'s published pixels local, if this host can fetch them and has not already.
   *
   * The write half of [bakedRender]'s read: that one is local-only by design, so a preview whose
   * PNG has never been fetched answers null and its card falls back to the full-resolution
   * `/render/` URL. Calling this is what lets a *later* [bakedRender] — and therefore
   * [ServeHeroImages.gridThumbFor] — succeed.
   *
   * **Blocking, and never to be called on a request thread.** It performs the delivery-branch fetch
   * that `bakedRender` exists to avoid doing there; [ServeThumbWarmer] is what calls it, off a
   * bounded background pool.
   *
   * Fetches published bytes only — it must never render, and never wake a suspended daemon.
   * Best-effort throughout: an unknown id, a deferred (live-only) preview, a host with no fetch
   * source, or a failed fetch are all silent no-ops, and a failure is not remembered, so the next
   * attempt retries. Default does nothing, which is right for every host with no published bytes
   * behind it.
   */
  fun warmBakedRender(previewId: String) {}

  /**
   * [previewId]'s baked render size in pixels, read from the PNG header alone — no decode, no
   * fetch, no daemon — or null when the pixels aren't already on this box.
   *
   * Exists so a page can advertise `og:image:width`/`height` (see [ServeWeb.UnfurlMetadata]) for
   * free. The dimensions are 8 bytes of a PNG's IHDR chunk, so the alternative — reading the whole
   * render through [bakedRender] just to measure it — would pull ~90 KB off disk on every page
   * build to learn two integers. Null is always safe: the page then omits the dimensions and the
   * unfurler measures the image itself.
   */
  fun bakedRenderSize(previewId: String): Pair<Int, Int>? = null

  /**
   * The bytes of one published animated capture, or null when this host has none to serve.
   *
   * Defaults to null so every host that isn't a published catalog — a daemon, a plain bundle —
   * simply has no motion lane rather than needing to say so. [extension] is part of the request
   * because the two formats aren't interchangeable to a browser, and it is validated against what
   * the catalog declared rather than trusted, so a request can't choose its own content type.
   */
  /**
   * One published capture, with the reason a failure failed.
   *
   * The reason is the point: a host that cannot distinguish a capture the catalog never published
   * from one the delivery branch is currently refusing to serve leaves the route with nothing to
   * say but 404, and the reader with "could not be loaded" for both. Defaults to
   * [BranchFetch.NotFound] — a host with no branch behind it publishes no captures.
   */
  fun motionRead(motionId: String, extension: String): BranchFetch = BranchFetch.NotFound

  /**
   * A visitor is present on this session's pages right now (see `POST /api/presence`) — get its
   * live render lane ready, if it has one and isn't ready already.
   *
   * Leasing the session is what keeps it from being reaped; this is the other half, for the common
   * case where the visitor has only browsed **prebaked** pixels and so has never woken a daemon at
   * all. Their first live render — picking a declared theme, opening a knob — would otherwise pay a
   * cold start (~68 s on Android) that no page-level retry outlasts. Warming while they read the
   * grid turns that into the warm path (~350 ms).
   *
   * Best-effort, non-blocking and idempotent: called every few minutes by every open tab, so an
   * implementation must return immediately and do nothing at all once its lane is ready. Hosts with
   * no live lane (a static baked bundle) keep the default no-op.
   */
  fun keepLiveWarm() {}

  /**
   * Aggregate render-performance counters for this host's live render lane, surfaced on `/status`
   * + `/status.json` (`runningServers[].renderStats`). Null when the host has no live render lane
   *   to measure — a static baked bundle never renders. Daemon-backed hosts ([ServeRenderHost])
   *   record every serve-side render round-trip; composites ([ServeCatalogLiveHost]) forward their
   *   carried daemon's stats.
   */
  fun renderPerfStats(): RenderPerfSnapshot? = null

  /**
   * This lane's open render breaker, or null while it is rendering normally (the healthy case, and
   * the default for a host with no live lane to break). A non-null value means the host has
   * **stopped attempting renders** — a linkage fault it can never recover from, or a sustained
   * failure rate — and is answering requests with [RenderBreakerSnapshot.reason] instead. Callers
   * that schedule background render work must consult it and stand down: feeding a broken renderer
   * is what burned 275s of render gate and a ~7h ETA in issue #3448.
   */
  fun renderBreaker(): RenderBreakerSnapshot? = null

  /**
   * Bounded child-daemon pools owned by this host, surfaced on `/status.json` so production
   * monitors can distinguish "one catalog daemon is up" from "a catalog daemon plus N per-preview
   * daemons are resident". Empty for ordinary hosts.
   */
  fun daemonPoolStats(): List<DaemonPoolSnapshot> = emptyList()

  /** Server-side catalog theme optimization progress, or null for hosts without that cache. */
  fun themeOptimizationSnapshot(): ThemeOptimizationSnapshot? = null

  /** Memory occupancy of this catalog generation's durable rendered-preview cache. */
  fun catalogRenderCacheSnapshot(): CatalogRenderCacheSnapshot? = null

  /** True while low-priority work still needs this host resident. */
  val backgroundWorkActive: Boolean
    get() = false

  /**
   * Whether this session's daemon can actually apply the **one-handed gesture** override
   * (`overrides.gestures`) — i.e. the daemon advertises `"gestures"` in its capabilities. Only the
   * Android (Robolectric) backend does; the desktop backend behind a CMP `serve` / the published
   * catalogs silently ignores it. The viewer gates the "Show gesture hints" control on this so a
   * `@GestureHintPreview` component doesn't show a toggle that would do nothing on a desktop-backed
   * session. Defaults false (a static bundle has no daemon; a desktop daemon doesn't support it).
   */
  val gesturesRenderable: Boolean
    get() = false

  /**
   * Whether [renderSvg] can actually produce a `compose/figma-svg` export for this session's
   * previews — a daemon-backed host always can, a static bundle only when it carried baked
   * `figma/<slug>.svg` vectors (a design catalog). Drives whether the viewer offers a copyable SVG
   * download URL alongside the PNG one. Defaults to false (a plain bundle 404s the `.svg` lane).
   */
  val hasSvgExport: Boolean
    get() = false

  /**
   * Whether [renderSvg] can produce a `compose/figma-svg` export for **this specific** [previewId]
   * — a per-preview refinement of [hasSvgExport]. A static catalog advertises SVG globally as soon
   * as it carries a `figma/` dir, but an individual preview whose component slug has no baked
   * `figma/<slug>.svg` still 404s the `.svg` lane; the viewer gates its SVG control on this so it
   * isn't offered on a preview that would then render "failed" (issue #2352). Defaults to the
   * session-wide [hasSvgExport] — a daemon-backed host exports any of its previews.
   */
  fun hasSvgExportFor(previewId: String): Boolean = hasSvgExport

  /** Whether this host can produce the tall raster `render/scroll/long` export. */
  val hasScrollExport: Boolean
    get() = false

  /** Per-preview refinement of [hasScrollExport]. */
  fun hasScrollExportFor(previewId: String): Boolean = hasScrollExport

  /**
   * Whether a **live daemon stream** ("Live (stream)") is available for this session — distinct
   * from [canApplyOverrides], which governs whether the *snapshot* lane re-renders on override
   * edits. The two usually coincide (a plain [ServeRenderHost] has both; a static [ServeBundleHost]
   * neither), so this defaults to [canApplyOverrides]. A trusted-catalog live session
   * ([ServeCatalogLiveHost]) is the exception: its snapshots stay baked (so browsing is instant and
   * stays on the published pixels) while the live stream is still offered on demand —
   * `canApplyOverrides = false` but `hasLiveStream = true`.
   */
  /**
   * How many render subprocesses this host is actually carrying right now.
   *
   * Distinct from [daemonStarted], which is a host-level "is anything up" used to keep `/status`
   * from listing catalogs that own no process. This is a count, and a host with no daemon lane at
   * all — a static baked bundle — must report 0 rather than inherit a truthy default, or the page
   * would tell a visitor a purely static catalog has a render server connected.
   */
  val daemonProcessCount: Int
    get() = 0

  /**
   * Whether this host's daemon subprocess actually exists yet.
   *
   * A daemon-backed host opens its session on first real use, so a *registered* catalog is not a
   * *running* daemon — and `/status` must not conflate them, or the running count stays pinned to
   * the catalog count and says nothing about what the box is really carrying. Defaults to true for
   * every host with nothing to defer (baked bundles, and daemon hosts handed an already-open
   * session), so their reporting is unchanged.
   */
  val daemonStarted: Boolean
    get() = true

  val hasLiveStream: Boolean
    get() = canApplyOverrides

  /** Render [previewId] at [overrides] (cached where possible). */
  fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome

  /**
   * Why this host has permanently given up on rendering [previewId] at [overrides], or null while
   * the render may still succeed. Lets the HTTP layer answer a repeat request with a **terminal**
   * status instead of the retryable one a transient [RenderOutcome.Busy] earns — a preview whose
   * live render always fails (a `painterResource` whose drawable never made it into the bundle,
   * say) otherwise reads to the browser as "try again", forever. Hosts with no such memory report
   * null and behave exactly as before.
   */
  fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? = null

  /**
   * Close daemon subprocesses this host has held idle for [idleMillis] without closing the host
   * itself, returning how many were closed. Default: nothing to shed.
   *
   * A **pinned** session (a registered bundle/catalog) is deliberately never suspended by
   * [ServeSessionRegistry.suspendIdle] — it must stay listed and instantly resumable. That
   * protection was reaching further than intended: it also kept every daemon process hanging off
   * the host alive for the life of the server, so a catalog that once served a burst sat on its
   * replica and per-preview pools forever. This is the narrower action — shed the processes, keep
   * the host.
   */
  fun releaseIdleDaemons(idleMillis: Long): Int = 0

  /**
   * Render a request admitted by the server's short-lived catalog theme lease. Hosts that cannot
   * parallelise keep the ordinary [render] behaviour. A catalog backed by a replica pool overrides
   * this to borrow an independent shared daemon, so only explicitly leased batches grow the pool.
   */
  fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    render(previewId, overrides)

  /**
   * The captured Remote Compose document bytes for [previewId] — the bundle's `ir/<id>.rc` sidecar
   * — or null when this host carries none. Served over `GET /render/<id>.rc` so an in-browser
   * Remote Compose player can render the document client-side (the browser counterpart of the
   * daemon render). Defaults to null: only a bundle host that carries the `ir/` sidecars returns
   * bytes; a daemon-only host has none.
   */
  fun remoteComposeDoc(previewId: String): ByteArray? = null

  /**
   * Whether [previewId] carries a captured Remote Compose document ([remoteComposeDoc]) the viewer
   * can render client-side in its `<canvas>` lane. Drives whether the viewer offers the "RC
   * (browser)" toggle and its live-in-browser knob controls for this preview. Defaults to reading
   * [remoteComposeDoc]; a bundle host overrides it with a cheap existence check so the per-preview
   * page render doesn't read the whole doc just to know it's there.
   */
  fun hasRemoteComposeDoc(previewId: String): Boolean = remoteComposeDoc(previewId) != null

  /**
   * The **published** Remote Compose player comparison this catalog carries — every player's render
   * of every `ir/<id>.rc` document plus the build-time pixel diffs, as the offline `rc-compare`
   * pipeline computed them (see [ServeRcCompare]). Drives the `?format=rc` half of the compare
   * page, which replays these instead of rendering documents in the visitor's browser: one player
   * runs in a browser, five ran offline, and replaying costs a few `<img>` loads.
   *
   * Null for every host but a catalog whose delivery branch published one — a plain uploaded
   * bundle, or a system that ships no `ir/<id>.rc`, keeps the client-rendered lane.
   */
  fun rcCompare(): RcCompareManifest? = null

  /**
   * Bytes for one staged rc-compare lane image ([RcCompareCell.render] / [RcCompareCell.diff]),
   * served over `GET /<system>/rc-compare/<lane>/<slot>.png`. Null for anything the host didn't
   * stage — the name vocabulary is fixed, so this is never a general file read.
   */
  fun rcCompareImage(name: String): ByteArray? = null

  /**
   * The **published** render of [previewId] by [backend], from this catalog's `rc-compare` staging
   * — or null when nothing was staged for that pair.
   *
   * The offline parity pipeline draws every `ir/<id>.rc` document with every player, so these bytes
   * already exist for exactly the browse a viewer performs when it opens on its default player.
   * Serving them makes that page cost what an override-free browse costs: a map lookup and a file
   * read, with no daemon, no render slot and no admission.
   *
   * Only ever an answer to a **bare** player selection. A request that also carries a font scale, a
   * knob or a theme is asking for pixels the parity run never drew, and the caller must route it to
   * the renderer as before — see [ServeHttpServer]'s use, which checks that before calling.
   */
  /**
   * The backends [previewId] has a **published** render for — the parity run's staging, in
   * [RcPlayerBackend.UNIVERSE] order.
   *
   * Folded into [enabledRcPlayersFor] so the picker offers exactly what the host can produce.
   * Without it the capability list and the render lane disagreed: a static bundle carrying staged
   * rasters would answer a hand-typed `?rcPlayer=cmp-android` perfectly well while showing that
   * option greyed, and Catalog mode would open on JS because its preferred embedded default was not
   * in the enabled set.
   *
   * Reads the manifest, not the images: this runs per preview while building a page, and whether a
   * lane was staged is a field on the row.
   */
  fun stagedRcPlayers(previewId: String): List<RcPlayerBackend> {
    val row = rcCompare()?.rows?.firstOrNull { it.previewId == previewId } ?: return emptyList()
    return RcPlayerBackend.UNIVERSE.filter { backend ->
      val cell = backend.rcCompareLane?.let { row.lanes[it] }
      cell != null && cell.rendered && cell.render.isNotEmpty()
    }
  }

  fun publishedRcPlayerRender(previewId: String, backend: RcPlayerBackend): ByteArray? {
    val lane = backend.rcCompareLane ?: return null
    val cell = rcCompare()?.rows?.firstOrNull { it.previewId == previewId }?.lanes?.get(lane)
    val name = cell?.takeIf { it.rendered }?.render?.takeIf { it.isNotEmpty() } ?: return null
    return rcCompareImage(name)
  }

  /**
   * True while the published comparison may still be arriving — the catalog's background staging
   * lane has not reported an outcome yet, so [rcCompare] returning null does not yet mean "this
   * catalog has none".
   *
   * The compare page reads this to decide whether it may be cached. Its shape (player wall vs the
   * in-browser lane) is decided by [rcCompare], and a short-lived edge cache would otherwise pin
   * the pre-manifest shape for minutes after the lanes had landed. False everywhere else: a host
   * with no staging lane is never provisional.
   */
  fun rcComparePending(): Boolean = false

  /**
   * The pixel size and density a **cmp-jvm** render of [previewId] should use — matched to the
   * baked View-player capture so the desktop-player PNG lands at the same size the viewer shows the
   * other lanes at. Null when this host cannot supply one (no captured doc, or size metadata
   * missing), in which case the cmp-jvm chip stays disabled. Only a bundle/catalog host that
   * carries both the `ir/<id>.rc` sidecar and the baked `previews/<id>.png` returns a spec.
   */
  fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? = null

  /**
   * Whether the server-side **cmp-jvm** lane can render [previewId]: the host carries the captured
   * document and a render spec, and the isolated desktop-player subprocess is installed
   * ([RcJvmServerRenderer.isAvailable]). Hosts fold this into [enabledRcPlayersFor].
   */
  fun supportsCmpJvm(previewId: String): Boolean =
    hasRemoteComposeDoc(previewId) &&
      remoteComposeRenderSpec(previewId) != null &&
      RcJvmServerRenderer.isAvailable()

  /**
   * The Remote Compose render backends the viewer may offer for [previewId] as **enabled** options
   * — the subset of the fixed [RcPlayerBackend.UNIVERSE] this host can actually produce pixels
   * through. The viewer renders every backend as a chip and enables the ones returned here; the
   * rest (e.g. [RcPlayerBackend.CMP_JVM] when its sidecar is not installed) are shown disabled, so
   * an unavailable lane remains visible without pretending it works.
   *
   * Empty for a non–Remote Compose preview (the viewer then shows no backend selector at all).
   * Defaults to the client-side [RcPlayerBackend.JS] lane whenever [hasRemoteComposeDoc] is true —
   * the in-browser player needs only the `.rc` bytes, so any host that carries the document
   * supports it. A daemon-backed Android host ([ServeRenderHost]) adds the server-side
   * [RcPlayerBackend.JAVA] / [RcPlayerBackend.CMP_ANDROID] lanes (they ride
   * `remoteCompose.player`).
   */
  /**
   * [bakedRcPlayer] as the backend that names it, or null when this session cannot say.
   *
   * Every `enabledRcPlayersFor` must union this in, including the two that override the default: a
   * bare URL serves those pixels, so the picker has to offer that lane even when the parity run
   * staged no column for it — and it never does for [RcPlayerBackend.JAVA], whose `rcCompareLane`
   * is null. A host that has just established which player drew its snapshot, and then greys out
   * that exact chip while the snapshot sits on the stage, is disagreeing with itself.
   *
   * Factored here rather than spelled out at each site, because three copies of one fact drifting
   * apart is the bug this whole seam exists to stop.
   */
  fun bakedRcPlayerBackend(previewId: String): RcPlayerBackend? =
    bakedRcPlayer(previewId)?.let { kind ->
      RcPlayerBackend.entries.firstOrNull { it.playerKind == kind }
    }

  fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> =
    if (hasRemoteComposeDoc(previewId)) {
      buildList {
        add(RcPlayerBackend.JS)
        // The desktop embedded player renders the same `.rc` server-side via an isolated
        // subprocess; enable it wherever the sidecar player is installed and a render spec exists.
        if (supportsCmpJvm(previewId)) add(RcPlayerBackend.CMP_JVM)
        // …and every player the parity run already drew. Those need no renderer at all, so a host
        // that carries the staging can offer them however little else it can do.
        // cmp-wasm is an interactive iframe lane, not a staged-raster lane. Advertising it from
        // parity output alone makes the viewer open /rc-wasm/ even when no Wasm distribution is
        // installed; the published raster remains available to the comparison surface.
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
   * Whether this host's live render lane honours the Remote Compose **player** override
   * (`remoteCompose.player`) — i.e. a daemon carrying the Android Remote Compose runtime, the only
   * backend where selecting the server-side VIEW ([RcPlayerBackend.JAVA]) vs EMBEDDED
   * ([RcPlayerBackend.CMP_ANDROID]) player actually changes pixels. The desktop backend has no
   * Remote Compose runtime and silently ignores it; a static bundle has no daemon at all. Gates
   * whether [enabledRcPlayersFor] offers the server-side lanes, so the viewer never shows a backend
   * chip that would re-render to the same image. Defaults false.
   */
  val remoteComposePlayerSelectable: Boolean
    get() = false

  /**
   * Render [previewId] at [overrides] and return its figma-svg export, or [SvgOutcome.NotFound]
   * when this host can't produce SVG. Defaults to `NotFound`: only the daemon-backed
   * [ServeRenderHost] overrides this — a static [ServeBundleHost] has no daemon to export one.
   */
  fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome = SvgOutcome.NotFound

  /**
   * The figma-svg export tailored for **web/document** viewing (`?mode=web`): where possible the
   * hybrid raster crops are *linked* (an `<image href>` to the crop's public home — the catalog's
   * delivery branch) instead of base64-embedded, so the served SVG stays kilobytes. Defaults to the
   * self-contained [renderSvg]: a host with no public raster home (a live daemon render whose crops
   * exist only on its disk, a plain uploaded bundle) keeps embedding — the HTTP layer's
   * font-`@import` rewrite still applies on top either way. Only the catalog-backed
   * [ServeBundleHost] (which knows the `repo@branch` its crops were published to) overrides this.
   */
  fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    renderSvg(previewId, overrides)

  /**
   * Render [previewId]'s **full-page** figma-svg export (`compose/figma-svg-long`) at [overrides] —
   * the whole scrollable screen as one editable SVG (a virtualised `LazyColumn` rendered at an
   * expanded viewport so every row composes), or [SvgOutcome.NotFound] when this host can't produce
   * it. Defaults to `NotFound`: only the daemon-backed [ServeRenderHost] overrides it (the tall
   * re-render needs a daemon; a static bundle has none). A non-scrolling preview yields its
   * ordinary viewport SVG. See [docs/design/SCROLLING_SVG.md].
   */
  fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * Render [previewId]'s full-page raster scroll capture (`render/scroll/long`) at [overrides], or
   * [RenderOutcome.NotFound] when this host has no daemon-backed scroll producer.
   */
  fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    RenderOutcome.NotFound

  /**
   * Render [previewId] at [overrides] and return its declared preview slots as JSON, or
   * [SlotsOutcome.NotFound] when this host can't extract them. Defaults to `NotFound`: only the
   * daemon-backed [ServeRenderHost] overrides this — a static [ServeBundleHost] has no daemon to
   * capture a semantics tree.
   */
  fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome =
    SlotsOutcome.NotFound

  /**
   * Whether this host can produce the accessibility products the viewer's overlay + legend draw
   * from (`a11y/hierarchy`, plus `a11y/atf` / `a11y/touchTargets` where the backend has them).
   * Defaults to false — only the daemon-backed [ServeRenderHost] carries an a11y producer, and a
   * static bundle has no daemon to walk a semantics tree.
   */
  val hasA11yOverlay: Boolean
    get() = false

  /** Per-preview accessibility availability; composite hosts may only map part of a catalog. */
  fun hasA11yOverlayFor(previewId: String): Boolean = hasA11yOverlay

  /**
   * Fetch [previewId]'s merged accessibility products at [overrides] as JSON (`{previewId, nodes,
   * findings, touchTargets}`), or [A11yOutcome.NotFound] when this host can't produce them. See
   * [ServeRenderHost.renderA11y].
   */
  fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome = A11yOutcome.NotFound

  /**
   * Whether this host can derive the viewer's typography, theme and layout inspection layers from a
   * render's `compose/semantics` tree ([renderAnnotations]). Tracks [canApplyOverrides]: capturing
   * a semantics tree needs a daemon, exactly like [renderSlots].
   */
  val hasDesignAnnotations: Boolean
    get() = canApplyOverrides

  /**
   * Per-preview annotation availability, the twin of [hasA11yOverlayFor]: a composite host may
   * front a whole catalog while only part of it has a daemon twin to capture semantics from, and
   * offering the Typography / Theme / Layout layers on a preview whose fetch can only 404 is
   * exactly the dead control [hasDesignAnnotations] exists to avoid.
   */
  fun hasDesignAnnotationsFor(previewId: String): Boolean = hasDesignAnnotations

  /**
   * Whether this host can answer `.annotations` for [previewId] from the catalog's **published**
   * annotations rather than from a daemon-captured semantics tree — see
   * [ServeBundleHost.renderAnnotations].
   *
   * Separate from [hasDesignAnnotationsFor] because the two lanes carry different layers, and the
   * viewer offers a checkbox per layer. A published bundle's preview annotations are typography (a
   * producer measures them off the frame); the theme attributes are projected live from a semantics
   * tree and nothing authors them into a bundle. Folding the two together would either hide the
   * Typography layer on a catalog that published it, or offer a Theme checkbox with nothing behind
   * it. Defaults to false — a host with no published annotation manifest has neither.
   */
  fun hasPublishedTypographyFor(previewId: String): Boolean = false

  /**
   * Render [previewId] at [overrides] and return its typography + theme inspection layers as JSON
   * (`{previewId, annotations, tags}`), or [AnnotationsOutcome.NotFound] when this host has no
   * daemon. See [ServeRenderHost.renderAnnotations].
   *
   * `tags` is [ServeSemanticsTags]' `testTag → {count, bounds}` index over the same semantics
   * payload the annotations are projected from — the element identity a scoped parity acceptance
   * targets. Sharing this response is what keeps the two projections describing one frame; it does
   * *not* couple either of them to the PNG a client already fetched. See
   * [ServeRenderHost.renderAnnotations] for what that still owes.
   *
   * [layers] names the inspect layers the caller will actually draw ([AnnotationKind.KNOWN]), or
   * null for "all of them" — the pre-`layers=` behaviour every unscoped caller still gets. It is a
   * routing hint, not a filter contract: a host may return a superset (the daemon projects all
   * three off one capture, so narrowing would cost a second render and save nothing), but must
   * never return a payload missing a layer that was named. What it buys is
   * [AnnotationKind.publishedLayersSuffice] — a typography-only request can be answered off a
   * published bundle without a daemon, which is worth 16-22s on an idle catalog.
   */
  fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>? = null,
  ): AnnotationsOutcome = AnnotationsOutcome.NotFound

  /**
   * Whether [renderAnnotations] describes the **same frame** an override-free `/render/<id>.png`
   * replays, rather than one produced for the annotations request itself.
   *
   * True only for a host whose annotations lane is a pure replay of published data
   * ([ServeBundleHost]). False by default, and deliberately so: getting this wrong the safe way
   * costs an affordance, and getting it wrong the other way records a region from a frame the
   * reporter never saw as an acceptance's authoring-time baseline.
   *
   * **It is not implied by `canApplyOverrides == false`.** That names the PNG lane, and both live
   * catalog wrappers keep the PNG baked for an override-free browse while asking their daemon for
   * annotations *first*, falling back to published only on `NotFound`. A baked frame with live
   * annotations is exactly the mismatch, so those hosts leave this false and the focused comparison
   * withholds annotation-box selection on them — the layers still draw, since being a render out of
   * date costs a reading aid nothing.
   */
  val annotationsFollowBakedFrame: Boolean
    get() = false

  /**
   * Join the shared live stream for [previewId], or `null` when this host has no live lane (the
   * snapshot fallback is used instead — always the case for [ServeBundleHost]).
   *
   * [onUnavailable] is invoked (once, before the `null` return) with a short human-readable reason
   * when the live lane can't be opened — the daemon's original failure (e.g. `interactive session
   * already held`, `previewSpecResolver returned null`, a `stream/start` timeout) or "no live
   * daemon twin for this variant". The caller surfaces it so the viewer shows *why* it fell back to
   * re-rendered snapshots instead of the opaque "input requires a live stream". Not called on
   * success (a non-null return).
   */
  fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?

  /** Count of live upstream streams (0 for hosts without a live lane). */
  fun activeStreamCount(): Int
}

/**
 * [ServeHost.motionRead]'s bytes, for callers that only care whether there are any.
 *
 * An **extension**, deliberately, rather than a second interface member: with both on the interface
 * an implementor could override this one, see its override silently ignored (every caller goes
 * through [ServeHost.motionRead]), and ship a host that serves no captures — which is the shape of
 * the bug that made the Motion lane 404 in the first place, where `ServeCatalogLiveHost`
 * implemented a pair and missed a member of it. An extension cannot be overridden, so there is one
 * place to implement and no way to implement the wrong one.
 */
fun ServeHost.motionBytes(motionId: String, extension: String): ByteArray? =
  motionRead(motionId, extension).bytesOrNull
