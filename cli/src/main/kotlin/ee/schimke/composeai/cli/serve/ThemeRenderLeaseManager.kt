package ee.schimke.composeai.cli.serve

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Grants one short-lived, server-wide burst for a catalog page's themed thumbnail renders.
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
 * Thread-safe. The caller must still use the server's ordinary global render semaphore: this
 * manager narrows admission for the privileged page and never expands the server-wide limit.
 */
internal class ThemeRenderLeaseManager(
  private val serverRenderSlots: Int,
  private val clock: () -> Long = System::currentTimeMillis,
  private val tokenSource: () -> String = { UUID.randomUUID().toString() },
) {
  data class Grant(val token: String, val concurrency: Int, val expiresAtMillis: Long)

  private class Lease(
    val token: String,
    val sessionId: String,
    val hostIdentity: Any,
    val concurrency: Int,
    val expiresAtMillis: Long,
    var inFlight: Int = 0,
    var released: Boolean = false,
  )

  private val lock = ReentrantLock()
  private var active: Lease? = null

  /**
   * Try to grant a burst for [sessionId] and this exact [hostIdentity]. Only one grant can exist
   * server-wide. Capacities that cannot exceed the serial baseline are denied.
   */
  fun acquire(sessionId: String, hostIdentity: Any, requestedCapacity: Int): Grant? =
    lock.withLock {
      reapTerminalLease()
      if (active != null) return null

      val concurrency = minOf(requestedCapacity, serverRenderSlots, MAX_CONCURRENCY)
      if (concurrency <= BASELINE_CONCURRENCY) return null

      val lease =
        Lease(
          token = tokenSource(),
          sessionId = sessionId,
          hostIdentity = hostIdentity,
          concurrency = concurrency,
          expiresAtMillis = clock() + TTL_MILLIS,
        )
      active = lease
      Grant(lease.token, lease.concurrency, lease.expiresAtMillis)
    }

  /**
   * Admit one render under [token]. A token is valid only for its original session and exact host
   * object, before expiry/release, and while fewer than the granted number are already in flight.
   * The returned permit must be closed after the render finishes.
   */
  fun admit(token: String, sessionId: String, hostIdentity: Any): Permit? = lock.withLock {
    val lease = active ?: return null
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
    val lease = active ?: return false
    if (lease.token != token) return false
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
    val lease = active ?: return
    if (clock() >= lease.expiresAtMillis) lease.released = true
    if (lease.released && lease.inFlight == 0) active = null
  }

  companion object {
    const val BASELINE_CONCURRENCY = 1
    const val MAX_CONCURRENCY = 5
    const val TTL_MILLIS = 60_000L
  }
}
