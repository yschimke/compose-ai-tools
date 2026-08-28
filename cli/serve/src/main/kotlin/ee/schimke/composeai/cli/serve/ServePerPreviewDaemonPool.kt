package ee.schimke.composeai.cli.serve

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded, LRU pool of per-preview daemon hosts backing [ServePerPreviewLiveHost]. Each entry is
 * a daemon-backed [ServeHost] materialised from ONE preview's own bundle
 * (`bundle/previews/<id>.png`), opened lazily on the first override render for that daemon id and
 * kept for reuse. When the pool exceeds [maxOpen] the least-recently-used daemon **that has no
 * active stream** is closed (its subprocess torn down), so the server holds live daemons only for
 * the handful of previews being actively edited — the per-preview counterpart of the one monolithic
 * catalog daemon. A daemon backing a live WebSocket stream is retained even past the cap (the cap
 * is a soft target): the pool is touched only once, when the stream is opened
 * ([ServeCatalogLiveHost.subscribeStream]), so a long-lived stream ages into the LRU slot and must
 * never be closed out from under its connected client.
 *
 * Thread-safe: a miss opens **under the lock**, so concurrent requests for the same id share one
 * daemon rather than racing two subprocess launches (opens are serialised, which is fine — a daemon
 * launch is the expensive step and duplicate launches for one preview would only waste a slot).
 *
 * [open] returns null when a preview has no usable per-preview bundle (fetch / materialise failed);
 * [get] then returns null and the caller ([ServePerPreviewLiveHost.resolveLive]) falls back to the
 * baked PNG. A null open is **not** cached, so a transient fetch failure recovers on a later
 * request.
 */
class ServePerPreviewDaemonPool(
  private val maxOpen: Int = DEFAULT_MAX_OPEN,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Whole-box daemon budget ([LiveSeatLimiter]). Every daemon this pool opens holds [seatWeight]
   * permits until it is evicted, reaped or closed, so pooled processes count against the same
   * ceiling as streams instead of being free. Null keeps the historical unbudgeted behaviour (the
   * default, and what tests use).
   *
   * Before this, [maxOpen] was the only bound and it is *per catalog*: with a dozen catalogs
   * registered, the seat budget could read 8 of 8 free while thirty daemon JVMs were resident,
   * because the budget was charged at exactly one place — the WebSocket stream lane — and pooled
   * daemons went through none of it.
   */
  private val liveSeats: LiveSeatLimiter? = null,
  private val seatWeight: () -> Int = { 1 },
  private val open: (daemonId: String) -> ServeHost?,
) : AutoCloseable {

  private val lock = ReentrantLock()

  // Access-order LRU: reading a key moves it to most-recently-used; the eldest entry is the LRU one
  // evicted first when the pool is over [maxOpen].
  private val hosts = LinkedHashMap<String, ServeHost>(16, 0.75f, true)

  // Wall-clock of the last [get] per daemon id — the basis for [reapIdle]. Kept beside `hosts`
  // rather than in it so the LRU's access ordering stays the only thing `hosts` encodes.
  private val lastUsed = mutableMapOf<String, Long>()

  // The seat reservation each open daemon holds, released when it goes.
  private val seatTickets = mutableMapOf<String, LiveSeatLimiter.Ticket>()

  private var closed = false

  /**
   * The pooled per-preview daemon for [daemonId], opening + caching it on a miss. Returns null when
   * no per-preview daemon could be opened (so the caller replays the baked PNG); a null is never
   * cached. Opening a new daemon beyond [maxOpen] evicts + closes the least-recently-used one that
   * isn't backing a live stream (see [evictExcess]).
   */
  fun get(daemonId: String): ServeHost? = lock.withLock {
    if (closed) return null
    hosts[daemonId]?.let {
      lastUsed[daemonId] = clock()
      return it
    }
    // Take the seat BEFORE opening: a refused daemon must not have been spawned. A miss returns
    // null exactly like an unusable bundle does, so the caller replays the baked PNG — a slightly
    // stale thumbnail under memory pressure, rather than a process the box can't afford.
    val ticket = liveSeats?.let { it.acquireBackground(seatWeight()) ?: return null }
    // A null open (no usable per-preview bundle) and a throwing one both have to hand the seat
    // back: neither leaves a daemon behind, and a leaked ticket shrinks the whole box's budget for
    // the life of the server.
    val host =
      try {
        open(daemonId)
      } catch (e: Throwable) {
        ticket?.close()
        throw e
      }
    if (host == null) {
      ticket?.close()
      return null
    }
    hosts[daemonId] = host
    lastUsed[daemonId] = clock()
    ticket?.let { seatTickets[daemonId] = it }
    evictExcess()
    host
  }

  /**
   * Close every pooled daemon untouched for [idleMillis] and not backing a live stream, returning
   * how many were closed.
   *
   * The LRU in [evictExcess] only fires when the pool is **over** [maxOpen], so a pool that filled
   * up during a burst and then went quiet held its daemons — up to [maxOpen] JVMs — indefinitely.
   * That is fine for a workstation and wrong for a long-lived public box: measured on
   * `preview.coo.ee`, one catalog sat on ten resident daemon processes with zero active streams and
   * no traffic, because nothing else reaps them either (a catalog session is registered `pinned`,
   * which [ServeSessionRegistry.suspendIdle] skips by design). A reopened daemon costs one cold
   * start; holding it costs a JVM for as long as the server runs.
   */
  fun reapIdle(idleMillis: Long): Int = lock.withLock {
    if (closed || idleMillis <= 0) return 0
    val now = clock()
    val stale =
      hosts.entries
        .filter { (id, host) ->
          host.activeStreamCount() == 0 && now - (lastUsed[id] ?: now) >= idleMillis
        }
        .map { it.key }
    for (id in stale) {
      val host = hosts.remove(id) ?: continue
      lastUsed.remove(id)
      seatTickets.remove(id)?.close()
      runCatching { host.close() }
    }
    stale.size
  }

  /**
   * Trim the pool back to [maxOpen] by closing least-recently-used daemons — but never one with an
   * active stream. Access-order iteration is LRU-first, so [firstOrNull] finds the least-recently
   * used **evictable** (stream-free) host; a streaming daemon is retained even past the cap rather
   * than closed out from under its connected client. When every held daemon is streaming there's
   * nothing to evict and the pool holds over the cap until a stream ends (bounded by the live-seat
   * cap). Caller holds [lock]. Iterating `entries` doesn't count as an access, so it can't reorder
   * the LRU mid-scan.
   */
  private fun evictExcess() {
    while (hosts.size > maxOpen) {
      val evictable = hosts.entries.firstOrNull { it.value.activeStreamCount() == 0 } ?: break
      hosts.remove(evictable.key)
      lastUsed.remove(evictable.key)
      seatTickets.remove(evictable.key)?.close()
      runCatching { evictable.value.close() }
    }
  }

  /** Live per-preview daemons currently held (diagnostics). */
  fun openCount(): Int = lock.withLock { hosts.size }

  /** Total live upstream streams across the pooled per-preview daemons. */
  fun activeStreamCount(): Int = lock.withLock { hosts.values.sumOf { it.activeStreamCount() } }

  /** Resident occupancy for `/status.json` without opening or touching any daemon. */
  fun snapshot(name: String = "per-preview"): DaemonPoolSnapshot = lock.withLock {
    DaemonPoolSnapshot(
      name = name,
      open = hosts.size,
      maxOpen = maxOpen,
      activeStreams = hosts.values.sumOf { it.activeStreamCount() },
    )
  }

  /**
   * Render-latency snapshots of the currently-pooled per-preview daemons (see
   * [RenderPerfSnapshot]). The per-preview lane is the DEFAULT render path for a trusted catalog,
   * so [ServeCatalogLiveHost] folds these into its `/status` roll-up alongside the monolithic
   * daemon's — without them the catalog's stats would sit empty while the pool does the actual
   * render work. Snapshot-of-the-pool semantics: a reaped (LRU-evicted) daemon's history leaves
   * with it, so the numbers describe the daemons currently alive, matching [activeStreamCount].
   */
  fun renderPerfStats(): List<RenderPerfSnapshot> = lock.withLock {
    hosts.values.mapNotNull { runCatching { it.renderPerfStats() }.getOrNull() }
  }

  override fun close() = lock.withLock {
    closed = true
    hosts.values.forEach { runCatching { it.close() } }
    hosts.clear()
    lastUsed.clear()
    seatTickets.values.forEach { it.close() }
    seatTickets.clear()
  }

  companion object {
    /**
     * Default cap on concurrently-held per-preview daemons. A preview server sees a few previews
     * edited at a time, and each held daemon is a JVM Compose render subprocess, so keep this
     * small; the LRU reaps the rest.
     */
    const val DEFAULT_MAX_OPEN = 8
  }
}
