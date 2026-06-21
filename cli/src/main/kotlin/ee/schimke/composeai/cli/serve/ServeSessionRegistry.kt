package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds (forks) a tenant's *session state* on demand — the expensive discover/build step. The fork
 * happens behind this seam, so the registry and HTTP layer stay transport- and policy-agnostic and
 * tests can inject a fake. Returns `null` when no such session can be created.
 */
fun interface ServeSessionFactory {
  fun create(sessionId: String): ServeSessionState?
}

/**
 * Multi-tenant registry of serve sessions behind **one** HTTP server, so a shared server fronts
 * many sessions instead of spawning a server per module.
 *
 * Sessions follow an **Activity-style lifecycle** so daemons don't run forever:
 * - **created** lazily via [factory] (the expensive build) on first use, keyed by id;
 * - **opened** into a live daemon-backed [ServeRenderHost] via [open] (cheap — relaunches from the
 *   built descriptor);
 * - **suspended** when idle ([suspendIdle]): the daemon subprocess is closed but the cheap
 *   [ServeSessionState] is kept, so the session can be **resumed** on the next request by
 *   re-[open]ing from that state — no rebuild.
 *
 * A session is never suspended while it has an open [lease] (e.g. a live WebSocket) or active
 * streams. Concurrency-safe: at most one build per id under racing callers.
 */
class ServeSessionRegistry(
  private val open: (ServeSessionState) -> ServeHost?,
  private val factory: ServeSessionFactory = ServeSessionFactory { null },
  private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
  reaperIntervalMillis: Long = idleTimeoutMillis,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

  private class Entry(
    /** How to (re)open the host on resume; null for pinned sessions that are never suspended. */
    val state: ServeSessionState?,
    /** The live host, or null while suspended. */
    @Volatile var host: ServeHost?,
    /** Pinned sessions (e.g. static bundle hosts — no daemon to reclaim) are never suspended. */
    val pinned: Boolean,
    @Volatile var lastAccess: Long,
    /** Open long-lived holders (e.g. WebSocket connections) keeping this session resident. */
    @Volatile var leases: Int = 0,
  )

  /** A live hold on a session that keeps it from being suspended until [close] (idempotent). */
  class Lease internal constructor(val host: ServeHost, private val onRelease: () -> Unit) :
    AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (released.compareAndSet(false, true)) onRelease()
    }
  }

  private val lock = ReentrantLock()
  private val sessions = HashMap<String, Entry>()
  private var closed = false

  // Wall-clock of the most recent acquire/lease/release across all sessions — the basis for the
  // server-level idle check ([idleMillis]) that the ephemeral exit-when-idle watchdog reads.
  @Volatile private var lastActivity: Long = clock()

  // A daemon reaper suspends idle sessions. Disabled (null) when either knob is non-positive —
  // tests
  // drive suspension directly with a fake clock instead.
  private val reaper: ScheduledExecutorService? =
    if (idleTimeoutMillis > 0 && reaperIntervalMillis > 0) {
      Executors.newSingleThreadScheduledExecutor { r ->
          Thread(r, "serve-session-reaper").apply { isDaemon = true }
        }
        .also {
          it.scheduleWithFixedDelay(
            { runCatching { suspendIdle() } },
            reaperIntervalMillis,
            reaperIntervalMillis,
            TimeUnit.MILLISECONDS,
          )
        }
    } else {
      null
    }

  /**
   * Seed a session from already-known [state] (e.g. the CLI's current checkout), optionally with an
   * already-open [host]. Replaces any prior entry. The session participates in suspend/resume like
   * a forked one — its daemon is released when idle and reopened from [state] on demand.
   */
  fun register(
    sessionId: String,
    state: ServeSessionState? = null,
    host: ServeHost? = null,
    pinned: Boolean = false,
  ) {
    lock.withLock {
      check(!closed) { "ServeSessionRegistry is closed" }
      sessions[sessionId] = Entry(state, host, pinned, lastAccess = clock())
    }
  }

  /**
   * The live host for [sessionId] — resuming a suspended session or forking a new one via
   * [factory]. Returns `null` when the session can't be created/opened, so the caller can 404.
   * Touches the idle clock.
   */
  fun acquire(sessionId: String): ServeHost? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    val entry = entryFor(sessionId) ?: return null
    entry.lastAccess = clock()
    lastActivity = clock()
    liveHost(entry)
  }

  /**
   * Acquire [sessionId] and hold it resident for the returned [Lease]'s lifetime, so a long-lived
   * connection — including a WebSocket on the snapshot fallback lane that opens no stream — isn't
   * suspended mid-connection. Returns `null` when the session can't be created/opened.
   */
  fun lease(sessionId: String): Lease? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    val entry = entryFor(sessionId) ?: return null
    entry.lastAccess = clock()
    lastActivity = clock()
    val host = liveHost(entry) ?: return null
    entry.leases++
    Lease(host) {
      lock.withLock {
        entry.leases--
        entry.lastAccess = clock() // start the idle clock fresh once the holder leaves
        lastActivity = clock()
      }
    }
  }

  /**
   * Milliseconds the *whole server* has been idle, or `null` when it's busy (any session has an
   * open lease — e.g. a live WebSocket). Idle counts from the last acquire/lease/release; with no
   * leases and no requests it grows unbounded. Drives the ephemeral "exit when idle" watchdog.
   */
  fun idleMillis(now: Long = clock()): Long? = lock.withLock {
    if (sessions.values.any { it.leases > 0 }) null else now - lastActivity
  }

  /** Suspend (close the daemon of, keep the state of) resident sessions idle past the timeout. */
  fun suspendIdle(): Int = lock.withLock {
    if (closed) return 0
    val now = clock()
    var suspended = 0
    for (entry in sessions.values) {
      val host = entry.host ?: continue
      if (
        !entry.pinned &&
          entry.leases == 0 &&
          host.activeStreamCount() == 0 &&
          now - entry.lastAccess >= idleTimeoutMillis
      ) {
        entry.host = null
        runCatching { host.close() }
        suspended++
      }
    }
    suspended
  }

  /** Total known sessions (resident + suspended). */
  fun activeCount(): Int = lock.withLock { sessions.size }

  /** Sessions with a live daemon right now (resident, not suspended). */
  fun residentCount(): Int = lock.withLock { sessions.values.count { it.host != null } }

  override fun close() {
    val hosts = lock.withLock {
      if (closed) return
      closed = true
      sessions.values.mapNotNull { it.host }.also { sessions.clear() }
    }
    reaper?.shutdownNow()
    hosts.forEach { runCatching { it.close() } }
  }

  /** Existing entry, or one forked via [factory]. Caller holds [lock]. */
  private fun entryFor(sessionId: String): Entry? {
    sessions[sessionId]?.let {
      return it
    }
    // Hold the lock across the build so racing first-callers for one id can't build twice. A build
    // is
    // slow, but a shared dev/CI server has few tenants and correctness beats build concurrency.
    val state = factory.create(sessionId) ?: return null
    return Entry(state, host = null, pinned = false, lastAccess = clock()).also {
      sessions[sessionId] = it
    }
  }

  /** The entry's live host, resuming (re-opening) from its state if it was suspended. */
  private fun liveHost(entry: Entry): ServeHost? {
    entry.host?.let {
      return it
    }
    val state = entry.state ?: return null
    val resumed = open(state) ?: return null
    entry.host = resumed
    return resumed
  }

  private companion object {
    /** Default idle window before a resident session's daemon is suspended. */
    const val DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
  }
}
