package ee.schimke.composeai.cli.serve

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Grants short-lived, server-wide bursts for catalog pages' themed thumbnail renders.
 *
 * This is deliberately separate from [ServeSessionRegistry.Lease]: a session lease keeps a host
 * resident, whereas this lease limits how much parallel render work one catalog may admit. Browser
 * pages receive distinct capability tokens, but tokens for the same session and exact host share
 * one catalog allocation and one in-flight counter. Capacity is therefore never multiplied by
 * users, tabs, or reconnects. Replacing a catalog host invalidates its outstanding tokens without
 * relying on a generation string.
 *
 * Releasing or expiring a lease stops new admissions immediately. Existing [Permit]s may finish;
 * its claim remains in a draining state until its last permit closes. The catalog allocation is
 * retained while any page claim or admitted render remains. The fixed TTL cannot be renewed, so a
 * lost page cannot retain burst capacity indefinitely.
 *
 * There are [LEASE_TIERS] catalog slots rather than one. The tiers are unequal on purpose: the
 * first active catalog gets the full burst and a second gets a smaller one, so two catalogs can
 * make progress together without their combined width exceeding what the box can render at once.
 * Every page viewing either catalog shares its catalog's width. A third catalog falls back to the
 * serial path and retries for a catalog slot.
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

  private class Claim(
    val token: String,
    val expiresAtMillis: Long,
    var inFlight: Int = 0,
    var released: Boolean = false,
  )

  private class CatalogAllocation(
    /** Which of [LEASE_TIERS] this catalog occupies, so the slot is freed at the same width. */
    val tier: Int,
    val sessionId: String,
    val hostIdentity: Any,
    val concurrency: Int,
    var inFlight: Int = 0,
    val claims: MutableList<Claim> = mutableListOf(),
  )

  private val lock = ReentrantLock()
  private val active = mutableListOf<CatalogAllocation>()

  /**
   * Try to grant a page a capability for [sessionId] and this exact [hostIdentity]. All pages for
   * an already-active catalog join its allocation. Otherwise, at most [LEASE_TIERS] catalogs are
   * active server-wide and the widest free tier is handed out. Capacities that cannot exceed the
   * serial baseline are denied: a grant that admits no more than the unleased path would is not
   * worth the round-trip.
   */
  fun acquire(sessionId: String, hostIdentity: Any, requestedCapacity: Int): Grant? =
    lock.withLock {
      reapTerminalLease()
      val existing = active.firstOrNull {
        it.sessionId == sessionId && it.hostIdentity === hostIdentity
      }
      if (existing != null) return existing.addClaim()

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

      val allocation =
        CatalogAllocation(
          tier = tier,
          sessionId = sessionId,
          hostIdentity = hostIdentity,
          concurrency = concurrency,
        )
      active += allocation
      allocation.addClaim()
    }

  /** Caller holds [lock]. */
  private fun CatalogAllocation.addClaim(): Grant {
    val claim = Claim(token = tokenSource(), expiresAtMillis = clock() + TTL_MILLIS)
    claims += claim
    return Grant(claim.token, concurrency, claim.expiresAtMillis)
  }

  /**
   * The outcome of offering a token at admission. Two ways of saying no, and the caller must not
   * conflate them: a [Saturated] claim is alive and its holder should come back, while an [Unknown]
   * one never admits anything again — refusing that render leaves the page permanently stuck, so
   * its caller falls back to the unleased lane instead.
   */
  sealed interface Admission {
    data class Admitted(val permit: Permit) : Admission

    data object Saturated : Admission

    data object Unknown : Admission
  }

  /**
   * Offer [token] for one render. A token is valid only for its original session and exact host
   * object, before expiry/release, and while fewer than the granted number are already in flight.
   * An [Admission.Admitted] permit must be closed after the render finishes.
   */
  fun admission(token: String, sessionId: String, hostIdentity: Any): Admission = lock.withLock {
    val lease =
      active.firstOrNull { allocation -> allocation.claims.any { it.token == token } }
        ?: return Admission.Unknown
    val claim = lease.claims.first { it.token == token }
    if (lease.sessionId != sessionId || lease.hostIdentity !== hostIdentity) {
      return Admission.Unknown
    }
    if (claim.released || clock() >= claim.expiresAtMillis) {
      claim.released = true
      reapTerminalLease()
      return Admission.Unknown
    }
    if (lease.inFlight >= lease.concurrency) return Admission.Saturated

    lease.inFlight++
    claim.inFlight++
    val permit = Permit {
      lock.withLock {
        check(lease.inFlight > 0) { "theme render lease permit underflow" }
        check(claim.inFlight > 0) { "theme render lease claim permit underflow" }
        lease.inFlight--
        claim.inFlight--
        reapTerminalLease()
      }
    }
    Admission.Admitted(permit)
  }

  /** [admission], for callers that only need the permit. */
  fun admit(token: String, sessionId: String, hostIdentity: Any): Permit? =
    (admission(token, sessionId, hostIdentity) as? Admission.Admitted)?.permit

  /**
   * Stop new admissions for [token]. Returns false for an unknown token. The active slot becomes
   * available immediately when there are no in-flight renders, otherwise after the final permit
   * closes. Repeated releases are harmless.
   */
  fun release(token: String): Boolean = lock.withLock {
    val lease =
      active.firstOrNull { allocation -> allocation.claims.any { it.token == token } }
        ?: return false
    lease.claims.first { it.token == token }.released = true
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
    for (lease in active) {
      for (claim in lease.claims) {
        if (clock() >= claim.expiresAtMillis) claim.released = true
      }
      lease.claims.removeAll { it.released && it.inFlight == 0 }
    }
    active.removeAll { it.claims.isEmpty() && it.inFlight == 0 }
  }

  companion object {
    const val BASELINE_CONCURRENCY = 1

    /**
     * Burst width per concurrently active catalog, widest first. All users of a catalog share one
     * tier. Two slots let a second catalog progress, and unequal widths keep their combined work
     * inside what the box can render at once.
     */
    val LEASE_TIERS = intArrayOf(5, 3)

    const val MAX_CONCURRENCY = 5
    const val TTL_MILLIS = 60_000L
  }
}
