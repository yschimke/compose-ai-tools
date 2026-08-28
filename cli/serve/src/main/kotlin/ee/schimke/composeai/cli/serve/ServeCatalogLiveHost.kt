package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
  /** Availability probe backed by the publication-aware per-preview fetcher. */
  private val executableBundleAvailable: ((daemonId: String) -> Boolean)? = null,
  /** Hydrated per-preview bundle bytes for the viewer's executable download lane. */
  private val executableBundleProvider: ((daemonId: String) -> ByteArray?)? = null,
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
  private val themeOptimizationIdleMillis: Long = themeOptimizationIdleMillisDefault(),
  /**
   * How long the idle gate may withhold a turn before one is granted anyway — see
   * [grantForcedTurn]. Non-positive disables the ceiling and restores the pure gate.
   */
  private val optimizerGateCeilingMillis: Long = optimizerGateCeilingMillisDefault(),
  /**
   * How long one admitted pass may hold its optimizer lane before giving it back and re-queueing.
   *
   * The knob trades **rotation latency against re-warming**. A slice shorter than a cold daemon
   * start (34-68s on an Android/Robolectric lane) spends most of its lane warming and renders
   * almost nothing; a slice long enough to finish a large catalog is no slice at all, and on a box
   * with 22 catalogs and 2 lanes that is what starved `m3-catalog` to `turnsGranted 0`. Five
   * minutes puts a full rotation of 22 catalogs at under an hour while keeping the worst-case warm
   * overhead near a fifth of the lane — and the warm is paid once per slice, not per preview.
   */
  private val optimizerSliceMillis: Long =
    System.getProperty("composeai.serve.themeOptimizerSliceMillis")?.toLongOrNull()
      ?: DEFAULT_OPTIMIZER_SLICE_MILLIS,
  /** Bounds the override render that follows a successful cold-id warm. */
  private val foregroundOverrideTimeoutMillis: Long = FOREGROUND_WARM_AWAIT_MILLIS,
  /** Injectable so admission retry behavior can be covered without a 20-second test. */
  private val optimizerAdmissionWaitMillis: Long = OPTIMIZER_ADMISSION_WAIT_MILLIS,
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
  override fun canDownloadExecutableBundle(previewId: String): Boolean =
    alias[previewId]?.let { daemonId ->
      executableBundleProvider != null && executableBundleAvailable?.invoke(daemonId) == true
    } == true

  override fun executableBundle(previewId: String): ByteArray? =
    alias[previewId]?.let { executableBundleProvider?.invoke(it) }

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

  // A capture is a published artifact of the delivery branch, like every other delegation here —
  // the daemon has no notion of one, and nothing about fronting this session with a live lane makes
  // the branch's recordings stop existing. Missing this override is what made the Motion lane 404
  // in production while passing every test: `previews` above is merged FROM `baked`, so the viewer
  // read the captures off the baked host and offered the chip, and then the bytes behind that chip
  // fell to `ServeHost.motionBytes`'s null default because this composite never forwarded them.
  // A static catalog is pinned and served by the bundle host directly, which is why the fixtures —
  // all of them pinned — never met the shape that breaks.
  override fun motionRead(motionId: String, extension: String): BranchFetch =
    baked.motionRead(motionId, extension)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    baked.designReferenceRaster(referenceId)

  // Backdrops ride the baked staging dir, so a live-lane catalog shows the same screens — with its
  // overlay renders coming from the live daemon rather than the baked PNGs.
  override fun designPages(): ServeDesignPageStore = baked.designPages()

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    baked.annotationsForPreview(previewId)

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    baked.annotationsForReference(referenceId)

  override fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    baked.tagIndexForPreview(previewId)

  override fun parityActivity(): ParityActivity? = baked.parityActivity()

  override fun parityIssues(): ParityIssues? = baked.parityIssues()

  override fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    baked.parityFindingsFor(previewId, referenceId)

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
  /**
   * When the gate started withholding a turn, or [Long.MIN_VALUE] while it isn't — the clock the
   * ceiling in [grantForcedTurn] measures. Reset the moment a turn is granted, by either route, so
   * it always reads "how long has this catalog been shut out *right now*".
   */
  private val optimizerGateBlockedSince = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
  /** Set while the pass is running on a turn the ceiling forced rather than the box granting. */
  private val optimizerTurnForced = AtomicBoolean(false)
  /**
   * Next preview position for a new optimizer slice; prevents an evicted prefix monopolising it.
   */
  private val optimizerPreviewCursor = AtomicInteger()
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
  /**
   * Persisted renders are checked against this renderer once per host — see
   * [verifyPersistedRenders].
   */
  private val persistenceVerified = AtomicBoolean()
  /**
   * True only while the pass **holds an optimizer lane**, which is what [backgroundWorkActive] —
   * and through it [ServeSessionRegistry.suspendIdle] — reads as "this host must stay resident".
   *
   * It used to be set for the whole life of the worker, and the worker does not end: on a catalog
   * with targets left it loops through the quiet gate forever, so the flag was effectively "this
   * catalog is not fully optimized". That made a catalog's own unfinished optimization the reason
   * its daemon could never be suspended, and the daemon is the expensive part — an Android lane is
   * priced at ~1.2 GB in the seat budget. preview.coo.ee reached nine such residents with zero
   * active streams, `MemAvailable` pinned at 14-21%, and the pressure gate therefore holding: the
   * optimizer's own residency was what stopped the optimizer running, and the box made progress
   * only on [OptimizerPressureThresholds.dutyCycleMillis] concessions — 50 of them across 40 hours,
   * 1,502 of 18,604 entries.
   *
   * A pass parked at the gate or queued for a lane needs nothing resident. Its progress lives in
   * [catalogThemeCache], which is held in [ServeSessionState] precisely so it survives daemon
   * suspension, and [ServeSessionRegistry.resumeIdleOptimizers] brings the host back when a lane
   * frees. So the flag covers the slice and nothing more.
   */
  private val optimizationActive = AtomicBoolean()
  private val warmExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-warm").apply { isDaemon = true }
    }
  }
  private val foregroundRenderExecutorDelegate = lazy {
    Executors.newCachedThreadPool { r ->
      Thread(r, "serve-catalog-foreground-render").apply { isDaemon = true }
    }
  }
  private val foregroundRenderExecutor by foregroundRenderExecutorDelegate
  private val optimizationExecutorDelegate = lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-theme-optimize").apply { isDaemon = true }
    }
  }
  private val optimizationExecutor by optimizationExecutorDelegate

  /**
   * Workers for one prefetch batch. Sized to the burst width, daemon threads so a shutdown mid
   * batch never holds the process open. Lazy like the pass itself — a catalog that never optimizes
   * never creates it.
   */
  private val optimizerBatchExecutorDelegate = lazy {
    Executors.newFixedThreadPool(MAX_OPTIMIZER_BATCH) { r ->
      Thread(r, "serve-catalog-theme-batch").apply { isDaemon = true }
    }
  }
  private val optimizerBatchExecutor by optimizerBatchExecutorDelegate

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
   * Used for every foreground override request. Serving baked pixels cannot satisfy any override:
   * the HTTP layer deliberately rejects that fallback as "override not applied", so returning it
   * makes the first knob edit on each cold preview fail with a 503 even while this warm succeeds in
   * the background (issue #4149).
   *
   * Bounded by [FOREGROUND_WARM_AWAIT_MILLIS] rather than the full cold-start time: the caller is
   * holding one of the server's render slots while it waits, so an unbounded wait would let a burst
   * of cold ids consume every slot. Past the bound the caller still gets Busy — the same answer as
   * before, just after actually trying.
   */
  private fun awaitForegroundWarm(daemonId: String): Boolean {
    if (!warmInBackground) return false
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
   * This is [prewarm]'s warming half — the same [scheduleWarm] call, under the same conditions. It
   * is safe to call on every heartbeat because `scheduleWarm` returns immediately once the id is
   * warm or a warm is already in flight; a suspended session is rebuilt with a fresh host (and so a
   * fresh warm set) on resume, which is exactly when a heartbeat should warm it again.
   *
   * It also **re-enters the theme-optimization pass**. That pass used to run exactly once, from
   * [prewarm] at catalog open, and `startThemeOptimization` clears `optimizationStarted` in its
   * `finally` — so a pass that ended with targets still unfilled left them unfilled for the life of
   * the catalog generation, with nothing to start another. Anything the one pass could not get on
   * its single attempt (a daemon still warming, a replica the seat budget could not afford, a
   * preview whose live lane was momentarily contended) was simply abandoned.
   *
   * meshcore-mobile sat at `paused 288/372, failed: 0` across two server lifetimes because of it,
   * stopping at the same 288 both times while the other fourteen catalogs on the box reached
   * `complete`. `failed: 0` is what makes this hard to see: the missing 84 were never *attempted*
   * again, so nothing was ever recorded against them.
   *
   * Re-entering here is safe and self-limiting: `startThemeOptimization` returns immediately when
   * the cache is already `fullyOptimized` or a pass is in flight (`optimizationStarted` is a CAS),
   * and the pass's own [awaitOptimizerTurn] still holds the idle gate — so a heartbeat arriving
   * while a visitor is browsing schedules work that waits for quiet rather than competing with
   * them.
   */
  override fun keepLiveWarm() {
    // Ahead of the `warmInBackground` guard, exactly as in [prewarm]: the two are independent
    // switches, and a box that has disabled background warming still wants its prefetch to finish.
    startThemeOptimization()
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

  /**
   * Whether the pass **worker** is alive, as opposed to [backgroundWorkActive], which says only
   * whether it currently holds a lane.
   *
   * The two stopped being the same question when residency was scoped to the lane: a worker parked
   * at the quiet gate is running and holding nothing. Tests that want "the pass has settled" need
   * this one — reading the residency flag would let them proceed while the worker was merely
   * between slices, or before it had taken its first.
   */
  internal val optimizationPassRunning: Boolean
    get() = optimizationStarted.get()

  private data class ThemeOptimizationJob(
    val previewId: String,
    val overrides: PreviewOverrides,
    val cacheKey: String,
  )

  /** Fill every catalog-preview × declared-theme cache entry while the whole server is idle. */
  private fun startThemeOptimization() {
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
    // The finite declared-theme set is declared to the disk tier FIRST and unconditionally, so that
    // a deployment with the eager pass switched off still persists the renders visitors ask for.
    catalogThemeCache.configurePersistable(jobs.map { it.cacheKey })
    // Off by default — see [themeOptimizationEnabled]. Returning before `configureTargets` leaves
    // the cache with no targets, so `themeOptimizationSnapshot()` reports null and `/status` shows
    // no optimization row at all rather than one stuck at "waiting" forever.
    //
    // But renders adopted from disk still have to be CHECKED. Disabling the eager pass turns off
    // filling the cache, not trusting it: a restarted server with the pass off would otherwise
    // serve
    // every persisted entry without the fingerprint safety check ever running.
    if (!themeOptimizationEnabled) {
      verifyAdoptedRendersOnly(jobs)
      return
    }
    catalogThemeCache.configureTargets(jobs.map { it.cacheKey })
    if (jobs.isEmpty()) return
    // `fullyOptimized` is deliberately NOT an early return until the persisted renders have been
    // checked. A generation adopted whole from disk reports fully optimized on the first heartbeat,
    // so returning here would skip verification in exactly the fully-warmed restart case it exists
    // for — and a fingerprint that missed an input would then serve stale pixels indefinitely. The
    // check moves inside the task, below.
    // `converged`, not `fullyOptimized`, for the same reason the inner gate uses it: a catalog
    // holding another build's renders is warm everywhere and finished nowhere, so the narrower
    // question turned every heartbeat into an early return and the dirty queue was never reached
    // by a resident host either.
    if (catalogThemeCache.snapshot().converged && persistenceVerified.get()) return
    // Never start a pass into a broken renderer. The optimizer is the largest consumer of the
    // render gate, and every item it queues against an open breaker is pure waste — 4740 remaining
    // at a ~7h ETA on work where every single render fails (issue #3448). Targets stay configured
    // so `/status` keeps reporting the shortfall; `keepLiveWarm` re-enters this on every presence
    // heartbeat, so the pass resumes by itself if the breaker closes.
    if (renderBreakerStopsBackgroundWork()) return
    if (!optimizationStarted.compareAndSet(false, true)) return
    optimizationExecutor.execute {
      try {
        // Verification does NOT run here, ahead of admission — see the slot below. It renders, and
        // renders at startup are exactly what the catalog-load gate and the lane cap exist to
        // hold back: `prewarm` starts one of these tasks per catalog, so a warmed restart would
        // cold-start a daemon for every catalog at once while the others were still loading.
        // No stagger before the door, deliberately. Every catalog does become runnable the instant
        // the idle gate opens — measured on the deployed box as 11 catalogs entering inside 464 ms
        // — but with the cap in place a simultaneous arrival is harmless: two are admitted and the
        // rest are refused in microseconds and park. Sleeping them first would delay the two that
        // are going to win anyway, which costs cache throughput on an idle box to solve a problem
        // the cap already solved. Admission orders the arrivals by who has gone longest without a
        // lane, so losing a draw is not a permanent condition.
        //
        // ONE pass slot for the whole SLICE, held across warms and batches alike. Taking it per
        // batch would let a catalog pay a cold warm and then lose the slot before rendering
        // anything, which is the waste this cap exists to remove, not to reproduce.
        //
        // The loop is what makes rotation actually happen. Fair admission alone does not: a pass on
        // an idle box runs until its catalog is fully optimized, which for the 10,120-target
        // m3-catalog is ~28 hours, and a queue you reach the front of in 28 hours is still
        // starvation. A slice returns the lane on a preview boundary and re-queues — where its
        // freshly-stamped `lastRanAt` puts it behind everyone still waiting, so the next slice goes
        // to them and this catalog resumes once they have had theirs.
        while (true) {
          // **The gate is waited out BEFORE a lane is taken, not inside one.** Waiting inside
          // converts "the box is busy" into "two catalogs own both lanes indefinitely": the quiet
          // wait blocks until the server goes quiet, and on a box that never does — one held
          // session lease is enough, since the registry's idle clock then answers busy outright —
          // the two admitted passes park on their lanes forever while every other catalog is
          // refused every 20s. Measured on the deployed server: 2 lanes held for the whole 3h
          // uptime, 16 catalogs queued behind them, 8,052 refusals, `turnsGranted 0` everywhere.
          // A pass parked at the gate holding nothing costs a sleeping thread; parked on a lane it
          // costs every other catalog its turn.
          if (!awaitOptimizerTurn()) {
            catalogThemeCache.markPaused()
            if (!awaitOptimizerResume()) return@execute
            continue
          }
          val outcome =
            backgroundWork.withOptimizerSlot(label, optimizerAdmissionWaitMillis) {
              // The lane, not the worker, is what this host has to be resident for — see
              // [optimizationActive]. Set inside the slot and cleared on the way out, so the
              // catalog is suspendable again the instant it re-queues.
              optimizationActive.set(true)
              try {
                // Holding the turn established above, so the sample's renders are admitted on
                // exactly the terms every other background render is. Cheap and once per host: a
                // no-op when nothing was adopted from disk.
                if (!persistenceVerified.get()) verifyPersistedRenders(jobs)
                // `converged`, NOT `fullyOptimized`. The latter asks only whether every target is
                // cached, and a dirty entry IS cached — so on the normal state after adopting a
                // previous build's generation, or right after an operator asks for a regenerate,
                // this returned FINISHED and the pass never ran. The dirty queue inside
                // `runOptimizerPass` was unreachable in exactly the case it exists for.
                if (catalogThemeCache.snapshot().converged) PassOutcome.FINISHED
                else runOptimizerPass(jobs, sliceUntil = clock() + optimizerSliceMillis)
              } finally {
                optimizationActive.set(false)
              }
            }
          if (outcome == null) {
            // Stay in the admission queue without needing a visitor heartbeat to resurrect this
            // catalog. A global pause parks this worker too: expiry/resume does not emit a visitor
            // heartbeat, so exiting here would strand every unfinished host until someone opened
            // its page again.
            catalogThemeCache.markPaused()
            if (!awaitOptimizerResume()) return@execute
            continue
          }
          // A spent slice re-queues, and so does a pass the gate took the turn back from — that
          // one used to exit and wait for a visitor heartbeat, on the reasoning that re-queueing
          // would spin. It no longer can: the loop's next stop is the quiet gate above, which
          // parks until the box is actually quiet. Anything else — finished, breakered,
          // interrupted — is the pass deciding it is done for now.
          if (outcome != PassOutcome.SLICE_SPENT && outcome != PassOutcome.GATED) return@execute
        }
      } finally {
        optimizationActive.set(false)
        optimizationStarted.set(false)
      }
    }
  }

  /**
   * Check a few renders adopted from disk against what this daemon produces now, once per host.
   *
   * The fingerprint that named the persisted generation covers the inputs it was told about. An
   * input nobody thought of — a base image bumped without a release, a render default that never
   * reached the config string — changes the pixels without changing the name, and every entry under
   * that name is then quietly wrong. That matters more here than in an ordinary build cache: a
   * stale build artifact gets caught by a test, a stale preview is handed to an agent as ground
   * truth.
   *
   * Rendered through [live] rather than [renderPrefetch], deliberately: the prefetch path consults
   * this very cache and would hand back the bytes being verified, so the comparison would pass by
   * construction. Only a `DAEMON` generation counts as fresh evidence — anything served from a
   * cache proves nothing, and a daemon that cannot answer yet is "no evidence", not "mismatch".
   */
  private fun verifyPersistedRenders(jobs: List<ThemeOptimizationJob>) {
    if (persistenceVerified.get()) return
    val byKey = jobs.associateBy { it.cacheKey }
    val outcome = catalogThemeCache.verifySample { key ->
      val job = byKey[key] ?: return@verifySample null
      val daemonId = alias[job.previewId] ?: return@verifySample null
      val outcome = runCatching { live.render(daemonId, job.overrides) }.getOrNull()
      (outcome as? RenderOutcome.Ok)
        ?.takeIf { it.generation == RenderOutcome.Generation.DAEMON }
        ?.png
    }
    // Latched only once the question is actually answered. `NO_EVIDENCE` — every sampled render
    // came back Busy, Failed, or out of some cache — leaves it unlatched so the next pass asks
    // again; latching there would permanently skip the check on the one occasion it never ran.
    if (outcome.settled) persistenceVerified.set(true)
    if (outcome == CatalogThemeCache.VerifyOutcome.MISMATCH) {
      persistenceVerified.set(true)
      System.err.println(
        "serve: catalog $label — persisted theme renders no longer match this renderer; " +
          "dropped the generation and re-warming from scratch"
      )
    }
    // Deliberately NOT latched. The mismatch was detected but the generation is still on disk (its
    // write lock stayed held), so the question is not answered and the next pass must ask again.
    // Latching here would quarantine the adopted entries for the life of the process — withheld
    // from reads, still reported by `contains`, so the optimizer skips re-warming them — with
    // nothing left that would ever try the discard again.
    if (outcome == CatalogThemeCache.VerifyOutcome.MISMATCH_UNDISCARDED) {
      System.err.println(
        "serve: catalog $label — persisted theme renders no longer match this renderer, and the " +
          "generation could not be discarded (write lock held); withholding them and retrying"
      )
    }
  }

  /**
   * Check adopted renders for a catalog whose eager pass is switched off.
   *
   * Same admission as the pass itself — a lane, then the idle gate — because it renders, and a
   * disabled optimizer is not a licence to spend the box's daemons at startup. Runs on the
   * optimizer executor so the caller (a prewarm or a presence heartbeat) is never blocked on it.
   */
  private fun verifyAdoptedRendersOnly(jobs: List<ThemeOptimizationJob>) {
    if (persistenceVerified.get() || jobs.isEmpty()) return
    if (!optimizationStarted.compareAndSet(false, true)) return
    optimizationExecutor.execute {
      try {
        backgroundWork.withOptimizerSlot(label, OPTIMIZER_ADMISSION_WAIT_MILLIS) {
          // Resident for the lane, like the pass proper — this one renders too, and a suspension
          // landing mid-sample would close the daemon under it.
          optimizationActive.set(true)
          try {
            if (awaitOptimizerTurn()) verifyPersistedRenders(jobs)
          } finally {
            optimizationActive.set(false)
          }
          true
        }
      } finally {
        optimizationStarted.set(false)
      }
    }
  }

  /** Why an optimizer pass returned; [SLICE_SPENT] and [GATED] ask for another lane. */
  private enum class PassOutcome {
    /** Every target this pass could see is cached — nothing left to re-queue for. */
    FINISHED,
    /** The render breaker or a shutdown interrupt stopped the pass. */
    STOPPED,
    /**
     * Traffic took the turn back and it did not come back inside [OPTIMIZER_RESUME_WAIT_MILLIS], so
     * the pass returned its lane rather than idling on one.
     *
     * Distinct from [STOPPED] because the two want opposite things from the caller: a breakered
     * pass must not re-queue (nothing it renders can succeed), while a gated one must, or the
     * catalog is stranded until a visitor's heartbeat happens to revive it.
     */
    GATED,
    /** The lane slice ran out with work remaining. */
    SLICE_SPENT,
  }

  /**
   * One lane slice of the pass. Ends at [sliceUntil], when the work runs out, or when the gate, a
   * pause or the breaker stops it.
   *
   * Deliberately does NOT clear `optimizationActive` / `optimizationStarted` — those belong to the
   * executor task that may call this several times across slices, and clearing them here would let
   * a presence heartbeat start a second task on top of the loop still running.
   */
  private fun runOptimizerPass(jobs: List<ThemeOptimizationJob>, sliceUntil: Long): PassOutcome {
    // Render in BATCHES through the replica pool rather than one at a time through the
    // monolithic daemon. The pool is already five wide — it is what a visitor gets when they
    // pick a theme — and the prefetcher was queueing behind a single daemon lock right next to
    // it. One catalog at a time still (the background permit now wraps the batch, not each
    // render), so the box sees one bursting catalog rather than 21 taking turns per render.
    // Batched by PREVIEW, which is both the unit the daemon warms for and the unit the job
    // list is already ordered by: every theme of one preview renders together, so one warm is
    // amortised across all of them and the pass never interleaves two previews' daemon opens.
    // `contains`, not `get`: planning only needs to know WHETHER a target is warm. Reading it
    // pulls every already-persisted PNG off disk on every slice — hundreds of megabytes for a
    // partly warmed catalog — only for the 128 MB memory window to evict most of them again
    // before the next slice repeats the whole thing.
    val gaps = jobs.filterNot { catalogThemeCache.contains(it.cacheKey) }
    // Whether this slice is working the dirty queue rather than filling gaps, which decides how its
    // renders are issued: a gap can be answered from any tier, a dirty entry only by the daemon.
    // Carried as a property of the slice rather than of each job because the two queues are never
    // mixed — the dirty one is reached only once the gaps are gone.
    val regenerating = gaps.isEmpty()
    val byPreview =
      gaps
        .ifEmpty {
          // Gaps first, dirt second. Once every target is warm the pass used to report FINISHED
          // and stop, which was the whole story while warm meant "rendered by this build". It no
          // longer does: a generation adopted across a release is warm and inherited, and left
          // alone it would stay another build's pixels for the life of the catalog. These are
          // re-rendered at the same admission and the same slice as anything else — they are the
          // lowest-value work the pass has, because unlike a gap they are already serving
          // something.
          val dirty = catalogThemeCache.dirtyTargets().toSet()
          jobs.filter { it.cacheKey in dirty }
        }
        .groupBy { it.previewId }
    if (byPreview.isEmpty()) return PassOutcome.FINISHED
    val allPreviewIds = jobs.map { it.previewId }.distinct()
    val start = Math.floorMod(optimizerPreviewCursor.get(), allPreviewIds.size)
    val previewOrder =
      (allPreviewIds.drop(start) + allPreviewIds.take(start)).filter(byPreview::containsKey)
    var previewsDone = 0
    for (previewId in previewOrder) {
      val previewJobs = byPreview.getValue(previewId)
      // The slice is checked HERE and nowhere finer, on the preview boundary. A preview is the
      // unit a daemon warms for, so giving the lane back between previews never abandons a warm
      // that has just been paid for — whereas cutting mid-preview would throw away the most
      // expensive thing the pass does (34-68s on an Android lane) to save a few seconds of the
      // cheapest.
      //
      // A slice always yields at least one preview, whatever the clock says. Checking the deadline
      // before any work would let a slice shorter than the admission round-trip re-queue forever
      // without rendering anything — a livelock dressed as fairness, and the exact shape of the
      // starvation this whole change is undoing.
      if (previewsDone > 0 && clock() >= sliceUntil) {
        catalogThemeCache.markPaused()
        return PassOutcome.SLICE_SPENT
      }
      previewsDone++
      optimizerPreviewCursor.set((allPreviewIds.indexOf(previewId) + 1) % allPreviewIds.size)
      // Re-checked per preview as well as at entry: a breaker can trip mid-pass (that is the
      // rate trip's whole job), and the pass must stop feeding the renderer the moment it does
      // rather than grinding through the remaining thousands of items.
      if (renderBreakerStopsBackgroundWork()) return PassOutcome.STOPPED
      // Gate BEFORE the warm, not just before the renders. `daemonWarmOrScheduling` starts a
      // cold daemon, which is the single most expensive thing this pass can do to a box that is
      // still loading catalogs or serving traffic — exactly what the idle gate exists to
      // prevent. Warming ahead of it let every catalog host kick off a cold start at prewarm.
      if (!awaitOptimizerTurn()) return gateStopOutcome()
      val previewDaemonId = alias[previewId]
      // Await a cold warm ONCE per preview rather than letting each theme rediscover it. The
      // old per-job loop spent retry budget on this; here it is a precondition of the batch.
      // A shared-pool optimizer deliberately leaves the catalog primary cold. Its background
      // renders use reapable replicas, so RAM returns between slices instead of accumulating one
      // permanent primary for every catalog the fair scheduler visits. Without the pool there is
      // no disposable lane, so retain the ordinary one-time warm.
      if (
        (sharedDaemonPool == null || !sharedDaemonRenders) &&
          previewDaemonId != null &&
          !warmDaemonIds.contains(previewDaemonId)
      ) {
        daemonWarmOrScheduling(previewDaemonId)
        // A cold warm is real render work and can run to minutes. Excluding it from both
        // buckets shrank the rate's denominator, so a cold catalog reported a rate it was
        // nowhere near.
        val warmFrom = clock()
        if (warmingInFlight.contains(previewDaemonId) && !awaitWarmCompletion(previewDaemonId)) {
          catalogThemeCache.recordWarm(clock() - warmFrom)
          return PassOutcome.STOPPED
        }
        catalogThemeCache.recordWarm(clock() - warmFrom)
      }
      var index = 0
      while (index < previewJobs.size) {
        // Checked per batch, and a batch is bounded by ONE render — so a visitor arriving mid
        // batch still waits at most a render, which is the guarantee the old per-render permit
        // was expressing.
        if (!awaitOptimizerTurn()) return gateStopOutcome()
        val batch =
          previewJobs.subList(index, minOf(index + optimizerBatchWidth(), previewJobs.size))
        index += batch.size
        catalogThemeCache.markRunning(clock())
        // Time the RENDER inside the permit. Starting the clock before `withRenderPermit`
        // charged the server-wide queue to renderMillis, which would make a permit-bound
        // deployment read as render-bound — defeating the one diagnostic this exists for.
        // The queue time is its own bucket: this pass HAS its turn and is merely outnumbered by
        // other catalogs, which is a different problem from the gate withholding the turn.
        val permitWaitFrom = clock()
        val outcomes =
          backgroundWork.withRenderPermit {
            val renderFrom = clock()
            catalogThemeCache.recordPermitWait(renderFrom - permitWaitFrom)
            // Clear the pool's high-water marks so the reads below belong to THIS batch.
            sharedDaemonPool?.takePeakInFlight()
            sharedDaemonPool?.takeColdStartMillis()
            renderOptimizerBatch(batch, regenerating).also {
              val elapsed = clock() - renderFrom
              // A replica's daemon starts on its FIRST render, so that render carries a full
              // cold start. Only the primary's warm is visible above (`awaitWarmCompletion`),
              // and a five-wide batch can be opening four cold replicas underneath it — which
              // would land 34-68s each in the per-entry bucket, exactly the conflation the
              // warm/batch split exists to remove. Capped at the interval it is taken from:
              // the pool reports the longest overlapping cold start, but a foreground borrow
              // could have started one before this batch began.
              val cold = (sharedDaemonPool?.takeColdStartMillis() ?: 0L).coerceIn(0L, elapsed)
              catalogThemeCache.recordWarm(cold)
              // Width is the peak number of daemons that ran CONCURRENTLY, not the job count.
              // A batch submits N jobs, but when the seat budget affords no replica the pool
              // queues them onto a host already in circulation instead of spawning one — so N
              // jobs can be N threads taking turns on one daemon. Counting jobs reported that
              // as N-wide, which is exactly the collapse this number exists to expose.
              catalogThemeCache.recordBatch(
                sharedDaemonPool?.takePeakInFlight() ?: 1,
                elapsed - cold,
              )
            }
          } ?: return PassOutcome.STOPPED
        // Only a FRESH daemon render is optimizer production. The batch is filtered for cache
        // misses when it is built, but a foreground request can fill a target while this
        // catalog queues for the render permit — `renderLeased` then short-circuits through
        // `cachedRender` and hands back an Ok stamped CATALOG_CACHE. Counting that would
        // re-open the same inflated rate this counter exists to close.
        catalogThemeCache.recordProduced(
          outcomes.count {
            it is RenderOutcome.Ok && it.generation == RenderOutcome.Generation.DAEMON
          }
        )
        for ((job, outcome) in batch.zip(outcomes)) {
          // A render that SUCCEEDED needs no bookkeeping — `put` cleared this key's failure and
          // busy counts on the way through the cache. Tested before anything else because the
          // `when` below ends in an `else` that marks the key failed, so letting an `Ok` reach it
          // would record a failure for every entry the pass got right.
          if (outcome is RenderOutcome.Ok) continue
          // A warm key means the gap closed — by a foreground render that beat this one — so again
          // there is nothing to record. Not so while REGENERATING: every dirty key is warm by
          // definition, and skipping on that basis would swallow a Busy or a Failed on the one
          // queue whose whole purpose is to replace what is already there.
          if (!regenerating && catalogThemeCache.get(job.cacheKey) != null) continue
          // Busy is "ask again", not a failure: the warm above may still be settling. Leave it
          // unmarked so a later pass retries instead of spending the `failed` count on it.
          when (outcome) {
            // "ask again" — the warm above may still be settling, so a later pass retries
            // rather than spending the catalog's `failed` count on it. Counted, though: "ask
            // again" with no ceiling is indistinguishable from "never", and this is the only
            // lane that can tell them apart. After `BUSY_LATCH` consecutive passes the key
            // latches with a reason, so /status names it instead of reporting `failed: 0`
            // beside a `remaining` that never moves. A successful render clears the count.
            RenderOutcome.Busy -> catalogThemeCache.recordBackgroundBusy(job.cacheKey)
            // Count the failure but do NOT latch on the first one. `failureReason` treats a
            // latched key as terminal and answers foreground requests with a 409, so a single
            // flaky background render — a cold-start timeout, a daemon restart — would
            // otherwise make that thumbnail permanently unavailable until the catalog
            // generation refreshes. `recordRenderFailure` latches only after a run of them,
            // which is the retry budget the old per-job loop provided.
            is RenderOutcome.Failed ->
              catalogThemeCache.recordRenderFailure(job.cacheKey, outcome.reason)
            else -> catalogThemeCache.markFailed(job.cacheKey)
          }
        }
      }
      // A turn the ceiling forced buys exactly this one preview. Hand the lane back rather than
      // carrying a turn the box never actually granted into the next one — the ceiling exists so a
      // permanently-shut gate still makes progress, not so it stops being a gate.
      if (optimizerTurnForced.compareAndSet(true, false)) {
        optimizerHasTurn.set(false)
        catalogThemeCache.markPaused()
        return PassOutcome.SLICE_SPENT
      }
    }
    catalogThemeCache.markPassFinished(clock())
    return PassOutcome.FINISHED
  }

  /**
   * True when the live lane's circuit breaker is open, so background prefetch must stand down.
   * Marks the cache paused on the way out — the pass is stopping, not finishing, and `/status` must
   * not read a broken catalog's abandoned targets as a completed optimization.
   */
  private fun renderBreakerStopsBackgroundWork(): Boolean {
    if (renderBreaker()?.open != true) return false
    catalogThemeCache.markPaused()
    return true
  }

  /**
   * How many prefetch renders to run at once: the catalog's own burst width, which is the replica
   * pool's capacity when it has one and 1 otherwise. Bounded by the pool itself — a replica that
   * the seat budget cannot afford narrows the batch rather than spawning a JVM the box can't run.
   */
  private fun optimizerBatchWidth(): Int =
    // Only the SHARED replica pool makes a per-preview batch parallel: its replicas are independent
    // processes. Under per-preview routing (`sharedDaemonRenders=false`) every theme of one preview
    // resolves to the SAME per-preview daemon, so a wide batch would contend on one render lock and
    // all but one would come back Busy — `themeRenderBurstCapacity` is 5 there because different
    // *previews* can run in parallel, which is not what this batch is.
    if (sharedDaemonRenders && sharedDaemonPool != null) {
      sharedDaemonPool.backgroundCapacity().coerceIn(1, MAX_OPTIMIZER_BATCH)
    } else {
      1
    }

  /**
   * Render one batch concurrently through the leased lane, preserving input order in the result.
   *
   * `renderLeased` is the same entry point a visitor's theme burst uses, so the prefetcher borrows
   * the same replicas — which is the whole point: a five-wide lane sitting idle next to a serial
   * prefetcher was the throughput bug.
   */
  private fun renderOptimizerBatch(
    batch: List<ThemeOptimizationJob>,
    regenerating: Boolean,
  ): List<RenderOutcome> {
    if (batch.size == 1) {
      val job = batch.single()
      return listOf(renderPrefetch(job.previewId, job.overrides, regenerating))
    }
    return batch
      .map { job ->
        optimizerBatchExecutor.submit<RenderOutcome> {
          runCatching { renderPrefetch(job.previewId, job.overrides, regenerating) }
            .getOrElse { RenderOutcome.Failed("prefetch render threw: ${it.message}") }
        }
      }
      .map { future ->
        runCatching { future.get() }
          .getOrElse { RenderOutcome.Failed("prefetch batch: ${it.message}") }
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
    // An operator standing the optimizer down means the pass in flight too, not just the next one
    // admitted — checked here because this is the one call every warm and every batch already goes
    // through, so a pause takes effect within a render rather than at the end of a catalog.
    if (!awaitOptimizerResume()) {
      optimizerHasTurn.set(false)
      catalogThemeCache.markPaused()
      return false
    }
    if (!optimizerHasTurn.get()) {
      // The wait is charged inside [awaitQuiet], per poll — see there for why charging it here,
      // on the way out, was the wrong place. Unbounded, deliberately: this call holds no lane, so
      // a pass parked here costs a sleeping thread and nothing else.
      val granted = awaitServerIdle()
      if (!granted) return false
      catalogThemeCache.recordTurnGranted()
      optimizerHasTurn.set(true)
      optimizerSampledAt.set(clock())
      return true
    }
    // A forced turn is not re-examined against traffic: the ceiling granted it precisely because
    // the box never looks quiet, so asking again would take it straight back. It lasts one preview
    // — see where [optimizerTurnForced] is cleared.
    if (optimizerTurnForced.get()) return true
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
    catalogThemeCache.recordTurnYielded()
    catalogThemeCache.markPaused()
    // Re-enter on the SHORT window, not the full entry one. [themeOptimizationIdleMillis] answers
    // "may I start work on a box someone might be using?" — a cold-start question, asked once. Once
    // the pass has been running, the box has already proved it goes quiet, and the only question
    // left is "has the visitor who just interrupted me finished?". Charging the full entry window
    // per interruption is what capped throughput: at a ~50% yield rate a 60s re-entry averages
    // ~30s/entry against a sub-second render, i.e. ~97% waiting.
    //
    // **Bounded, unlike the cold entry above**, because this one waits with a lane in hand. A pass
    // that cannot get its turn back inside [OPTIMIZER_RESUME_WAIT_MILLIS] gives the lane up and
    // re-parks at the cold gate, where waiting is free; holding it while the box stays busy is how
    // two catalogs came to own both lanes for three hours.
    return awaitQuiet(OPTIMIZER_RESUME_MILLIS, maxWaitMillis = OPTIMIZER_RESUME_WAIT_MILLIS).also {
      if (it) {
        // A resume IS a grant. Counting only cold entries made yields exceed grants after any
        // interrupted pass, which reads as the gate losing turns it never handed out.
        catalogThemeCache.recordTurnGranted()
        optimizerHasTurn.set(true)
        optimizerSampledAt.set(clock())
      }
    }
  }

  /**
   * Why a mid-pass [awaitOptimizerTurn] returned false: a shutdown interrupt, or the gate simply
   * not reopening in time.
   *
   * They differ in what the caller should do next — [PassOutcome.STOPPED] ends the worker,
   * [PassOutcome.GATED] sends it back to the cold gate to wait without a lane — and conflating them
   * is what left a gated catalog stranded until a visitor's heartbeat revived it.
   */
  private fun gateStopOutcome(): PassOutcome =
    if (Thread.currentThread().isInterrupted) PassOutcome.STOPPED else PassOutcome.GATED

  private fun awaitServerIdle(): Boolean = awaitQuiet(themeOptimizationIdleMillis)

  /**
   * Block until the server has been untouched for [quietMillis]. Polls at a fraction of the window
   * so a short resume window is not rounded up to a full second of dead time — a 1s poll against a
   * 1.5s window would put the floor back where it started.
   *
   * **The gate wait is charged here, per poll, not by the caller once this returns.** Charging on
   * the way out means a pass still waiting has spent, as far as `/status` is concerned, no time at
   * the gate — so the one counter that names a closed gate reads `gateWaitMillis: 0`, which is also
   * what a pass that sailed straight through reports. A box whose gate had never opened once in
   * three hours published an all-zero optimizer row on every catalog and looked idle by choice.
   * Accruing as we wait makes an unopened gate visible while it is still unopened, which is the
   * only time the reading is any use.
   */
  private fun awaitQuiet(quietMillis: Long, maxWaitMillis: Long = Long.MAX_VALUE): Boolean {
    val pollMillis = quietMillis.coerceAtMost(1_000L).coerceAtLeast(50L) / 2
    val startedAt = clock()
    optimizerGateBlockedSince.compareAndSet(Long.MIN_VALUE, startedAt)
    while (true) {
      if (!awaitOptimizerResume()) return false
      val idleMillis = serverIdleMillis()
      if (idleMillis != null && idleMillis >= quietMillis) {
        optimizerGateBlockedSince.set(Long.MIN_VALUE)
        return true
      }
      if (grantForcedTurn()) return true
      if (clock() - startedAt >= maxWaitMillis) return false
      catalogThemeCache.markPaused()
      val polledFrom = clock()
      val slept = pauseOptimization(pollMillis)
      catalogThemeCache.recordGateWait(clock() - polledFrom)
      if (!slept) return false
    }
  }

  /**
   * The ceiling: once the gate has withheld a turn for [optimizerGateCeilingMillis] without
   * interruption, grant one anyway.
   *
   * **A gate that can close permanently is indistinguishable from the feature being off**, and this
   * one could: the quiet window is measured from `ServeSessionRegistry.idleMillis()`, which used to
   * answer *busy* outright — not a large number, but `null` — while any session held an open lease.
   * One long-lived WebSocket, or one lease leaked by a request cancelled mid-flight, and no amount
   * of waiting would ever satisfy the window. The deployed server sat in exactly that state for its
   * whole uptime: 23 catalogs, `turnsGranted 0`, 1 of 17,914 entries cached.
   *
   * That clock has since been relaxed (#4312): a lease stops suppressing it once its holder has
   * been quiet, so the ordinary idle-tab case now opens the gate on its own and this ceiling should
   * fire far less often. It stays because it is a backstop against the *class* of failure, not
   * against that one instance — a leaked lease still counts as busy for its quiet window, and any
   * future clock input can jam the same way. `turnsForced` climbing on a box with no visitors is
   * the signal that something is jamming it again.
   *
   * The grant is deliberately small and self-limiting — one preview, then back to the gate (see
   * [optimizerTurnForced]) — so a genuinely busy box pays one preview per ceiling period per
   * catalog rather than losing the politeness the gate exists for.
   *
   * Two things it will **not** override, because both are correct refusals rather than a stuck
   * clock: a pause (manual or pressure), which [awaitOptimizerResume] has already blocked on above,
   * and catalog loading — a slow daemon start there is recorded as `livebundle-unavailable` and
   * degrades that catalog to baked PNGs for the life of the process, which is a far worse outcome
   * than a cold theme cache.
   */
  private fun grantForcedTurn(): Boolean {
    if (optimizerGateCeilingMillis <= 0 || backgroundWork.catalogsLoading) return false
    val blockedSince = optimizerGateBlockedSince.get()
    if (blockedSince == Long.MIN_VALUE || clock() - blockedSince < optimizerGateCeilingMillis) {
      return false
    }
    optimizerGateBlockedSince.set(Long.MIN_VALUE)
    optimizerTurnForced.set(true)
    catalogThemeCache.recordTurnForced()
    return true
  }

  /** Park an unfinished optimizer through a timed/manual pause, stopping only for shutdown. */
  private fun awaitOptimizerResume(): Boolean {
    while (backgroundWork.optimizersPaused()) {
      catalogThemeCache.markPaused()
      if (!pauseOptimization(OPTIMIZER_PAUSE_POLL_MILLIS)) return false
    }
    return !Thread.currentThread().isInterrupted
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

  /**
   * Accessibility inspection is another explicit live render, just like scroll capture. The
   * catalog's baked lane has no semantics tree, but its carried daemon does; forwarding the
   * capability is what makes the Accessibility control appear on a catalog component page.
   */
  override val hasA11yOverlay: Boolean by lazy { live.hasA11yOverlay }

  override fun hasA11yOverlayFor(previewId: String): Boolean =
    previewId in alias && live.hasA11yOverlayFor(alias.getValue(previewId))

  /**
   * Annotation capability comes from the live lane, NOT from [canApplyOverrides].
   *
   * The composite reports `canApplyOverrides = false` because browsing serves baked pixels — but
   * the default `hasDesignAnnotations` reads exactly that flag, so a catalog fronted by a live
   * daemon advertised itself as unable to produce the layers its daemon produces on request.
   */
  override val hasDesignAnnotations: Boolean by lazy { live.hasDesignAnnotations }

  override fun hasDesignAnnotationsFor(previewId: String): Boolean =
    previewId in alias && live.hasDesignAnnotations

  /** The baked half: a catalog that published typography can inspect an unmapped variant too. */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    baked.hasPublishedTypographyFor(previewId)

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

  /**
   * The "Live (stream)" toggle is offered (unlike a plain static catalog) — until this catalog's
   * live lane breaks. An open render breaker means no live render can succeed, so the catalog must
   * stop advertising `live` on `/status` and stop offering the toggle: reporting a healthy live
   * lane at a 95% failure rate is issue #3448's third consequence.
   *
   * Read off [renderBreaker] rather than `live.hasLiveStream` so a composite whose live host is a
   * non-daemon stand-in still advertises the stream exactly as before.
   */
  override val hasLiveStream: Boolean
    get() = renderBreaker()?.open != true

  /**
   * The live lane's open breaker, if any. Only the monolithic daemon is consulted: the per-preview
   * pool's residents come and go (and a broken *classpath* breaks them all identically, so the
   * monolith speaks for them), while the pool snapshot would need a live read of hosts this must
   * never wake.
   */
  override fun renderBreaker(): RenderBreakerSnapshot? = live.renderBreaker()

  /**
   * The baked catalog's own degradations (baked-only, unverified, deferred-not-served — which this
   * composite previously dropped on the floor, reporting `degradation: null` for every catalog it
   * fronted), plus the live lane's broken-render-lane degradation when its breaker is open.
   */
  override val degradations: List<ServeDegradation>
    get() = baked.degradations + live.degradations

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

  // Unconditional, unlike [bakedRender] above: this measures the *published* pixels, and an unfurl
  // card always points at the override-free render regardless of what the live lane could produce.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? =
    baked.bakedRenderSize(previewId)

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = false)

  /**
   * A broken live lane latches every render **that would have reached it**, not just the ones the
   * theme cache has recorded against: the fault is in the daemon, so the answer is the same for
   * every preview it serves. Checked first so a `/render` gets the terminal 409 naming the real
   * error before it takes a render slot — ahead of the per-key theme latch, which only knows about
   * keys the optimizer reached.
   *
   * Scoped by [daemonIdForOverrideRender] — the same predicate [render] routes on — rather than by
   * bare membership in [alias], and that scoping is the whole point. A broken daemon must only
   * refuse the requests the daemon was the answer to:
   * - an **unmapped** variant has no live twin at all and always replayed baked pixels;
   * - a **mapped** id browsed with no override (or with one the baked PNG already satisfies) also
   *   replays baked pixels — the routing has said so since the composite was written.
   *
   * Latching those turned one broken daemon into a catalog-wide blackout: with the breaker open,
   * `bakedRender` answers only from pixels **already local** (deliberately — measuring an image
   * must not trigger a fetch), so on a catalog whose PNGs are fetched lazily from its delivery
   * branch the fast path returns null, the latch fired ahead of [render] — which *would* have
   * fetched them — and every `<img>` on every page 409'd, including the live-render URLs the
   * issue-report template embeds as evidence (compose-ai-tools#4220). The degradation banner
   * meanwhile promised those very "baked PNG snapshots", so the page contradicted itself.
   *
   * A request that genuinely needs the daemon — any override the baked PNG can't represent, or a
   * live-only (deferred) id with no baked pixels at all — still gets the terminal 409 naming the
   * linkage error, because for those there is nothing honest to serve.
   */
  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    (if (daemonIdForOverrideRender(previewId, overrides) != null)
      live.renderBreaker()?.takeIf { it.open }?.reason
    else null) ?: themeCacheKey(previewId, overrides)?.let(catalogThemeCache::failureReason)

  /**
   * Shed the pooled daemons — burst replicas and per-preview residents — while keeping the
   * monolithic [live] daemon, which is this catalog's warm browse lane and the thing the whole
   * cold-start design exists to hold on to. Both pools reopen on demand.
   */
  override fun releaseIdleDaemons(idleMillis: Long): Int =
    (sharedDaemonPool?.reapIdle(idleMillis) ?: 0) + perPreviewReapIdle(idleMillis)

  override fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = true)

  /**
   * A leased render issued by the idle theme optimizer rather than by a waiting visitor.
   *
   * Identical routing to [renderLeased] — it wants the same wide shared-daemon lane — but any
   * replica it opens is charged to the BACKGROUND seat remainder. The optimizer is background
   * residency by definition and does not end, so pricing it as foreground let prefetching hold the
   * stream reserve for hours; see [ServeSharedDaemonPool.render].
   */
  private fun renderPrefetch(
    previewId: String,
    overrides: PreviewOverrides,
    regenerating: Boolean = false,
  ): RenderOutcome =
    renderInternal(
      previewId,
      overrides,
      leased = true,
      background = true,
      bypassCache = regenerating,
    )

  private fun renderInternal(
    previewId: String,
    overrides: PreviewOverrides,
    leased: Boolean,
    background: Boolean = false,
    /**
     * Skip the cache read and go to the daemon.
     *
     * Set only by the dirty queue, and the thing that makes that queue work at all. A dirty entry
     * is deliberately still *servable* — that is the point, a possibly-stale preview beats a cold
     * render — so the ordinary read here answers from [CatalogThemeCache] and the render never
     * reaches a daemon. No fresh bytes, no `put`, no flag cleared: the pass would select the same
     * dirty set every slice, render nothing, and report progress it had not made. Regeneration has
     * to ask the renderer, because a render is the entire question being asked.
     */
    bypassCache: Boolean = false,
  ): RenderOutcome {
    val catalogCacheKey = catalogCacheKey(previewId, overrides)
    val themeCacheKey = themeCacheKey(previewId, overrides)
    if (!bypassCache) {
      cachedRender(previewId, overrides)?.let {
        return it
      }
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
        return cacheCatalogRender(
          catalogCacheKey,
          renderDaemon(daemonId, overrides, leased, background),
        )
      }
      // Only await the daemon when it's warm and free. A cold Android render can take minutes, and
      // blocking the browse — and the HTTP render slot it holds — on it is what saturates the whole
      // server. Override requests cannot fall back to baked pixels: those pixels ignore the
      // requested value and the HTTP layer refuses them rather than returning a dishonest 200.
      // A leased batch is an explicit request to pay for parallel live pixels now. Let its shared
      // replicas cold-start on the request path if necessary; otherwise the per-id warm guard would
      // return Busy for every card and the pool would never grow. Ordinary renders retain the
      // baked-first/background-warm behaviour.
      // A foreground override on a cold id used to schedule a warm and then abandon its own render
      // — returning Busy (themes) or baked pixels that become a 503 (knobs) despite already holding
      // a render slot it was prepared to wait on. For themes that made the cache load-bearing for
      // correctness; for knobs it made the first edit on every cold preview fail (#4149).
      //
      // A warm render is sub-second (p50 ~0.25-1.1s on the public box), so the honest answer is to
      // render. The gate exists for the one case where that isn't true — a COLD daemon, 34-68s —
      // so wait for the warm this request just scheduled, bounded, and only give up if the cold
      // start really is going to outlast the request.
      var liveNotFound = false
      if (leased || daemonWarmOrScheduling(daemonId) || awaitForegroundWarm(daemonId)) {
        val live = renderForegroundBounded(daemonId, overrides, leased, background)
        liveNotFound = live is RenderOutcome.NotFound
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
      // Every remaining request here carries a routed override. Baked pixels cannot satisfy it,
      // so a cold warm that missed the foreground bound is retryable Busy, never a dishonest baked
      // response that the HTTP correctness guard converts into a late 503.
      if (overrides != PreviewOverrides() && !liveNotFound) return RenderOutcome.Busy
      return baked.render(previewId, overrides)
    } finally {
      if (themeCacheKey != null) themeRendersInFlight.remove(themeCacheKey)
    }
  }

  private fun renderForegroundBounded(
    daemonId: String,
    overrides: PreviewOverrides,
    leased: Boolean,
    background: Boolean,
  ): RenderOutcome {
    if (!warmInBackground || leased || background || foregroundOverrideTimeoutMillis <= 0)
      return renderDaemon(daemonId, overrides, leased, background)
    val task =
      foregroundRenderExecutor.submit<RenderOutcome> {
        renderDaemon(daemonId, overrides, leased, background)
      }
    return try {
      task.get(foregroundOverrideTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      task.cancel(true)
      RenderOutcome.Busy
    } catch (_: InterruptedException) {
      task.cancel(true)
      Thread.currentThread().interrupt()
      RenderOutcome.Busy
    } catch (e: ExecutionException) {
      task.cancel(true)
      RenderOutcome.Failed(e.cause?.message ?: "foreground render failed")
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
      // A player the parity run staged is offerable whatever the daemon is doing — the bytes are
      // published, so the lane answers without one. This is also what keeps the catalog's
      // preferred embedded default in the enabled set on a box whose daemon is down or absent,
      // rather than silently demoting the page to the JS canvas.
      addAll(stagedRcPlayers(previewId).filterNot { it in this })
    }
      .sortedBy { RcPlayerBackend.UNIVERSE.indexOf(it) }
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
    background: Boolean = false,
  ): RenderOutcome {
    val outcome =
      if (sharedDaemonRenders && leased && sharedDaemonPool != null) {
        sharedDaemonPool.render(daemonId, overrides, background = background)
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
   * Produce accessibility data from the live daemon rather than the baked snapshot. The exact
   * viewer overrides are deliberately retained: changing theme, font scale, device, locale or a
   * named knob must inspect the newly rendered composition, not the catalog's original pixels.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    val daemonId = alias[previewId] ?: return A11yOutcome.NotFound
    return liveHostFor(daemonId).renderA11y(daemonId, overrides)
  }

  /**
   * The typography + theme inspection layers follow [renderA11y] exactly: the baked lane has no
   * daemon to capture a `compose/semantics` tree from, so a mapped id routes live and an unmapped
   * one has nothing to inspect.
   *
   * Without this the composite inherited [ServeHost]'s daemon-less default — `NotFound` — while the
   * viewer still offered the Typography checkbox from the catalog's *published reference*
   * annotations (issue #4254). Ticking it on the Compose render fetched `<frame>.annotations`, got
   * a 404, and drew nothing: a control that looked live on every catalog page and worked on none of
   * them.
   */
  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
  ): AnnotationsOutcome {
    val daemonId = alias[previewId] ?: return bakedAnnotations(previewId, overrides)
    val live = liveHostFor(daemonId).renderAnnotations(daemonId, overrides)
    // A daemon that carries no semantics lane answers NotFound. The catalog may still have
    // published typography for this preview, so fall back to it rather than leaving the layer
    // blank — under the same rule [bakedAnnotations] states. A `Failed` is a real error and
    // travels, unchanged: quietly answering a broken render with the published facts would
    // describe pixels the visitor is not being shown.
    return if (live is AnnotationsOutcome.NotFound) bakedAnnotations(previewId, overrides) else live
  }

  /**
   * The catalog's published annotations, but ONLY where they describe the pixels on screen.
   *
   * They were measured over the baked frame, so they are true exactly while the baked frame is what
   * this host serves — which is what [CatalogLiveRouting.overridesAffectRender] answers, the same
   * predicate [render] routes on. Under a font scale or a knob edit the daemon draws different
   * pixels, and published bounds over those would put every box in the wrong place while looking
   * entirely deliberate.
   */
  private fun bakedAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
  ): AnnotationsOutcome =
    if (CatalogLiveRouting.overridesAffectRender(previewId, overrides)) AnnotationsOutcome.NotFound
    else baked.renderAnnotations(previewId, overrides)

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
        // OR rather than overwrite: the baked side may already know this card is a specimen from
        // its catalog `section`/`fixedTheme` metadata, and a daemon twin built from an older
        // bundle (no `fixedTheme` in its `previews.json`) must not be able to clear that.
        fixedTheme = p.fixedTheme || twin.fixedTheme,
        uiMode = twin.uiMode,
        // …and the rest of the backdrop evidence with it. A published catalog's baked staging
        // directory carries no root `previews.json`, so the baked side's `showBackground` /
        // `backgroundColor` are always the annotation defaults; the daemon twin is the only place
        // those values exist on this path. Copying `uiMode` alone would leave the main
        // catalog-serving lane resolving every preview's ground from the catalog stage — the
        // per-preview half of `PreviewBackdrop` silently inert exactly where it matters most.
        showBackground = twin.showBackground,
        backgroundColor = twin.backgroundColor,
        // The device frame arrives the same way and for the same reason — `@Preview(device = …)`
        // lives in `previews.json`, which the baked staging directory does not carry — so without
        // this the round-device clip is inert on precisely the lane that serves a published Wear
        // catalog. Preferring the twin but falling back keeps a baked-only card (no daemon twin
        // for it) on whatever it already had rather than clearing it to null.
        deviceFrame = twin.deviceFrame ?: p.deviceFrame,
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
    if (optimizerBatchExecutorDelegate.isInitialized()) {
      try {
        optimizerBatchExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread prefetch batch pool
      }
    }
    if (warmInBackground) {
      try {
        warmExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread warm pool
      }
    }
    if (foregroundRenderExecutorDelegate.isInitialized()) {
      try {
        foregroundRenderExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of bounded foreground render workers
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
    /**
     * How long a parked catalog waits at the admission door before giving up for this pass.
     *
     * Bounded, and deliberately shorter than the idle window: a catalog that sleeps at the door
     * through the whole quiet period has converted "wait your turn" into "miss your turn", which is
     * the starvation the cap is meant to prevent, not cause. `keepLiveWarm` re-enters it on the
     * next presence heartbeat.
     */
    internal const val OPTIMIZER_ADMISSION_WAIT_MILLIS = 20_000L

    /**
     * Whole-server quiet the idle gate requires before a cold pass may start.
     *
     * Read through a function rather than held in a `val` so the system property is still honoured
     * when it is set after this class loads, and so [ServeBackgroundWork] can publish the same
     * number on `/status.json` without restating the default. A gate whose threshold is invisible
     * is one nobody can tell from a gate that is simply never reached.
     */
    internal fun themeOptimizationIdleMillisDefault(): Long =
      System.getProperty("composeai.serve.themeOptimizationIdleMillis")?.toLongOrNull() ?: 60_000L

    /** Default lane slice — see the `optimizerSliceMillis` constructor parameter for the trade. */
    internal const val DEFAULT_OPTIMIZER_SLICE_MILLIS = 5 * 60_000L

    internal const val OPTIMIZER_YIELD_MILLIS = 1_500L

    private const val OPTIMIZER_PAUSE_POLL_MILLIS = 100L

    /**
     * Quiet window required to RESUME after yielding, as opposed to the cold-entry window. Short on
     * purpose: the visitor who interrupted has stopped, and the pass is trying to fill a cache at
     * render speed (sub-second per entry). It must still exceed [OPTIMIZER_YIELD_MILLIS], or a
     * resume could immediately re-detect the activity it just waited out and livelock.
     */
    internal const val OPTIMIZER_RESUME_MILLIS = 2_000L

    /**
     * How long a pass that has yielded will wait, **holding its lane**, for the box to go quiet
     * again before giving the lane back.
     *
     * The trade is re-warming against lane occupancy. Shorter than a cold daemon start (34-68s on
     * an Android/Robolectric lane) and a pass keeps throwing away warms it has just paid for;
     * unbounded — which is what this was — and a box that never goes quiet has its lanes held by
     * the first passes to take them, permanently. Thirty seconds rides out an ordinary browse
     * without re-warming, while a box that is busy for minutes rotates its lanes instead of
     * freezing them.
     */
    internal const val OPTIMIZER_RESUME_WAIT_MILLIS = 30_000L

    /**
     * Default ceiling on how long the idle gate may withhold a turn — see [grantForcedTurn] for why
     * a gate with no ceiling is a gate that can turn the feature off.
     *
     * Ten minutes, and the cost of being wrong is bounded by the grant's size rather than by this
     * number: a forced turn buys ONE preview. A 23-catalog box that never goes quiet therefore
     * spends 23 previews per ten minutes on background work — still behind the render permit and
     * the two-lane cap — against a cache that otherwise never fills at all.
     */
    internal fun optimizerGateCeilingMillisDefault(): Long =
      System.getProperty("composeai.serve.themeOptimizationGateCeilingMillis")?.toLongOrNull()
        ?: (10 * 60_000L)

    /**
     * Ceiling on prefetch batch width. Matches the replica pool's own capacity, so the batch can
     * never ask the pool for more lanes than it has; the seat budget narrows it further on a small
     * box.
     */
    internal const val MAX_OPTIMIZER_BATCH = ServeSharedDaemonPool.DEFAULT_CAPACITY

    private const val WARM_POLL_MILLIS = 50L
  }
}
