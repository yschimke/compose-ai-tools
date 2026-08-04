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
  private val openReplica: () -> ServeHost,
) : AutoCloseable {
  private val lock = ReentrantLock()
  private val permits = Semaphore(capacity, true)
  private val available = ArrayDeque<ServeHost>().apply { add(primary) }
  private val replicas = mutableListOf<ServeHost>()
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
        available.removeFirstOrNull() ?: openReplica().also { replicas += it }
      }
      return borrowed.render(previewId, overrides)
    } finally {
      borrowed?.let { host -> lock.withLock { if (!closed) available.addLast(host) } }
      permits.release()
    }
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
      replicas.toList().also { replicas.clear() }
    }
    toClose.forEach { runCatching { it.close() } }
  }

  companion object {
    const val DEFAULT_CAPACITY = 5
  }
}
