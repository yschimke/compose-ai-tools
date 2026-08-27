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
   * When each system's last pass *ended*, and the whole of admission's memory.
   *
   * End, not start: a pass that held a lane for an hour finished recently, and keying on its start
   * would let it outrank catalogs that have been waiting that entire hour. A system absent from
   * this map has never run and sorts ahead of every system that has.
   */
  private val optimizerLastRanAt = ConcurrentHashMap<String, Long>()
  // A refresh opens the replacement host before the registry closes the previous one, so two
  // generations of one system can legitimately hold lanes together. A set collapses those two
  // admissions and the first release removes the replacement from status as well; counts preserve
  // both the total and the de-duplicated system labels.
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

  /**
   * The clocks [idleClock] handed out, retained so [optimizerAdmissionSnapshot] can publish the
   * value the optimizer's quiet gate actually reads.
   *
   * Every counter on `/status.json` described what the optimizer was doing *after* it got a turn,
   * and none described the input that decides whether it ever gets one. A box where the gate never
   * opens therefore reports the same all-zero row as a box with nothing left to do — which is how a
   * server ran for hours with `turnsGranted 0` on all 23 catalogs and no page saying why.
   * [publishedRequestIdleClock] is kept alongside the composed one so the null can be attributed: a
   * held session lease and a catalog load are both "busy" to the gate and have nothing else in
   * common.
   */
  @Volatile private var publishedIdleClock: (() -> Long?)? = null
  @Volatile private var publishedRequestIdleClock: (() -> Long?)? = null

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
  fun idleClock(idleMillis: () -> Long?): () -> Long? =
    composeIdleClock(idleMillis).also {
      // Every catalog host asks for one and they all wrap the same registry, so last-wins is the
      // same clock each time. Retained purely so status can read it; nothing here drives behaviour.
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
   * Take a lane for [system], preferring whoever has gone longest without one.
   *
   * **This was a fair `Semaphore` and fairness there did not reach far enough.** A semaphore orders
   * the callers *currently blocked on it*, and an optimizer pass is only blocked for
   * `OPTIMIZER_ADMISSION_WAIT_MILLIS` (20s) before it gives up and parks until the next presence
   * heartbeat. So the ordering only ever covered whoever happened to be at the door inside the same
   * 20s window, and a catalog refused on one attempt arrived at the next one with no advantage over
   * a catalog that had just run. Nothing accumulated, so nothing prevented the same few systems
   * winning every draw.
   *
   * Measured on the deployed box 45 minutes after v1.14.0: `admissions 5, refusals 20`, with three
   * catalogs having taken both lanes and the other nineteen reporting `turnsGranted 0` — including
   * `m3-catalog`, the largest queue on the box at 10,120 targets and therefore the one that most
   * needed the time.
   *
   * [optimizerLastRanAt] is the memory the semaphore lacked. A never-run system outranks every
   * system that has run, and among equals it is first-come — so **every catalog gets a lane before
   * any catalog gets a second**, which is a stronger guarantee than a size heuristic and needs no
   * knowledge of how much work each one has left.
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
        // Re-checked every wakeup rather than only on entry: a pause can land while this catalog is
        // queueing, and admitting it then would let one pass slip past an operator who just asked
        // for quiet.
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
      // Whether this waiter was admitted or gave up, the queue's head may have moved — and the
      // remaining waiters are parked in `awaitNanos` with no other reason to look again. Without
      // this, a free lane can sit unclaimed until someone's timeout happens to fire.
      laneFreed.signalAll()
      admissionLock.unlock()
      optimizerAdmissionWaitMillis.addAndGet((clock() - waitedFrom).coerceAtLeast(0))
    }
  }

  /**
   * How many parked catalogs it is worth making resident again — the lanes nothing is holding,
   * **plus one challenger**.
   *
   * [ServeSessionRegistry.resumeIdleOptimizers] reads this because resuming costs a cold Android
   * daemon (34-68s) and roughly a gigabyte for as long as it stays up, so it must not resume a
   * catalog that would merely stand at the door. But bounding it at the *free* lanes alone starves
   * every catalog that is not already resident: a pass returns its lane on a slice boundary and
   * re-queues immediately (see `ServeCatalogLiveHost.startThemeOptimization`), so on a box with
   * more unfinished catalogs than lanes every later sweep reads zero free lanes and the parked ones
   * wait for an incumbent to *finish* — hours, for a 10,440-target catalog.
   *
   * [PLUS_ONE_CHALLENGER] is what makes the rotation reach them. Admission's fairness
   * ([acquireOptimizerLane]) orders catalogs **at the door** by who has gone longest without a
   * lane, so a parked catalog wins its turn the moment it is standing there — but it has to be
   * standing there. Keeping exactly one challenger queued hands it the next lane release ahead of
   * the incumbent that just ran; the displaced incumbent then goes idle and is suspended in its
   * turn. One extra resident buys a rotation whose period is the idle window rather than a
   * catalog's whole backlog.
   *
   * Advisory, and deliberately tolerant of races: an admission landing in the same instant makes
   * this read one too high and the resumed pass simply queues, which is what a challenger does
   * anyway.
   */
  fun optimizerResumeSlots(): Int =
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
  fun recordOptimizerHostSuspended() {
    optimizerHostSuspensions.incrementAndGet()
  }

  /** A parked catalog was made resident again so it could take a lane. */
  fun recordOptimizerHostResumed() {
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
    // Wake the queue so a pause is felt at the door now, rather than when each waiter's admission
    // timeout happens to expire.
    admissionLock.lock()
    try {
      laneFreed.signalAll()
    } finally {
      admissionLock.unlock()
    }
    return until
  }

  /** Lift a pause early. */
  fun resumeOptimizers() {
    optimizerPausedUntil.set(Long.MIN_VALUE)
    optimizerPauseReason.clear()
  }

  /** Whether optimizer passes are currently stood down. Cheap enough for a per-batch check. */
  fun optimizersPaused(): Boolean =
    clock() < optimizerPausedUntil.get() || pressureGate?.snapshot()?.constrained == true

  /** Counters for `/status.json`; see [ThemeOptimizerAdmissionSnapshot]. */
  fun optimizerAdmissionSnapshot(): ThemeOptimizerAdmissionSnapshot {
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
    // Read the composed clock ONCE; the attribution below re-reads the request side only when it
    // has already answered "busy", so the two can never contradict each other in the same row.
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
      idleThresholdMillis = ServeCatalogLiveHost.themeOptimizationIdleMillisDefault(),
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

    /**
     * The one queued challenger [optimizerResumeSlots] keeps beyond the lanes themselves, so a
     * parked catalog can actually win a turn instead of waiting for an incumbent to finish.
     */
    const val PLUS_ONE_CHALLENGER: Int = 1

    const val HOST_COORDINATION_RETRY_MILLIS: Long = 100L

    /** Pause reasons are bounded before they reach a status page. */
    const val MAX_PAUSE_REASON_CHARS: Int = 200

    /** [ThemeOptimizerAdmissionSnapshot.idleBlockedBy]: a session holds an open lease. */
    const val IDLE_BLOCKED_BY_SESSION_LEASE: String = "session-lease"

    /** [ThemeOptimizerAdmissionSnapshot.idleBlockedBy]: catalogs are still loading. */
    const val IDLE_BLOCKED_BY_CATALOG_LOAD: String = "catalog-load"

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
 *
 * [waitingSystems] is who is at the door **in the order they will be let in**, which is the read
 * that was missing when the cap starved the box's largest catalog: `refusals 20` said work was
 * being turned away and nothing said the same system was being turned away every time.
 */
@Serializable
data class ThemeOptimizerAdmissionSnapshot(
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
   * Catalogs whose host was released while they still had optimization left, and catalogs made
   * resident again to take a lane — the residency the pass costs when it is *not* running.
   *
   * The pair is the read that was missing while every unfinished catalog stayed resident for the
   * life of the process: `running`/`waiting` describe passes, and a parked pass looks free there
   * while its daemon holds ~1.2 GB. A [hostSuspensions] that stays 0 on a box with more unfinished
   * catalogs than [lanes] means the residency rule is not firing, whatever the memory reading says.
   * [hostResumes] climbing far faster than [admissions] is the opposite fault: catalogs paying a
   * cold start to queue rather than to render.
   */
  val hostSuspensions: Long = 0,
  val hostResumes: Long = 0,
  val paused: Boolean,
  val pausedUntilEpochMillis: Long? = null,
  val pauseReason: String? = null,
  val pressure: OptimizerPressureSnapshot? = null,
  /**
   * The whole-server idle clock the optimizer's quiet gate reads, or null when it reads *busy*.
   *
   * This is the gate's input, and until it was published nothing on the page distinguished "the box
   * is never quiet enough to start" from "there is nothing left to do": both showed a pass that had
   * rendered nothing. Compare against [idleThresholdMillis] — a number consistently below it, or a
   * persistent null, means no catalog will ever be granted a turn, whatever [lanes], [admissions]
   * and [paused] say.
   */
  val serverIdleMillis: Long? = null,
  /**
   * Why [serverIdleMillis] is null, when it is: [IDLE_BLOCKED_BY_SESSION_LEASE] (a session holds an
   * open lease *and is actively using it* — `daemons.busyLeasedSessions` names it, against
   * `daemons.leasedSessions` for every holder) or [IDLE_BLOCKED_BY_CATALOG_LOAD] (catalogs are
   * still being fetched). Null when the clock is running, or before any catalog host has asked for
   * one.
   *
   * The two have opposite fixes and are indistinguishable from the outside: a lease that outlives
   * its request is a bug that stands the optimizer down permanently, while a catalog load is the
   * gate working as designed.
   */
  val idleBlockedBy: String? = null,
  /** Quiet [serverIdleMillis] must reach before a cold pass may start. */
  val idleThresholdMillis: Long = 0,
)
