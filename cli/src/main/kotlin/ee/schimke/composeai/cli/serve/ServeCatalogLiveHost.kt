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
   * Close per-preview daemons idle for the given window, returning how many (supplied by the pool).
   * Drives the pooled half of [releaseIdleDaemons]; the default no-ops for a host with no pool.
   */
  private val perPreviewReapIdle: (idleMillis: Long) -> Int = { 0 },
  /** Identical monolithic daemon replicas used only for a leased theme-render batch. */
  private val sharedDaemonPool: ServeSharedDaemonPool? = null,
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
   * Whether to **eagerly** fill [catalogThemeCache] on the idle pass below — on by default.
   *
   * The pass renders `previews × declaredThemes` for every catalog: potentially hundreds of daemon
   * renders. It used to start too eagerly and could keep a public box permanently busy. The
   * default-on version is guarded by [ServeBackgroundWork], the one-minute quiet window below, and
   * the cache's byte-bounded LRU; foreground traffic or catalog loading parks it between images.
   *
   * The pass is deliberately gentle: it waits for the server-wide quiet window and takes one
   * background render permit at a time. `-Dcomposeai.serve.themeOptimization=false` disables it.
   */
  private val themeOptimizationEnabled: Boolean =
    System.getProperty("composeai.serve.themeOptimization")?.toBooleanStrictOrNull() ?: true,
  private val serverIdleMillis: () -> Long? = { Long.MAX_VALUE },
  /**
   * Server-wide admission for the idle theme optimizer below. Shared by every catalog host in a
   * `serve` run, so their background passes take turns rather than each holding a live seat — see
   * [ServeBackgroundWork].
   */
  private val backgroundWork: ServeBackgroundWork = ServeBackgroundWork(),
  private val themeOptimizationIdleMillis: Long =
    System.getProperty("composeai.serve.themeOptimizationIdleMillis")?.toLongOrNull() ?: 60_000L,
  /**
   * Route snapshot renders to the shared monolithic daemon rather than the per-preview pool — see
   * [renderHostFor]. `-Dcomposeai.serve.sharedDaemonRenders=false` restores per-preview routing for
   * a deployment that wants each preview isolated at the cost of a cold start per card.
   */
  private val sharedDaemonRenders: Boolean =
    System.getProperty("composeai.serve.sharedDaemonRenders")?.toBooleanStrictOrNull() ?: true,
  /**
   * Whether [prewarm] warms this catalog's daemon when its session is **opened** — off by default.
   *
   * Opening happens for every catalog at boot, so this used to launch one JVM per catalog
   * simultaneously: measured on the public box, 18 daemons resident at 6 minutes uptime against a
   * live-seat budget that models ~1.2 GB each and permits 8. It settles — the reaper had it down to
   * 3 by 85 minutes — so this was never permanent over-commitment, but the spike lands exactly when
   * the box is also fetching all 18 catalogs, and for pixels nobody has asked for.
   *
   * The case eager warming existed for is now served on demand: a visitor's presence heartbeat
   * ([keepLiveWarm]) warms the catalog they actually opened, and fires as soon as the page loads.
   * `-Dcomposeai.serve.eagerWarmOnOpen=true` restores boot-time warming for a deployment that would
   * rather pay the memory than the first visitor's cold start.
   */
  private val eagerWarmOnOpen: Boolean =
    System.getProperty("composeai.serve.eagerWarmOnOpen")?.toBooleanStrictOrNull() ?: false,
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

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    baked.annotationsForPreview(previewId)

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    baked.annotationsForReference(referenceId)

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
  // Whether the optimizer currently holds its turn: set once the full quiet window is met, cleared
  // the moment a request arrives. Without it the pass re-earned the whole window per render.
  private val optimizerHasTurn = AtomicBoolean(false)
  // When the optimizer last checked for activity. Any activity newer than this happened while it
  // was rendering, and must cost it the turn even if the server looks quiet again by now.
  private val optimizerSampledAt = java.util.concurrent.atomic.AtomicLong(0)
  private val warmDaemonIds = ConcurrentHashMap.newKeySet<String>()
  private val warmingInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Completed catalog renders are retained in [catalogThemeCache] for this catalog generation. The
   * per-preview daemon pool is deliberately LRU and may evict the daemon (and its local cache)
   * between selections; keeping every successful override result here makes repeat theme, knob,
   * locale, font-scale, and other selections instant without pinning every preview daemon. The
   * shared cache survives idle host suspension and accumulates for the generation; a catalog
   * refresh creates a new generation and cache, flushing pixels produced from the old content.
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

  /**
   * Wait, briefly, for the background warm [daemonWarmOrScheduling] just scheduled for [daemonId],
   * so a cold-id request can render instead of failing. Returns whether the daemon came up warm.
   *
   * Only for a **pure theme** request ([themeCacheKey] non-null): those have no useful fallback —
   * serving baked pixels for a requested theme would show the wrong colours under a successful
   * status — whereas an ordinary override request drops to baked and loses nothing by not waiting.
   *
   * Bounded by [FOREGROUND_WARM_AWAIT_MILLIS] rather than the full cold-start time: the caller is
   * holding one of the server's render slots while it waits, so an unbounded wait would let a burst
   * of cold ids consume every slot. Past the bound the caller still gets Busy — the same answer as
   * before, just after actually trying.
   */
  private fun awaitForegroundWarm(daemonId: String, themeCacheKey: String?): Boolean {
    if (themeCacheKey == null || !warmInBackground) return false
    val deadline = clock() + FOREGROUND_WARM_AWAIT_MILLIS
    while (clock() < deadline) {
      if (warmDaemonIds.contains(daemonId)) return true
      // The warm finished without succeeding (a genuinely failing preview): stop waiting and let
      // the caller take its normal path rather than burning the whole budget on a lost cause.
      if (!warmingInFlight.contains(daemonId)) return false
      try {
        Thread.sleep(WARM_POLL_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    return warmDaemonIds.contains(daemonId)
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
    if (!warmInBackground || !eagerWarmOnOpen) return
    // A per-preview catalog deliberately skips eager warming — one JVM per preview would make
    // startup fan out into dozens of them. But when snapshots share the monolithic daemon, that
    // daemon is exactly what the first theme selection will wait on, and its cold start (~68s on
    // Android) outlasts the page's three 2/4/8s retries — so the grid would sit unchanged until
    // someone selected the theme a second time. One warm render, off the request path, closes it.
    if (perPreviewResolve != null && !sharedDaemonRenders) return
    alias.values.firstOrNull()?.let { scheduleWarm(it, live) }
  }

  /**
   * A visitor is on this catalog's pages: make sure the shared daemon is up, so their first theme
   * selection is a warm render rather than a cold start.
   *
   * This is [prewarm]'s warming half without the theme-optimization pass — the same [scheduleWarm]
   * call, under the same conditions. It is safe to call on every heartbeat because `scheduleWarm`
   * returns immediately once the id is warm or a warm is already in flight; a suspended session is
   * rebuilt with a fresh host (and so a fresh warm set) on resume, which is exactly when a
   * heartbeat should warm it again.
   */
  override fun keepLiveWarm() {
    if (!warmInBackground) return
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

  override fun catalogRenderCacheSnapshot(): CatalogRenderCacheSnapshot =
    catalogThemeCache.renderCacheSnapshot()

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
          var lastFailure: String? = null
          while (catalogThemeCache.get(job.cacheKey) == null && attempts < 3) {
            if (!awaitOptimizerTurn()) return@execute
            catalogThemeCache.markRunning(clock())
            // One background render server-wide: the permit is taken per job, not per pass, so a
            // catalog that parks for traffic hands it straight to the next one.
            val outcome =
              backgroundWork.withRenderPermit { render(job.previewId, job.overrides) }
                ?: return@execute
            if (outcome is RenderOutcome.Ok) break
            if (outcome is RenderOutcome.Failed) lastFailure = outcome.reason
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
            catalogThemeCache.markFailed(job.cacheKey, lastFailure)
        }
        catalogThemeCache.markPassFinished(clock())
      } finally {
        optimizationActive.set(false)
        optimizationStarted.set(false)
      }
    }
  }

  /**
   * Gate one background render.
   *
   * The pass *enters* on the full [themeOptimizationIdleMillis] quiet window — that is the "don't
   * start work on a box someone is using" rule and it stays. What changed is what happens once it
   * is running: it used to re-demand the whole 60s window before **every single render**, so any
   * request anywhere in the process reset it and the pass could only ever advance during a full
   * minute of total silence. On a public server with 21 catalogs that is close to never — measured
   * throughput was one entry per ~105s against a sub-second render, i.e. ~99% waiting.
   *
   * Now it keeps its turn while the server stays quiet and yields as soon as a request actually
   * arrives ([OPTIMIZER_YIELD_MILLIS]), which is the property that matters: a visitor never waits
   * behind more than the render already in flight, and an idle box fills the cache at render speed
   * instead of one entry a minute.
   */
  private fun awaitOptimizerTurn(): Boolean {
    if (!optimizerHasTurn.get()) {
      if (!awaitServerIdle()) return false
      optimizerHasTurn.set(true)
      optimizerSampledAt.set(clock())
      return true
    }
    // Holding a turn. The question is NOT "is the server idle right this instant" — sampling that
    // misses every request that arrived *during* the render we just finished. A render can outlast
    // OPTIMIZER_YIELD_MILLIS several times over, so by the time we look, a visitor's request has
    // come and gone and the instantaneous idle reads as quiet again. That visitor never caused a
    // yield, which is precisely the starvation this gate exists to prevent.
    //
    // Ask instead whether anything happened SINCE we last looked. `serverIdleMillis` is the age of
    // the last activity, so `now - idle` is when that activity happened; if that timestamp is newer
    // than our previous sample, a request landed while we were busy.
    val now = clock()
    val idleMillis = serverIdleMillis()
    val lastActivityAt = idleMillis?.let { now - it }
    val quiet = lastActivityAt != null && lastActivityAt <= optimizerSampledAt.get()
    optimizerSampledAt.set(now)
    if (quiet) return true
    optimizerHasTurn.set(false)
    catalogThemeCache.markPaused()
    return awaitServerIdle().also {
      if (it) {
        optimizerHasTurn.set(true)
        optimizerSampledAt.set(clock())
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

  /** A leased burst is safe only when requests can borrow independent daemon processes. */
  /**
   * Shared mode borrows identical monolithic replicas, retaining one warm catalog classpath per
   * process while allowing a leased batch to render five cards at once. The older per-preview mode
   * remains independently parallel. Without either pool the single daemon render lock is serial.
   */
  override val themeRenderBurstCapacity: Int =
    when {
      sharedDaemonRenders -> sharedDaemonPool?.capacity ?: 1
      perPreviewResolve != null -> ThemeRenderLeaseManager.MAX_CONCURRENCY
      else -> 1
    }

  /** The gesture override is honoured by the daemon lane, if that daemon is Android-backed. */
  // The four capability flags below are `by lazy` for one reason: reading them forces the daemon
  // session open. `ServeRenderHost` defers its subprocess to first use so a registered catalog
  // costs nothing until someone needs a live render — and an eager `val` here would have undone
  // that at construction, which is exactly where every catalog builds its host. The browse surface
  // never touches them; the viewer chrome that does is already a per-preview request.
  /**
   * Whether this catalog is carrying any daemon at all — the monolithic one OR a pooled per-preview
   * one.
   *
   * The pool matters: an interactive stream and an explicit SVG / scroll export both route through
   * [liveHostFor], which can stand a per-preview daemon up without the monolithic [live] host ever
   * being touched. Forwarding only `live.daemonStarted` would make `runningDaemons()` drop such a
   * catalog outright, hiding its active streams, its pool occupancy and a running process from
   * `/status` — the opposite of what this reporting is for. The baked lane never has a subprocess,
   * so it contributes nothing.
   */
  /**
   * The primary shared daemon, its leased-batch replicas, plus the per-preview pool's residents.
   * Delegating to [live.daemonProcessCount] rather than adding one for [daemonStarted] matters:
   * this host reports started when only a pooled child is up, so a flat `+1` would invent a
   * monolithic daemon that does not exist.
   */
  override val daemonProcessCount: Int
    get() =
      live.daemonProcessCount +
        (sharedDaemonPool?.replicaProcessCount() ?: 0) +
        perPreviewPoolStats().sumOf { it.open }

  override val daemonStarted: Boolean
    get() =
      live.daemonStarted ||
        (sharedDaemonPool?.replicaProcessCount() ?: 0) > 0 ||
        perPreviewStreamCount() > 0 ||
        perPreviewPoolStats().any { it.open > 0 }

  override val gesturesRenderable: Boolean by lazy { live.gesturesRenderable }

  /**
   * SVG is exportable when either lane can produce it — the baked catalog carries
   * `figma/<slug>.svg` vectors, and the daemon exports a `compose/figma-svg` for a knob-bearing
   * render.
   */
  override val hasSvgExport: Boolean by lazy { baked.hasSvgExport || live.hasSvgExport }

  override val hasScrollExport: Boolean by lazy { live.hasScrollExport }

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

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = false)

  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    themeCacheKey(previewId, overrides)?.let(catalogThemeCache::failureReason)

  /**
   * Shed the pooled daemons — burst replicas and per-preview residents — while keeping the
   * monolithic [live] daemon, which is this catalog's warm browse lane and the thing the whole
   * cold-start design exists to hold on to. Both pools reopen on demand.
   */
  override fun releaseIdleDaemons(idleMillis: Long): Int =
    (sharedDaemonPool?.reapIdle(idleMillis) ?: 0) + perPreviewReapIdle(idleMillis)

  override fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = true)

  private fun renderInternal(
    previewId: String,
    overrides: PreviewOverrides,
    leased: Boolean,
  ): RenderOutcome {
    val catalogCacheKey = catalogCacheKey(previewId, overrides)
    val themeCacheKey = themeCacheKey(previewId, overrides)
    cachedRender(previewId, overrides)?.let {
      return it
    }
    // A theme render this catalog has already proved it cannot produce is answered from the latch,
    // not by asking the daemon again. The daemon's answer would be the same failure, but arriving
    // via the render lock — which is what let a handful of broken cards keep the lock busy and push
    // every *other* card on the grid into a Busy back-off.
    if (themeCacheKey != null) {
      catalogThemeCache.failureReason(themeCacheKey)?.let {
        return RenderOutcome.Failed(it)
      }
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
        return cacheCatalogRender(catalogCacheKey, renderDaemon(daemonId, overrides, leased))
      }
      // Only await the daemon when it's warm and free. A cold Android render can take minutes, and
      // blocking the browse — and the HTTP render slot it holds — on it is what saturates the whole
      // server. Ordinary override requests may fall back to baked pixels while warming. A pure
      // theme request must instead report Busy: returning baked pixels as a successful 200 would
      // make the grid believe the requested theme had loaded and it would never retry.
      // A leased batch is an explicit request to pay for parallel live pixels now. Let its shared
      // replicas cold-start on the request path if necessary; otherwise the per-id warm guard would
      // return Busy for every card and the pool would never grow. Ordinary renders retain the
      // baked-first/background-warm behaviour.
      // A pure theme request on a cold id used to schedule a warm and then abandon its own render
      // — returning Busy despite already holding a render slot it was prepared to wait 30s on. That
      // made the theme cache load-bearing for correctness rather than an optimization: a cache miss
      // was an error, so a restart (which empties the cache) broke the whole grid until the
      // background pass refilled it, which on a busy box takes hours.
      //
      // A warm render is sub-second (p50 ~0.25-1.1s on the public box), so the honest answer is to
      // render. The gate exists for the one case where that isn't true — a COLD daemon, 34-68s —
      // so wait for the warm this request just scheduled, bounded, and only give up if the cold
      // start really is going to outlast the request.
      if (
        leased || daemonWarmOrScheduling(daemonId) || awaitForegroundWarm(daemonId, themeCacheKey)
      ) {
        val live = renderDaemon(daemonId, overrides, leased)
        // Count a real render failure against this theme key so a permanently broken preview stops
        // being re-attempted (see [CatalogThemeCache.recordRenderFailure]). Busy / NotFound are not
        // failures of the render — they are "ask again" and "wrong lane" — and must not latch.
        if (themeCacheKey != null && live is RenderOutcome.Failed) {
          catalogThemeCache.recordRenderFailure(themeCacheKey, live.reason)
        }
        // NotFound joins Busy in falling through rather than being returned. It means no daemon on
        // either lane carries this id — the shared one never listed it and its per-preview bundle
        // didn't start (a classpath the box can't resolve, say). That is a statement about the
        // daemons, not about the pixels: the preview has a baked PNG right there, and showing the
        // visitor a broken image instead of the un-overridden snapshot helps nobody. Matters most
        // for a catalog whose supplement module carries its own live lane, where an id can be
        // aliased yet reachable only through the pool.
        if (live !is RenderOutcome.Busy && live !is RenderOutcome.NotFound)
          return cacheCatalogRender(catalogCacheKey, live)
      }
      if (themeCacheKey != null) return RenderOutcome.Busy
      return baked.render(previewId, overrides)
    } finally {
      if (themeCacheKey != null) themeRendersInFlight.remove(themeCacheKey)
    }
  }

  override fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
    catalogCacheKey(previewId, overrides)?.let(catalogThemeCache::get)?.let { bytes ->
      RenderOutcome.Ok(bytes, RenderOutcome.Generation.CATALOG_CACHE)
    }

  /** Cache every successful live catalog render, never a baked fallback or a failure. */
  private fun cacheCatalogRender(key: String?, outcome: RenderOutcome): RenderOutcome {
    if (
      key != null &&
        outcome is RenderOutcome.Ok &&
        outcome.generation != RenderOutcome.Generation.BAKED
    ) {
      catalogThemeCache.put(key, outcome.png)
    }
    return outcome
  }

  /**
   * A content-generation cache entry exists for every request that actually routes to the daemon.
   * Override-free baked browsing stays on disk and needs no duplicate entry here. The surrounding
   * [ServeSessionState] owns this map, so ordinary idle daemon suspension leaves it intact while a
   * catalog refresh replaces the state (and therefore the whole map) atomically.
   */
  private fun catalogCacheKey(previewId: String, overrides: PreviewOverrides): String? {
    if (daemonIdForOverrideRender(previewId, overrides) == null) return null
    return ServeOverrides.cacheKey(previewId, overrides)
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
  override val remoteComposePlayerSelectable: Boolean by lazy { live.remoteComposePlayerSelectable }

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
  private fun renderDaemon(
    daemonId: String,
    overrides: PreviewOverrides,
    leased: Boolean = false,
  ): RenderOutcome {
    val outcome =
      if (sharedDaemonRenders && leased && sharedDaemonPool != null) {
        sharedDaemonPool.render(daemonId, overrides)
      } else {
        renderHostFor(daemonId).render(daemonId, overrides)
      }
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
    val pool = perPreviewRenderStats() + (sharedDaemonPool?.renderPerfStats() ?: emptyList())
    val monolithic = live.renderPerfStats()
    // Aggregating a single snapshot would null its percentiles (windows don't merge), so keep the
    // monolithic view verbatim until the pool actually has daemons to fold in.
    if (pool.isEmpty()) return monolithic
    return RenderPerfSnapshot.aggregate(listOfNotNull(monolithic) + pool)
  }

  override fun daemonPoolStats(): List<DaemonPoolSnapshot> =
    listOfNotNull(sharedDaemonPool?.takeIf { sharedDaemonRenders }?.snapshot()) +
      perPreviewPoolStats()

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
      sharedDaemonPool?.close()
      live.close()
    } finally {
      baked.close()
    }
  }

  companion object {
    /**
     * How long a pure-theme request will wait for the daemon warm it scheduled before giving up.
     *
     * Sized between the two render regimes: a warm render is sub-second, a cold Android start is
     * 34-68s. Waiting the full cold start would tie up a render slot for a minute; not waiting at
     * all is what made a cache miss an error. This covers a warm that is already in flight and
     * nearly done, and lets a genuinely cold one fall through to the previous behaviour.
     */
    internal const val FOREGROUND_WARM_AWAIT_MILLIS = 15_000L

    /**
     * How recently a request must have touched the server for the optimizer to give up its turn.
     * Short: the point is to step aside for a live visitor within one render, not to re-earn the
     * whole entry window after every request.
     */
    internal const val OPTIMIZER_YIELD_MILLIS = 1_500L

    private const val WARM_POLL_MILLIS = 50L
  }
}
