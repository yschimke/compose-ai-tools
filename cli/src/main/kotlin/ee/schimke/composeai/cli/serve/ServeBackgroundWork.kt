package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 *   server-wide (one by default), so the optimizers take turns instead of holding every live seat —
 *   and a foreground render is never queued behind more than one background one.
 *
 * Both knobs are process-wide: one instance is built per `serve` run and shared by every catalog
 * host it opens.
 */
class ServeBackgroundWork(maxConcurrentRenders: Int = 1) {
  private val loadsInFlight = AtomicInteger()
  private val initialLoadPending = AtomicBoolean(false)
  private val renderPermits = Semaphore(maxConcurrentRenders.coerceAtLeast(1))

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
  }

  /** Run one catalog load, counted so background work stays parked for its duration. */
  fun <T> whileLoadingCatalog(block: () -> T): T {
    loadsInFlight.incrementAndGet()
    try {
      return block()
    } finally {
      loadsInFlight.decrementAndGet()
    }
  }

  /**
   * Wrap the registry's whole-server idle clock so a loading server reads as busy (`null`), which
   * is how [ServeCatalogLiveHost]'s optimizer already spells "not now".
   */
  fun idleClock(idleMillis: () -> Long?): () -> Long? = {
    if (catalogsLoading) null else idleMillis()
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
}
