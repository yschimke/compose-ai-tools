package ee.schimke.composeai.cli.serve

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Grants short-lived, server-wide bursts for catalog pages' themed thumbnail renders.
 *
 * This is deliberately separate from [ServeSessionRegistry.Lease]: a session lease keeps a host
 * resident, whereas this lease limits how much parallel render work one browser page may admit. A
 * grant is bound to both its session and the exact host instance, so replacing a catalog host
 * invalidates its outstanding token without relying on a generation string.
 *
 * Releasing or expiring a lease stops new admissions immediately. Existing [Permit]s may finish;
 * the lease remains in a draining state until the last one closes, preventing a replacement page
 * from overlapping the old burst. The fixed TTL cannot be renewed, so a lost page cannot retain
 * burst capacity indefinitely.
 *
 * There are [LEASE_TIERS] slots rather than one. A single grant meant that the moment two people
 * (or two tabs) looked at catalogs, the second fell all the way back to the strictly serial
 * baseline — on a box whose whole job is showing catalogs, that is the common case, not the edge
 * one. The tiers are unequal on purpose: the first page gets the full burst and a second gets a
 * smaller one, so two pages can make progress together without their combined width exceeding what
 * the box can actually render at once. A third page still falls back to serial.
 *
 * Thread-safe. The caller must still use the server's ordinary global render semaphore: this
 * manager narrows admission for the privileged pages and never expands the server-wide limit.
 */
internal class ThemeRenderLeaseManager(
  private val serverRenderSlots: Int,
  private val clock: () -> Long = System::currentTimeMillis,
  private val tokenSource: () -> String = { UUID.randomUUID().toString() },
) {
  data class Grant(val token: String, val concurrency: Int, val expiresAtMillis: Long)

  private class Lease(
    /** Which of [LEASE_TIERS] this lease occupies, so the slot is freed for the same width. */
    val tier: Int,
    val token: String,
    val sessionId: String,
    val hostIdentity: Any,
    val concurrency: Int,
    val expiresAtMillis: Long,
    var inFlight: Int = 0,
    var released: Boolean = false,
  )

  private val lock = ReentrantLock()
  private val active = mutableListOf<Lease>()

  /**
   * Try to grant a burst for [sessionId] and this exact [hostIdentity]. At most [LEASE_TIERS]
   * grants exist server-wide, and the widest free tier is handed out — so a page arriving after
   * another has drained gets the full burst back rather than being stuck on the narrow one.
   * Capacities that cannot exceed the serial baseline are denied: a grant that admits no more than
   * the unleased path would is not worth the round-trip.
   */
  fun acquire(sessionId: String, hostIdentity: Any, requestedCapacity: Int): Grant? =
    lock.withLock {
      reapTerminalLease()
      val tier =
        LEASE_TIERS.indices.firstOrNull { t -> active.none { it.tier == t } } ?: return null

      // Sized from what is left of the shared budget, not from the whole of it. Clamping each
      // grant independently would promise more total width than the server has permits for — a
      // four-slot box would hand out 4 and then 3 — and the second page's renders would simply
      // queue on the global semaphore and 503, which is the outcome the second tier exists to
      // avoid. A tier that cannot beat the serial baseline out of the remainder is refused.
      val promised = active.sumOf { it.concurrency }
      val remaining = serverRenderSlots - promised
      val concurrency = minOf(requestedCapacity, remaining, LEASE_TIERS[tier])
      if (concurrency <= BASELINE_CONCURRENCY) return null

      val lease =
        Lease(
          tier = tier,
          token = tokenSource(),
          sessionId = sessionId,
          hostIdentity = hostIdentity,
          concurrency = concurrency,
          expiresAtMillis = clock() + TTL_MILLIS,
        )
      active += lease
      Grant(lease.token, lease.concurrency, lease.expiresAtMillis)
    }

  /**
   * Admit one render under [token]. A token is valid only for its original session and exact host
   * object, before expiry/release, and while fewer than the granted number are already in flight.
   * The returned permit must be closed after the render finishes.
   */
  fun admit(token: String, sessionId: String, hostIdentity: Any): Permit? = lock.withLock {
    val lease = active.firstOrNull { it.token == token } ?: return null
    if (
      lease.token != token || lease.sessionId != sessionId || lease.hostIdentity !== hostIdentity
    ) {
      return null
    }
    if (lease.released || clock() >= lease.expiresAtMillis) {
      lease.released = true
      reapTerminalLease()
      return null
    }
    if (lease.inFlight >= lease.concurrency) return null

    lease.inFlight++
    Permit {
      lock.withLock {
        check(lease.inFlight > 0) { "theme render lease permit underflow" }
        lease.inFlight--
        reapTerminalLease()
      }
    }
  }

  /**
   * Stop new admissions for [token]. Returns false for an unknown token. The active slot becomes
   * available immediately when there are no in-flight renders, otherwise after the final permit
   * closes. Repeated releases are harmless.
   */
  fun release(token: String): Boolean = lock.withLock {
    val lease = active.firstOrNull { it.token == token } ?: return false
    lease.released = true
    reapTerminalLease()
    true
  }

  /** One admitted render. Closing it is idempotent. */
  class Permit internal constructor(private val onClose: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
      if (closed.compareAndSet(false, true)) onClose()
    }
  }

  /** Caller holds [lock]. Expiry drains just like an explicit release. */
  private fun reapTerminalLease() {
    for (lease in active) if (clock() >= lease.expiresAtMillis) lease.released = true
    active.removeAll { it.released && it.inFlight == 0 }
  }

  companion object {
    const val BASELINE_CONCURRENCY = 1

    /**
     * Burst width per concurrent grant, widest first. Two slots so a second viewer isn't dropped to
     * the serial baseline, and unequal so their combined width stays inside what the box can render
     * at once — each grant is additionally clamped to the server's render slots, and to what the
     * host says it can actually parallelise.
     */
    val LEASE_TIERS = intArrayOf(5, 3)

    const val MAX_CONCURRENCY = 5
    const val TTL_MILLIS = 60_000L
  }
}
