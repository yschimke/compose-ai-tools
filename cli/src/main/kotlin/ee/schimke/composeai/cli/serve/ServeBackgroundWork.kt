package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

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
   * Background renders admitted at once, server-wide. Defaults to the conservative single lane; a
   * server that knows its seat budget passes [renderLaneFor] instead.
   */
  maxConcurrentRenders: Int = CONSERVATIVE_MAX_CONCURRENT_RENDERS,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * How many catalogs may be **inside an optimizer pass** at once, server-wide.
   *
   * [withRenderPermit] bounds the renders; nothing bounded the *passes*, and those are not the same
   * thing. A pass that holds no render permit is still holding a turn, a warm daemon and a live
   * seat, and is still queueing — so on the deployed box every loaded catalog entered its pass
   * within half a second of the gate opening (measured: 11 catalogs inside 464 ms) and then 15 of
   * them contended for 8 render permits. The result was 64% of all optimizer time spent waiting on
   * that permit and 43.5% of what remained spent *re-warming* daemons that got yielded before they
   * rendered anything: 10,120 entries with 8 cached after half an hour, an ETA of 21 days.
   *
   * Capping the passes fixes what capping the renders cannot. Two at a time still saturates an
   * 8-permit render lane (each pass batches up to five wide), while leaving the rest parked cheaply
   * instead of parked expensively.
   */
  maxConcurrentOptimizers: Int = DEFAULT_MAX_CONCURRENT_OPTIMIZERS,
) {
  private val loadsInFlight = AtomicInteger()
  private val initialLoadPending = AtomicBoolean(false)
  private val renderPermits = Semaphore(maxConcurrentRenders.coerceAtLeast(1))
  private val lastCatalogLoadFinishedAt = AtomicLong(Long.MIN_VALUE)

  private val optimizerLanes = maxConcurrentOptimizers.coerceAtLeast(1)
  private val optimizerPermits = Semaphore(optimizerLanes, true)
  private val optimizerRunning = ConcurrentHashMap.newKeySet<String>()
  private val optimizerWaiting = AtomicInteger()
  private val optimizerAdmissions = AtomicLong()
  private val optimizerRefusals = AtomicLong()
  private val optimizerAdmissionWaitMillis = AtomicLong()
  private val optimizerPausedUntil = AtomicLong(Long.MIN_VALUE)
  private val optimizerPauseReason = ConcurrentHashMap<String, String>()

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
  /**
   * Hold one of the [maxConcurrentOptimizers] pass slots for [system] while [block] runs, or return
   * null when none came free within [waitMillis] (or the optimizer is paused, or the thread was
   * interrupted).
   *
   * Refusal is the *point*, not a failure: a catalog that cannot get a slot parks and tries again
   * on the next pass instead of joining a queue with a warm daemon in hand. The wait is bounded so
   * a parked catalog re-checks the idle gate rather than sleeping through the quiet window it was
   * waiting for.
   */
  fun <T : Any> withOptimizerSlot(system: String, waitMillis: Long, block: () -> T): T? {
    if (optimizersPaused()) {
      optimizerRefusals.incrementAndGet()
      return null
    }
    val waitedFrom = clock()
    optimizerWaiting.incrementAndGet()
    val acquired =
      try {
        optimizerPermits.tryAcquire(waitMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
      } finally {
        optimizerWaiting.decrementAndGet()
      }
    optimizerAdmissionWaitMillis.addAndGet((clock() - waitedFrom).coerceAtLeast(0))
    if (!acquired) {
      optimizerRefusals.incrementAndGet()
      return null
    }
    // Re-checked under the permit: a pause can land while this catalog was queueing, and admitting
    // it then would let one pass slip past an operator who just asked for quiet.
    if (optimizersPaused()) {
      optimizerPermits.release()
      optimizerRefusals.incrementAndGet()
      return null
    }
    optimizerAdmissions.incrementAndGet()
    optimizerRunning.add(system)
    return try {
      block()
    } finally {
      optimizerRunning.remove(system)
      optimizerPermits.release()
    }
  }

  /**
   * Stop admitting optimizer passes for [millis], and ask the ones already running to stop at their
   * next check ([optimizersPaused]).
   *
   * The operational hole this fills: the optimizer is the largest consumer of a busy box and there
   * was no way to stand it down. Restarting the server did it, at the cost of every warm daemon and
   * every catalog's load — so the lever people actually had was the one they least wanted to pull
   * while the box was already struggling. [reason] is recorded for `/status.json` so a quiet server
   * explains itself rather than looking broken.
   *
   * Returns the epoch instant the pause lifts.
   */
  fun pauseOptimizers(millis: Long, reason: String): Long {
    val until = clock() + millis.coerceAtLeast(0)
    optimizerPausedUntil.set(until)
    optimizerPauseReason["reason"] = reason.take(MAX_PAUSE_REASON_CHARS)
    return until
  }

  /** Lift a pause early. */
  fun resumeOptimizers() {
    optimizerPausedUntil.set(Long.MIN_VALUE)
    optimizerPauseReason.clear()
  }

  /** Whether optimizer passes are currently stood down. Cheap enough for a per-batch check. */
  fun optimizersPaused(): Boolean = clock() < optimizerPausedUntil.get()

  /** Counters for `/status.json`; see [ThemeOptimizerAdmissionSnapshot]. */
  fun optimizerAdmissionSnapshot(): ThemeOptimizerAdmissionSnapshot {
    val until = optimizerPausedUntil.get()
    val paused = clock() < until
    return ThemeOptimizerAdmissionSnapshot(
      lanes = optimizerLanes,
      running = optimizerRunning.size,
      runningSystems = optimizerRunning.toSortedSet().toList(),
      waiting = optimizerWaiting.get(),
      admissions = optimizerAdmissions.get(),
      refusals = optimizerRefusals.get(),
      admissionWaitMillis = optimizerAdmissionWaitMillis.get(),
      paused = paused,
      pausedUntilEpochMillis = if (paused) until else null,
      pauseReason = if (paused) optimizerPauseReason["reason"] else null,
    )
  }

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
     * The historical lane: one background render server-wide. Still the right answer when nothing
     * else bounds daemon count — see [renderLaneFor].
     */
    const val CONSERVATIVE_MAX_CONCURRENT_RENDERS: Int = 1

    /**
     * Catalogs allowed inside an optimizer pass at once. Two, not one: a single lane would leave
     * the 8-permit render lane idle whenever the one admitted catalog is warming a daemon, and
     * warming is where a pass spends most of its time. Two overlaps one catalog's warm with
     * another's renders without recreating the free-for-all.
     */
    const val DEFAULT_MAX_CONCURRENT_OPTIMIZERS: Int = 2

    /** Pause reasons are bounded before they reach a status page. */
    const val MAX_PAUSE_REASON_CHARS: Int = 200

    /** Widest lane [renderLaneFor] will derive on its own. Beyond this, ask for it explicitly. */
    const val MAX_DERIVED_CONCURRENT_RENDERS: Int = 3

    /**
     * How many background renders this server admits at once, given its live-seat budget.
     *
     * **The lane was 1, and one permit shared by every catalog was the prefetcher's dominant
     * bottleneck.** Measured on the deployed server (0.19.41, 15 catalogs, no visitors) once the
     * gate/permit split made it visible: **74.3%** of the optimizer's active time spent waiting for
     * this permit, against 10.1% at the idle gate and **6.3%** actually rendering. Every batch
     * collapsed to a single daemon as a result.
     *
     * The 1 was chosen so "a foreground render is never queued behind more than one background
     * one". That is cheaper to relax than it sounds: a background batch holds the permit only for
     * its renders — the expensive part, a cold daemon warm of 34-68s, is awaited *outside* it — and
     * a warm background render is sub-second.
     *
     * **But widening it is only safe because something else bounds daemon count.** Each admitted
     * catalog submits up to five parallel renders and each one the pool can't serve opens another
     * daemon, so a lane of 3 is a licence for up to fifteen concurrent daemons. On the deployed box
     * the seat budget refuses that long before memory does; with [LiveSeatLimiter.unbounded] seats
     * — the CLI default, `--live-seats 0`, for a local dev box — **nothing does**, and the same
     * widening that helps a public server would spawn fifteen JVMs on a laptop. So an unbounded
     * budget keeps [CONSERVATIVE_MAX_CONCURRENT_RENDERS]; only a bounded one derives a wider lane,
     * from the daemons it could actually afford to run concurrently.
     *
     * `-Dcomposeai.serve.backgroundRenders=<n>` overrides both, for a deployment that knows better
     * than either rule.
     */
    fun renderLaneFor(seats: LiveSeatLimiter?): Int {
      System.getProperty("composeai.serve.backgroundRenders")?.toIntOrNull()?.let {
        return it.coerceAtLeast(1)
      }
      if (seats == null || seats.unbounded) return CONSERVATIVE_MAX_CONCURRENT_RENDERS
      // What the budget can hold beyond the stream reserve, at the heaviest backend's weight —
      // the same arithmetic the pool does when it decides whether it can afford a replica.
      val affordable =
        (seats.totalPermits - LiveSeatLimiter.STREAM_RESERVE) /
          ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT
      return affordable.coerceIn(
        CONSERVATIVE_MAX_CONCURRENT_RENDERS,
        MAX_DERIVED_CONCURRENT_RENDERS,
      )
    }
  }
}

/**
 * Cross-catalog optimizer admission on `/status.json` (`themeOptimizer`).
 *
 * The number that matters when the box feels slow is [running] against [lanes], and [waiting]
 * beside it: passes parked at the door are cheap, passes inside the door are not. [refusals]
 * climbing while [admissions] holds steady is the cap doing its job.
 */
@Serializable
data class ThemeOptimizerAdmissionSnapshot(
  val lanes: Int,
  val running: Int,
  val runningSystems: List<String>,
  val waiting: Int,
  val admissions: Long,
  val refusals: Long,
  val admissionWaitMillis: Long,
  val paused: Boolean,
  val pausedUntilEpochMillis: Long? = null,
  val pauseReason: String? = null,
)
