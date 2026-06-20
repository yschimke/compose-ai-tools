package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds (forks) a tenant's render host on demand. The fork — discover/build a module, launch a
 * daemon, open a [ServeRenderHost] — happens behind this seam, so the registry and HTTP layer stay
 * transport- and policy-agnostic and tests can inject a fake.
 */
fun interface ServeSessionFactory {
  /** Open a render host for [sessionId], or return `null` when no such session can be created. */
  fun create(sessionId: String): ServeRenderHost?
}

/**
 * Multi-tenant registry of [ServeRenderHost]s behind **one** HTTP server, so a shared server fronts
 * many sessions instead of spawning a server per module. Sessions are created lazily via [factory]
 * (the fork-behind-the-API seam) on first use, cached by id, and idle-evicted: an unpinned host
 * with no live streams that hasn't been touched within [idleTimeoutMillis] is closed and its daemon
 * subprocess released.
 *
 * Pre-seeded sessions ([register]) — e.g. the CLI's primary module — are **pinned** and never
 * evicted.
 *
 * Concurrency-safe: [acquire] forks at most once per id even under racing callers, and eviction
 * never closes a host that still has watchers ([ServeRenderHost.activeStreamCount]).
 */
class ServeSessionRegistry(
  private val factory: ServeSessionFactory = ServeSessionFactory { null },
  private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
  reaperIntervalMillis: Long = idleTimeoutMillis,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

  private class Entry(
    val host: ServeRenderHost,
    val pinned: Boolean,
    @Volatile var lastAccess: Long,
  )

  private val lock = ReentrantLock()
  private val sessions = HashMap<String, Entry>()
  private var closed = false

  // A daemon reaper sweeps idle sessions. Disabled (null) when either knob is non-positive — tests
  // drive eviction directly with a fake clock instead.
  private val reaper: ScheduledExecutorService? =
    if (idleTimeoutMillis > 0 && reaperIntervalMillis > 0) {
      Executors.newSingleThreadScheduledExecutor { r ->
          Thread(r, "serve-session-reaper").apply { isDaemon = true }
        }
        .also {
          it.scheduleWithFixedDelay(
            { runCatching { evictIdle() } },
            reaperIntervalMillis,
            reaperIntervalMillis,
            TimeUnit.MILLISECONDS,
          )
        }
    } else {
      null
    }

  /**
   * Pin an externally-opened [host] under [sessionId] (never evicted). Replaces any prior entry.
   */
  fun register(sessionId: String, host: ServeRenderHost) {
    lock.withLock {
      check(!closed) { "ServeSessionRegistry is closed" }
      sessions[sessionId] = Entry(host, pinned = true, lastAccess = clock())
    }
  }

  /**
   * The host for [sessionId], forking one via [factory] on a miss. Returns `null` when the session
   * doesn't exist and can't be created, so the caller can 404. Touches the session's idle clock.
   */
  fun acquire(sessionId: String): ServeRenderHost? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    sessions[sessionId]?.let {
      it.lastAccess = clock()
      return it.host
    }
    // Hold the lock across the fork so racing first-callers for one id can't fork twice. A fork is
    // slow, but a shared dev/CI server has few tenants and correctness beats fork concurrency.
    val host = factory.create(sessionId) ?: return null
    sessions[sessionId] = Entry(host, pinned = false, lastAccess = clock())
    host
  }

  /** Close unpinned, watcher-free sessions idle past the timeout. Returns the count evicted. */
  fun evictIdle(): Int = lock.withLock {
    if (closed) return 0
    val now = clock()
    val dead = sessions.filterValues {
      !it.pinned && it.host.activeStreamCount() == 0 && now - it.lastAccess >= idleTimeoutMillis
    }
    dead.forEach { (id, entry) ->
      sessions.remove(id)
      runCatching { entry.host.close() }
    }
    dead.size
  }

  /** Number of live sessions (pinned + forked). */
  fun activeCount(): Int = lock.withLock { sessions.size }

  override fun close() {
    val toClose = lock.withLock {
      if (closed) return
      closed = true
      sessions.values.toList().also { sessions.clear() }
    }
    reaper?.shutdownNow()
    toClose.forEach { runCatching { it.host.close() } }
  }

  private companion object {
    /** Default idle window before an unused forked session's daemon is released. */
    const val DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
  }
}
