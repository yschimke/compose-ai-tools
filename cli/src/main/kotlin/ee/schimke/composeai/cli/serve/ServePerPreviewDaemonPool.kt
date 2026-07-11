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
  private val open: (daemonId: String) -> ServeHost?,
) : AutoCloseable {

  private val lock = ReentrantLock()

  // Access-order LRU: reading a key moves it to most-recently-used; the eldest entry is the LRU one
  // evicted first when the pool is over [maxOpen].
  private val hosts = LinkedHashMap<String, ServeHost>(16, 0.75f, true)

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
      return it
    }
    val host = open(daemonId) ?: return null
    hosts[daemonId] = host
    evictExcess()
    host
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
      runCatching { evictable.value.close() }
    }
  }

  /** Live per-preview daemons currently held (diagnostics). */
  fun openCount(): Int = lock.withLock { hosts.size }

  /** Total live upstream streams across the pooled per-preview daemons. */
  fun activeStreamCount(): Int = lock.withLock { hosts.values.sumOf { it.activeStreamCount() } }

  override fun close() = lock.withLock {
    closed = true
    hosts.values.forEach { runCatching { it.close() } }
    hosts.clear()
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
