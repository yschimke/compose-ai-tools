package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-wide admission for **background, best-effort catalog work** — today the catalog
 * theme-cache optimizer ([ServeCatalogLiveHost]'s idle pass), which pre-renders every catalog
 * preview under every declared theme so a later theme selection is instant.
 *
 * That work is worth doing and worth *never* doing at the expense of something a visitor is waiting
 * for. Two things it must yield to, both learned from the deployed server:
 *
 * - **Catalog loading.** A public box brings its catalogs up one at a time, and each load fetches a
 *   branch, resolves a live bundle's classpath and starts a render daemon. The optimizer reads the
 *   registry's idle clock, which counts only *request* traffic — so on a freshly-rolled server with
 *   no visitors yet, catalog #1's optimizer sees a perfectly idle server and starts hundreds of
 *   renders while catalogs #2…#18 are still loading. Each loaded catalog adds another optimizer, so
 *   the contention compounds: the later a catalog sits in the list, the longer its daemon waits for
 *   a render slot, and a slow enough daemon start is recorded as `livebundle-unavailable` and
 *   degrades the catalog to baked PNGs for the life of the process. [catalogsLoading] makes the
 *   whole startup pass read as *busy* so the optimizer stays parked until the catalogs are up.
 * - **Each other.** Once loading finishes, every catalog's optimizer becomes runnable at the same
 *   instant. [withRenderPermit] caps the background lane at [maxConcurrentRenders] renders
 *   server-wide, so the optimizers take turns instead of holding every live seat.
 *
 * Both knobs are process-wide: one instance is built per `serve` run and shared by every catalog
 * host it opens.
 */
class ServeBackgroundWork(
  /**
   * Background renders admitted at once, server-wide. See [DEFAULT_MAX_CONCURRENT_RENDERS] for why
   * this is no longer 1.
   */
  maxConcurrentRenders: Int = DEFAULT_MAX_CONCURRENT_RENDERS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val loadsInFlight = AtomicInteger()
  private val initialLoadPending = AtomicBoolean(false)
  private val renderPermits = Semaphore(maxConcurrentRenders.coerceAtLeast(1))
  private val lastCatalogLoadFinishedAt = AtomicLong(Long.MIN_VALUE)

  /**
   * True while the server is bringing catalogs up: the startup pass hasn't finished, or a refresh /
   * admin registration is fetching one right now. Background work treats this as "busy" even though
   * no visitor is waiting, because a catalog that loads slowly enough loses its live lane.
   */
  val catalogsLoading: Boolean
    get() = initialLoadPending.get() || loadsInFlight.get() > 0

  /**
   * Declare that a startup catalog pass is coming, before it starts. Called when the loader is
   * built — not when it runs — so the window between "server up" and "first catalog load" is busy
   * too, rather than a gap the optimizer can start in.
   */
  fun expectInitialCatalogLoad() {
    initialLoadPending.set(true)
  }

  /** The startup pass is done (however it ended — loaded, failed, or shut down mid-pass). */
  fun initialCatalogLoadFinished() {
    initialLoadPending.set(false)
    lastCatalogLoadFinishedAt.set(clock())
  }

  /** Run one catalog load, counted so background work stays parked for its duration. */
  fun <T> whileLoadingCatalog(block: () -> T): T {
    loadsInFlight.incrementAndGet()
    try {
      return block()
    } finally {
      loadsInFlight.decrementAndGet()
      lastCatalogLoadFinishedAt.set(clock())
    }
  }

  /**
   * Wrap the registry's whole-server idle clock so a loading server reads as busy (`null`) and the
   * clock restarts at zero when startup, refresh, or admin registration finishes. The catalog host
   * applies its quiet-window threshold to the smaller of this and request/render idleness.
   */
  fun idleClock(idleMillis: () -> Long?): () -> Long? = {
    if (catalogsLoading) {
      null
    } else {
      val requestIdleMillis = idleMillis()
      if (requestIdleMillis == null || catalogsLoading) {
        null
      } else {
        val finishedAt = lastCatalogLoadFinishedAt.get()
        val catalogIdleMillis =
          if (finishedAt == Long.MIN_VALUE) Long.MAX_VALUE
          else (clock() - finishedAt).coerceAtLeast(0)
        minOf(requestIdleMillis, catalogIdleMillis)
      }
    }
  }

  /**
   * Run one background render under the server-wide permit. Returns null — and leaves the thread
   * interrupted — when the wait was interrupted (shutdown), which the caller treats as "stop".
   */
  fun <T : Any> withRenderPermit(block: () -> T): T? {
    try {
      renderPermits.acquire()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    }
    try {
      return block()
    } finally {
      renderPermits.release()
    }
  }

  companion object {
    /**
     * Background renders admitted at once, server-wide.
     *
     * **This was 1, and one permit for every catalog on the box was the prefetcher's dominant
     * bottleneck.** Measured on the deployed server (0.19.41, 15 catalogs, no visitors) once the
     * gate/permit split made it visible: **74.3%** of the optimizer's active time was spent waiting
     * for this permit, against 10.1% at the idle gate and **6.3%** actually rendering. Fifteen
     * optimizers queueing on one permit, and every batch collapsing to a single daemon as a result.
     *
     * The 1 was chosen so "a foreground render is never queued behind more than one background
     * one". That guarantee is worth less than it sounds and cost more than it saved: a background
     * batch holds this permit only for its renders — the expensive part, a cold daemon warm of
     * 34-68s, is awaited *outside* it — and a warm background render is sub-second, so the
     * foreground now queues behind at most a few seconds instead of one.
     *
     * Sized to what the box can actually run concurrently rather than to a round number: the live
     * seat budget is 8 with Android weight 2, and prefetch replicas are charged to the background
     * remainder (leaving [LiveSeatLimiter.STREAM_RESERVE] for a visitor), so at most three heavy
     * daemons can be rendering at once anyway. A larger cap would only queue inside the seat budget
     * instead of here, moving the wait rather than removing it.
     *
     * `-Dcomposeai.serve.backgroundRenders=<n>` overrides it; a box with a different seat budget is
     * the case that wants a different number.
     */
    val DEFAULT_MAX_CONCURRENT_RENDERS: Int =
      System.getProperty("composeai.serve.backgroundRenders")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
  }
}
