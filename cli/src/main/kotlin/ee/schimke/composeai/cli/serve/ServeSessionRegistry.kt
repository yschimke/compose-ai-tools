package ee.schimke.composeai.cli.serve

import java.util.concurrent.CopyOnWriteArrayList
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
  /**
   * Idle window for shedding **pooled daemons** ([releaseIdleDaemons]), separate from
   * [idleTimeoutMillis], which suspends whole sessions.
   *
   * They are different questions. Suspending a session throws away a catalog's warm state and the
   * visitor pays to rebuild it, so ten minutes is a reasonable price of admission. A pool replica
   * is reopened from the same launch descriptor whenever the next burst needs it, and while it sits
   * there it holds a live seat weighted 2 — so on an eight-seat box a handful of forgotten replicas
   * is the whole budget. Reusing the session window meant a replica could hold its seat for the ten
   * minutes it takes to qualify plus up to another sweep interval before anything looked.
   *
   * Non-positive falls back to [idleTimeoutMillis], preserving the old behaviour.
   */
  private val daemonIdleMillis: Long = DEFAULT_DAEMON_IDLE_MILLIS,
  /**
   * How recently a leaseholder must have shown activity for its lease to keep answering *busy* on
   * the whole-server idle clock ([idleMillis]). See [DEFAULT_LEASE_BUSY_MILLIS] for why residency
   * and busyness are asked as two questions rather than one.
   */
  private val leaseBusyMillis: Long = DEFAULT_LEASE_BUSY_MILLIS,
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
    /**
     * Open **request-scoped** holders: a lease taken for the duration of one HTTP request
     * (`withLeasedSession`). These count as busy for their whole life, however long that is — a
     * cold `/render` or a `/bundle.zip` legitimately runs for minutes, and the quiet gate exists to
     * keep background work off exactly that.
     */
    @Volatile var requestLeases: Int = 0,
    /**
     * Open **connection** holders: a viewer WebSocket, held for the socket's whole life. These keep
     * the session resident unconditionally but only count as busy while [lastLeaseActivity] is
     * recent — see [idleMillis] and issue #4312.
     */
    @Volatile var connectionLeases: Int = 0,
    /**
     * Wall-clock of the last thing a *leaseholder* actually did — a lease being taken, a client
     * message arriving on its socket ([Lease.touch]), or any acquire of this session.
     *
     * Separate from [lastAccess], which answers "may this session's daemon be suspended?" and is
     * deliberately generous. This one answers "is someone being served right now?" against the much
     * shorter [leaseBusyMillis], and only [idleMillis] reads it.
     */
    @Volatile var lastLeaseActivity: Long = lastAccess,
    /**
     * Wall-clock when [host] last transitioned suspended→resident (null while suspended). The basis
     * for the "up for" figure the `/status` page shows per running daemon; reset each time the
     * daemon is re-opened so it reflects the *current* run, not the session's first-ever open.
     */
    @Volatile var startedAt: Long? = null,
    /**
     * A suspension detached this entry's host and is closing it **right now** (outside the lock, so
     * a blocking daemon shutdown doesn't stall every other session). [liveHost] waits this out
     * before reopening: without it, a request arriving inside that window sees `host == null` and
     * launches a replacement daemon while the previous one is still shutting down, so a single
     * session momentarily runs two daemon subprocesses and overshoots the live-seat/memory budget.
     * Closing under the lock used to serialise this implicitly.
     */
    /**
     * When [suspendIdle] released this session's host, or null while it is resident.
     *
     * The rotation key for [resumeIdleOptimizers], and deliberately not [lastAccess]: a catalog
     * suspended in the very sweep that then resumes is the one with the OLDEST `lastAccess`, so
     * ordering on that resurrected whatever had just been parked and left the genuinely long-parked
     * catalogs exactly where they were. Suspension order is least-recently-parked-first, which is
     * the round-robin the fair admission rule already assumes.
     */
    @Volatile var suspendedAt: Long? = null,
    @Volatile var closing: Boolean = false,
  ) {
    /** Every open holder, of either kind — the residency question. */
    val leases: Int
      get() = requestLeases + connectionLeases
  }

  /**
   * A read-only snapshot of one **currently-resident** session (its host is live right now), for
   * the `/status` page's "running servers" view. [hasLiveStream] distinguishes a live daemon-backed
   * host (a render daemon is up) from a pinned static bundle host that merely replays baked PNGs.
   */
  data class RunningDaemon(
    val id: String,
    val label: String,
    val pinned: Boolean,
    val hasLiveStream: Boolean,
    val liveSeatWeight: Int,
    val activeStreams: Int,
    val leases: Int,
    val startedAt: Long?,
    /** Render-latency counters for the host's live lane; null for hosts that don't track them. */
    val renderStats: RenderPerfSnapshot? = null,
    val daemonPools: List<DaemonPoolSnapshot> = emptyList(),
  )

  /** A live hold on a session that keeps it from being suspended until [close] (idempotent). */
  class Lease
  internal constructor(
    val host: ServeHost,
    private val onTouch: () -> Unit = {},
    private val onRelease: () -> Unit,
  ) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /**
     * Serialises [touch] against [close], so "a no-op once released" is a guarantee rather than a
     * likelihood.
     *
     * An `AtomicBoolean` read alone makes [touch] a check-then-act: it can see the lease open, be
     * overtaken by a `close()` that releases the hold, and only then write its timestamps — marking
     * a session busy on behalf of a holder that has already left, and (with a second connection
     * lease on the same session) restarting that one's quiet window from a stale instant.
     *
     * A private monitor rather than the registry's own lock: [touch] is on the socket's per-message
     * path, and the registry lock is held across a session *build* in `entryFor`, so borrowing it
     * here would park a message loop behind an unrelated tenant's Gradle work. Lock ordering is
     * one-way — this monitor is taken before the registry lock (via `onRelease`) and never the
     * other way round — so the pair cannot deadlock.
     */
    private val gate = Any()

    /**
     * Report that the holder is *doing* something — a client message on its socket, say — as
     * opposed to merely still being connected.
     *
     * Holding a lease is what keeps the session resident; calling this is what keeps a **connection
     * lease** counting as busy on the whole-server idle clock (see [idleMillis]). A long-lived
     * connection that never touches goes quiet on that clock while staying resident, which is the
     * point: an open browser tab nobody is looking at should not stand the theme optimizer down for
     * hours. Request-scoped leases count as busy regardless and need not call this.
     *
     * A no-op once the lease is [close]d.
     */
    fun touch() {
      synchronized(gate) { if (!released.get()) onTouch() }
    }

    override fun close() {
      synchronized(gate) { if (released.compareAndSet(false, true)) onRelease() }
    }
  }

  private val lock = ReentrantLock()
  private val sessions = HashMap<String, Entry>()
  private var closed = false

  /** Signalled when a detached host finishes closing, releasing waiters in [liveHost]. */
  private val closeFinished = lock.newCondition()

  /**
   * Observers notified as a session transitions resident→suspended, with the host that's about to
   * be closed. The seam exists so a caller can snapshot facts that are only readable off a *live*
   * host (a catalog's trust verdict, provenance and preview count) before suspension makes them
   * unobservable — [peekHost] deliberately never resumes, so without this the `/status` page can't
   * tell "suspended, trusted" from "untrusted". Invoked **outside** the registry lock, so a
   * listener may call back in; failures are swallowed (an observer must never block suspension).
   */
  private val suspendListeners = CopyOnWriteArrayList<(String, ServeHost) -> Unit>()

  /** Register a resident→suspended observer. See [suspendListeners]. */
  fun addSuspendListener(listener: (sessionId: String, host: ServeHost) -> Unit) {
    suspendListeners += listener
  }

  /**
   * Observers notified when a session is **retired** ([unregister]), so a caller holding a
   * last-known snapshot of it (see [peekHost]) can drop it.
   *
   * The snapshot advice in [peekHost] has no counterpart without this: a holder is told to keep
   * facts across suspension, and then has no way to learn that the session it kept them for is
   * gone. On a host with catalog churn — publish, serve, retire, repeat through the admin API —
   * every retired catalog's snapshot is retained for the life of the process.
   */
  private val unregisterListeners = CopyOnWriteArrayList<(String) -> Unit>()

  /** Register a retirement observer. See [unregisterListeners]. */
  fun addUnregisterListener(listener: (sessionId: String) -> Unit) {
    unregisterListeners += listener
  }

  /**
   * A projection of a session's host that has to outlive its residency, captured and discarded as
   * part of the registry's **own** transitions rather than alongside them.
   *
   * [addSuspendListener] cannot provide this, and the difference is the whole point. A suspend
   * listener runs *after* the lock is released, so a reader can observe the moment in between:
   * `peekHost` already null, [isKnownSession] still true, and no snapshot yet. And because a
   * listener writes to storage the registry does not own, a retirement can be overtaken by a slower
   * writer still holding the removed host, which resurrects the entry it just evicted.
   *
   * Both disappear when the capture and the transition are the same act. [capture] runs under the
   * lock immediately before the host is detached, so "resident" and "snapshotted" are the only two
   * states a reader can see; [discard] runs under the same lock as the removal, so nothing can be
   * written back afterwards.
   *
   * The cost of that guarantee is the contract: both run **with the registry lock held**, so an
   * implementation must do no I/O, must not block, and must never re-enter the registry.
   */
  interface SessionSnapshots {
    /** Under the lock, immediately before [host] is detached from [sessionId]. */
    fun capture(sessionId: String, host: ServeHost)

    /** Under the lock, as [sessionId] is retired. */
    fun discard(sessionId: String)
  }

  @Volatile private var snapshots: SessionSnapshots? = null

  /** Install the [SessionSnapshots] hook. See its KDoc for the contract it must honour. */
  fun setSessionSnapshots(snapshots: SessionSnapshots?) {
    this.snapshots = snapshots
  }

  // Wall-clock of the most recent acquire/lease/touch/release across all sessions — the basis for
  // the server-level idle checks ([idleMillis], [connectionIdleMillis]) that the theme optimizer's
  // quiet gate and the ephemeral exit-when-idle watchdog read.
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
              runCatching { releaseIdleDaemons() }
              runCatching { reclaimIdleForked() }
              // After the shedding, not before: the lane a parked catalog is resumed into is
              // usually the one this sweep's suspensions just freed.
              runCatching { resumeIdleOptimizers() }
            },
            reaperIntervalMillis,
            reaperIntervalMillis,
            TimeUnit.MILLISECONDS,
          )
          // Pooled daemons are swept on their OWN cadence when it is shorter. A replica holds a
          // live seat weighted 2 and is cheap to reopen, so waiting a session-suspension interval
          // to look at it means a handful of forgotten replicas can hold an eight-seat box's whole
          // budget. Same single thread, so the two sweeps never overlap.
          if (daemonIdleMillis > 0 && daemonIdleMillis < reaperIntervalMillis) {
            it.scheduleWithFixedDelay(
              { runCatching { releaseIdleDaemons() } },
              daemonIdleMillis,
              daemonIdleMillis,
              TimeUnit.MILLISECONDS,
            )
          }
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
    // A session may never take one of the server's own top-level route names. Such a session is
    // unreachable at its own landing anyway — Ktor scores a constant segment above `/{system}` —
    // and it breaks the [ServeSites] interceptor's invariant that a reserved first segment is
    // always a route: `/api/` matches no constant route, so it would fall to `/{system}/` and
    // serve that session through a hostname published as one catalog.
    //
    // Enforced HERE rather than at each ingestion point because there are five of those (an
    // upload, a `--bundles` directory, a `--bundle` argument, a catalog id, a revision ref) and
    // fixing them one at a time is how the last two review rounds went. This is one of the two
    // places a session id is ever bound to an entry — [entryFor] is the other (a ref forked on
    // demand by [factory], which never passes through here), and it carries the same guard.
    if (sessionId in ServeSites.RESERVED_SYSTEMS) {
      System.err.println(
        "serve: refusing session '$sessionId' — that name is one of the server's own routes"
      )
      return
    }
    val replaced = lock.withLock {
      check(!closed) { "ServeSessionRegistry is closed" }
      val prior = sessions[sessionId]
      // Registered sessions (the current-checkout default, bundle/catalog hosts) are never GC'd:
      // forked = false keeps them permanently resumable regardless of pinning.
      sessions[sessionId] =
        Entry(state, host, pinned, forked = false, lastAccess = clock()).also {
          if (host != null) it.startedAt = clock()
        }
      prior?.host?.takeIf { it !== host }
    }
    replaced?.let { runCatching { it.close() } }
  }

  /**
   * Drop [sessionId] entirely — the counterpart of [register], for a catalog **retired** at runtime
   * ([ServeCatalogAdmin]). The removed entry's host (and its daemon subprocess) is closed outside
   * the lock, like the replacement path in [register]. Returns false when nothing was registered
   * under that id.
   */
  fun unregister(sessionId: String): Boolean {
    val removed = lock.withLock {
      val removed = sessions.remove(sessionId)
      // Discarded under the SAME lock as the removal, so a concurrent detach either captured
      // before this and is discarded here, or finds no entry to capture from at all. Outside the
      // lock the two can interleave, and the slower writer resurrects what the retirement just
      // evicted.
      if (removed != null) snapshots?.let { runCatching { it.discard(sessionId) } }
      removed
    }
    removed?.host?.let { runCatching { it.close() } }
    // Outside the lock and guarded, exactly like the suspend notification: a listener may re-enter
    // the registry, and one that throws must not leave the session half-retired.
    if (removed != null) {
      unregisterListeners.forEach { listener -> runCatching { listener(sessionId) } }
    }
    return removed != null
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
    entry.lastLeaseActivity = clock()
    lastActivity = clock()
    liveHost(entry)
  }

  /**
   * Acquire [sessionId] and hold it resident for the returned [Lease]'s lifetime, so a long-lived
   * connection — including a WebSocket on the snapshot fallback lane that opens no stream — isn't
   * suspended mid-connection. Returns `null` when the session can't be created/opened.
   *
   * [connection] picks which of the two questions this hold answers on the idle clock, and the
   * default is the conservative one:
   * - **false (default), a request-scoped hold.** `withLeasedSession` wraps ordinary HTTP work in
   *   one of these, and that work is not always short — a cold `/render` is 30-70s and a
   *   `/bundle.zip` longer still. It counts as busy for its whole life, because keeping background
   *   work off a foreground render is precisely what the quiet gate is for.
   * - **true, a connection hold.** A viewer WebSocket, which lives as long as the tab does. It
   *   keeps the session resident unconditionally, but counts as busy only while its holder is
   *   actually doing something ([Lease.touch]) — see [idleMillis] and issue #4312.
   */
  fun lease(sessionId: String, connection: Boolean = false): Lease? = lock.withLock {
    check(!closed) { "ServeSessionRegistry is closed" }
    val entry = entryFor(sessionId) ?: return null
    entry.lastAccess = clock()
    entry.lastLeaseActivity = clock()
    lastActivity = clock()
    val host = liveHost(entry) ?: return null
    if (connection) entry.connectionLeases++ else entry.requestLeases++
    Lease(
      host,
      onTouch = {
        // No registry lock here — see [Lease.gate] for why, and for what serialises this against
        // the release below. Three independent volatile writes, on the per-message path.
        val now = clock()
        entry.lastLeaseActivity = now
        entry.lastAccess = now
        lastActivity = now
      },
    ) {
      lock.withLock {
        if (connection) entry.connectionLeases-- else entry.requestLeases--
        entry.lastAccess = clock() // start the idle clock fresh once the holder leaves
        entry.lastLeaseActivity = clock()
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
   * Whether [sessionId] names a session this registry actually has. Cheap and non-opening — it does
   * not lease, resume, or spawn anything.
   *
   * Exists so the live-seat budget can tell a real admission attempt from a request for something
   * that was never here. The seat is reserved *before* the session is leased (leasing resumes the
   * host and spawns its daemon, so a later check would be too late to bound anything), which means
   * a request for a nonexistent session reaches the budget too — harmless for admission, but it
   * would let anyone inflate the refusal counter that budget decisions are supposed to rest on.
   */
  fun isKnownSession(sessionId: String): Boolean = lock.withLock { sessions.containsKey(sessionId) }

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
   * Milliseconds the *whole server* has been idle, or `null` when someone is actually being served.
   * Idle counts from the last acquire/lease/release/[Lease.touch]; with nothing happening it grows
   * unbounded. Drives the theme optimizer's quiet gate.
   *
   * **A connection lease answers busy only while its holder is still doing something**
   * (issue #4312). This used to be `any { leases > 0 }`, and a viewer WebSocket holds a lease for
   * the socket's whole life — so one browser tab left open on a catalog pinned the clock at *busy*
   * indefinitely, whether or not anyone was looking at it. Everything gated on the clock then
   * stopped: measured on the public box, a single idle tab held the optimizer's gate shut for eight
   * consecutive minutes with zero renders, after which only the ceiling (#4288) let work through,
   * at a trickle.
   *
   * Residency and busyness are two questions, and `leases > 0` answered both with one number. A
   * lease still keeps its session resident unconditionally — the reaper must never close a live
   * socket's host mid-connection — but a **connection** lease stops *suppressing this clock* once
   * its holder has been quiet for [leaseBusyMillis]. Interrupting an optimizer pass costs a
   * returning visitor at most one render (`OPTIMIZER_YIELD_MILLIS`), so the trade is one-sided.
   *
   * A **request-scoped** lease is never aged out, however long it runs. `withLeasedSession` wraps
   * ordinary HTTP work in one, and that work is not always quick — a cold `/render` is 30-70s, a
   * `/bundle.zip` longer — so ageing those out would let background work start against exactly the
   * foreground render this gate exists to protect. See [lease].
   *
   * Use [connectionIdleMillis] where a live connection must count regardless of activity.
   */
  fun idleMillis(now: Long = clock()): Long? = lock.withLock {
    if (sessions.values.any { it.isBusy(now) }) null else now - lastActivity
  }

  /**
   * [idleMillis] under the strict rule: **any** open lease answers busy, however quiet its holder.
   *
   * The `--exit-when-idle` watchdog reads this one rather than the relaxed clock. Standing an
   * optimizer pass down under an idle tab costs that tab one render when it comes back; tearing the
   * process down under it drops a live socket, so the two want different definitions of busy even
   * though both are asking "is anyone here?".
   */
  fun connectionIdleMillis(now: Long = clock()): Long? = lock.withLock {
    if (sessions.values.any { it.leases > 0 }) null else now - lastActivity
  }

  /**
   * Whether this entry has a holder that answers *busy*: any request-scoped lease, or a connection
   * lease whose holder has been active recently enough. See [idleMillis].
   */
  private fun Entry.isBusy(now: Long): Boolean =
    requestLeases > 0 || (connectionLeases > 0 && now - lastLeaseActivity < leaseBusyMillis)

  /**
   * Session ids holding at least one open lease, sorted — i.e. exactly the set keeping a session
   * resident, and exactly the set that makes [connectionIdleMillis] answer `null`.
   *
   * Published on `/status.json` because a busy answer with nothing to attribute it to is not
   * diagnosable from outside the process, and everything downstream of the idle clock (the theme
   * optimizer's quiet gate, the `--exit-when-idle` watchdog) then looks broken for no visible
   * reason. A lease is released in a `finally`, but a request cancelled mid-flight can still leak
   * one — see `withLeasedSessionOrNull` — and a leaked lease keeps a session resident for the life
   * of the process. This names the holder so that failure is a one-line read rather than an
   * inference.
   *
   * Since #4312 this is a *superset* of what shuts the optimizer's gate: see [busyLeasedSessions]
   * for the holders that are also currently counting as busy.
   */
  fun leasedSessions(): List<String> = lock.withLock {
    sessions.entries.filter { it.value.leases > 0 }.map { it.key }.sorted()
  }

  /**
   * The subset of [leasedSessions] whose holder has been active within [leaseBusyMillis] — i.e.
   * exactly the set that makes [idleMillis] answer `null`.
   *
   * The two lists are published side by side so the interesting state is readable rather than
   * inferred: `leasedSessions` non-empty with this one empty is the idle-tab case, a session held
   * resident for a connection nobody is using.
   */
  fun busyLeasedSessions(now: Long = clock()): List<String> = lock.withLock {
    sessions.entries.filter { it.value.isBusy(now) }.map { it.key }.sorted()
  }

  /**
   * Suspend (close the daemon of, keep the state of) resident sessions idle past the timeout.
   *
   * The [suspendListeners] notification and the host `close()` both run **after** the lock is
   * released — a `close()` can block on daemon shutdown, and a listener may re-enter the registry —
   * so the only work under the lock is detaching each host from its entry. Listeners see the host
   * before it's closed, so a snapshot they take reads live state.
   *
   * Each detached entry is marked [Entry.closing] for that window so a concurrent resume waits for
   * the old daemon to die rather than starting a second one alongside it (see [liveHost]) — the
   * serialisation that closing-under-the-lock used to provide, without the stall.
   */
  fun suspendIdle(): Int {
    val detached = lock.withLock {
      if (closed) return 0
      val now = clock()
      val detached = mutableListOf<Triple<String, Entry, ServeHost>>()
      for ((id, entry) in sessions) {
        val host = entry.host ?: continue
        if (
          !entry.pinned &&
            entry.leases == 0 &&
            host.activeStreamCount() == 0 &&
            !host.backgroundWorkActive &&
            now - entry.lastAccess >= idleTimeoutMillis
        ) {
          // Under the lock, BEFORE the detach: this and `entry.host = null` are one transition,
          // so no reader can catch the session detached with its snapshot not yet published.
          snapshots?.let { runCatching { it.capture(id, host) } }
          if (optimizerUnfinished(entry.state)) {
            runCatching { entry.state?.backgroundWork?.recordOptimizerHostSuspended() }
          }
          entry.host = null
          entry.startedAt = null
          entry.suspendedAt = now
          entry.closing = true
          detached += Triple(id, entry, host)
        }
      }
      detached
    }
    for ((id, entry, host) in detached) {
      try {
        suspendListeners.forEach { listener -> runCatching { listener(id, host) } }
        runCatching { host.close() }
      } finally {
        // Always clear the gate, even if a listener threw something runCatching doesn't hold —
        // a stuck `closing` flag would block this session's resume forever.
        lock.withLock {
          entry.closing = false
          closeFinished.signalAll()
        }
      }
    }
    return detached.size
  }

  /**
   * Ask every resident host to close the daemon subprocesses it has held idle past
   * [idleTimeoutMillis], returning the total closed. Complements [suspendIdle] rather than
   * duplicating it: that one releases a whole host and skips **pinned** sessions, which is exactly
   * the set (registered bundle/catalog hosts) whose pooled daemons were accumulating unbounded —
   * one catalog on the public box held ten resident daemon processes with no streams and no
   * traffic. A pinned host stays listed and instantly resumable; only its idle pool shrinks.
   *
   * Runs outside the lock: closing a subprocess can block, and a host doing so must not stall an
   * unrelated session's acquire. A host closed concurrently by [suspendIdle] just reports zero.
   */
  fun releaseIdleDaemons(): Int {
    val window = if (daemonIdleMillis > 0) daemonIdleMillis else idleTimeoutMillis
    if (window <= 0) return 0
    val hosts = lock.withLock {
      if (closed) emptyList() else sessions.values.mapNotNull { it.host }
    }
    return hosts.sumOf { host -> runCatching { host.releaseIdleDaemons(window) }.getOrDefault(0) }
  }

  /**
   * Bring back the parked catalog that has waited longest, while a lane is free to give it.
   *
   * The counterpart to letting an unfinished optimizer be suspended at all. Its progress survives —
   * a catalog's rendered PNGs live in [ServeSessionState.catalogThemeCache], which outlives the
   * host — but nothing would *restart* the pass: re-entry rides on [ServeHost.keepLiveWarm], which
   * a visitor's presence heartbeat drives, so on a box nobody is browsing the parked catalogs would
   * simply stop. This is the heartbeat they would otherwise never get.
   *
   * Bounded by [ServeBackgroundWork.optimizerResumeSlots] because resuming is not free: it costs a
   * cold Android daemon (34-68s) and holds roughly a gigabyte for as long as the host stays up.
   * That budget is the free lanes **plus one challenger**: bounding it at the free lanes alone
   * starves every catalog that is not already resident, because a pass re-queues the instant its
   * slice ends, so every later sweep reads zero and the parked ones wait for an incumbent to
   * *finish* — hours, for a 10,440-target catalog. The extra slot puts the longest-parked catalog
   * at the door, where admission's own fairness hands it the next lane ahead of the incumbent that
   * just ran, and the displaced incumbent is suspended in its turn.
   *
   * Longest-parked first by [Entry.suspendedAt] — not by `lastAccess`, which would resurrect
   * whatever the same sweep had just suspended.
   *
   * **Only on a quiet server.** A resume is background work like the renders it leads to, and a
   * cold start landing while someone is browsing competes with them for the seat budget.
   */
  fun resumeIdleOptimizers(): Int {
    val toWarm = lock.withLock {
      if (closed) return 0
      if (idleMillis() == null) return 0
      val candidates =
        sessions.values
          .filter { it.host == null && !it.closing && optimizerUnfinished(it.state) }
          // Longest-parked first — see [Entry.suspendedAt] for why this is not `lastAccess`.
          .sortedBy { it.suspendedAt ?: Long.MIN_VALUE }
      val resumed = mutableListOf<ServeHost>()
      // Read once, from any candidate: every catalog on a server shares the one process-wide
      // [ServeBackgroundWork], and re-reading it per entry would let a resume this loop just made
      // widen its own budget.
      var slots = candidates.firstOrNull()?.state?.backgroundWork?.optimizerResumeSlots() ?: 0
      for (entry in candidates) {
        if (slots <= 0) break
        val host = liveHost(entry) ?: continue
        // The session's own idle clock, NOT `lastActivity`: that one is the whole-server quiet
        // gate the optimizer reads, and stamping it here would have the resume report the server
        // as busy and refuse the very turn it was resumed to take. This one only buys the host
        // `idleTimeoutMillis` before [suspendIdle] looks at it again, which is the slice it needs
        // to win a lane and render.
        entry.lastAccess = clock()
        entry.suspendedAt = null
        runCatching { entry.state?.backgroundWork?.recordOptimizerHostResumed() }
        resumed += host
        slots--
      }
      resumed
    }
    // Outside the lock: `keepLiveWarm` re-enters the optimization pass, and a pass that starts
    // synchronously here would hold the registry lock across a daemon warm.
    toWarm.forEach { runCatching { it.keepLiveWarm() } }
    return toWarm.size
  }

  /**
   * Put one named catalog's optimizer back to work, reviving its host if it has been suspended.
   *
   * The explicit counterpart to [resumeIdleOptimizers]. Marking a catalog's renders dirty changes
   * what there is to do but wakes nobody: a converged catalog's pass has already exited, and for
   * most catalogs most of the time the host is suspended as well. Left to the background rotation
   * the mark would sit until the reaper next ran and a lane happened to be free, so the action that
   * reports a queue would be telling the truth about the queue and not about anyone working it.
   *
   * Deliberately NOT bounded by [ServeBackgroundWork.optimizerResumeSlots]: that budget paces a
   * rotation nobody asked for, and this is a request. Admission still applies once the pass runs —
   * waking a host is not the same as granting it a lane.
   */
  fun wakeOptimizer(sessionId: String): Boolean {
    val host =
      lock.withLock {
        if (closed) return false
        val entry = sessions[sessionId] ?: return false
        if (entry.closing) return false
        // Buys the host `idleTimeoutMillis` before [suspendIdle] looks at it again — the slice it
        // needs to win a lane. See [resumeIdleOptimizers] for why this is `lastAccess` and not the
        // server-wide quiet clock the optimizer's own gate reads.
        entry.lastAccess = clock()
        if (entry.suspendedAt != null) {
          entry.suspendedAt = null
          runCatching { entry.state?.backgroundWork?.recordOptimizerHostResumed() }
        }
        liveHost(entry)
      } ?: return false
    // Outside the lock, for the reason [resumeIdleOptimizers] gives: the pass this re-enters can
    // warm a daemon, and holding the registry lock across that stalls every other session.
    runCatching { host.keepLiveWarm() }
    return true
  }

  /**
   * Whether this session is a catalog with theme-optimization targets left to fill.
   *
   * Read from [ServeSessionState] rather than from a host, because the whole point is to ask it of
   * a session whose host is gone. A session with no cache, or one whose optimizer is switched off
   * (no targets are ever configured, so `total` stays 0), is not a candidate.
   */
  private fun optimizerUnfinished(state: ServeSessionState?): Boolean {
    val snapshot = state?.catalogThemeCache?.snapshot() ?: return false
    // `converged`, NOT `fullyOptimized`. A catalog whose every target is warm but whose renders
    // came from another build still has work — and it is the case an operator's "regenerate this
    // catalog" creates deliberately. Asking the narrower question here left the newly marked
    // catalog excluded from every resume, so the action reported a queue that nothing would ever
    // come and work.
    return snapshot.total > 0 && !snapshot.converged
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
      // The second removal path, and it has to discard too. Every suspended session is captured —
      // a forked revision host included, which contributes an empty map since it is no catalog —
      // so a GC that removed the entry without the snapshot would leak one per reclaimed revision
      // and defeat exactly the bound this function exists to enforce. Under the same lock as the
      // removal, like [unregister].
      snapshots?.let { runCatching { it.discard(id) } }
      runCatching { entry.state?.reclaim?.invoke() }
    }
    stale.size
  }

  /**
   * The resident host for [sessionId] **without** resuming a suspended one — for read-only status
   * introspection (`/status`) that must not wake an idle daemon (a monitor/Home Assistant poll
   * shouldn't keep every live catalog's daemon alive). Null when unknown or currently suspended.
   *
   * A null here means "not resident", **not** "no such metadata": a caller that needs a suspended
   * session's facts should keep its own last-known snapshot via [addSuspendListener] rather than
   * reading absence as a verdict.
   */
  fun peekHost(sessionId: String): ServeHost? = lock.withLock { sessions[sessionId]?.host }

  /**
   * The retained state for [sessionId] without resuming it — null when nothing is registered under
   * that id.
   *
   * [peekHost] answers null for a session the idle reaper has suspended, which since the optimizer
   * residency work is *most catalogs most of the time*: a caller that only peeks at hosts therefore
   * cannot tell "no such catalog" from "that catalog is idle", and would refuse work on the ones it
   * exists to serve. The state outlives the host by design, and the durable things hang off it —
   * `catalogThemeCache` among them — so an operation that touches those should reach them here and
   * leave the daemon asleep.
   */
  fun peekState(sessionId: String): ServeSessionState? = lock.withLock {
    sessions[sessionId]?.state
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
      // Release anyone parked on a mid-suspension close; they re-check `closed` and give up.
      closeFinished.signalAll()
      sessions.values.mapNotNull { it.host }.also { sessions.clear() }
    }
    reaper?.shutdownNow()
    hosts.forEach { runCatching { it.close() } }
  }

  /**
   * Existing entry, or one forked via [factory]. Caller holds [lock].
   *
   * The reserved-name guard from [register] applies here too: a `--revisions` ref named after one
   * of the server's own routes (`api`, `status`, …) would otherwise be forked on first request,
   * never having passed through [register], and would then be served by `/{system}/` on a top-level
   * site host — the exact leak the guard exists to prevent. Refusing the fork keeps the invariant
   * "no session is ever named after a rooted route" true for every entry in [sessions].
   */
  private fun entryFor(sessionId: String): Entry? {
    sessions[sessionId]?.let {
      return it
    }
    if (sessionId in ServeSites.RESERVED_SYSTEMS) return null
    // Hold the lock across the build so racing first-callers for one id can't build twice. A build
    // is
    // slow, but a shared dev/CI server has few tenants and correctness beats build concurrency.
    val state = factory.create(sessionId) ?: return null
    // forked = true: built on demand (a git worktree on disk), so it's GC-eligible once long idle.
    return Entry(state, host = null, pinned = false, forked = true, lastAccess = clock()).also {
      sessions[sessionId] = it
    }
  }

  /**
   * The entry's live host, resuming (re-opening) from its state if it was suspended. Caller holds
   * [lock].
   *
   * A resume that lands mid-suspension first waits for the outgoing daemon to finish closing
   * ([Entry.closing]) — otherwise this would open its replacement alongside a daemon that is still
   * shutting down, briefly doubling that session's memory and live-seat cost. `await` releases the
   * lock while parked, so the closer (which re-takes it only to clear the flag) still makes
   * progress, and other sessions are unaffected.
   */
  private fun liveHost(entry: Entry): ServeHost? {
    while (entry.closing) closeFinished.awaitUninterruptibly()
    // The registry may have been closed while we were parked; don't resurrect a daemon into it.
    if (closed) return null
    entry.host?.let {
      return it
    }
    val state = entry.state ?: return null
    val resumed = open(state) ?: return null
    entry.host = resumed
    entry.startedAt = clock()
    return resumed
  }

  /**
   * A snapshot of every **currently-resident** session (host live right now) for the `/status`
   * page's "running servers" view — id-sorted for stable output. Reads each host's cheap
   * [ServeHost.activeStreamCount]/[ServeHost.hasLiveStream] getters under the lock (neither
   * re-enters the registry). Suspended sessions are omitted; a caller wanting only live *daemons*
   * filters on [RunningDaemon.hasLiveStream] (a pinned static bundle host is resident but runs no
   * daemon).
   */
  fun runningDaemons(): List<RunningDaemon> = lock.withLock {
    sessions
      .mapNotNull { (id, entry) ->
        val host = entry.host ?: return@mapNotNull null
        // A registered-but-never-woken catalog carries a host object and no subprocess. Reporting
        // it here would keep the running count (and its `startedAt` uptime) tied to registration,
        // which is precisely what the lazy open exists to decouple.
        if (!host.daemonStarted) return@mapNotNull null
        RunningDaemon(
          id = id,
          label = host.label,
          pinned = entry.pinned,
          hasLiveStream = host.hasLiveStream,
          liveSeatWeight = entry.state?.liveSeatWeight ?: 1,
          activeStreams = runCatching { host.activeStreamCount() }.getOrDefault(0),
          leases = entry.leases,
          startedAt = entry.startedAt,
          renderStats = runCatching { host.renderPerfStats() }.getOrNull(),
          daemonPools = runCatching { host.daemonPoolStats() }.getOrDefault(emptyList()),
        )
      }
      .sortedBy { it.id }
  }

  internal companion object {
    /**
     * Default idle window before a resident session's daemon is suspended.
     *
     * Visible beyond this class because the page-side presence heartbeat
     * ([ServeWeb.PRESENCE_INTERVAL_SECONDS]) has to fit inside it with room for a dropped ping — a
     * relationship worth asserting rather than restating.
     */
    const val DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L

    /**
     * Default second-level window before a *forked* suspended session is removed and its worktree
     * pruned (issue #2022) — an hour, comfortably past the 10-minute suspend window so a session
     * always suspends first. Pinned/registered sessions are exempt regardless.
     */
    const val DEFAULT_SUSPENDED_GC_TIMEOUT_MILLIS = 60 * 60 * 1000L

    /**
     * Default idle window before a pooled daemon is closed and its live seat returned — a minute,
     * against the ten a whole session gets. A replica reopens from its launch descriptor whenever
     * the next burst needs it; a suspended session costs a visitor a rebuild. Different prices, so
     * different windows.
     */
    const val DEFAULT_DAEMON_IDLE_MILLIS = 60 * 1000L

    /**
     * Default quiet window before an open lease stops answering *busy* on [idleMillis] — thirty
     * seconds, against the ten minutes a whole session gets before suspension.
     *
     * The two windows price different mistakes. Suspending a session under a visitor costs them a
     * daemon rebuild, so that one is generous. Letting a background pass start under a visitor
     * costs them one render — the optimizer yields as soon as a request lands
     * (`OPTIMIZER_YIELD_MILLIS`) — so this one can afford to be short, and has to be: it must sit
     * **below** the optimizer's 60s cold-entry window (`themeOptimizationIdleMillis`), or a lease
     * that only stops counting as busy after the gate's own window would still be the binding
     * constraint and the gate would never open under a held lease.
     *
     * It also sits well below the page's presence heartbeat ([ServeWeb.PRESENCE_INTERVAL_SECONDS],
     * 240s), so an open tab's own keepalive can't keep the lease permanently "active" — the
     * heartbeat re-arms the clock every four minutes and it runs freely in between.
     */
    const val DEFAULT_LEASE_BUSY_MILLIS = 30 * 1000L
  }
}
