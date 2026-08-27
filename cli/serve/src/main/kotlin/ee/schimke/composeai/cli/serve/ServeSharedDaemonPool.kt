package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A lazy pool of identical monolithic catalog daemons used only by leased theme-render batches.
 *
 * Slot zero is the catalog's ordinary shared daemon. It stays the sole lane for browsing, knob
 * edits, streams and unleased theme renders. Concurrent leased requests borrow it first, then
 * lazily open up to [capacity] - 1 replicas from the same launch descriptor. A sequential batch
 * therefore remains one warm process; only actual overlap creates replicas.
 *
 * The primary is owned by [ServeCatalogLiveHost]. This pool owns and closes replicas only.
 */
class ServeSharedDaemonPool(
  private val primary: ServeHost,
  val capacity: Int = DEFAULT_CAPACITY,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Whole-box daemon budget ([LiveSeatLimiter]). A replica holds [seatWeight] permits for as long
   * as it is open, so burst width is bounded by what the box can actually afford and not only by
   * [capacity], which is per catalog. Null keeps the historical unbudgeted behaviour.
   *
   * Charged as **foreground** ([LiveSeatLimiter.acquire]) for a visitor's burst: a replica opens
   * when *leased* renders overlap — someone is sitting in front of the grid waiting for those
   * pixels — so it is the same class of demand as a stream. It is also short-lived: the burst ends,
   * the replica goes idle, and [reapIdle] returns the seat. Making it compete for the background
   * remainder instead capped a visitor's burst at whatever the prefetcher had left over, which is
   * backwards.
   *
   * The idle theme optimizer reaches this pool through the same leased path but satisfies neither
   * premise — it is background residency and it does not end — so it passes `background = true` to
   * [render] and its replicas take the background remainder instead. Never the per-preview slice:
   * that is another lane's guarantee, not spare capacity.
   */
  private val liveSeats: LiveSeatLimiter? = null,
  private val seatWeight: () -> Int = { 1 },
  private val openReplica: () -> ServeHost,
) : AutoCloseable {
  private val lock = ReentrantLock()
  private val permits = Semaphore(capacity, true)
  private val available = ArrayDeque<ServeHost>().apply { add(primary) }
  private val hostReturned = lock.newCondition()
  private val seatTickets = mutableMapOf<ServeHost, LiveSeatLimiter.Ticket>()
  private val replicas = mutableListOf<ServeHost>()
  // Wall-clock of the last render each replica finished, for [reapIdle]. The primary isn't tracked:
  // it belongs to the catalog host and this pool never closes it.
  private val replicaLastUsed = mutableMapOf<ServeHost, Long>()
  // Concurrent borrows in flight, and the high-water mark since it was last taken. A borrowed host
  // is out of `available`, so concurrent borrows IS the number of daemons rendering at once — which
  // is what a caller means by "how wide did that batch actually run", and is not the same as how
  // many jobs it submitted. See [takePeakInFlight].
  private val inFlight = AtomicInteger(0)
  private val peakInFlight = AtomicInteger(0)
  // Replicas opened but not yet rendered, and the longest first-render seen since it was last
  // taken. A replica is opened lazily and its daemon session does not start until that first
  // render, so the render carries a 34-68s cold start on an Android lane. See
  // [takeColdStartMillis].
  private val coldReplicas = mutableSetOf<ServeHost>() // guarded by [lock]
  private val peakColdStartMillis = AtomicLong(0)
  // Replicas whose seat was taken on the FOREGROUND budget, i.e. opened by a visitor's burst. A
  // prefetch that reuses one must not quietly inherit that pricing — see [render].
  private val foregroundSeated = mutableSetOf<ServeHost>() // guarded by [lock]
  private var closed = false

  init {
    require(capacity >= 1) { "capacity must be >= 1, got $capacity" }
  }

  /**
   * Peak concurrent borrows since the last call, resetting the high-water mark.
   *
   * The measurement a batch needs: it submits N jobs, but when the seat budget affords no replica
   * the pool queues them onto a host already in circulation rather than spawning one. N jobs can
   * therefore be N threads taking turns on one daemon — indistinguishable from a genuinely N-wide
   * batch if you count jobs. Read-and-reset rather than a plain gauge because the caller wants the
   * peak *within its batch*, and sampling the instantaneous value almost never catches it.
   *
   * Pool-wide, not per-caller: a foreground leased render borrowing at the same time counts too. In
   * the optimizer's case that is rare (it runs on an idle box, by construction) and errs toward
   * reporting the batch as wider than it was — so a NARROW reading is trustworthy, which is the
   * direction that matters here.
   */
  fun takePeakInFlight(): Int = peakInFlight.getAndSet(inFlight.get())

  /**
   * The longest replica cold start since the last call, resetting the mark.
   *
   * A replica is opened lazily and its daemon session does not start until its first render, so
   * that render carries the full cold start — 34-68s on an Android/Robolectric lane. Without this
   * the caller charges it to per-entry render cost, which is precisely the conflation the
   * warm/batch split exists to remove: only the PRIMARY's warm is visible to the caller, and a
   * five-wide batch can be opening four cold replicas underneath it.
   *
   * The **longest**, not the sum: the cold starts overlap inside one batch, whose wall-clock is
   * bounded by its slowest lane. Summing them would exceed the interval being attributed.
   *
   * A replica stays cold until a render actually enters its session — a `NotFound` (answered from
   * the id set first) or a throw leaves it cold, so the cold start is still reported when whichever
   * later render does start the daemon pays it.
   */
  fun takeColdStartMillis(): Long = peakColdStartMillis.getAndSet(0)

  /**
   * [background] prices any replica this render has to open against the BACKGROUND remainder
   * ([LiveSeatLimiter.acquireBackground]), leaving [LiveSeatLimiter.STREAM_RESERVE] free.
   *
   * The default (foreground) is right for a leased browse burst: a visitor is sitting in front of
   * the grid waiting for those pixels, so it is the same class of demand as a stream. It is wrong
   * for the idle theme optimizer, which reaches this pool through the same *leased* path but is
   * background residency by definition — and, unlike a burst, does not end. On the deployed box
   * that combination held 6-8 of 8 seats for hours with `activeStreams: 0`, against a reserve whose
   * entire job is to guarantee a visitor can always start a stream.
   */
  fun render(
    previewId: String,
    overrides: PreviewOverrides,
    background: Boolean = false,
  ): RenderOutcome {
    permits.acquire()
    var borrowed: ServeHost? = null
    // An explicit flag, not a `coldStartFrom > 0` sentinel: [clock] is injectable and a test clock
    // legitimately starts at 0, which would silently disable the measurement.
    var cold = false
    var coldStartFrom = 0L
    var startedDaemon = false
    // Whether this borrow may extend the replica's life — see the repricing block below.
    var refreshLastUsed = true
    try {
      borrowed = lock.withLock {
        check(!closed) { "shared daemon pool is closed" }
        val host =
          if (background && !primary.daemonStarted && capacity > 1) {
            // Do not turn an idle optimizer slice into another permanently-resident catalog
            // primary. Replicas are owned here and reaped after the burst; the primary is owned by
            // the catalog host and otherwise survives every optimizer rotation. Production reached
            // 17 primaries with no traffic that way, and their RAM kept the pressure gate closed.
            available.firstOrNull { it !== primary }?.also { available.remove(it) }
              ?: if (replicas.size < capacity - 1) {
                openSeatedReplica(background = true, avoidPrimaryFallback = replicas.isNotEmpty())
              } else {
                awaitAvailableReplica()
              }
          } else {
            available.removeFirstOrNull() ?: openSeatedReplica(background)
          }
        // Claimed under the lock so exactly one borrow times the cold start.
        cold = coldReplicas.remove(host)
        if (cold) coldStartFrom = clock()
        // Reprice a replica a VISITOR opened but a prefetch is now reusing. Opening it background
        // is only half the job: a foreground burst leaves a foreground-priced replica behind, and
        // a continuously running optimizer would then keep it alive — refreshing `replicaLastUsed`
        // every batch so the idle sweep never closes it — while it sits inside the stream reserve.
        // That is the production state this whole change exists to end, reached by another road.
        if (background && liveSeats != null && host in foregroundSeated) {
          val repriced = liveSeats.acquireBackground(seatWeight(), dedicatedSlice = false)
          if (repriced != null) {
            seatTickets.put(host, repriced)?.close()
            foregroundSeated -= host
          } else {
            // No background headroom to move it to. Serve the render anyway — narrowing is this
            // pool's contract and failing prefetch helps nobody — but do NOT extend its life, so
            // the idle sweep can close it and hand the foreground seat back once the burst is
            // over. The next batch reopens it priced correctly, or narrows.
            refreshLastUsed = false
          }
        }
        host
      }
      peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
      return borrowed.render(previewId, overrides).also {
        // `ServeRenderHost.render` answers NotFound from its id set BEFORE touching its lazy
        // session, so an id this replica doesn't carry leaves the daemon just as cold as it was.
        // Consuming the marker there would hand the real cold start — paid by whichever later
        // render does start the session — straight to `batchMillis`. A throw is the same case.
        startedDaemon = it !is RenderOutcome.NotFound
      }
    } finally {
      if (cold && startedDaemon) {
        peakColdStartMillis.accumulateAndGet(clock() - coldStartFrom, ::maxOf)
      }
      borrowed?.let { host ->
        inFlight.decrementAndGet()
        lock.withLock {
          if (!closed) {
            if (cold && !startedDaemon) coldReplicas += host
            available.addLast(host)
            if (refreshLastUsed) replicaLastUsed[host] = clock()
            hostReturned.signalAll()
          }
        }
      }
      permits.release()
    }
  }

  /**
   * Open one replica, charged to the seat budget. Caller holds [lock].
   *
   * When the budget is exhausted the pool does **not** spawn anyway and does not fail the render:
   * it waits for one of its own in-flight borrows to come back. That wait is bounded by a render,
   * and the primary is always in circulation, so there is always something to wait for — the batch
   * simply narrows to the width the box can afford instead of adding a JVM it can't.
   *
   * [background] takes the seat from the background remainder instead, so prefetch residency can
   * never occupy the stream reserve — see [render].
   */
  private fun openSeatedReplica(
    background: Boolean,
    avoidPrimaryFallback: Boolean = false,
  ): ServeHost {
    var ticket: LiveSeatLimiter.Ticket? = null
    if (liveSeats != null) {
      // countRefusal = false: a miss here does NOT refuse the render. The burst simply narrows
      // onto a host already in circulation (below), so counting it would report throttled widening
      // as visitors turned away — and `liveSeatRefusals` is the evidence any budget change rests
      // on. (`acquireBackground` never counts a refusal for the same reason.)
      // `dedicatedSlice = false`: this pool is background work but it is NOT per-preview work, and
      // that slice is the one seat a supplement-only preview is guaranteed.
      ticket =
        if (background) liveSeats.acquireBackground(seatWeight(), dedicatedSlice = false)
        else liveSeats.acquire(seatWeight(), countRefusal = false)
      if (ticket == null) {
        if (avoidPrimaryFallback) return awaitAvailableReplica()
        while (available.isEmpty() && !closed) hostReturned.await()
        check(!closed) { "shared daemon pool is closed" }
        return available.removeFirst()
      }
    }
    // Hand the seat back if the launch throws (temp-dir setup, `openHost`, the daemon process
    // itself). The ticket isn't in `seatTickets` yet, so nothing else would ever release it and the
    // budget would shrink permanently — refusing later streams and replicas for a process that
    // doesn't exist.
    val replica =
      try {
        openReplica()
      } catch (e: Throwable) {
        ticket?.close()
        throw e
      }
    replicas += replica
    // Not warm yet: its daemon session starts on the first render. [takeColdStartMillis].
    coldReplicas += replica
    if (!background && ticket != null) foregroundSeated += replica
    ticket?.let { seatTickets[replica] = it }
    return replica
  }

  /** Wait for a reapable replica without consuming the idle primary. Caller holds [lock]. */
  private fun awaitAvailableReplica(): ServeHost {
    while (available.none { it !== primary } && !closed) hostReturned.await()
    check(!closed) { "shared daemon pool is closed" }
    return available.first { it !== primary }.also { available.remove(it) }
  }

  /**
   * Width background prefetch may use without waking a cold primary. A visitor-warmed primary is
   * already resident and can participate; otherwise reserve that slot and use reapable replicas.
   */
  fun backgroundCapacity(): Int =
    if (!primary.daemonStarted && capacity > 1) capacity - 1 else capacity

  /**
   * Close every **replica** idle for [idleMillis], returning how many were closed. The primary is
   * never touched — it is the catalog's own daemon and this pool doesn't own it.
   *
   * Replicas exist to widen one leased burst; without this they outlived the burst by the life of
   * the server, since nothing else closes them ([close] runs only when the catalog host does, and a
   * catalog session is `pinned` so [ServeSessionRegistry.suspendIdle] never reaps it). A replica is
   * cheap to reopen from the same launch descriptor when the next burst needs it.
   *
   * Takes a permit per reaped replica so it can't close a host mid-render: [render] holds a permit
   * for the whole borrow, so acquiring here proves the lane is free. Reaping is best-effort — if
   * every permit is busy, the next sweep tries again.
   */
  fun reapIdle(idleMillis: Long): Int {
    if (idleMillis <= 0) return 0
    var reaped = 0
    while (permits.tryAcquire()) {
      val victim = lock.withLock {
        if (closed) null
        else {
          val now = clock()
          available.firstOrNull { host ->
            host !== primary && now - (replicaLastUsed[host] ?: now) >= idleMillis
          }
        }
      }
      if (victim == null) {
        permits.release()
        break
      }
      lock.withLock {
        // Every per-host collection, or a closed host stays strongly reachable until the whole
        // pool closes — and a pinned catalog repeats burst-to-idle indefinitely, so that is
        // unbounded retention rather than a bounded wart. `coldReplicas` is on this list too: a
        // replica reaped before its first render (opened, answered NotFound, went idle) never
        // clears itself.
        available.remove(victim)
        replicas.remove(victim)
        replicaLastUsed.remove(victim)
        coldReplicas.remove(victim)
        foregroundSeated.remove(victim)
        seatTickets.remove(victim)?.close()
      }
      permits.release()
      runCatching { victim.close() }
      reaped++
    }
    return reaped
  }

  /** Actual replica subprocesses (the primary is counted separately by the composite host). */
  fun replicaProcessCount(): Int = lock.withLock { replicas.sumOf { it.daemonProcessCount } }

  fun renderPerfStats(): List<RenderPerfSnapshot> = lock.withLock {
    replicas.mapNotNull { it.renderPerfStats() }
  }

  fun snapshot(): DaemonPoolSnapshot = lock.withLock {
    DaemonPoolSnapshot(
      name = "shared-replicas",
      open = replicas.count { it.daemonProcessCount > 0 },
      maxOpen = capacity - 1,
      activeStreams = 0,
    )
  }

  override fun close() {
    val toClose = lock.withLock {
      if (closed) return
      closed = true
      available.clear()
      replicaLastUsed.clear()
      coldReplicas.clear()
      foregroundSeated.clear()
      seatTickets.values.forEach { it.close() }
      seatTickets.clear()
      // Wake anyone parked in [openSeatedReplica]; the `closed` check turns their wait into the
      // pool's ordinary closed-state error rather than a hang.
      hostReturned.signalAll()
      replicas.toList().also { replicas.clear() }
    }
    toClose.forEach { runCatching { it.close() } }
  }

  companion object {
    const val DEFAULT_CAPACITY = 5
  }
}
