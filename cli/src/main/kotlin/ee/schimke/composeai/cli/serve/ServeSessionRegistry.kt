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
  /**
   * Second-level idle window (issue #2022): a *forked* session that has stayed suspended this long
   * is removed entirely and its git worktree pruned (via [ServeSessionState.reclaim]), so a
   * long-lived project-mode server doesn't accumulate suspended-session state + worktrees for every
   * revision it has ever served. Must exceed [idleTimeoutMillis] (a session suspends first, then
   * GCs). Non-positive disables the GC (tests drive [reclaimIdleForked] directly with a fake
   * clock).
   */
  private val suspendedGcTimeoutMillis: Long = DEFAULT_SUSPENDED_GC_TIMEOUT_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

  private class Entry(
    /** How to (re)open the host on resume; null for pinned sessions that are never suspended. */
    val state: ServeSessionState?,
    /** The live host, or null while suspended. */
    @Volatile var host: ServeHost?,
    /** Pinned sessions (e.g. static bundle hosts — no daemon to reclaim) are never suspended. */
    val pinned: Boolean,
    /**
     * True only for sessions built on demand by [factory] (project mode `?session=<rev>`), each
     * with a git worktree on disk. These are the only entries the second-level GC
     * ([reclaimIdleForked]) *removes* — [register]ed sessions (the pinned checkout, bundle/catalog
     * hosts) are kept permanently resumable, matching the register-vs-fork distinction in the issue
     * (#2022).
     */
    val forked: Boolean,
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
            {
              // Suspend first, then GC: a session must be suspended (host released) before it's
              // eligible for the longer-window forked-session reclaim below.
              runCatching { suspendIdle() }
              runCatching { reclaimIdleForked() }
            },
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
   *
   * **Re-registration closes the replaced host.** A catalog refresh ([ServeCatalogRefresher])
   * re-runs the catalog load and re-registers the same pinned id with a fresh host; the prior
   * entry's host (and its live daemon subprocess) is dropped from [sessions] and would otherwise
   * never be closed (`close()` only walks the live map), leaking the daemon. So close it here —
   * outside the lock, since a host `close()` can block on daemon shutdown. A no-op on first
   * registration (no prior entry) and when the same host instance is re-registered.
   */
  fun register(
    sessionId: String,
    state: ServeSessionState? = null,
    host: ServeHost? = null,
    pinned: Boolean = false,
  ) {
    val replaced = lock.withLock {
      check(!closed) { "ServeSessionRegistry is closed" }
      val prior = sessions[sessionId]
      // Registered sessions (the current-checkout default, bundle/catalog hosts) are never GC'd:
      // forked = false keeps them permanently resumable regardless of pinning.
      sessions[sessionId] = Entry(state, host, pinned, forked = false, lastAccess = clock())
      prior?.host?.takeIf { it !== host }
    }
    replaced?.let { runCatching { it.close() } }
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
   * True when [sessionId] is an already-registered **static** (pinned) session — a bundle/catalog
   * host that replays baked PNGs and holds no daemon, so leasing it spawns nothing. Unknown or
   * daemon-backed (non-pinned, incl. a lazily-forked one) sessions return false, so the live-seat
   * gate reserves a seat for anything whose open could cost a render daemon. Never opens/forks a
   * host.
   */
  fun isKnownStatic(sessionId: String): Boolean = lock.withLock {
    sessions[sessionId]?.pinned == true
  }

  /**
   * Live-seat cost of [sessionId]'s daemon in [LiveSeatLimiter] permits — its session state's
   * [ServeSessionState.liveSeatWeight], or `1` for an unknown / lazily-forked session (whose
   * on-demand build hasn't run yet, so it's treated as a default desktop-weight daemon). Read
   * before leasing so the seat gate can charge a heavy Android catalog more than a cheap desktop
   * one without opening the daemon.
   */
  fun liveSeatWeight(sessionId: String): Int = lock.withLock {
    sessions[sessionId]?.state?.liveSeatWeight ?: 1
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

  /**
   * Second-level reclaim (issue #2022): fully **remove** *forked* sessions — ones built on demand
   * by [factory] (project mode `?session=<rev>`), each with a git worktree on disk — that have
   * stayed suspended (no live host, no lease) past [suspendedGcTimeoutMillis], running each one's
   * [ServeSessionState.reclaim] to prune its worktree. Pinned/registered sessions are never
   * removed, so the current checkout and bundle/catalog hosts stay permanently resumable. A later
   * `?session=<rev>` for a reclaimed revision simply rebuilds it. Returns the number reclaimed.
   *
   * Idle is measured from [Entry.lastAccess] (the last acquire/lease), the same basis as
   * [suspendIdle], so the window means "untouched for this long" — which is what a long-lived
   * project server wants: a revision nobody has opened in the GC window is gone, worktree and all.
   */
  fun reclaimIdleForked(): Int = lock.withLock {
    if (closed || suspendedGcTimeoutMillis <= 0) return 0
    val now = clock()
    val stale = sessions.filterValues { entry ->
      entry.forked &&
        entry.host == null &&
        entry.leases == 0 &&
        now - entry.lastAccess >= suspendedGcTimeoutMillis
    }
    for ((id, entry) in stale) {
      sessions.remove(id)
      runCatching { entry.state?.reclaim?.invoke() }
    }
    stale.size
  }

  /** Total known sessions (resident + suspended). */
  fun activeCount(): Int = lock.withLock { sessions.size }

  /**
   * Any registered session id, or null when none are — used by the module-less server to pick a
   * landing session so `/` resolves to something. Insertion order isn't guaranteed (HashMap), so
   * the caller prefers a specific id (a catalog / the first bundle) and only falls back to this.
   */
  fun anySessionId(): String? = lock.withLock { sessions.keys.firstOrNull() }

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
    // forked = true: built on demand (a git worktree on disk), so it's GC-eligible once long idle.
    return Entry(state, host = null, pinned = false, forked = true, lastAccess = clock()).also {
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

    /**
     * Default second-level window before a *forked* suspended session is removed and its worktree
     * pruned (issue #2022) — an hour, comfortably past the 10-minute suspend window so a session
     * always suspends first. Pinned/registered sessions are exempt regardless.
     */
    const val DEFAULT_SUSPENDED_GC_TIMEOUT_MILLIS = 60 * 60 * 1000L
  }
}
