package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [ServeHost] that fronts a trusted design-system catalog with its baked-PNG render **and** an
 * opt-in live daemon stream, bridging the two id namespaces so the published catalog URLs keep
 * working.
 *
 * ## Why
 *
 * A daemon knows its previews by the function-based descriptor id it discovered
 * (`FilledButton_Dark`), but the published `design-artifacts/<system>` catalog links + image routes
 * use the componentId-slug id (`button-filled__ideal__default__dark`) — and the two don't match.
 * Registering a bare [ServeRenderHost] for a catalog therefore 404s every published `/p/<id>` deep
 * link and `/render/<id>.png` thumbnail, drops the catalog's title/trust badge (only a
 * [ServeBundleHost] carries those), and — because a daemon host reports `canApplyOverrides = true`
 * — flips the viewer into dynamic mode, so ordinary browsing renders every preview through the
 * daemon (cold-start "rendering…") instead of showing the instant baked PNG.
 *
 * ## What
 *
 * This composite keeps the [baked] [ServeBundleHost] as the whole **snapshot** surface —
 * [previews], the grid, deep links, thumbnails, title, and trust badge all resolve to the baked
 * catalog exactly as a static catalog would, and every snapshot is the baked PNG (so browsing never
 * wakes the daemon). The [live] daemon is offered only through the **"Live (stream)"** toggle:
 * [hasLiveStream] is true, so the viewer enables the checkbox, and [subscribeStream] maps the
 * catalog id to the daemon preview id via [alias] and streams it. An id with no alias (an
 * Android-only variant the desktop daemon can't render) simply has no stream and stays baked.
 *
 * The net effect: the published catalog behaves exactly as before (static, trusted, instant), plus
 * the CMP components the desktop daemon can run gain an interactive live stream on demand.
 *
 * ## Per-preview live lane (default, with monolithic fallback)
 *
 * When [perPreviewResolve] is supplied, an override-bearing render/stream first tries to resolve a
 * daemon that re-renders **only that one preview** from its own per-preview bundle
 * (`bundle/previews/<daemon-id>.png`, materialised + pooled by the caller). This is the default
 * render path — small, addressable, per-preview daemons the pool reaps when idle — so the
 * per-preview bundles the delivery branch ships are exercised routinely. It falls back to the
 * monolithic [live] `liveBundle` daemon when a per-preview daemon can't be resolved (fetch /
 * materialise failed, or the preview ships no per-preview bundle), and both fall back to [baked]
 * when the id has no daemon twin at all. So the worst case is exactly the pre-per-preview
 * behaviour; the composite never regresses. With [perPreviewResolve] absent it is the plain
 * monolithic-only host described above.
 */
class ServeCatalogLiveHost(
  /**
   * Catalog id (`button-filled__ideal__default__dark`) → daemon preview id (`FilledButton_Dark`).
   */
  private val alias: Map<String, String>,
  /** The daemon-backed host, keyed by daemon preview ids (the [alias] values). */
  private val live: ServeHost,
  /** The static baked-PNG host, keyed by catalog ids (the browse + snapshot surface). */
  private val baked: ServeHost,
  /**
   * Resolve a daemon-backed host that re-renders the given **daemon-preview id** from its own
   * per-preview bundle, or null when none is available. Tried FIRST for an alias-mapped id carrying
   * a pixel-changing override; a null result falls back to the monolithic [live] daemon. The
   * returned host is owned + pooled by the caller (this host never closes it), so repeated calls
   * for the same id should return the pooled instance. `null` (the default) disables the
   * per-preview lane, leaving the plain monolithic-only host.
   */
  private val perPreviewResolve: ((daemonId: String) -> ServeHost?)? = null,
  /** Live upstream stream count across the pooled per-preview daemons (supplied by the pool). */
  private val perPreviewStreamCount: () -> Int = { 0 },
  /**
   * Render-latency snapshots of the pooled per-preview daemons (supplied by the pool, mirroring
   * [perPreviewStreamCount]). Folded into [renderPerfStats] — the per-preview lane is the DEFAULT
   * render path ([liveHostFor] tries it first), so the catalog's `/status` roll-up must include it
   * or it misses most real renders.
   */
  private val perPreviewRenderStats: () -> List<RenderPerfSnapshot> = { emptyList() },
  /** Pool occupancy snapshots for `/status.json`, supplied by the pool. */
  private val perPreviewPoolStats: () -> List<DaemonPoolSnapshot> = { emptyList() },
  /**
   * Serve the baked vector immediately and warm the daemon in the background rather than blocking a
   * browse on a cold (possibly minutes-long, esp. Android/Robolectric) first render — see the
   * cold-start note below. Off by default so the synchronous #2448 per-variant guarantee (and its
   * tests) are unchanged; a deploy fronting a slow-cold-starting catalog sets it on via
   * `-Dcomposeai.serve.warmInBackground=true`.
   */
  private val warmInBackground: Boolean =
    System.getProperty("composeai.serve.warmInBackground")?.toBooleanStrictOrNull() ?: false,
  private val catalogThemeCache: CatalogThemeCache = CatalogThemeCache(),
  /**
   * Whether to **eagerly** fill [catalogThemeCache] on the idle pass below — off by default.
   *
   * The pass renders `previews × declaredThemes` for every catalog: hundreds of daemon renders,
   * server-wide, for pixels no visitor has asked for. Even parked behind [ServeBackgroundWork] it
   * competes with the work that is actually on a request path, and on the public box it is what
   * turned a quiet server into a permanently busy one. The *reactive* half of the cache is
   * untouched and is where the value was: a theme a visitor actually selects is still cached on
   * completion (see [cachedRender]), so re-selecting it stays instant.
   *
   * `-Dcomposeai.serve.themeOptimization=true` turns the eager pass back on for a deployment that
   * wants it.
   */
  private val themeOptimizationEnabled: Boolean =
    System.getProperty("composeai.serve.themeOptimization")?.toBooleanStrictOrNull() ?: false,
  private val serverIdleMillis: () -> Long? = { Long.MAX_VALUE },
  /**
   * Server-wide admission for the idle theme optimizer below. Shared by every catalog host in a
   * `serve` run, so their background passes take turns rather than each holding a live seat — see
   * [ServeBackgroundWork].
   */
  private val backgroundWork: ServeBackgroundWork = ServeBackgroundWork(),
  private val themeOptimizationIdleMillis: Long =
    System.getProperty("composeai.serve.themeOptimizationIdleMillis")?.toLongOrNull() ?: 30_000L,
  /**
   * Route snapshot renders to the shared monolithic daemon rather than the per-preview pool — see
   * [renderHostFor]. `-Dcomposeai.serve.sharedDaemonRenders=false` restores per-preview routing for
   * a deployment that wants each preview isolated at the cost of a cold start per card.
   */
  private val sharedDaemonRenders: Boolean =
    System.getProperty("composeai.serve.sharedDaemonRenders")?.toBooleanStrictOrNull() ?: true,
  private val clock: () -> Long = System::currentTimeMillis,
) : ServeHost {

  /**
   * Browse + snapshot surface is the baked catalog — its ids are the published catalog ids. The
   * author-declared knobs ([ServePreview.overrides]), however, are carried by the *daemon* previews
   * (read from the live bundle's `previews/<daemon-id>.overrides.json` sidecars, keyed by the
   * daemon descriptor id), not by the baked catalog images. So graft each mapped catalog preview's
   * knob declarations across from its daemon twin via [alias]; an unmapped (Android-only) preview
   * keeps the baked entry as-is (no live lane, no editable knobs).
   */
  override val previews: List<ServePreview> = mergeDeclaredKnobs(baked.previews, live.previews)

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    baked.designReferencesFor(previewId)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    baked.designReferenceRaster(referenceId)

  /**
   * The baked host's live-only (deferred) ids — previews it lists with no PNG behind them, which
   * the catalog publishes for on-demand render. Carried through so the routing below sends them to
   * the daemon on every request (there is nothing to replay) and `/api/previews` can badge them.
   */
  override val liveOnlyPreviewIds: Set<String> = baked.liveOnlyPreviewIds

  // ── Non-blocking cold start ────────────────────────────────────────────────────────────────────
  // The no-override SVG lane prefers the daemon's per-variant vector over the baked per-slug one
  // (the #2448 fix). But a daemon's FIRST render can be slow — a desktop/Skiko daemon warms in
  // seconds, an Android/Robolectric daemon's cold render can take minutes. When [warmInBackground]
  // is on, a not-yet-"warm" daemon serves the BAKED vector immediately and warms in the background;
  // once a daemon id has produced one successful render it's warm and the per-variant lane kicks in
  // for it. [prewarm] closes the window off the request path so the first real browse is already
  // per-variant.
  private val warmDaemonIds = ConcurrentHashMap.newKeySet<String>()
  private val warmingInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Completed catalog-grid theme renders are retained in [catalogThemeCache] for this catalog
   * generation. The per-preview daemon pool is deliberately LRU and may evict the daemon (and its
   * local cache) between theme selections; keeping the pure `themeProvider` result here makes a
   * repeat selection instant without pinning every preview daemon. The shared cache survives idle
   * host suspension; a catalog refresh creates a new generation and cache. Only pure theme
   * selections participate, so the key space is bounded by `previews × declaredThemes`; arbitrary
   * knob combinations stay in the daemon's bounded cache.
   */
  private val themeRendersInFlight = ConcurrentHashMap.newKeySet<String>()
  private val optimizationStarted = AtomicBoolean()
  private val optimizationActive = AtomicBoolean()
  private val warmExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-warm").apply { isDaemon = true }
    }
  }
  private val optimizationExecutorDelegate = lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-theme-optimize").apply { isDaemon = true }
    }
  }
  private val optimizationExecutor by optimizationExecutorDelegate

  /**
   * True when [daemonId] is warm (a live render is safe to await now). When it isn't and
   * [warmInBackground] is on, kick a one-shot background warm (a throwaway render that flips it
   * warm on success) and return false so the caller falls back to baked — the request never blocks
   * on a cold daemon. With [warmInBackground] off this always returns true (old always-block
   * behaviour).
   */
  private fun daemonWarmOrScheduling(daemonId: String): Boolean {
    if (!warmInBackground || warmDaemonIds.contains(daemonId)) return true
    if (warmingInFlight.add(daemonId)) {
      warmExecutor.execute {
        try {
          if (renderDaemon(daemonId, PreviewOverrides()) is RenderOutcome.Ok) {
            warmDaemonIds.add(daemonId)
          }
        } catch (_: Throwable) {
          // Best-effort: a failed warm just leaves the id cold; the next request retries.
        } finally {
          warmingInFlight.remove(daemonId)
        }
      }
    }
    return false
  }

  private fun scheduleWarm(daemonId: String, host: ServeHost = live) {
    if (!warmInBackground || warmDaemonIds.contains(daemonId)) return
    if (warmingInFlight.add(daemonId)) {
      warmExecutor.execute {
        try {
          if (host.render(daemonId, PreviewOverrides()) is RenderOutcome.Ok) {
            warmDaemonIds.add(daemonId)
          }
        } catch (_: Throwable) {
          // Best-effort: a failed warm just leaves the id cold; the next request retries.
        } finally {
          warmingInFlight.remove(daemonId)
        }
      }
    }
  }

  /**
   * Warm the live daemon(s) off the request path so the first real browse already gets the
   * per-variant SVG lane rather than the baked fallback. Best-effort + async: for a monolithic-only
   * catalog this warms one shared daemon render; a per-preview catalog deliberately skips eager
   * render warming so startup never fans out into one JVM per preview. No-op when
   * [warmInBackground] is off.
   */
  fun prewarm() {
    startThemeOptimization()
    if (!warmInBackground) return
    // A per-preview catalog deliberately skips eager warming — one JVM per preview would make
    // startup fan out into dozens of them. But when snapshots share the monolithic daemon, that
    // daemon is exactly what the first theme selection will wait on, and its cold start (~68s on
    // Android) outlasts the page's three 2/4/8s retries — so the grid would sit unchanged until
    // someone selected the theme a second time. One warm render, off the request path, closes it.
    if (perPreviewResolve != null && !sharedDaemonRenders) return
    alias.values.firstOrNull()?.let { scheduleWarm(it, live) }
  }

  override val label: String = baked.label

  /**
   * The app-declared `@ThemeCatalog` themes come from the daemon lane (read from the live bundle's
   * `previews.json`) — the baked browse surface carries none. Forwarded so the viewer's App theme
   * selector renders and, since [canRenderOverrides] is true, actually re-renders under a chosen
   * theme via the carried daemon.
   */
  override val declaredThemes: List<ServeTheme> = live.declaredThemes

  override fun themeOptimizationSnapshot(): ThemeOptimizationSnapshot? =
    catalogThemeCache.snapshot().takeIf { it.total > 0 }

  override val backgroundWorkActive: Boolean
    get() = optimizationActive.get()

  private data class ThemeOptimizationJob(
    val previewId: String,
    val overrides: PreviewOverrides,
    val cacheKey: String,
  )

  /** Fill every catalog-preview × declared-theme cache entry while the whole server is idle. */
  private fun startThemeOptimization() {
    // Off by default — see [themeOptimizationEnabled]. Returning before `configureTargets` leaves
    // the cache with no targets, so `themeOptimizationSnapshot()` reports null and `/status` shows
    // no optimization row at all rather than one stuck at "waiting" forever.
    if (!themeOptimizationEnabled) return
    val catalogIds =
      previews.asSequence().map { it.id }.filter(alias::containsKey).sorted().toList()
    val jobs = catalogIds.flatMap { previewId ->
      declaredThemes.map { theme ->
        val overrides = PreviewOverrides(themeProvider = theme.providerFqn)
        ThemeOptimizationJob(
          previewId = previewId,
          overrides = overrides,
          cacheKey = ServeOverrides.cacheKey(previewId, overrides),
        )
      }
    }
    catalogThemeCache.configureTargets(jobs.map { it.cacheKey })
    if (jobs.isEmpty() || catalogThemeCache.snapshot().fullyOptimized) return
    if (!optimizationStarted.compareAndSet(false, true)) return
    optimizationActive.set(true)
    optimizationExecutor.execute {
      try {
        for (job in jobs) {
          if (catalogThemeCache.get(job.cacheKey) != null) continue
          var attempts = 0
          while (catalogThemeCache.get(job.cacheKey) == null && attempts < 3) {
            if (!awaitServerIdle()) return@execute
            catalogThemeCache.markRunning(clock())
            // One background render server-wide: the permit is taken per job, not per pass, so a
            // catalog that parks for traffic hands it straight to the next one.
            val outcome =
              backgroundWork.withRenderPermit { render(job.previewId, job.overrides) }
                ?: return@execute
            if (outcome is RenderOutcome.Ok) break
            val daemonId = alias[job.previewId]
            if (
              outcome == RenderOutcome.Busy &&
                daemonId != null &&
                warmingInFlight.contains(daemonId)
            ) {
              if (!awaitWarmCompletion(daemonId)) return@execute
              // A successful cold warm should not consume the optimizer's retry budget: the Busy
              // response only meant "warming asynchronously", not that the theme render failed.
              if (warmDaemonIds.contains(daemonId)) continue
            }
            attempts++
            if (attempts < 3 && !pauseOptimization(250)) return@execute
          }
          if (catalogThemeCache.get(job.cacheKey) == null)
            catalogThemeCache.markFailed(job.cacheKey)
        }
        catalogThemeCache.markPassFinished(clock())
      } finally {
        optimizationActive.set(false)
        optimizationStarted.set(false)
      }
    }
  }

  private fun awaitServerIdle(): Boolean {
    while (true) {
      if (Thread.currentThread().isInterrupted) return false
      val idleMillis = serverIdleMillis()
      if (idleMillis != null && idleMillis >= themeOptimizationIdleMillis) return true
      catalogThemeCache.markPaused()
      if (!pauseOptimization(1_000)) return false
    }
  }

  private fun awaitWarmCompletion(daemonId: String): Boolean {
    while (warmingInFlight.contains(daemonId)) {
      if (Thread.currentThread().isInterrupted || !pauseOptimization(1_000)) return false
    }
    return true
  }

  private fun pauseOptimization(millis: Long): Boolean =
    try {
      Thread.sleep(millis)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant and the viewer shows the published
   * pixels + trust badge — the live daemon is opt-in via [hasLiveStream], not the snapshot lane.
   */
  override val canApplyOverrides: Boolean = false

  /**
   * The carried daemon CAN re-render a snapshot on demand, so an override-bearing `/render` (a
   * `?knob.<key>=…` edit, or a display-axis change on a mapped id) returns fresh pixels — see
   * [render] / [renderSvg]. This leaves [canApplyOverrides] false (ordinary browsing never wakes
   * the daemon) while enabling the viewer's knob controls as live rather than baked-and-disabled.
   */
  override val canRenderOverrides: Boolean = true

  /**
   * Only a preview with a daemon twin ([alias]) can actually re-render an override; an unaliased
   * (Android-only) variant always replays the baked PNG, which ignores overrides. So the viewer
   * must treat those as non-renderable — otherwise the App theme selector (advertised host-wide via
   * [declaredThemes]) would render enabled on a variant where picking a theme changes nothing.
   */
  override fun canRenderOverridesFor(previewId: String): Boolean = previewId in alias

  /** A leased burst is safe only when distinct previews have independent pooled daemons. */
  /**
   * A burst is only real if the renders can actually proceed in parallel. The per-preview pool can
   * — one daemon per preview — but the shared daemon has a single render lock, so advertising five
   * there would funnel five workers into one lock, hand four of them `Busy`, and the page would
   * exhaust its three retries and leave those cards on baked pixels. Worse than serial, not better.
   * So capacity follows the lane snapshots actually use.
   */
  override val themeRenderBurstCapacity: Int =
    if (perPreviewResolve != null && !sharedDaemonRenders) 5 else 1

  /** The gesture override is honoured by the daemon lane, if that daemon is Android-backed. */
  override val gesturesRenderable: Boolean = live.gesturesRenderable

  /**
   * SVG is exportable when either lane can produce it — the baked catalog carries
   * `figma/<slug>.svg` vectors, and the daemon exports a `compose/figma-svg` for a knob-bearing
   * render.
   */
  override val hasSvgExport: Boolean = baked.hasSvgExport || live.hasSvgExport

  override val hasScrollExport: Boolean = live.hasScrollExport

  override fun hasScrollExportFor(previewId: String): Boolean =
    previewId in alias && live.hasScrollExportFor(alias.getValue(previewId))

  /**
   * Per-preview SVG availability (issue #2352): narrows [hasSvgExport] to a specific preview so the
   * viewer doesn't offer the SVG control where the `.svg` lane would 404. A daemon-twinned id can
   * export its variant vector when the daemon lane can ([live.hasSvgExport]); an unmapped
   * (Android-only) id only when the baked catalog carried its slug's `figma/<slug>.svg`. Mirrors
   * [renderSvg]'s routing and never advertises more broadly than [hasSvgExport].
   */
  override fun hasSvgExportFor(previewId: String): Boolean =
    (previewId in alias && live.hasSvgExport) || baked.hasSvgExportFor(previewId)

  /** The "Live (stream)" toggle is offered (unlike a plain static catalog). */
  override val hasLiveStream: Boolean = true

  /**
   * The underlying baked catalog host, so the HTTP layer can read its title / subtitle / trust
   * verdict (which only a [ServeBundleHost] carries) even though the session is fronted by this
   * composite. See `ServeHttpServer.catalogBundleHost`.
   */
  internal val bakedHost: ServeHost = baked

  /**
   * Ordinary browsing serves the baked catalog PNG — instant, and never wakes the daemon: an
   * override-free render (or one carrying only a `uiMode` that matches the variant's baked theme,
   * as the viewer replays from its sticky theme) lands on baked pixels. Any override that would
   * change those pixels — a named knob, a font scale, device, locale, orientation, a feature
   * override, … — is routed to the [live] daemon to re-render, since the baked PNG can't represent
   * it ([overridesAffectRender]). So a `/render?fontScale=…` or `?knob.label=…` URL returns fresh
   * pixels, while the default browse stays baked-instant. Only the mapped (daemon-twinned) ids can
   * re-render; an unmapped Android-only variant always replays baked.
   */
  /**
   * Answerable without admission only when this request would not have reached a daemon at all —
   * the same [daemonIdForOverrideRender] predicate `render` routes on, so the fast path can never
   * silently serve baked pixels for something that was supposed to be re-rendered. An override-free
   * browse (the default page) always lands here, which is the point: a default page view must
   * replay published pixels, never generate them.
   */
  override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? {
    cachedRender(previewId, overrides)?.let {
      return it
    }
    if (daemonIdForOverrideRender(previewId, overrides) != null) return null
    return baked.bakedRender(previewId, overrides)
  }

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val themeCacheKey = themeCacheKey(previewId, overrides)
    cachedRender(previewId, overrides)?.let {
      return it
    }
    if (themeCacheKey != null && !themeRendersInFlight.add(themeCacheKey)) {
      return RenderOutcome.Busy
    }
    try {
      val daemonId =
        daemonIdForOverrideRender(previewId, overrides) ?: return baked.render(previewId, overrides)
      // A live-only (deferred) preview has NO baked PNG to fall back to — the daemon is its only
      // lane, so it must be awaited even cold and its outcome returned as-is. Everything below is
      // the baked-first routing, which such an id can't use.
      if (previewId in liveOnlyPreviewIds) {
        return cacheThemeRender(themeCacheKey, renderDaemon(daemonId, overrides))
      }
      // Only await the daemon when it's warm and free. A cold Android render can take minutes, and
      // blocking the browse — and the HTTP render slot it holds — on it is what saturates the whole
      // server. Ordinary override requests may fall back to baked pixels while warming. A pure
      // theme request must instead report Busy: returning baked pixels as a successful 200 would
      // make the grid believe the requested theme had loaded and it would never retry.
      if (daemonWarmOrScheduling(daemonId)) {
        val live = renderDaemon(daemonId, overrides)
        if (live !is RenderOutcome.Busy) return cacheThemeRender(themeCacheKey, live)
      }
      if (themeCacheKey != null) return RenderOutcome.Busy
      return baked.render(previewId, overrides)
    } finally {
      if (themeCacheKey != null) themeRendersInFlight.remove(themeCacheKey)
    }
  }

  override fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
    themeCacheKey(previewId, overrides)?.let(catalogThemeCache::get)?.let { bytes ->
      RenderOutcome.Ok(bytes, RenderOutcome.Generation.CATALOG_CACHE)
    }

  /** Cache only the catalog grid's pure theme selection, never a baked fallback or a failure. */
  private fun cacheThemeRender(key: String?, outcome: RenderOutcome): RenderOutcome {
    if (
      key != null &&
        outcome is RenderOutcome.Ok &&
        outcome.generation != RenderOutcome.Generation.BAKED
    ) {
      catalogThemeCache.put(key, outcome.png)
    }
    return outcome
  }

  private fun themeCacheKey(previewId: String, overrides: PreviewOverrides): String? {
    val provider = overrides.themeProvider ?: return null
    if (previewId !in alias || declaredThemes.none { it.providerFqn == provider }) return null
    if (overrides != PreviewOverrides(themeProvider = provider)) return null
    return ServeOverrides.cacheKey(previewId, overrides)
  }

  /**
   * The captured Remote Compose document rides in the baked bundle's `ir/<id>.rc` sidecar (the
   * daemon has no such static export), so delegate straight to [baked]. The in-browser player
   * replays it and applies knob edits client-side — no daemon round-trip — so the live twin never
   * enters this lane.
   */
  override fun remoteComposeDoc(previewId: String): ByteArray? = baked.remoteComposeDoc(previewId)

  /** The cmp-jvm render spec (baked size + density) comes from the baked bundle, like the doc. */
  override fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? =
    baked.remoteComposeRenderSpec(previewId)

  /** The daemon lane honours the RC player override when the carried daemon is Android-backed. */
  override val remoteComposePlayerSelectable: Boolean = live.remoteComposePlayerSelectable

  /**
   * The RC backend selector unions the two lanes: the client-side [RcPlayerBackend.JS] canvas
   * whenever the baked bundle carries the `.rc` document, plus the server-side
   * [RcPlayerBackend.JAVA] / [RcPlayerBackend.CMP_ANDROID] lanes when this Remote Compose preview
   * has a daemon twin ([canRenderOverridesFor]) on a backend that honours the player override
   * ([remoteComposePlayerSelectable]). A preview with no `.rc` doc is not Remote Compose, so it
   * gets no selector at all. [RcPlayerBackend.CMP_JVM] joins when the isolated desktop player is
   * installed and the baked bundle can size a render for it ([supportsCmpJvm]).
   */
  override fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> {
    if (!hasRemoteComposeDoc(previewId)) return emptyList()
    return buildList {
      add(RcPlayerBackend.JS)
      if (canRenderOverridesFor(previewId) && remoteComposePlayerSelectable) {
        add(RcPlayerBackend.JAVA)
        add(RcPlayerBackend.CMP_ANDROID)
      }
      if (supportsCmpJvm(previewId)) add(RcPlayerBackend.CMP_JVM)
    }
  }

  /**
   * The daemon-backed host to route a mapped [daemonId] to: the per-preview daemon if
   * [perPreviewResolve] resolves one (the default lane, exercised routinely), else the monolithic
   * [live] daemon. Both re-render the same daemon id — the per-preview bundle simply carries only
   * that one preview's closure — so callers pass the daemon id either way.
   */
  private fun liveHostFor(daemonId: String): ServeHost = perPreviewResolve?.invoke(daemonId) ?: live

  /**
   * Which daemon answers a **snapshot render** — the grid, and every themed thumbnail on it.
   *
   * The shared monolithic daemon, by default, because a grid is a *batch*: one cold start and then
   * every remaining card is warm. Routing these to the per-preview pool instead made each card pay
   * its own cold start, which on an Android catalog is tens of seconds apiece — measured at 68s
   * cold against 356ms warm — so selecting a theme across a 42-card grid went from "fills in at
   * about one a second" to "mostly stalled". The pool's LRU cap of 8 made it worse than linear: a
   * grid larger than the cap evicts daemons while the same page is still using them, so scrolling
   * back can pay the cold start a second time.
   *
   * Interactive streams keep the per-preview lane ([liveHostFor]) — there the isolation is the
   * point, one long-lived session per preview being edited, and there is no batch to amortise.
   *
   * A per-preview daemon that the monolithic one cannot serve still resolves: an id the shared
   * daemon reports as unknown falls back to the pool rather than failing.
   */
  /**
   * Render [daemonId] on the shared daemon, falling back to its per-preview daemon for an id the
   * shared one doesn't carry (a split/IR-backed bundle the monolithic descriptor never listed).
   * Only [RenderOutcome.NotFound] falls through — a Busy or a failure is that daemon's real answer
   * and re-running it elsewhere would just double the work.
   */
  private fun renderDaemon(daemonId: String, overrides: PreviewOverrides): RenderOutcome {
    val outcome = renderHostFor(daemonId).render(daemonId, overrides)
    if (outcome != RenderOutcome.NotFound || !sharedDaemonRenders) return outcome
    val perPreview = perPreviewResolve?.invoke(daemonId) ?: return outcome
    return perPreview.render(daemonId, overrides)
  }

  private fun renderHostFor(daemonId: String): ServeHost =
    if (sharedDaemonRenders) live else liveHostFor(daemonId)

  /**
   * SVG export mirrors [render]'s knob routing, plus a fallback: the SVG row is advertised whenever
   * *either* lane can export ([hasSvgExport]), but a specific mapped preview may have no baked
   * `figma/<slug>.svg` (or the whole catalog carried none and only the daemon exports). So when the
   * baked lane can't produce the vector, fall back to the daemon for a mapped id rather than 404
   * the advertised link. Unlike PNG browsing, an SVG export is an explicit user action (the
   * Download / Copy link), so waking the daemon here is fine.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    daemonIdForOverrideRender(previewId, overrides)?.let {
      return liveHostFor(it).renderSvg(it, overrides)
    }
    // No override — prefer the daemon's freshly-rendered per-variant SVG for a daemon-twinned id.
    // The baked lane now resolves a per-variant `figma/<slug>/<variant>.svg` itself (so a `…__dark`
    // id serves the dark vector even from a cold daemon), but a catalog published before the
    // per-variant emit existed only carries the light-preferred `figma/<slug>.svg` — the warm
    // daemon stays the more faithful source when it's already up.
    alias[previewId]?.let { daemonId ->
      // Only await the daemon when it's warm — otherwise a cold (possibly minutes-long) render
      // would
      // hang the browse. A cold daemon serves the baked vector now and warms in the background; a
      // warm daemon that still fails/NotFounds also falls through to baked (never surface an error
      // where a baked vector exists).
      if (daemonWarmOrScheduling(daemonId)) {
        val live = liveHostFor(daemonId).renderSvg(daemonId, overrides)
        if (live is SvgOutcome.Ok) return live
      }
    }
    return baked.renderSvg(previewId, overrides)
  }

  /**
   * Web mode prefers the **baked** lane: only the catalog's published crops have a public branch
   * home to link (`ServeBundleHost.renderSvgForWeb`), while a daemon render's crops exist on its
   * disk alone and would have to be embedded anyway. An override render can't be represented by
   * baked files, so it stays on the live (embedded) lane; a preview the baked lane can't serve
   * falls back to the ordinary [renderSvg] routing.
   */
  override fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    daemonIdForOverrideRender(previewId, overrides)?.let {
      return liveHostFor(it).renderSvg(it, overrides)
    }
    val linked = baked.renderSvgForWeb(previewId, overrides)
    if (linked is SvgOutcome.Ok) return linked
    return renderSvg(previewId, overrides)
  }

  /** Full-page raster capture is daemon-produced; route every mapped catalog preview live. */
  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = alias[previewId] ?: return RenderOutcome.NotFound
    return liveHostFor(daemonId).renderScrollPng(daemonId, overrides)
  }

  /** Full-page vector capture follows the same explicit live route as its raster counterpart. */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val daemonId = alias[previewId] ?: return SvgOutcome.NotFound
    return liveHostFor(daemonId).renderScrollSvg(daemonId, overrides)
  }

  /**
   * The daemon preview id to route a [render] / [renderSvg] to, or null to stay baked. Delegates to
   * [CatalogLiveRouting] — the same predicate [ServePerPreviewLiveHost] uses — so the "baked vs
   * re-render" decision is identical across the two trusted-catalog live hosts.
   */
  private fun daemonIdForOverrideRender(previewId: String, overrides: PreviewOverrides): String? =
    CatalogLiveRouting.daemonIdForRender(previewId, overrides, alias, liveOnlyPreviewIds)

  /** Live streaming is available only for aliased ids; others have no stream (snapshot only). */
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
          // An unmapped (Android-only) variant has no daemon twin, so no live lane — report it so
          // the viewer explains the snapshot fallback rather than a bare "input requires a stream".
          onUnavailable?.invoke("no live daemon twin for '$previewId' (baked snapshot only)")
          return null
        }
    return liveHostFor(daemonId)
      .subscribeStream(
        daemonId,
        overrides,
        codec,
        maxFps,
        onUnavailable = onUnavailable,
        onFrame = onFrame,
      )
  }

  override fun activeStreamCount(): Int = live.activeStreamCount() + perPreviewStreamCount()

  /**
   * This catalog's live-lane render stats: the carried monolithic daemon's counters folded together
   * with the pooled per-preview daemons' ([perPreviewRenderStats]) — the per-preview lane is the
   * default render path, so a monolithic-only view would sit empty while the pool does the real
   * render work (mirrors how [activeStreamCount] adds the pool's streams).
   */
  override fun renderPerfStats(): RenderPerfSnapshot? {
    val pool = perPreviewRenderStats()
    val monolithic = live.renderPerfStats()
    // Aggregating a single snapshot would null its percentiles (windows don't merge), so keep the
    // monolithic view verbatim until the pool actually has daemons to fold in.
    if (pool.isEmpty()) return monolithic
    return RenderPerfSnapshot.aggregate(listOfNotNull(monolithic) + pool)
  }

  override fun daemonPoolStats(): List<DaemonPoolSnapshot> = perPreviewPoolStats()

  /**
   * Graft the daemon previews' per-preview metadata onto the baked browse surface. The daemon knows
   * its previews by descriptor id (`FilledButton_Dark`) and carries their author-declared knobs
   * ([ServePreview.overrides] + [ServePreview.remoteComposeKnobs], from the bundle sidecars), its
   * discovery-time [ServePreview.uiMode], and the detected-feature flags
   * ([ServePreview.supportsFocus] / [supportsGestures], from `@FocusedPreview` /
   * `@GestureHintPreview` discovery); the baked catalog keys by catalog id
   * (`button-filled__ideal__default__dark`) and may carry none of them. For each mapped baked
   * preview, copy its daemon twin's metadata across so `/api/previews` + the viewer advertise the
   * editable knobs and detected-feature controls while retaining the actual baked Day/Night
   * default. Unmapped previews (Android-only variants with no daemon lane) are returned unchanged.
   */
  private fun mergeDeclaredKnobs(
    bakedPreviews: List<ServePreview>,
    livePreviews: List<ServePreview>,
  ): List<ServePreview> {
    val twinByDaemonId = livePreviews.associateBy { it.id }
    return bakedPreviews.map { p ->
      val twin = alias[p.id]?.let { twinByDaemonId[it] } ?: return@map p
      p.copy(
        overrides = twin.overrides,
        remoteComposeKnobs = twin.remoteComposeKnobs,
        supportsFocus = twin.supportsFocus,
        supportsGestures = twin.supportsGestures,
        uiMode = twin.uiMode,
      )
    }
  }

  override fun close() {
    themeRendersInFlight.clear()
    if (optimizationExecutorDelegate.isInitialized()) {
      try {
        optimizationExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread optimization pool
      }
    }
    if (warmInBackground) {
      try {
        warmExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread warm pool
      }
    }
    try {
      live.close()
    } finally {
      baked.close()
    }
  }
}
