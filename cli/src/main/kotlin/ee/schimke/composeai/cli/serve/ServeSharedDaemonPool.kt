package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.util.concurrent.Semaphore
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
  private var closed = false

  init {
    require(capacity >= 1) { "capacity must be >= 1, got $capacity" }
  }

  fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    permits.acquire()
    var borrowed: ServeHost? = null
    try {
      borrowed = lock.withLock {
        check(!closed) { "shared daemon pool is closed" }
        available.removeFirstOrNull() ?: openSeatedReplica()
      }
      return borrowed.render(previewId, overrides)
    } finally {
      borrowed?.let { host ->
        lock.withLock {
          if (!closed) {
            available.addLast(host)
            replicaLastUsed[host] = clock()
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
   */
  private fun openSeatedReplica(): ServeHost {
    var ticket: LiveSeatLimiter.Ticket? = null
    if (liveSeats != null) {
      ticket = liveSeats.acquire(seatWeight())
      if (ticket == null) {
        while (available.isEmpty() && !closed) hostReturned.await()
        check(!closed) { "shared daemon pool is closed" }
        return available.removeFirst()
      }
    }
    val replica = openReplica()
    replicas += replica
    ticket?.let { seatTickets[replica] = it }
    return replica
  }

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
        available.remove(victim)
        replicas.remove(victim)
        replicaLastUsed.remove(victim)
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
