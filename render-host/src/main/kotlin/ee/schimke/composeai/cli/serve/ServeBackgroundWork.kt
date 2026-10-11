package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable

/**
 * Server-wide admission for background, best-effort catalog work: today the theme-cache optimizer
 * ([ServeCatalogLiveHost]'s idle pass). It must yield to:
 * - catalog loading ([catalogsLoading]): the request idle clock reads a fresh server as idle, and
 *   an optimizer competing with later catalogs' daemon starts can degrade them to baked PNGs;
 * - each other: [withRenderPermit] caps background renders and [withOptimizerSlot] caps passes,
 *   server-wide.
 *
 * One instance per `serve` run, shared by every catalog host.
 */
public class ServeBackgroundWork(
  /**
   * Background renders admitted at once, server-wide. Defaults to the conservative single lane; a
   * server that knows its seat budget passes [renderLaneFor] instead.
   */
  maxConcurrentRenders: Int = CONSERVATIVE_MAX_CONCURRENT_RENDERS,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * How many catalogs may be inside an optimizer pass at once (each holds a turn, a warm daemon and
   * a seat). Must not be below [maxConcurrentRenders]: a pass holds one permit for its whole batch.
   */
  maxConcurrentOptimizers: Int = DEFAULT_MAX_CONCURRENT_OPTIMIZERS,
  private val hostCoordinator: OptimizerHostCoordinator = OptimizerHostCoordinator.NONE,
  private val pressureGate: OptimizerPressureGate? = null,
) {
  private val loadsInFlight = AtomicInteger()
  private val initialLoadPending = AtomicBoolean(false)
  private val renderPermits = Semaphore(maxConcurrentRenders.coerceAtLeast(1))
  private val lastCatalogLoadFinishedAt = AtomicLong(Long.MIN_VALUE)

  private val optimizerLanes = maxConcurrentOptimizers.coerceAtLeast(1)
  // Admission is a priority handoff, not a semaphore — see [withOptimizerSlot]. All four fields
  // below are guarded by [admissionLock].
  private val admissionLock = ReentrantLock()
  private val laneFreed = admissionLock.newCondition()
  private var optimizerLanesInUse = 0
  private val optimizerQueue = ArrayList<OptimizerWaiter>()
  private var optimizerArrivals = 0L
  /**
   * When each system's last pass ended (not started, or a long pass would outrank catalogs that
   * waited through it). Absent means never run, which sorts first.
   */
  private val optimizerLastRanAt = ConcurrentHashMap<String, Long>()
  // Counted, not a set: during a refresh two generations of one system can hold lanes together.
  private val optimizerRunning = ConcurrentHashMap<String, AtomicInteger>()
  private val optimizerWaiting = AtomicInteger()
  private val optimizerAdmissions = AtomicLong()
  private val optimizerRefusals = AtomicLong()
  private val optimizerAdmissionWaitMillis = AtomicLong()
  private val optimizerHostSuspensions = AtomicLong()
  private val optimizerHostResumes = AtomicLong()
  private val optimizerPausedUntil = AtomicLong(Long.MIN_VALUE)
  private val optimizerPauseReason = ConcurrentHashMap<String, String>()
  private val optimizerHostRefusals = AtomicLong()

  /** The clocks [idleClock] handed out, so `/status.json` can say why the gate reads busy. */
  @Volatile private var publishedIdleClock: (() -> Long?)? = null
  @Volatile private var publishedRequestIdleClock: (() -> Long?)? = null

  /**
   * True while the server is bringing catalogs up: the startup pass hasn't finished, or a refresh /
   * admin registration is fetching one right now. Background work treats this as "busy" even though
   * no visitor is waiting, because a catalog that loads slowly enough loses its live lane.
   */
  public val catalogsLoading: Boolean
    get() = initialLoadPending.get() || loadsInFlight.get() > 0

  /**
   * Declare that a startup catalog pass is coming. Called when the loader is built, so the gap
   * before the first load also reads as busy.
   */
  public fun expectInitialCatalogLoad() {
    initialLoadPending.set(true)
  }

  /** The startup pass is done (however it ended — loaded, failed, or shut down mid-pass). */
  public fun initialCatalogLoadFinished() {
    initialLoadPending.set(false)
    lastCatalogLoadFinishedAt.set(clock())
  }

  /** Run one catalog load, counted so background work stays parked for its duration. */
  public fun <T> whileLoadingCatalog(block: () -> T): T {
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
  public fun idleClock(idleMillis: () -> Long?): () -> Long? =
    composeIdleClock(idleMillis).also {
      // Every host wraps the same registry clock, so last-wins is fine; status only.
      publishedRequestIdleClock = idleMillis
      publishedIdleClock = it
    }

  private fun composeIdleClock(idleMillis: () -> Long?): () -> Long? = {
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
   * Hold an optimizer pass slot for [system] while [block] runs, or return null when none came free
   * within [waitMillis] (or optimizers are paused, or the thread was interrupted). Refusal is the
   * point: the catalog parks and retries rather than queueing with a warm daemon in hand.
   */
  public fun <T : Any> withOptimizerSlot(system: String, waitMillis: Long, block: () -> T): T? {
    if (!acquireOptimizerLane(system, waitMillis)) {
      optimizerRefusals.incrementAndGet()
      return null
    }
    val hostLease = acquireHostLease(system, waitMillis)
    if (hostLease == null) {
      releaseOptimizerLane(system)
      optimizerHostRefusals.incrementAndGet()
      optimizerRefusals.incrementAndGet()
      return null
    }
    optimizerAdmissions.incrementAndGet()
    optimizerRunning.computeIfAbsent(system) { AtomicInteger() }.incrementAndGet()
    return try {
      block()
    } finally {
      optimizerRunning.computeIfPresent(system) { _, count ->
        if (count.decrementAndGet() == 0) null else count
      }
      hostLease.close()
      releaseOptimizerLane(system)
    }
  }

  private fun acquireHostLease(system: String, waitMillis: Long): OptimizerHostLease? {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis.coerceAtLeast(0L))
    while (!optimizersPaused()) {
      hostCoordinator.tryAcquire(system)?.let {
        return it
      }
      if (System.nanoTime() >= deadline) return null
      try {
        Thread.sleep(HOST_COORDINATION_RETRY_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
      }
    }
    return null
  }

  /**
   * Take a lane for [system], preferring whoever has gone longest without one. A fair semaphore
   * only orders callers blocked at the same moment, and passes give up after a bounded wait, so the
   * same few catalogs kept winning. [optimizerLastRanAt] makes every catalog get a lane before any
   * gets a second.
   */
  private fun acquireOptimizerLane(system: String, waitMillis: Long): Boolean {
    val waitedFrom = clock()
    admissionLock.lock()
    val waiter =
      OptimizerWaiter(
        system = system,
        lastRanAt = optimizerLastRanAt[system] ?: Long.MIN_VALUE,
        arrival = optimizerArrivals++,
      )
    optimizerQueue.add(waiter)
    optimizerWaiting.incrementAndGet()
    try {
      var remainingNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis.coerceAtLeast(0))
      while (true) {
        // Re-checked on every wakeup so a pause lands on queued catalogs too.
        if (optimizersPaused()) return false
        if (optimizerLanesInUse < optimizerLanes && optimizerQueue.min() === waiter) {
          optimizerLanesInUse++
          return true
        }
        if (remainingNanos <= 0) return false
        remainingNanos =
          try {
            laneFreed.awaitNanos(remainingNanos)
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
          }
      }
    } finally {
      optimizerQueue.remove(waiter)
      optimizerWaiting.decrementAndGet()
      // The head may have moved; wake the others or a free lane can sit unclaimed.
      laneFreed.signalAll()
      admissionLock.unlock()
      optimizerAdmissionWaitMillis.addAndGet((clock() - waitedFrom).coerceAtLeast(0))
    }
  }

  /**
   * How many parked catalogs to make resident again: free lanes plus [PLUS_ONE_CHALLENGER].
   * Resuming costs a cold daemon (~1 GB), so only catalogs that will get a lane are resumed; the
   * challenger takes the next released lane so parked catalogs rotate. Advisory.
   */
  public fun optimizerResumeSlots(): Int =
    if (optimizersPaused()) 0
    else
      admissionLock.run {
        lock()
        try {
          (optimizerLanes + PLUS_ONE_CHALLENGER - optimizerLanesInUse - optimizerQueue.size)
            .coerceAtLeast(0)
        } finally {
          unlock()
        }
      }

  /** A catalog with optimization left to do had its host released to reclaim its daemon's RAM. */
  public fun recordOptimizerHostSuspended() {
    optimizerHostSuspensions.incrementAndGet()
  }

  /** A parked catalog was made resident again so it could take a lane. */
  public fun recordOptimizerHostResumed() {
    optimizerHostResumes.incrementAndGet()
  }

  private fun releaseOptimizerLane(system: String) {
    admissionLock.lock()
    try {
      optimizerLanesInUse--
      optimizerLastRanAt[system] = clock()
      laneFreed.signalAll()
    } finally {
      admissionLock.unlock()
    }
  }

  /** One catalog queued for a lane. Ordered by [acquireOptimizerLane]'s priority rule. */
  private class OptimizerWaiter(
    val system: String,
    val lastRanAt: Long,
    val arrival: Long,
  ) : Comparable<OptimizerWaiter> {
    override fun compareTo(other: OptimizerWaiter): Int =
      compareValuesBy(this, other, { it.lastRanAt }, { it.arrival })
  }

  /**
   * Stop admitting optimizer passes for [millis] and ask running ones to stop at their next check,
   * without restarting the server. [reason] is shown on `/status.json`. Returns when the pause
   * lifts (epoch millis).
   */
  public fun pauseOptimizers(millis: Long, reason: String): Long {
    val until = clock() + millis.coerceAtLeast(0)
    optimizerPausedUntil.set(until)
    optimizerPauseReason["reason"] = reason.take(MAX_PAUSE_REASON_CHARS)
    // Wake the queue so waiters see the pause now.
    admissionLock.lock()
    try {
      laneFreed.signalAll()
    } finally {
      admissionLock.unlock()
    }
    return until
  }

  /** Lift a pause early. */
  public fun resumeOptimizers() {
    optimizerPausedUntil.set(Long.MIN_VALUE)
    optimizerPauseReason.clear()
  }

  /** Whether optimizer passes are currently stood down. Cheap enough for a per-batch check. */
  public fun optimizersPaused(): Boolean =
    clock() < optimizerPausedUntil.get() || pressureGate?.snapshot()?.constrained == true

  /** Counters for `/status.json`; see [ThemeOptimizerAdmissionSnapshot]. */
  public fun optimizerAdmissionSnapshot(): ThemeOptimizerAdmissionSnapshot {
    val until = optimizerPausedUntil.get()
    val manuallyPaused = clock() < until
    val pressure = pressureGate?.snapshot()
    val paused = manuallyPaused || pressure?.constrained == true
    val queued = admissionLock.run {
      lock()
      try {
        optimizerQueue.sorted().map { it.system }
      } finally {
        unlock()
      }
    }
    // Read once; the request clock is only consulted when this one says busy, so they agree.
    val serverIdle = publishedIdleClock?.invoke()
    val idleBlockedBy =
      when {
        publishedIdleClock == null || serverIdle != null -> null
        publishedRequestIdleClock?.invoke() == null -> IDLE_BLOCKED_BY_SESSION_LEASE
        else -> IDLE_BLOCKED_BY_CATALOG_LOAD
      }
    return ThemeOptimizerAdmissionSnapshot(
      lanes = optimizerLanes,
      running = optimizerRunning.values.sumOf(AtomicInteger::get),
      runningSystems = optimizerRunning.keys.toSortedSet().toList(),
      waiting = optimizerWaiting.get(),
      waitingSystems = queued,
      admissions = optimizerAdmissions.get(),
      refusals = optimizerRefusals.get(),
      hostRefusals = optimizerHostRefusals.get(),
      admissionWaitMillis = optimizerAdmissionWaitMillis.get(),
      hostSuspensions = optimizerHostSuspensions.get(),
      hostResumes = optimizerHostResumes.get(),
      paused = paused,
      pausedUntilEpochMillis = if (manuallyPaused) until else null,
      pauseReason =
        when {
          manuallyPaused -> optimizerPauseReason["reason"]
          pressure?.constrained == true -> pressure.reason
          else -> null
        },
      pressure = pressure,
      serverIdleMillis = serverIdle,
      idleBlockedBy = idleBlockedBy,
      idleThresholdMillis = themeOptimizationIdleMillisDefault(),
    )
  }

  /**
   * Run one background render under the server-wide permit. Returns null, leaving the thread
   * interrupted, when the wait was interrupted (shutdown).
   */
  public fun <T : Any> withRenderPermit(block: () -> T): T? {
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

  public companion object {
    /**
     * Whole-server quiet the optimizer's idle gate requires before a cold pass. A function so a
     * late-set system property is honoured; lives here because this class publishes it.
     */
    public fun themeOptimizationIdleMillisDefault(): Long =
      System.getProperty("composeai.serve.themeOptimizationIdleMillis")?.toLongOrNull() ?: 60_000L

    /** One background render server-wide; right when nothing else bounds daemon count. */
    public const val CONSERVATIVE_MAX_CONCURRENT_RENDERS: Int = 1

    /** Two passes, so one catalog's daemon warm-up overlaps another's renders. */
    public const val DEFAULT_MAX_CONCURRENT_OPTIMIZERS: Int = 2

    /** The queued challenger [optimizerResumeSlots] keeps beyond the lanes. */
    public const val PLUS_ONE_CHALLENGER: Int = 1

    public const val HOST_COORDINATION_RETRY_MILLIS: Long = 100L

    /** Pause reasons are bounded before they reach a status page. */
    public const val MAX_PAUSE_REASON_CHARS: Int = 200

    /** [ThemeOptimizerAdmissionSnapshot.idleBlockedBy]: a session holds an open lease. */
    public const val IDLE_BLOCKED_BY_SESSION_LEASE: String = "session-lease"

    /** [ThemeOptimizerAdmissionSnapshot.idleBlockedBy]: catalogs are still loading. */
    public const val IDLE_BLOCKED_BY_CATALOG_LOAD: String = "catalog-load"

    /** Widest lane [renderLaneFor] will derive on its own. Beyond this, ask for it explicitly. */
    public const val MAX_DERIVED_CONCURRENT_RENDERS: Int = 3

    /**
     * Background renders admitted at once, given the live-seat budget. Widened only when seats
     * bound the daemon count; an unbounded budget keeps [CONSERVATIVE_MAX_CONCURRENT_RENDERS].
     * `-Dcomposeai.serve.backgroundRenders=<n>` overrides.
     */
    public fun renderLaneFor(seats: LiveSeatLimiter?): Int {
      System.getProperty("composeai.serve.backgroundRenders")?.toIntOrNull()?.let {
        return it.coerceAtLeast(1)
      }
      if (seats == null || seats.unbounded) return CONSERVATIVE_MAX_CONCURRENT_RENDERS
      // Heaviest-backend daemons the budget holds beyond the stream reserve.
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
 * Cross-catalog optimizer admission on `/status.json` (`themeOptimizer`). Read [running] against
 * [lanes] with [waiting] beside it; [waitingSystems] is the admission order, so a catalog refused
 * every time is visible.
 */
@Serializable
public data class ThemeOptimizerAdmissionSnapshot(
  val lanes: Int,
  val running: Int,
  val runningSystems: List<String>,
  val waiting: Int,
  val waitingSystems: List<String> = emptyList(),
  val admissions: Long,
  val refusals: Long,
  val hostRefusals: Long = 0,
  val admissionWaitMillis: Long,
  /**
   * Hosts released with optimization left, and parked hosts made resident again. Suspensions stuck
   * at 0 with more unfinished catalogs than [lanes] means the residency rule isn't firing; resumes
   * far outpacing [admissions] means catalogs pay cold starts just to queue.
   */
  val hostSuspensions: Long = 0,
  val hostResumes: Long = 0,
  val paused: Boolean,
  val pausedUntilEpochMillis: Long? = null,
  val pauseReason: String? = null,
  val pressure: OptimizerPressureSnapshot? = null,
  /**
   * The idle clock the optimizer's gate reads, or null when busy. Persistently below
   * [idleThresholdMillis] (or null) means no catalog will ever get a turn.
   */
  val serverIdleMillis: Long? = null,
  /**
   * Why [serverIdleMillis] is null: [IDLE_BLOCKED_BY_SESSION_LEASE] (an actively used lease; one
   * that outlives its request stands the optimizer down for good) or [IDLE_BLOCKED_BY_CATALOG_LOAD]
   * (working as designed).
   */
  val idleBlockedBy: String? = null,
  /** Quiet [serverIdleMillis] must reach before a cold pass may start. */
  val idleThresholdMillis: Long = 0,
)
